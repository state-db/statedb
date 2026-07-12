package io.statedb.consoleapi.serviceaccount;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.statedb.consoleapi.auth.SessionPrincipal;
import io.statedb.persistence.RequestValidationException;
import io.statedb.persistence.authz.Authorize;
import io.statedb.persistence.authz.AuthorizedResource;
import io.statedb.persistence.authz.OrgEntityLoader;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.ServiceAccountCreator;
import io.statedb.persistence.identity.ServiceAccountCreator.MintedServiceAccount;
import io.statedb.persistence.identity.ServiceAccountDeleter;
import io.statedb.persistence.identity.ServiceAccountFinder;
import io.statedb.persistence.identity.ServiceAccountView;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Console management of an org's CI service accounts. Owner-only (plus platform admins) via the
 * Cedar {@code serviceaccount:*} actions — {@link SessionPrincipal} bridges the session into the
 * same PDP path the Pulumi surface uses. The raw token appears exactly once, in the create
 * response; only its hash is stored.
 */
@Path("/api/orgs/{org}/service-accounts")
@SessionPrincipal
@Produces(MediaType.APPLICATION_JSON)
public class ServiceAccountsResource {

  @Inject ServiceAccountCreator creator;

  @Inject ServiceAccountFinder finder;

  @Inject ServiceAccountDeleter deleter;

  @Inject AuthorizedResource authorized;

  @POST
  @Authorize(action = "serviceaccount:Create", loader = OrgEntityLoader.class)
  @Consumes(MediaType.APPLICATION_JSON)
  public CreatedServiceAccountDto create(@PathParam("org") String org, CreateRequest body) {
    if (body == null) {
      throw new RequestValidationException("request body is required");
    }
    Organization target = authorized.subject(Organization.class);
    MintedServiceAccount minted = creator.create(target.id, body.name, body.expiresInDays);
    return new CreatedServiceAccountDto(
        minted.accountId(), minted.name(), minted.rawToken(), minted.expiresAt());
  }

  @GET
  @Authorize(action = "serviceaccount:List", loader = OrgEntityLoader.class)
  public List<ServiceAccountDto> list(@PathParam("org") String org) {
    Organization target = authorized.subject(Organization.class);
    return finder.forOrg(target.id).stream().map(ServiceAccountDto::from).toList();
  }

  @DELETE
  @Path("/{id}")
  @Authorize(action = "serviceaccount:Revoke", loader = OrgEntityLoader.class)
  public Response revoke(@PathParam("org") String org, @PathParam("id") String id) {
    Organization target = authorized.subject(Organization.class);
    UUID accountId = parseIdOr404(id);
    if (!deleter.delete(target.id, accountId)) {
      throw new NotFoundException();
    }
    return Response.noContent().build();
  }

  // A malformed id is indistinguishable from an unknown one — tenant isolation over diagnostics.
  private static UUID parseIdOr404(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new NotFoundException();
    }
  }

  public static class CreateRequest {
    @JsonProperty("name")
    public String name;

    @JsonProperty("expiresInDays")
    public Integer expiresInDays;
  }

  /** The one-time create response — the only place the raw token ever appears. */
  public record CreatedServiceAccountDto(
      @JsonProperty("id") UUID id,
      @JsonProperty("name") String name,
      @JsonProperty("token") String token,
      @JsonProperty("expiresAt") Instant expiresAt) {}

  public record ServiceAccountDto(
      @JsonProperty("id") UUID id,
      @JsonProperty("name") String name,
      @JsonProperty("createdAt") Instant createdAt,
      @JsonProperty("tokenExpiresAt") Instant tokenExpiresAt,
      @JsonProperty("tokenLastUsedAt") Instant tokenLastUsedAt) {

    static ServiceAccountDto from(ServiceAccountView v) {
      return new ServiceAccountDto(
          v.id(), v.name(), v.createdAt(), v.tokenExpiresAt(), v.tokenLastUsedAt());
    }
  }
}
