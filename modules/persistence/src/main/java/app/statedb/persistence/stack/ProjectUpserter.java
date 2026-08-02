package app.statedb.persistence.stack;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;

/**
 * Concurrency-safe project upsert shared by {@link StackCreator} and {@link StackRenamer}: {@code
 * ON CONFLICT DO NOTHING} then {@code SELECT}. Two concurrent callers that target the same {@code
 * (org, name)} project must not produce two project rows; the loser of the insert race still reads
 * back the winner's row.
 */
@ApplicationScoped
public class ProjectUpserter {

  @Inject EntityManager em;

  public UUID upsert(UUID orgId, String name) {
    em.createNativeQuery(
            "INSERT INTO projects (id, org_id, name) VALUES (?1, ?2, ?3) "
                + "ON CONFLICT (org_id, name) DO NOTHING")
        .setParameter(1, UuidVersion7Strategy.INSTANCE.generateUuid(null))
        .setParameter(2, orgId)
        .setParameter(3, name)
        .executeUpdate();
    return Project.find("orgId = ?1 AND name = ?2", orgId, name).<Project>firstResult().id;
  }
}
