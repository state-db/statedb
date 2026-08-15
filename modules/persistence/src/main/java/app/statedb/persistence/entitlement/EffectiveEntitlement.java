package app.statedb.persistence.entitlement;

import java.time.Instant;

public record EffectiveEntitlement(
    Tier tier, LicenseStatus status, Long stackLimit, Instant expiresAt, String validationFailure) {

  public enum Tier {
    COMMUNITY,
    PAID
  }

  public static EffectiveEntitlement community(LicenseStatus status, String failure) {
    return new EffectiveEntitlement(
        Tier.COMMUNITY, status, Entitlements.COMMUNITY_STACK_LIMIT, null, failure);
  }

  public static EffectiveEntitlement paid(Instant expiresAt) {
    return new EffectiveEntitlement(Tier.PAID, LicenseStatus.VALID, null, expiresAt, null);
  }
}
