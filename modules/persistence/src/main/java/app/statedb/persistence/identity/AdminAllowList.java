package app.statedb.persistence.identity;

import app.statedb.persistence.bootstrap.BootstrapProvisioner;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The admin allow-list, read once from {@code STATEDB_ADMIN_SUBJECTS} at boot.
 *
 * <p>Admin status is env-driven, not stored in the DB (see design/auth.md): an operator lists the
 * canonical {@code <iss>:<sub>} identity of each admin, comma-separated. There is deliberately no
 * {@code users.is_admin} column — the env is the single source of truth and the membership is
 * re-derived from it on every check.
 *
 * <p>The {@code <iss>} part is itself a URL containing colons (e.g. {@code
 * https://accounts.google.com}), so an entry like {@code https://accounts.google.com:115...} is the
 * issuer followed by {@code :} followed by the subject. Matching is exact-string against {@code iss
 * + ":" + sub}; we never split an entry on {@code :}.
 *
 * <p>This is the single source of truth for "is this principal a platform admin": the env-listed
 * subjects plus the reserved bootstrap break-glass identity ({@link
 * BootstrapProvisioner#BOOTSTRAP_ISS} / {@link BootstrapProvisioner#BOOTSTRAP_SUB}), which is admin
 * so a fresh deployment is administrable before any OIDC subject is configured. Both the {@code
 * /api/user} {@code siteAdmin} flag and the Cedar authorization layer (Authorizer + entity loaders)
 * resolve admin status through here, so they cannot drift apart.
 */
@ApplicationScoped
public class AdminAllowList {

  private final Set<String> subjects;

  public AdminAllowList(@ConfigProperty(name = "statedb.admin.subjects") Optional<String> raw) {
    this.subjects = parse(raw.orElse(""));
  }

  /** True iff {@code iss:sub} is the bootstrap admin or appears in the configured allow-list. */
  public boolean isAdmin(String iss, String sub) {
    if (BootstrapProvisioner.BOOTSTRAP_ISS.equals(iss)
        && BootstrapProvisioner.BOOTSTRAP_SUB.equals(sub)) {
      return true;
    }
    return subjects.contains(iss + ":" + sub);
  }

  static Set<String> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }
}
