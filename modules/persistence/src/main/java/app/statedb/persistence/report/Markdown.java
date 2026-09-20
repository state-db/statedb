package app.statedb.persistence.report;

import java.net.URI;

/**
 * Markdown emission primitives for {@link MarkdownReportRenderer}.
 *
 * <p>This is the render-time half of the sanitization contract. {@link ReportText} has already made
 * every fragment single-line and free of invisible and direction-flipping codepoints; what remains
 * is format-specific, and so cannot live on the value object: a JSON consumer must receive the real
 * URN, not one with its markdown-significant characters rewritten.
 *
 * <h2>Everything from state is emitted as a code span</h2>
 *
 * <p>{@link #code(String)} is the only way a state-derived fragment enters the body, and that is a
 * structural decision rather than a stylistic one. Inside a code span, GitHub-flavoured markdown
 * interprets nothing: {@code @everyone} does not notify, {@code #123} does not become an issue
 * link, {@code https://…} does not autolink, {@code <img onerror=…>} is not HTML, and {@code *},
 * {@code _}, {@code [} and {@code ]} are literal. Character-class escaping would have to enumerate
 * every such form and stay correct as GFM grows; a code span neutralizes the whole class at once.
 *
 * <p>Two things a code span does not handle on its own, and this class does:
 *
 * <ul>
 *   <li><b>Backticks in the content.</b> CommonMark closes a span at the first run of backticks
 *       matching the opening run, so content containing backticks needs a longer fence. {@link
 *       #code(String)} picks one longer than any run inside, and pads with spaces when the content
 *       starts or ends with a backtick, which CommonMark strips on render.
 *   <li><b>A pipe inside a table cell.</b> GFM splits table rows on {@code |} <em>before</em> it
 *       parses cell contents, so a literal pipe breaks the row even inside a code span. It must be
 *       backslash-escaped, which GFM then unescapes back to a pipe.
 * </ul>
 *
 * <h2>Links</h2>
 *
 * <p>{@link #link(String, URI)} is the only link form emitted, and its target is never derived from
 * state — it is statedb's own console URL, passed in by the caller. {@link #isEmittableLink(URI)}
 * is the guard that keeps it that way.
 */
public final class Markdown {

  private static final char BACKTICK = '`';

  /**
   * {@code content} as an inline code span, safe to place anywhere in a body including a table
   * cell.
   *
   * <p>{@code content} is expected to have been through {@link ReportText#neutralize}; this method
   * assumes it is single-line and does not re-check.
   */
  public static String code(String content) {
    if (content == null || content.isEmpty()) {
      return "";
    }
    String escaped = content.replace("|", "\\|");
    String fence = String.valueOf(BACKTICK).repeat(longestBacktickRun(escaped) + 1);
    // CommonMark strips one leading and one trailing space from a code span, so pad when the
    // content would otherwise abut the fence and either merge with it or lose a character.
    String pad =
        escaped.charAt(0) == BACKTICK || escaped.charAt(escaped.length() - 1) == BACKTICK
            ? " "
            : "";
    return fence + pad + escaped + pad + fence;
  }

  /** A markdown link. {@code target} must have passed {@link #isEmittableLink(URI)}. */
  public static String link(String label, URI target) {
    if (!isEmittableLink(target)) {
      throw new IllegalArgumentException("refusing to emit a link to a non-emittable target");
    }
    return "[" + label + "](" + target.toASCIIString() + ")";
  }

  /**
   * Whether {@code target} may be emitted as a link.
   *
   * <p>The URL policy in "Invariant 2" is that only statedb's own console links are links and
   * anything from state is plain text. This method does not and cannot decide whose URL it is —
   * that is the caller's job, and the caller only ever passes a URL it constructed itself. What
   * this guards is the rest: an absolute {@code http}/{@code https} URL with a host, so a {@code
   * javascript:} or {@code data:} target, or a relative reference that would resolve against the
   * viewing host, cannot become a link even by mistake. {@link URI#toASCIIString()} then
   * percent-encodes anything outside ASCII, so no character in the emitted target can close the
   * markdown link and start new content.
   */
  public static boolean isEmittableLink(URI target) {
    if (target == null || !target.isAbsolute() || target.getHost() == null) {
      return false;
    }
    String scheme = target.getScheme();
    return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
  }

  private static int longestBacktickRun(String content) {
    int longest = 0;
    int run = 0;
    for (int i = 0; i < content.length(); i++) {
      if (content.charAt(i) == BACKTICK) {
        run++;
        longest = Math.max(longest, run);
      } else {
        run = 0;
      }
    }
    return longest;
  }

  private Markdown() {}
}
