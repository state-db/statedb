package app.statedb.consoleapi.token;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.identity.AdminAllowList;
import app.statedb.persistence.identity.User;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;

@Path("/api/tokens")
public class TokensResource {

  @Inject SecurityIdentity identity;

  @Inject AdminAllowList adminAllowList;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public List<TokenDto> list() {
    SessionUser caller = caller();
    List<ApiToken> tokens = ApiToken.list("userId = ?1 order by createdAt desc", caller.id);
    return tokens.stream().map(TokenDto::from).toList();
  }

  @DELETE
  @Path("/{id}")
  @Transactional
  public Response revoke(@PathParam("id") String rawId) {
    SessionUser caller = caller();
    UUID id = parseIdOr404(rawId);
    ApiToken token = ApiToken.findById(id);
    if (token == null
        || (!token.userId.equals(caller.id) && !adminAllowList.isAdmin(caller.iss, caller.sub))) {
      throw new NotFoundException();
    }
    token.delete();
    return Response.noContent().build();
  }

  private SessionUser caller() {
    if (!(identity.getPrincipal() instanceof JsonWebToken jwt)) {
      throw new NotAuthorizedException("OIDC");
    }
    User user =
        User.<User>find("iss = ?1 and sub = ?2", jwt.getIssuer(), jwt.getSubject())
            .firstResultOptional()
            .orElseThrow(() -> new NotAuthorizedException("OIDC"));
    return new SessionUser(user.id, jwt.getIssuer(), jwt.getSubject());
  }

  private static UUID parseIdOr404(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new NotFoundException();
    }
  }

  private record SessionUser(UUID id, String iss, String sub) {}

  public record TokenDto(
      UUID id,
      String description,
      ApiToken.Kind kind,
      Instant createdAt,
      Instant expiresAt,
      Instant lastUsedAt) {

    static TokenDto from(ApiToken token) {
      return new TokenDto(
          token.id,
          token.description,
          token.kind,
          token.createdAt,
          token.expiresAt,
          token.lastUsedAt);
    }
  }
}
