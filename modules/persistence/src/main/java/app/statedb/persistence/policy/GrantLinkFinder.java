package app.statedb.persistence.policy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;

/**
 * Reads every {@code policy_grants} row joined to the names that form its Cedar {@code ?resource}
 * id: the org name (always) and, for per-stack shares, the {@code org/project/stack} path.
 *
 * <p>Escape hatch (EntityManager + HQL): a constructor projection over a multi-table outer join.
 * Panache finders can't express the optional stack join + DTO shape cleanly, and this runs once at
 * startup / reload, not on the request hot path.
 */
@ApplicationScoped
public class GrantLinkFinder {

  @Inject EntityManager em;

  public List<GrantLink> all() {
    return em.createQuery(
            "select new app.statedb.persistence.policy.GrantLink("
                + "g.id, g.templateId, g.principalUserId, o.name, so.name, sp.name, s.name) "
                + "from PolicyGrant g "
                + "join Organization o on o.id = g.orgId "
                + "left join Stack s on s.id = g.resourceStackId "
                + "left join Project sp on sp.id = s.projectId "
                + "left join Organization so on so.id = s.orgId",
            GrantLink.class)
        .getResultList();
  }
}
