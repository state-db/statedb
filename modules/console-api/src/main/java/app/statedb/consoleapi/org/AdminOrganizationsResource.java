package app.statedb.consoleapi.org;

import app.statedb.consoleapi.auth.SessionPrincipal;
import app.statedb.persistence.NameGrammar;
import app.statedb.persistence.RequestValidationException;
import app.statedb.persistence.identity.AdminAllowList;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.stack.Stack;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;

@Path("/api/admin/orgs")
@SessionPrincipal
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminOrganizationsResource {
  private static final Set<String> RESERVED =
      Set.of(
          "api",
          "pulumi",
          "tofu",
          "console",
          "assets",
          "cli-login",
          "welcome",
          "account",
          "user",
          "favicon.ico");
  private static final List<OrgMember.Role> HUMAN_ROLES =
      List.of(
          OrgMember.Role.owner, OrgMember.Role.member, OrgMember.Role.viewer, OrgMember.Role.guest);

  @Inject SecurityIdentity identity;
  @Inject AdminAllowList adminAllowList;

  @GET
  public List<OrganizationDto> list() {
    requireAdmin();
    return Organization.<Organization>list("order by name").stream().map(this::dto).toList();
  }

  @POST
  @Transactional
  public OrganizationDto create(CreateOrganization body) {
    requireAdmin();
    if (body == null
        || body.name == null
        || body.displayName == null
        || body.displayName.isBlank()) {
      throw new RequestValidationException("name and displayName are required");
    }
    String name = body.name.trim();
    NameGrammar.requireValid("organization name", name);
    if (RESERVED.contains(name.toLowerCase())) {
      throw new RequestValidationException("organization name is reserved");
    }
    if (Organization.count("name", name) > 0) {
      throw new RequestValidationException("organization name already exists");
    }
    Organization org = new Organization();
    org.name = name;
    org.displayName = body.displayName.trim();
    org.persistAndFlush();
    return dto(org);
  }

  @DELETE
  @Path("/{id}")
  @Transactional
  public Response delete(@PathParam("id") String rawId) {
    requireAdmin();
    Organization org = find(rawId);
    if (Stack.count("orgId", org.id) > 0) {
      return Response.status(Response.Status.CONFLICT)
          .entity(java.util.Map.of("error", "organization still contains stacks"))
          .build();
    }
    org.delete();
    return Response.ok().build();
  }

  private OrganizationDto dto(Organization org) {
    long members = OrgMember.count("orgId = ?1 and role in ?2", org.id, HUMAN_ROLES);
    return new OrganizationDto(
        org.id, org.name, org.displayName, members, Stack.count("orgId", org.id), org.createdAt);
  }

  private static Organization find(String rawId) {
    try {
      return Organization.<Organization>findByIdOptional(UUID.fromString(rawId))
          .orElseThrow(NotFoundException::new);
    } catch (IllegalArgumentException e) {
      throw new NotFoundException();
    }
  }

  private void requireAdmin() {
    if (!(identity.getPrincipal() instanceof JsonWebToken jwt)
        || !adminAllowList.isAdmin(jwt.getIssuer(), jwt.getSubject()))
      throw new ForbiddenException();
  }

  public static class CreateOrganization {
    @JsonProperty("name")
    public String name;

    @JsonProperty("displayName")
    public String displayName;
  }

  public record OrganizationDto(
      UUID id, String name, String displayName, long members, long stacks, Instant createdAt) {}
}
