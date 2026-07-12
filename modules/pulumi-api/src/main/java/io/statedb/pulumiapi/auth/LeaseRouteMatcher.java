package io.statedb.pulumiapi.auth;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single owner of the lease-scoped path shape ({@code
 * /pulumi/api/stacks/{org}/{project}/{stack}/update/{updateID}[/...]}): both the "does this route
 * authenticate with {@code Authorization: update-token}?" predicate and the extraction of the path
 * parts. {@link TokenAuthFilter} and {@link LeaseTokenFilter} consult the same predicate so neither
 * owns the path knowledge alone — if a lease-scoped path were missing here, {@link TokenAuthFilter}
 * would 401 the request before {@link LeaseTokenFilter} could validate the update-token.
 */
public final class LeaseRouteMatcher {

  private static final Pattern UPDATE_PATH =
      Pattern.compile("^/pulumi/api/stacks/([^/]+)/([^/]+)/([^/]+)/update/([^/]+)(/.*)?$");

  private LeaseRouteMatcher() {}

  /** The org/project/stack/updateID parts of a path under {@code .../update/{updateID}}. */
  public record UpdatePath(String org, String project, String stack, String updateId) {}

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
    boolean hasSubPath = m.group(5) != null;
    if (hasSubPath) {
      return true;
    }
    return "DELETE".equalsIgnoreCase(method);
  }

  /** Extracts the path parts of any {@code .../update/{updateID}} path, lease-scoped or not. */
  public static Optional<UpdatePath> parse(String path) {
    Matcher m = UPDATE_PATH.matcher(path);
    if (!m.matches()) {
      return Optional.empty();
    }
    return Optional.of(new UpdatePath(m.group(1), m.group(2), m.group(3), m.group(4)));
  }
}
