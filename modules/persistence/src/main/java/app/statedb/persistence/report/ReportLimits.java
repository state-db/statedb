package app.statedb.persistence.report;

/**
 * The bounds a {@link ChangeReport} is built under.
 *
 * <p>"Invariant 2" asks for byte limits at three levels — per field, per row, and per body. They
 * are here, expressed as the three things that actually vary: how long one field may be, how many
 * property rows one resource may carry, and how many resources the whole report may carry. A row's
 * size is then bounded by the product of the first two, and the body by all three, which is what
 * {@link #maxBodyBytes()} is checked against as a backstop rather than as the primary control.
 *
 * <p>Field budgets are in codepoints; see {@link ReportText}. The body budget is in bytes, because
 * that is the unit the receiving host counts in.
 *
 * @param maxResources resources carried before the rest are dropped and the report marked truncated
 * @param maxPropertiesPerResource changed-property paths carried per resource
 * @param maxFieldCodePoints bound on one URN, type, name or property path
 * @param maxSecretScanDepth how deep {@link SecretSentinel#containsSecret} descends before
 *     answering "secret" for an unexamined subtree
 * @param maxBodyBytes UTF-8 bound on a rendered body
 */
public record ReportLimits(
    int maxResources,
    int maxPropertiesPerResource,
    int maxFieldCodePoints,
    int maxSecretScanDepth,
    int maxBodyBytes) {

  /** The construction-time ceiling for every report string. */
  public static final int MAX_FIELD_CODE_POINTS = 256;

  /**
   * Bounds chosen for a pull-request comment.
   *
   * <p>{@code maxBodyBytes} sits well under GitHub's ~65 KB comment limit so that a report which
   * truncates is still a successful POST rather than a rejected one — a truncated comment is a
   * degraded result, a failed comment is no result at all. The resource and property bounds are
   * what keep a body near that budget in the first place; they are set so an ordinary large run
   * renders whole and only a pathological one is cut.
   */
  public static ReportLimits defaults() {
    return new ReportLimits(200, 25, MAX_FIELD_CODE_POINTS, 32, 48_000);
  }

  public ReportLimits {
    requirePositive("maxResources", maxResources);
    requirePositive("maxPropertiesPerResource", maxPropertiesPerResource);
    requirePositive("maxFieldCodePoints", maxFieldCodePoints);
    requirePositive("maxSecretScanDepth", maxSecretScanDepth);
    requirePositive("maxBodyBytes", maxBodyBytes);
    if (maxFieldCodePoints > MAX_FIELD_CODE_POINTS) {
      throw new IllegalArgumentException(
          "maxFieldCodePoints exceeds construction-time ceiling " + MAX_FIELD_CODE_POINTS);
    }
  }

  private static void requirePositive(String name, int value) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive, got " + value);
    }
  }
}
