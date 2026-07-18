package io.statedb.persistence.authz;

import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.stack.Stack;
import java.util.function.BooleanSupplier;

/**
 * The in-memory arm of the read-visibility predicate (design/stack-sharing.md §9): mirrors the
 * Cedar read policies exactly as {@code StackListFinder}'s SQL does, and is what {@code
 * StackEntityLoader} uses to collapse unreadable stacks to 404. Pure logic so the list/PDP
 * equivalence test can pin it to the PDP alongside the SQL arm.
 */
public final class StackReadVisibility {

  private StackReadVisibility() {}

  /**
   * Whether a principal holding {@code role} in the stack's org may read a stack of the given
   * visibility. {@code hasReadShare} is consulted lazily — only the tiers whose read can come from
   * a per-stack share pay the grant lookup.
   */
  public static boolean canRead(
      OrgMember.Role role, String visibility, BooleanSupplier hasReadShare) {
    return switch (role) {
      // owner.cedar / service.cedar: unconditional org-wide read.
      case owner, service -> true;
      // viewer.cedar (member inherits): internal stacks; shares fill in private ones.
      case member, viewer ->
          Stack.VISIBILITY_INTERNAL.equals(visibility) || hasReadShare.getAsBoolean();
      // Guests match no static policy: shares are all they have.
      case guest -> hasReadShare.getAsBoolean();
    };
  }
}
