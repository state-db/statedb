package app.statedb.persistence.entitlement;

import app.statedb.persistence.update.StackUpdate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Deep entitlement module: callers know usage and operation kind, not license mechanics. */
@ApplicationScoped
public class Entitlements {
  public static final long COMMUNITY_STACK_LIMIT = 10;
  static final int MAX_LICENSE_BYTES = 64 * 1024;
  private static final Logger LOG = Logger.getLogger(Entitlements.class);

  // RELEASE BLOCKER: this placeholder has no known private half. Replace it with the production
  // issuer's public key before commercial certificates are issued; never add the private half.
  private static final Map<String, String> VERIFICATION_KEYS =
      Map.of("2026-01", "MCowBQYDK2VwAyEAH7KZ8GOvxY/RWPllwraaJ0Kf229zKjdKq1gwg7Puojg=");

  private final Optional<String> path;
  private final Optional<String> inline;
  private final LicenseVerifier verifier;
  private volatile String lastWarning;

  @Inject
  public Entitlements(
      @ConfigProperty(name = "statedb.license.path") Optional<String> path,
      @ConfigProperty(name = "statedb.license.inline") Optional<String> inline,
      @ConfigProperty(name = "statedb.deployment-id") Optional<String> deploymentId) {
    this(
        path.filter(value -> !value.isBlank()),
        inline.filter(value -> !value.isBlank()),
        new LicenseVerifier(
            VERIFICATION_KEYS,
            Clock.systemUTC(),
            deploymentId.filter(value -> !value.isBlank()).orElse(null)));
  }

  Entitlements(Optional<String> path, Optional<String> inline, LicenseVerifier verifier) {
    this.path = path;
    this.inline = inline;
    this.verifier = verifier;
  }

  public EffectiveEntitlement effective() {
    EffectiveEntitlement result;
    if (path.isPresent()) {
      try {
        byte[] bytes;
        try (var input = Files.newInputStream(Path.of(path.get()))) {
          bytes = input.readNBytes(MAX_LICENSE_BYTES + 1);
        }
        if (bytes.length > MAX_LICENSE_BYTES) {
          result =
              EffectiveEntitlement.community(
                  LicenseStatus.INVALID, "configured license exceeds 64 KiB");
        } else {
          result = verifier.verify(new String(bytes, StandardCharsets.UTF_8));
        }
      } catch (Exception e) {
        result =
            EffectiveEntitlement.community(
                LicenseStatus.INVALID, "configured license file cannot be read");
      }
    } else {
      result =
          inline
              .map(verifier::verify)
              .orElseGet(
                  () ->
                      EffectiveEntitlement.community(
                          LicenseStatus.MISSING, "no license configured"));
    }
    warnOnce(result);
    return result;
  }

  public void requireStackCreationAllowed(long stackCount) {
    requireStackCreationAllowed(stackCount, effective());
  }

  public void requireStackCreationAllowed(long stackCount, EffectiveEntitlement entitlement) {
    if (entitlement.tier() != EffectiveEntitlement.Tier.PAID
        && stackCount >= COMMUNITY_STACK_LIMIT) {
      throw new EntitlementDeniedException(
          "Stack creation", stackCount, COMMUNITY_STACK_LIMIT, entitlement.status());
    }
  }

  public void requireUpdateAllowed(long stackCount, StackUpdate.Kind kind, boolean dryRun) {
    requireUpdateAllowed(stackCount, kind, dryRun, effective());
  }

  public void requireUpdateAllowed(
      long stackCount, StackUpdate.Kind kind, boolean dryRun, EffectiveEntitlement entitlement) {
    if (!requiresPaidEntitlement(kind, dryRun)) return;
    if (entitlement.tier() != EffectiveEntitlement.Tier.PAID
        && stackCount > COMMUNITY_STACK_LIMIT) {
      throw new EntitlementDeniedException(
          "New " + kind.wireValue(), stackCount, COMMUNITY_STACK_LIMIT, entitlement.status());
    }
  }

  public boolean requiresPaidEntitlement(StackUpdate.Kind kind, boolean dryRun) {
    return (!dryRun && kind == StackUpdate.Kind.UPDATE) || kind == StackUpdate.Kind.IMPORT;
  }

  private synchronized void warnOnce(EffectiveEntitlement entitlement) {
    if (entitlement.status() == LicenseStatus.MISSING
        || entitlement.status() == LicenseStatus.VALID) {
      lastWarning = null;
      return;
    }
    String warning = entitlement.status().wireValue() + ": " + entitlement.validationFailure();
    if (!warning.equals(lastWarning)) {
      lastWarning = warning;
      LOG.warnf("License validation failed (%s); using Community entitlement", warning);
    }
  }
}
