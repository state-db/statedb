package io.statedb.pulumiapi.unsupported;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Stubs the organization-scoped Pulumi Cloud surfaces state-db does not implement: {@code org
 * member list}, {@code org role list}, {@code org webhook list}, {@code org audit-log list}, {@code
 * org usage get}, {@code org search}, and {@code policy ls}. state-db is a state backend, not an
 * organization/governance service.
 *
 * <p>Each path matches the whole surface — the trailing {@code {sub:(/.*)?}} covers the nested read
 * and write paths (for example {@code policypacks/{name}/{version}/complete}), so the CLI gets the
 * same answer whichever command it runs. Verbs are limited to the ones the CLI uses on these
 * surfaces; a {@code PATCH} or {@code DELETE} still falls through to a bare 404.
 *
 * <p>No {@code @Authorize} (nothing is loaded) — {@link io.statedb.pulumiapi.auth.TokenAuthFilter}
 * still enforces 401 globally.
 */
@Path("/pulumi/api/orgs/{org}")
public class UnsupportedOrgResource {

  private static final String MEMBERS = "organization members";
  private static final String ROLES = "organization custom roles";
  private static final String WEBHOOKS = "organization webhooks";
  private static final String AUDIT_LOGS = "organization audit logs";
  private static final String USAGE = "organization resource usage summaries";
  private static final String SEARCH = "organization resource searches";
  private static final String POLICY_PACKS = "policy packs";

  @GET
  @Path("/members{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response members() {
    return UnsupportedFeature.notSupported(MEMBERS);
  }

  @POST
  @Path("/members{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response addMember() {
    return UnsupportedFeature.notSupported(MEMBERS);
  }

  @GET
  @Path("/roles{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response roles() {
    return UnsupportedFeature.notSupported(ROLES);
  }

  @POST
  @Path("/roles{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response createRole() {
    return UnsupportedFeature.notSupported(ROLES);
  }

  @GET
  @Path("/hooks{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response webhooks() {
    return UnsupportedFeature.notSupported(WEBHOOKS);
  }

  @POST
  @Path("/hooks{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response createWebhook() {
    return UnsupportedFeature.notSupported(WEBHOOKS);
  }

  @GET
  @Path("/auditlogs{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response auditLogs() {
    return UnsupportedFeature.notSupported(AUDIT_LOGS);
  }

  @GET
  @Path("/resources/summary")
  @Produces(MediaType.APPLICATION_JSON)
  public Response usageSummary() {
    return UnsupportedFeature.notSupported(USAGE);
  }

  @GET
  @Path("/search/resources{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response searchResources() {
    return UnsupportedFeature.notSupported(SEARCH);
  }

  @GET
  @Path("/policypacks{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response policyPacks() {
    return UnsupportedFeature.notSupported(POLICY_PACKS);
  }

  @POST
  @Path("/policypacks{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response publishPolicyPack() {
    return UnsupportedFeature.notSupported(POLICY_PACKS);
  }
}
