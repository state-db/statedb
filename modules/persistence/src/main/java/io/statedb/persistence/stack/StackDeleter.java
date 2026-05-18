package io.statedb.persistence.stack;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/** Deletes a stack by deleting its workspace row and relying on DB-side cascade for child rows. */
@ApplicationScoped
public class StackDeleter {

  @Transactional
  public void delete(StackRef ref, boolean force) {
    StackState state = StackState.find("stackId", ref.stackId()).<StackState>firstResult();
    // TODO(Slice 15+): replace this proxy with a real current-resource check against the latest
    // reconstructed checkpoint. journalSeq > 0 only means checkpoint activity happened; after a
    // successful destroy it can remain > 0 while the stack is safe to remove without force.
    if (state.journalSeq > 0 && !force) {
      throw new StackNotEmptyException(ref.orgName(), ref.projectName(), ref.stackName());
    }

    // TODO: when update leases land, reject activeUpdateId unless force=true.
    Workspace.deleteById(ref.workspaceId());
  }
}
