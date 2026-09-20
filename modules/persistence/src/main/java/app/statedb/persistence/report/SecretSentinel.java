package app.statedb.persistence.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Recognises Pulumi's structural markers for special property values, and in particular the two
 * that carry secrets.
 *
 * <p>Pinned against Pulumi SDK v3.259.0 {@code sdk/go/common/resource/sig/sig.go} (re-exported from
 * {@code properties.go}) and against the recorded preview fixture under {@code
 * tools/pulumi/recorder/fixtures/by-command/04-preview/}. See "The secret sentinel" in {@code
 * design/pr-enrichment.md}.
 *
 * <p><b>Detection is structural, never a property-name heuristic.</b> A property called {@code
 * password} holding a plain string is not a secret; a property called {@code length} holding a
 * {@link #SECRET} wrapper is. Only the wrapper tells the truth, and only the wrapper survives a
 * provider renaming its fields.
 *
 * <p><b>There are two secret-bearing shapes, not one.</b> {@code MarshalPropertyValue} emits {@link
 * #SECRET} from its {@code IsSecret} branch, but its {@code IsOutput} branch — and its {@code
 * KeepOutputValues && UpgradeToOutputValues} path — emits an {@link #OUTPUT_VALUE} wrapper carrying
 * {@code "secret": true} whose {@code "value"} is <em>plaintext</em>. A redactor that knows only
 * {@link #SECRET} leaks the second one.
 *
 * <p><b>An unrecognised signature is treated as secret.</b> Pulumi's own unmarshaller refuses one
 * outright ({@code "unrecognized signature '%v' in property map"}), so a signature we do not know
 * is a shape we do not understand, and the safe reading of a value we do not understand is that it
 * may be sensitive.
 */
public final class SecretSentinel {

  /**
   * The reserved key under which every special value carries its type signature. {@code
   * resource.SigKey}.
   */
  public static final String SIG_KEY = "4dabf18193072939515e22adb298388d";

  /** {@code resource.SecretSig}. The wrapper is {@code {SIG_KEY: SECRET, "value"|"ciphertext"}}. */
  public static final String SECRET = "1b47061264138c4ac30d75fd1eb44270";

  /** {@code resource.OutputValueSig}. Secret when the wrapper carries {@code "secret": true}. */
  public static final String OUTPUT_VALUE = "d0e6a833031e9bbcd3f4e8bde6ca49a4";

  /** {@code resource.ResourceReferenceSig}. */
  public static final String RESOURCE_REFERENCE = "5cf8f73096256a8f31e491e813e4eb8e";

  /** {@code resource.AssetSig}. */
  public static final String ASSET = "c44067f5952c0a294b673a41bacd8c17";

  /** {@code resource.ArchiveSig}. */
  public static final String ARCHIVE = "0def7320c3a5731c473e5ecbe6d01bc7";

  /** {@code resource.ByteStringSig} — a string whose bytes are not valid UTF-8, base64-encoded. */
  public static final String BYTE_STRING = "803fd3297a5875dc03ca845dda5d2a98";

  /**
   * The signature carried by {@code node}, or null when {@code node} is not a signed wrapper.
   *
   * <p>Mirrors {@code resource.HasSig}: the node must be an object, and the value at {@link
   * #SIG_KEY} must be a <b>string</b>. A non-string there (a number, an object, {@code null}) is
   * not a signature, and the node is an ordinary object that happens to use the key.
   */
  public static String signatureOf(JsonNode node) {
    if (node == null || !node.isObject()) {
      return null;
    }
    JsonNode sig = node.get(SIG_KEY);
    return sig != null && sig.isTextual() ? sig.asText() : null;
  }

  /**
   * Whether {@code node} is a value we must never read through.
   *
   * <p>True for the two secret shapes, and for any signature not in the pinned set. False for a
   * plain object, an array, a scalar, and for the non-secret special types — an asset or a resource
   * reference is structured metadata, not a credential, and is safe to <em>walk past</em>. Nothing
   * in this module emits the values of any of them under the values-never-emitted default.
   */
  public static boolean isSecret(JsonNode node) {
    String signature = signatureOf(node);
    if (signature == null) {
      return false;
    }
    return switch (signature) {
      case SECRET -> true;
      case OUTPUT_VALUE -> node.path("secret").asBoolean(false);
      case RESOURCE_REFERENCE, ASSET, ARCHIVE, BYTE_STRING -> false;
      default -> true;
    };
  }

  /**
   * Whether the subtree rooted at {@code node} contains a secret anywhere, including as the node
   * itself.
   *
   * <p>Recursive because secrets nest: the recorded preview fixture carries them at {@code
   * outputs.password} and at {@code outputs.__pulumi_raw_state_delta.obj.ps.keepers} alike. The
   * walk normally stops descending at a signed wrapper — a wrapper's interior is its own encoding,
   * not more property structure. Output values are the exception: Pulumi's own {@code
   * PropertyValue.ContainsSecrets} considers both the output's {@code secret} flag and secrets
   * nested inside its {@code value}, so a non-secret output wrapper must still have its element
   * examined.
   *
   * <p>{@code maxDepth} bounds a pathological or hostile nesting depth; exceeding it answers {@code
   * true}, because an unexamined subtree is an unknown one.
   */
  public static boolean containsSecret(JsonNode node, int maxDepth) {
    if (node == null || node.isNull()) {
      return false;
    }
    if (maxDepth <= 0) {
      return true;
    }
    String signature = signatureOf(node);
    if (signature != null) {
      if (isSecret(node)) {
        return true;
      }
      return OUTPUT_VALUE.equals(signature) && containsSecret(node.get("value"), maxDepth - 1);
    }
    if (node.isObject() || node.isArray()) {
      for (JsonNode child : node) {
        if (containsSecret(child, maxDepth - 1)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Returns a detached tree with every secret-bearing subtree replaced by {@code "[secret]"}. */
  public static JsonNode redact(JsonNode node, int maxDepth) {
    if (node == null) {
      return null;
    }
    if (maxDepth <= 0 || isSecret(node)) {
      return JsonNodeFactory.instance.textNode("[secret]");
    }
    if (node.isTextual() && "04da6b54-80e4-46f7-96ec-b56ff0331ba9".equals(node.textValue())) {
      return JsonNodeFactory.instance.textNode("[unknown]");
    }
    if (node.isArray()) {
      ArrayNode copy = JsonNodeFactory.instance.arrayNode();
      for (JsonNode child : node) {
        copy.add(redact(child, maxDepth - 1));
      }
      return copy;
    }
    if (node.isObject()) {
      ObjectNode copy = JsonNodeFactory.instance.objectNode();
      node.fields()
          .forEachRemaining(
              field -> copy.set(field.getKey(), redact(field.getValue(), maxDepth - 1)));
      return copy;
    }
    return node.deepCopy();
  }

  private SecretSentinel() {}
}
