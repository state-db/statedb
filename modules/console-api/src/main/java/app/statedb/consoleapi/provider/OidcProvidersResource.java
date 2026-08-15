package app.statedb.consoleapi.provider;

import app.statedb.consoleapi.auth.SessionPrincipal;
import app.statedb.persistence.identity.AdminAllowList;
import app.statedb.persistence.identity.OidcProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** Runtime OIDC provider management for platform administrators. */
@Path("/api/admin/providers")
@SessionPrincipal
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OidcProvidersResource {

  private static final Pattern NAME = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

  @Inject SecurityIdentity identity;
  @Inject AdminAllowList adminAllowList;

  @ConfigProperty(name = "statedb.oidc.bootstrap.name")
  Optional<String> bootstrapName;

  @GET
  public List<ProviderDto> list() {
    requireAdmin();
    return OidcProvider.<OidcProvider>list("order by name").stream().map(this::dto).toList();
  }

  @POST
  @Transactional
  public ProviderDto create(ProviderWrite body) {
    requireAdmin();
    validateCreate(body);
    OidcProvider provider = new OidcProvider();
    apply(provider, body, true);
    requireRuntimeName(provider.name);
    requireUnique(provider, null);
    provider.persistAndFlush();
    return dto(provider);
  }

  @PATCH
  @Path("/{id}")
  @Transactional
  public ProviderDto update(@PathParam("id") String rawId, ProviderWrite body) {
    requireAdmin();
    OidcProvider provider = find(rawId);
    requireMutable(provider);
    if (body == null) {
      throw new BadRequestException("request body is required");
    }
    apply(provider, body, false);
    requireRuntimeName(provider.name);
    requireUnique(provider, provider.id);
    provider.persistAndFlush();
    return dto(provider);
  }

  @DELETE
  @Path("/{id}")
  @Transactional
  public Response delete(@PathParam("id") String rawId) {
    requireAdmin();
    OidcProvider provider = find(rawId);
    requireMutable(provider);
    provider.delete();
    return Response.noContent().build();
  }

  private void requireAdmin() {
    if (!(identity.getPrincipal() instanceof JsonWebToken jwt)
        || !adminAllowList.isAdmin(jwt.getIssuer(), jwt.getSubject())) {
      throw new ForbiddenException();
    }
  }

  private OidcProvider find(String rawId) {
    try {
      return OidcProvider.<OidcProvider>findByIdOptional(UUID.fromString(rawId))
          .orElseThrow(NotFoundException::new);
    } catch (IllegalArgumentException e) {
      throw new NotFoundException();
    }
  }

  private void requireMutable(OidcProvider provider) {
    if (managed(provider)) {
      throw new ForbiddenException("the env-managed provider cannot be changed through the API");
    }
  }

  private void requireRuntimeName(String name) {
    if (isBootstrapName(name)) {
      throw new ForbiddenException("the env-managed provider name is reserved");
    }
  }

  private void requireUnique(OidcProvider provider, UUID exceptId) {
    if (exceptId == null) {
      if (OidcProvider.count("name", provider.name) > 0) {
        throw new BadRequestException("a provider with that name already exists");
      }
      if (OidcProvider.count(
              "issuerUrl = ?1 and clientId = ?2", provider.issuerUrl, provider.clientId)
          > 0) {
        throw new BadRequestException("that issuer URL and client ID are already registered");
      }
      return;
    }
    if (OidcProvider.count("name = ?1 and id <> ?2", provider.name, exceptId) > 0) {
      throw new BadRequestException("a provider with that name already exists");
    }
    if (OidcProvider.count(
            "issuerUrl = ?1 and clientId = ?2 and id <> ?3",
            provider.issuerUrl,
            provider.clientId,
            exceptId)
        > 0) {
      throw new BadRequestException("that issuer URL and client ID are already registered");
    }
  }

  private void validateCreate(ProviderWrite body) {
    if (body == null) {
      throw new BadRequestException("request body is required");
    }
    if (blank(body.name)
        || blank(body.issuerUrl)
        || blank(body.clientId)
        || blank(body.clientSecret)) {
      throw new BadRequestException("name, issuerUrl, clientId, and clientSecret are required");
    }
  }

  private void apply(OidcProvider provider, ProviderWrite body, boolean creating) {
    if (body.name != null) {
      String name = body.name.trim();
      if (!NAME.matcher(name).matches()) {
        throw new BadRequestException(
            "name must be a lowercase slug of 1-63 letters, numbers, or hyphens");
      }
      provider.name = name;
    }
    if (body.issuerUrl != null) {
      provider.issuerUrl = issuer(body.issuerUrl);
    }
    if (body.clientId != null) {
      if (blank(body.clientId)) throw new BadRequestException("clientId must not be blank");
      provider.clientId = body.clientId.trim();
    }
    if (!blank(body.clientSecret)) {
      provider.clientSecret = body.clientSecret;
    } else if (creating) {
      throw new BadRequestException("clientSecret must not be blank");
    }
    if (body.scopes != null) {
      List<String> scopes =
          Arrays.stream(body.scopes)
              .map(String::trim)
              .filter(scope -> !scope.isEmpty())
              .distinct()
              .toList();
      if (scopes.isEmpty() || !scopes.contains("openid")) {
        throw new BadRequestException("scopes must include openid");
      }
      provider.scopes = scopes;
    } else if (creating) {
      provider.scopes = List.of("openid", "profile", "email");
    }
    if (body.enabled != null) provider.enabled = body.enabled;
    else if (creating) provider.enabled = false;
  }

  private String issuer(String raw) {
    try {
      URI uri = new URI(raw.trim());
      if (!uri.isAbsolute()
          || uri.getHost() == null
          || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
        throw new BadRequestException("issuerUrl must be an absolute HTTP(S) URL");
      }
      return uri.toString().replaceFirst("/$", "");
    } catch (URISyntaxException e) {
      throw new BadRequestException("issuerUrl must be an absolute HTTP(S) URL");
    }
  }

  private ProviderDto dto(OidcProvider provider) {
    return new ProviderDto(
        provider.id,
        provider.name,
        provider.issuerUrl,
        provider.clientId,
        provider.scopes,
        provider.enabled,
        managed(provider),
        provider.createdAt,
        provider.updatedAt);
  }

  private boolean managed(OidcProvider provider) {
    return isBootstrapName(provider.name);
  }

  private boolean isBootstrapName(String name) {
    return bootstrapName
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(name::equals)
        .orElse(false);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  public static class ProviderWrite {
    @JsonProperty("name")
    public String name;

    @JsonProperty("issuerUrl")
    public String issuerUrl;

    @JsonProperty("clientId")
    public String clientId;

    @JsonProperty("clientSecret")
    public String clientSecret;

    @JsonProperty("scopes")
    public String[] scopes;

    @JsonProperty("enabled")
    public Boolean enabled;
  }

  public record ProviderDto(
      UUID id,
      String name,
      String issuerUrl,
      String clientId,
      List<String> scopes,
      boolean enabled,
      boolean envManaged,
      Instant createdAt,
      Instant updatedAt) {}
}
