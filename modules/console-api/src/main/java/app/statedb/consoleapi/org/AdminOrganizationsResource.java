package app.statedb.consoleapi.org;

import app.statedb.consoleapi.auth.SessionPrincipal;
import app.statedb.persistence.NameGrammar;
import app.statedb.persistence.RequestValidationException;
import app.statedb.persistence.authz.Authorize;
import app.statedb.persistence.authz.SiteEntityLoader;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.stack.Stack;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Path("/api/admin/orgs")
@SessionPrincipal
@Authorize(action = "org:Admin", loader = SiteEntityLoader.class)
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

  @GET
  public List<OrganizationDto> list() {
    return Organization.<Organization>list("order by name").stream().map(this::dto).toList();
  }

  @POST
  @Transactional
  public OrganizationDto create(CreateOrganization body) {
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
    long members =
        OrgMember.count("orgId = ?1 and role in ?2", org.id, OrgMember.Role.humanRoles());
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

  public static class CreateOrganization {
    @JsonProperty("name")
    public String name;

    @JsonProperty("displayName")
    public String displayName;
  }

  public record OrganizationDto(
      UUID id, String name, String displayName, long members, long stacks, Instant createdAt) {}
}
