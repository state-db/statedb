package app.statedb.persistence.stack;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;

/** Loads stack-head metadata after a tenant-isolated {@link StackRef} has been resolved. */
@ApplicationScoped
public class StackHeadFinder {

  public Optional<StackHead> find(StackRef ref) {
    return Stack.<Stack>findByIdOptional(ref.stackId())
        .map(
            s ->
                new StackHead(
                    ref.stackId(),
                    ref.orgId(),
                    ref.orgName(),
                    ref.projectName(),
                    ref.stackName(),
                    s.tags == null ? Map.of() : s.tags,
                    ref.version(),
                    ref.activeUpdateId() == null ? "" : ref.activeUpdateId().toString(),
                    null,
                    0));
  }
}
