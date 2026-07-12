package io.statedb.persistence;

import java.util.regex.Pattern;

/**
 * The single grammar for org, project, and stack names: 1–100 characters from {@code
 * [A-Za-z0-9._-]}, matching what the Pulumi CLI itself accepts.
 *
 * <p>This is a security boundary, not cosmetics. Cedar resource ids are built by joining these
 * names with {@code /} ({@link io.statedb.persistence.authz.ResourceName}), and the auth filters
 * parse request paths by splitting on {@code /} — a name containing a slash would let two distinct
 * stacks collide on one Cedar entity id (so a policy grant for one would apply to the other) and
 * would desynchronize path parsing from route matching. The {@code *_name_grammar} CHECK
 * constraints (V011) enforce the same regex in the database so no write path can bypass it.
 */
public final class NameGrammar {

  private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");

  private NameGrammar() {}

  public static boolean isValid(String name) {
    return name != null && VALID.matcher(name).matches();
  }

  /**
   * @param kind human label for the error message, e.g. {@code "stack name"}
   * @throws RequestValidationException (400) when the name is outside the grammar
   */
  public static void requireValid(String kind, String name) {
    if (!isValid(name)) {
      throw new RequestValidationException(
          kind + " must be 1-100 characters of [A-Za-z0-9._-], got '" + name + "'");
    }
  }
}
