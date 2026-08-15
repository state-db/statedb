package app.statedb.persistence.entitlement;

public enum LicenseStatus {
  VALID,
  MISSING,
  INVALID,
  NOT_YET_VALID,
  EXPIRED;

  public String wireValue() {
    return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
  }
}
