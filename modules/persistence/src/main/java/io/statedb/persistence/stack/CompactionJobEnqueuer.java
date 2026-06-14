package io.statedb.persistence.stack;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;

@ApplicationScoped
public class CompactionJobEnqueuer {

  @Inject EntityManager em;

  public void enqueue(UUID stackId) {
    // Postgres upsert against the partial unique active-job index coalesces bursts per stack.
    em.createNativeQuery(
            """
            INSERT INTO compaction_jobs (id, stack_id, status)
            VALUES (?1, ?2, 'pending')
            ON CONFLICT (stack_id) WHERE status IN ('pending', 'running') DO NOTHING
            """)
        .setParameter(1, UuidVersion7Strategy.INSTANCE.generateUuid(null))
        .setParameter(2, stackId)
        .executeUpdate();
  }
}
