package io.statedb.pulumiapi.auth;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single owner of update-data path shapes. {@link TokenAuthFilter} and {@link LeaseTokenFilter}
 * consult the same predicate so neither owns the path knowledge alone — if a lease-scoped path were
 * missing here, {@link TokenAuthFilter} would 401 the request before {@link LeaseTokenFilter} could
 * validate the update-token.
 */
public final class LeaseRouteMatcher {

  private static final Pattern UPDATE_PATH =
      Pattern.compile("^/pulumi/api/stacks/([^/]+)/([^/]+)/([^/]+)/update/([^/]+)(/.*)?$");

  private static final Pattern FORCED_CANCEL_PATH =
      Pattern.compile("^/pulumi/api/stacks/([^/]+)/([^/]+)/([^/]+)/update/([^/]+)/cancel$");

  private LeaseRouteMatcher() {}

  /** The org/project/stack/updateID parts of a path under {@code .../update/{updateID}}. */
  public record UpdatePath(String org, String project, String stack, String updateId) {}

  /**
   * True when the request authenticates via {@code update-token}. Pulumi's forced cancel endpoint
   * is deliberately absent: {@code POST .../cancel} is invoked by a second CLI that does not hold
   * the lease, so it uses normal API-token auth.
   */
  public static boolean isLeaseScoped(String method, String path) {
    var m = UPDATE_PATH.matcher(path);
    if (!m.matches()) {
      return false;
    }
    String subPath = m.group(5);
    if (subPath == null) {
      return "DELETE".equalsIgnoreCase(method);
    }
    return switch (subPath) {
      case "/renew_lease", "/events/batch", "/complete" -> "POST".equalsIgnoreCase(method);
      case "/journalentries", "/checkpoint", "/checkpointdelta", "/checkpointverbatim" ->
          "PATCH".equalsIgnoreCase(method);
      default -> false;
    };
  }

  /** Extracts the path parts of any {@code .../update/{updateID}} path, lease-scoped or not. */
  public static Optional<UpdatePath> parse(String path) {
    Matcher m = UPDATE_PATH.matcher(path);
    if (!m.matches()) {
      return Optional.empty();
    }
    return Optional.of(new UpdatePath(m.group(1), m.group(2), m.group(3), m.group(4)));
  }

  /** Extracts path parts for Pulumi's normal-token forced cancellation endpoint. */
  public static Optional<UpdatePath> parseForcedCancel(String method, String path) {
    if (!"POST".equalsIgnoreCase(method)) {
      return Optional.empty();
    }
    Matcher m = FORCED_CANCEL_PATH.matcher(path);
    if (!m.matches()) {
      return Optional.empty();
    }
    return Optional.of(new UpdatePath(m.group(1), m.group(2), m.group(3), m.group(4)));
  }
}
