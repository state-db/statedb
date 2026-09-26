package app.statedb.persistence.report;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * A Pulumi property path, parsed, so a {@code detailedDiff} key can be resolved against the
 * resource state the same event carries.
 *
 * <p>Ported from {@code sdk/go/common/resource/properties_path.go} at Pulumi v3.259.0, in its
 * non-strict form, which is the form providers actually emit. Two deliberate matches to that source
 * rather than to its published grammar:
 *
 * <ul>
 *   <li>A bare property name is <b>any</b> run of characters up to the next {@code .} or {@code [},
 *       not the identifier production the grammar documents. Pulumi's own comment says the grammar
 *       is aspirational; {@code foo-bar} is one key.
 *   <li>A {@code .} directly followed by a {@code [} is tolerated, because old providers emit it.
 * </ul>
 *
 * <p>The wildcard {@code [*]} parses, but {@link #resolve} does not follow it: a wildcard names a
 * set of values, not one, and the only caller wants a single node. It resolves as a miss, which the
 * caller treats as "could not resolve" and handles conservatively.
 *
 * <p>This exists only to answer whether the value at a path is a secret. Values themselves are
 * never emitted; see {@link ChangeReport}.
 */
public final class PropertyPath {

  private final List<Object> segments;

  private PropertyPath(List<Object> segments) {
    this.segments = segments;
  }

  /** Parses {@code path}, or returns null when it is malformed. A miss is never an exception. */
  public static PropertyPath parse(String path) {
    if (path == null || path.isEmpty() || path.charAt(0) == '.') {
      return null;
    }
    List<Object> segments = new ArrayList<>();
    int i = 0;
    while (i < path.length()) {
      char c = path.charAt(i);
      if (c == '.') {
        i++;
        if (i == path.length()) {
          return null;
        }
      } else if (c == '[') {
        if (i + 1 < path.length() && path.charAt(i + 1) == '"') {
          StringBuilder key = new StringBuilder();
          int j = i + 2;
          boolean closed = false;
          while (j < path.length()) {
            char q = path.charAt(j);
            if (q == '"') {
              j++;
              closed = true;
              break;
            }
            if (q == '\\' && j + 1 < path.length() && path.charAt(j + 1) == '"') {
              key.append('"');
              j += 2;
            } else {
              key.append(q);
              j++;
            }
          }
          if (!closed || j >= path.length() || path.charAt(j) != ']') {
            return null;
          }
          segments.add(key.toString());
          i = j + 1;
        } else {
          int close = path.indexOf(']', i);
          if (close < 0) {
            return null;
          }
          String segment = path.substring(i + 1, close);
          if (segment.equals("*")) {
            segments.add("*");
          } else {
            try {
              segments.add(Integer.valueOf(Integer.parseInt(segment)));
            } catch (NumberFormatException e) {
              return null;
            }
          }
          i = close + 1;
        }
      } else {
        int end = i;
        while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') {
          end++;
        }
        segments.add(path.substring(i, end));
        i = end;
      }
    }
    return segments.isEmpty() ? null : new PropertyPath(segments);
  }

  /**
   * This path re-serialized for the report: simple keys stay dotted, and any other key is quoted in
   * Pulumi's {@code ["..."]} form, so a key cannot imitate the renderer's own annotations.
   */
  public String display() {
    StringBuilder out = new StringBuilder();
    for (Object segment : segments) {
      if (segment instanceof Integer index) {
        out.append('[').append(index).append(']');
      } else if ("*".equals(segment)) {
        out.append("[*]");
      } else {
        String key = (String) segment;
        if (!key.isEmpty() && key.indexOf('.') < 0 && Markdown.isBareToken(key)) {
          out.append(out.length() == 0 ? "" : ".").append(key);
        } else {
          out.append('[').append(Markdown.quote(key)).append(']');
        }
      }
    }
    return out.toString();
  }

  /**
   * The node this path names inside {@code root}, or null when any segment misses.
   *
   * <p>Mirrors {@code PropertyPath.Get}: a string segment indexes an object, an integer segment
   * indexes an array, and a mismatch between segment and container kind is a miss rather than a
   * coercion.
   */
  public JsonNode resolve(JsonNode root) {
    JsonNode current = root;
    for (Object segment : segments) {
      if (current == null || current.isNull()) {
        return null;
      }
      if (segment instanceof Integer index) {
        if (!current.isArray() || index < 0 || index >= current.size()) {
          return null;
        }
        current = current.get(index.intValue());
      } else {
        String key = (String) segment;
        if ("*".equals(key) || !current.isObject()) {
          return null;
        }
        current = current.get(key);
      }
    }
    return current;
  }
}
