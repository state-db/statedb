package app.statedb.persistence.events;

import app.statedb.persistence.RequestValidationException;
import app.statedb.persistence.lock.LeaseRevalidator;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Appends a batch of decoded Pulumi engine events. Events are observability data, not state — they
 * have no global ordering invariant, so this path skips the {@code stack_state} FOR UPDATE that
 * journal appends rely on. The composite FK {@code (update_id, stack_id) → stack_updates} rejects
 * any attempt to write events under an update that belongs to a different stack.
 *
 * <p>The lease filter has already validated the bearer token before this method runs, but a forced
 * cancel can delete the lock after admission. Revalidating the lease in the transaction keeps stale
 * event writes from landing after revocation.
 */
@ApplicationScoped
public class EventBatchAppender {

  @Inject EntityManager em;

  @Inject LeaseRevalidator leaseRevalidator;

  @Transactional
  public void append(UUID stackId, UUID updateId, UUID leaseId, List<JsonNode> rawEvents) {
    if (rawEvents == null || rawEvents.isEmpty()) {
      return;
    }

    // 1. Pure validation pass — no DB writes if anything is malformed. View construction also
    //    validates shape (non-null JSON object), so wrap the whole pass.
    List<UpdateEventView> views = new java.util.ArrayList<>(rawEvents.size());
    for (JsonNode raw : rawEvents) {
      try {
        UpdateEventView v = new UpdateEventView(raw);
        v.sequence();
        v.kind();
        views.add(v);
      } catch (IllegalArgumentException e) {
        throw new RequestValidationException(e.getMessage(), e);
      }
    }

    leaseRevalidator.requireLive(leaseId);

    // 2. Insert rows. Order doesn't matter — replay sorts by sequence.
    for (UpdateEventView v : views) {
      UpdateEvent e = new UpdateEvent();
      e.stackId = stackId;
      e.updateId = updateId;
      e.sequence = v.sequence();
      e.kind = v.kind();
      e.severity = v.severity();
      e.payload = v.payload();
      e.persist();
    }

    // 3. Flush so unique-constraint and FK violations surface as typed exceptions here rather
    //    than at commit time (where they'd escape the @Transactional boundary).
    try {
      em.flush();
    } catch (PersistenceException ex) {
      if (isUniqueViolation(ex)) {
        throw new EventSequenceConflictException(updateId, ex);
      }
      throw ex;
    }
  }

  private static boolean isUniqueViolation(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof ConstraintViolationException cve) {
        // Distinguish unique violation (→ 409 sequence conflict) from FK violation (cross-stack
        // smuggling — should propagate as 500, since the lease filter must have already prevented
        // it from reaching here). Postgres SQLSTATE 23505 = unique_violation.
        return cve.getSQLException() != null && "23505".equals(cve.getSQLException().getSQLState());
      }
    }
    return false;
  }
}
