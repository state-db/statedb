package app.statedb.persistence.report;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Markdown emission primitives for {@link MarkdownReportRenderer}.
 *
 * <p>This is the render-time half of the sanitization contract. {@link ReportText} has already made
 * every fragment single-line and free of invisible and direction-flipping codepoints; what remains
 * is format-specific, and so cannot live on the value object: a JSON consumer must receive the real
 * URN, not one with its markdown-significant characters rewritten.
 *
 * <h2>Everything from state is emitted as code</h2>
 *
 * <p>{@link #code(String)} emits inline fragments and {@link #diffBlock(String)} emits the compact
 * resource plan with the comment host's native diff highlighting. Inside either code form,
 * GitHub-flavoured markdown interprets nothing: {@code @everyone} does not notify, {@code #123}
 * does not become an issue link, {@code https://…} does not autolink, and HTML and markdown
 * punctuation stay literal. Character-class escaping would have to enumerate every such form and
 * stay correct as GFM grows; code neutralizes the whole class at once.
 *
 * <p>What a code span does not handle on its own is backticks in the content. CommonMark closes a
 * span at the first run of backticks matching the opening run, so content containing backticks
 * needs a longer fence. {@link #code(String)} picks one longer than any run inside, and pads with
 * spaces when the content starts or ends with a backtick, which CommonMark strips on render. {@link
 * #diffBlock(String)} lengthens its fence the same way.
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
   * {@code content} as an inline code span.
   *
   * <p>{@code content} is expected to have been through {@link ReportText#neutralize}; this method
   * assumes it is single-line and does not re-check.
   */
  public static String code(String content) {
    if (content == null || content.isEmpty()) {
      return "";
    }
    String fence = String.valueOf(BACKTICK).repeat(longestBacktickRun(content) + 1);
    // CommonMark strips one leading and one trailing space from a code span, so pad when the
    // content would otherwise abut the fence and either merge with it or lose a character.
    String pad =
        content.charAt(0) == BACKTICK || content.charAt(content.length() - 1) == BACKTICK
            ? " "
            : "";
    return fence + pad + content + pad + fence;
  }

  /** A fenced diff block whose fence cannot be closed by state-derived content. */
  public static String diffBlock(String content) {
    String value = content == null ? "" : content;
    String fence = String.valueOf(BACKTICK).repeat(fenceLength(longestBacktickRun(value)));
    return fence + DIFF_INFO + value + "\n" + fence;
  }

  /**
   * A {@link #diffBlock} built one row at a time, whose size is known before each row is added.
   *
   * <p>The fence grows with the longest backtick run in any row, so adding a row can lengthen both
   * fence lines; tracking that run makes each row cost one measurement rather than a re-render.
   */
  public static final class DiffBlock {
    private final StringBuilder rows = new StringBuilder();
    private int rowBytes;
    private int longestRun;

    /** UTF-8 bytes of the rendered block with {@code row} added. */
    public int bytesWith(String row) {
      int content = rowBytes + (rows.isEmpty() ? 0 : 1) + utf8Length(row);
      int fence = fenceLength(Math.max(longestRun, longestBacktickRun(row)));
      return fence + DIFF_INFO.length() + content + 1 + fence;
    }

    public void add(String row) {
      rowBytes += (rows.isEmpty() ? 0 : 1) + utf8Length(row);
      longestRun = Math.max(longestRun, longestBacktickRun(row));
      rows.append(rows.isEmpty() ? "" : "\n").append(row);
    }

    public String render() {
      return diffBlock(rows.toString());
    }
  }

  private static final String DIFF_INFO = "diff\n";

  private static int fenceLength(int longestRun) {
    return Math.max(3, longestRun + 1);
  }

  private static int utf8Length(String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }

  /**
   * {@code token} bare when it is one unambiguous word, otherwise double-quoted with {@code "} and
   * {@code \} escaped.
   *
   * <p>A diff block has no code spans to separate state from the renderer's own annotations, so a
   * name containing a space, comma, bracket or parenthesis could otherwise imitate them.
   */
  public static String diffToken(String token) {
    if (token != null && !token.isEmpty() && isBareToken(token)) {
      return token;
    }
    return quote(token == null ? "" : token);
  }

  static boolean isBareToken(String token) {
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      boolean safe =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '_'
              || c == '-'
              || c == ':'
              || c == '/'
              || c == '.';
      if (!safe) {
        return false;
      }
    }
    return true;
  }

  static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
