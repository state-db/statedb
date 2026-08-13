package app.statedb.app.ops;

import app.statedb.persistence.auth.AuthenticatedPrincipal;
import app.statedb.persistence.authz.AuthorizationDecision;
import app.statedb.persistence.authz.Authorizer;
import app.statedb.persistence.authz.CedarResource;
import app.statedb.persistence.identity.OrgMember;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.value.EntityTypeName;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimString;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

/**
 * Proves the Cedar native library and its cached authorization path work before serving traffic.
 * Owner-allow and viewer-deny for {@code org:ManageMembers} are intentional policy invariants; a
 * policy change must update this check explicitly rather than silently weakening readiness.
 */
@Readiness
@Startup
@ApplicationScoped
public class CedarAuthorizationReadinessCheck implements HealthCheck {

  private static final Logger LOG = Logger.getLogger(CedarAuthorizationReadinessCheck.class);
  private static final UUID PRINCIPAL_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

  @Inject Authorizer authorizer;

  private volatile Throwable failure;

  @PostConstruct
  void verify() {
    try {
      AuthenticatedPrincipal principal =
          AuthenticatedPrincipal.consoleSession(PRINCIPAL_ID, "readiness", "readiness");
      EntityUID resourceUid = uid("Org", "statedb:org:readiness");
      CedarResource resource =
          new CedarResource(
              resourceUid,
              Set.of(
                  new Entity(
                      resourceUid,
                      Map.of("id", new PrimString("statedb:org:readiness")),
                      Set.of())));

      AuthorizationDecision allowed =
          authorizer.authorize(principal, OrgMember.Role.owner, "org:ManageMembers", resource);
      AuthorizationDecision denied =
          authorizer.authorize(principal, OrgMember.Role.viewer, "org:ManageMembers", resource);
      if (!allowed.allowed() || denied.allowed()) {
        throw new IllegalStateException("Cedar self-test returned an unsafe decision");
      }
    } catch (Throwable t) {
      failure = t;
      LOG.error("Cedar production authorization self-test failed", t);
    }
  }

  @Override
  public HealthCheckResponse call() {
    return failure == null
        ? HealthCheckResponse.up("Cedar authorization self-test")
        : HealthCheckResponse.named("Cedar authorization self-test")
            .down()
            .withData("error", failure.getClass().getSimpleName())
            .build();
  }

  private static EntityUID uid(String type, String id) {
    return EntityTypeName.parse(type).orElseThrow().of(id);
  }
}
