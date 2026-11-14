package app.statedb.persistence.report;

/**
 * Neutralizes one state-derived string for carriage on {@link ChangeReport}.
 *
 * <p>This is the construction-time half of the sanitization contract (see {@code
 * design/pr-enrichment.md}). It makes a string <em>safe to carry and safe to read</em>: no
 * invisible or direction-flipping codepoints, single-line, bounded length. It deliberately does
 * <b>not</b> do markdown escaping or mention suppression — those are format-specific and belong to
 * a renderer (see {@link Markdown}), because a JSON consumer must receive the real URN, not one
 * with its {@code @} and {@code #} characters rewritten.
 *
 * <p>Every input here is user-controlled. A URN embeds the resource name a program author chose; a
 * property path embeds keys a provider or a program chose. Both reach a surface with a wider
 * audience than the console.
 *
 * <h2>What is removed</h2>
 *
 * The rule is keyed on Unicode general category rather than a hand-listed set, so it stays complete
 * as Unicode grows:
 *
 * <ul>
 *   <li><b>Cc (control)</b> — the ASCII whitespace members (tab, newline, vertical tab, form feed,
 *       carriage return) collapse to a space; the rest are dropped. A newline in a resource name
 *       would otherwise break out of its line in the report.
 *   <li><b>Cf (format)</b> — dropped entirely. This is the bidi class: the overrides {@code
 *       U+202A..U+202E} and isolates {@code U+2066..U+2069} that let a name render right-to-left
 *       and disguise what it says, and the zero-width characters {@code U+200B..U+200D}, {@code
 *       U+FEFF}, {@code U+2060} that let two different names render identically.
 *   <li><b>Cs (surrogate)</b> — an unpaired surrogate becomes {@code U+FFFD}. It cannot be encoded
 *       as UTF-8 and would fail or corrupt a downstream serializer.
 *   <li><b>Zs/Zl/Zp (separators)</b> — collapse to a space, including {@code U+00A0} and {@code
 *       U+2028}/{@code U+2029}.
 * </ul>
 *
 * <p>Runs of whitespace then collapse to a single {@code U+0020} and the ends are trimmed, so the
 * result is one line with no leading or trailing space.
 *
 * <h2>Length</h2>
 *
 * <p>Budgets are in <b>codepoints</b>, not chars and not bytes. Codepoints make truncation land on
 * a character boundary by construction, and they still bound bytes, since UTF-8 encodes a codepoint
 * in at most four.
 *
 * <p>Truncation appends {@link #TRUNCATION_MARKER}, and the marker counts <em>against</em> the
 * budget rather than being added on top of it, so the result never exceeds what the caller asked
 * for. The budget is only reduced once truncation is known to be needed: a value that fills the
 * bound exactly is complete, not truncated, and must not be marked as if content were missing.
 */
public final class ReportText {

  /** Appended to a value that did not fit. Deterministic: the same input always truncates alike. */
  public static final String TRUNCATION_MARKER = "…";

  private static final int MARKER_COST = 1;

  private static final char REPLACEMENT = '�';

  /**
   * A neutralized string and whether anything was dropped for length.
   *
   * <p>{@code truncated} exists so the report can set its own {@code truncated} flag from any field
   * that lost content, rather than a renderer having to guess from a trailing marker that a
   * resource name could legitimately end with.
   */
  public record Text(String value, boolean truncated) {

    public boolean isEmpty() {
      return value.isEmpty();
    }
  }

  /** Neutralizes {@code raw}, bounded to {@code maxCodePoints}. A null or blank input yields "". */
  public static Text neutralize(String raw, int maxCodePoints) {
    if (maxCodePoints <= 0) {
      throw new IllegalArgumentException("maxCodePoints must be positive, got " + maxCodePoints);
    }
    if (raw == null || raw.isEmpty()) {
      return new Text("", false);
    }

    StringBuilder out = new StringBuilder(Math.min(raw.length(), maxCodePoints));
    int emitted = 0;
    boolean pendingSpace = false;
    boolean truncated = false;

    int index = 0;
    while (index < raw.length()) {
      int codePoint = raw.codePointAt(index);
      int width = Character.charCount(codePoint);

      Disposition disposition = classify(codePoint);
      if (disposition == Disposition.DROP) {
        index += width;
        continue;
      }
      if (disposition == Disposition.SPACE) {
        // Leading whitespace is dropped rather than emitted and trimmed afterwards, so the budget
        // is never spent on characters that cannot survive. Trailing whitespace never lands
        // either, because a pending space is only paid for when a keeper follows it.
        pendingSpace = emitted > 0;
        index += width;
        continue;
      }

      int cost = pendingSpace ? 2 : 1;
      if (emitted + cost > maxCodePoints) {
        // More content than the bound allows. The marker has to fit *inside* the bound, so the
        // budget is only reduced once we know there is something to mark — a value that fills the
        // bound exactly is complete, not truncated.
        truncated = true;
        break;
      }
      if (pendingSpace) {
        out.append(' ');
        emitted++;
        pendingSpace = false;
      }
      if (disposition == Disposition.REPLACE) {
        out.append(REPLACEMENT);
      } else {
        out.appendCodePoint(codePoint);
      }
      emitted++;
      index += width;
    }

    if (truncated) {
      trimToCodePoints(out, Math.max(0, maxCodePoints - MARKER_COST));
      out.append(TRUNCATION_MARKER);
    }
    return new Text(out.toString(), truncated);
  }

  /**
   * Proves that a string crossing the {@link ChangeReport} boundary has already been neutralized
   * and bounded.
   *
   * <p>The deriver performs the lossy transform because it owns the report's {@code truncated}
   * flag. Constructors call this method as the backstop: a second producer cannot accidentally
   * bypass neutralization and hand a renderer controls, bidi text, multiline content, or an
   * unbounded field.
   */
  public static String requireNeutralized(String value, int maxCodePoints, String field) {
    if (value == null) {
      return null;
    }
    Text checked = neutralize(value, maxCodePoints);
    if (checked.truncated() || !checked.value().equals(value)) {
      throw new IllegalArgumentException(field + " must be neutralized and bounded");
    }
    return value;
  }

  /** Shortens {@code out} to at most {@code codePoints}, never splitting a surrogate pair. */
  private static void trimToCodePoints(StringBuilder out, int codePoints) {
    int current = out.codePointCount(0, out.length());
    if (current <= codePoints) {
      return;
    }
    out.setLength(out.offsetByCodePoints(0, codePoints));
  }

  private enum Disposition {
    KEEP,
    SPACE,
    DROP,
    REPLACE
  }

  private static Disposition classify(int codePoint) {
    return switch (Character.getType(codePoint)) {
      case Character.CONTROL ->
          codePoint == '\t'
                  || codePoint == '\n'
                  || codePoint == 0x0b
                  || codePoint == '\f'
                  || codePoint == '\r'
              ? Disposition.SPACE
              : Disposition.DROP;
      case Character.FORMAT -> Disposition.DROP;
      case Character.SURROGATE -> Disposition.REPLACE;
      case Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR ->
          Disposition.SPACE;
      default -> Disposition.KEEP;
    };
  }

  private ReportText() {}
}
