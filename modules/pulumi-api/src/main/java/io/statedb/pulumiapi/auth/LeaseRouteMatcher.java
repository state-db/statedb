package io.statedb.pulumiapi.auth;

import java.util.regex.Pattern;

/**
 * Shared predicate identifying routes that authenticate with {@code Authorization: update-token}
 * rather than the regular {@code Authorization: token} scheme. Both {@link TokenAuthFilter} and
 * {@link LeaseTokenFilter} consult this so neither owns the path-shape knowledge alone — if a
 * lease-scoped path were missing here, {@link TokenAuthFilter} would 401 the request before {@link
 * LeaseTokenFilter} could validate the update-token.
 */
public final class LeaseRouteMatcher {

  private static final Pattern UPDATE_PATH =
      Pattern.compile("^/pulumi/api/stacks/[^/]+/[^/]+/[^/]+/update/[^/]+(/.*)?$");

  private LeaseRouteMatcher() {}

  /**
   * True when the request authenticates via {@code update-token}:
   *
   * <ul>
   *   <li>{@code DELETE /pulumi/api/stacks/{org}/{project}/{stack}/update/{updateID}} (cancel)
   *   <li>Any method on a deeper sub-path (renew_lease, checkpoint, events/batch, complete — added
   *       in later slices)
   * </ul>
   *
   * {@code POST} on the bare {@code .../update/{updateID}} path (acquire) is the one exception —
   * the acquire itself uses the regular {@code token} scheme since the lease doesn't exist yet.
   */
  public static boolean isLeaseScoped(String method, String path) {
    var m = UPDATE_PATH.matcher(path);
    if (!m.matches()) {
      return false;
    }
    boolean hasSubPath = m.group(1) != null;
    if (hasSubPath) {
      return true;
    }
    return "DELETE".equalsIgnoreCase(method);
  }
}
