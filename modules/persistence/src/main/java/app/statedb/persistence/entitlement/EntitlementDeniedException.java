package app.statedb.persistence.entitlement;

public class EntitlementDeniedException extends RuntimeException {
  private final long stackCount;
  private final long limit;
  private final LicenseStatus licenseStatus;

  public EntitlementDeniedException(
      String action, long stackCount, long limit, LicenseStatus status) {
    super(
        "%s is unavailable: this deployment has %d stacks, the Community limit is %d, and license status is %s. Install a valid state-db license or delete stacks until usage is within the Community limit."
            .formatted(action, stackCount, limit, status.wireValue()));
    this.stackCount = stackCount;
    this.limit = limit;
    this.licenseStatus = status;
  }

  public long stackCount() {
    return stackCount;
  }

  public long limit() {
    return limit;
  }

  public LicenseStatus licenseStatus() {
    return licenseStatus;
  }
}
