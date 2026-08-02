package app.statedb.persistence.stack;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the {@link StackHead} for a {@code (org, project, stack)} path triple. Uses the
 * {@code @ManyToOne} navigation on {@link Stack} to keep this in idiomatic Panache; {@code join
 * fetch} collapses the chain into a single SQL statement so callers don't pay the lazy-load round
 * trips.
 */
@ApplicationScoped
public class StackHeadFinder {

  public Optional<StackHead> find(String org, String project, String stack) {
    return Stack.<Stack>find(
            "from Stack s "
                + "join fetch s.project p "
                + "join fetch p.organization o "
                + "where o.name = ?1 and p.name = ?2 and s.name = ?3",
            org,
            project,
            stack)
        .firstResultOptional()
        .map(
            s ->
                new StackHead(
                    s.id,
                    s.orgId,
                    s.project.organization.name,
                    s.project.name,
                    s.name,
                    s.tags == null ? Map.of() : s.tags,
                    s.version,
                    s.activeUpdateId == null ? "" : s.activeUpdateId.toString(),
                    null,
                    0));
  }
}
