package app.statedb.persistence.stack;

import app.statedb.persistence.RequestValidationException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.UUID;

/**
 * Replaces a stack's tag map wholesale — {@code pulumi stack tag set}/{@code rm} PATCH the full
 * desired set, not a delta. Tag edits don't take a CLI state lock, so the read-modify-write is
 * serialized against concurrent stack mutations with a {@code PESSIMISTIC_WRITE} row lock, the same
 * way {@link StackImporter} guards its lease-less write. Holding the row lock means a tag write
 * loads {@code active_update_id}/{@code version} under lock and writes them back unchanged, so it
 * can't clobber a concurrent update.
 */
@ApplicationScoped
public class StackTagWriter {

  static final int MAX_TAGS = 50;
  // Mirrors Pulumi's own rules (pkg/util/validation/stack.go): tag names are 1-40 chars of
  // [a-zA-Z0-9-_.:]; values are capped at 256. The CLI sends pulumi:description (the project
  // description, often >100 chars) as a tag on every `stack tag` operation, so a tighter value
  // cap rejects valid CLI traffic.
  static final int MAX_KEY_LENGTH = 40;
  static final int MAX_VALUE_LENGTH = 256;
  private static final java.util.regex.Pattern TAG_NAME =
      java.util.regex.Pattern.compile("^[a-zA-Z0-9-_.:]{1,40}$");

  @Transactional
  public void replaceTags(UUID stackId, Map<String, String> tags) {
    Map<String, String> safe = tags == null ? Map.of() : tags;
    validate(safe);

    Stack stack =
        Stack.<Stack>find("id", stackId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
    if (stack == null) {
      return;
    }
    stack.tags = safe;
    stack.persist();
  }

  private static void validate(Map<String, String> tags) {
    if (tags.size() > MAX_TAGS) {
      throw new RequestValidationException(
          "a stack may have at most " + MAX_TAGS + " tags, got " + tags.size());
    }
    for (Map.Entry<String, String> tag : tags.entrySet()) {
      String key = tag.getKey();
      String value = tag.getValue();
      if (key.length() > MAX_KEY_LENGTH) {
        throw new RequestValidationException(
            "tag key exceeds " + MAX_KEY_LENGTH + " characters: '" + key + "'");
      }
      if (!TAG_NAME.matcher(key).matches()) {
        throw new RequestValidationException(
            "stack tag names may only contain alphanumerics, hyphens, underscores, periods, or"
                + " colons: '"
                + key
                + "'");
      }
      if (value != null && value.length() > MAX_VALUE_LENGTH) {
        throw new RequestValidationException(
            "tag value for '" + key + "' exceeds " + MAX_VALUE_LENGTH + " characters");
      }
    }
  }
}
