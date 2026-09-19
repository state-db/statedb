// Pulumi's signatures for special property values, from
// sdk/go/common/resource/sig/sig.go (SDK v3.259.0). Java mirror:
// modules/persistence/src/main/java/app/statedb/persistence/report/SecretSentinel.java.

export const PULUMI_SIG_KEY = "4dabf18193072939515e22adb298388d";
export const PULUMI_SECRET_SIG = "1b47061264138c4ac30d75fd1eb44270";
export const PULUMI_OUTPUT_VALUE_SIG = "d0e6a833031e9bbcd3f4e8bde6ca49a4";
export const PULUMI_RESOURCE_REFERENCE_SIG = "5cf8f73096256a8f31e491e813e4eb8e";
export const PULUMI_ASSET_SIG = "c44067f5952c0a294b673a41bacd8c17";
export const PULUMI_ARCHIVE_SIG = "0def7320c3a5731c473e5ecbe6d01bc7";
export const PULUMI_BYTE_STRING_SIG = "803fd3297a5875dc03ca845dda5d2a98";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

// resource.HasSig: an object whose signature key holds a *string*. A number or
// object there is just an object that happens to use the key.
export function pulumiSignatureOf(value: unknown): string | null {
  if (!isRecord(value)) return null;
  const signature = value[PULUMI_SIG_KEY];
  return typeof signature === "string" ? signature : null;
}

// Two shapes carry secrets, not one: MarshalPropertyValue's IsSecret branch
// emits SECRET_SIG, its IsOutput branch (and the KeepOutputValues &&
// UpgradeToOutputValues path) emits OUTPUT_VALUE_SIG with `secret: true` around
// a *plaintext* value. An unknown signature counts as secret because Pulumi's
// unmarshaller rejects one outright, so it is a shape we cannot read. Assets and
// resource references are metadata, not credentials, and stay walkable.
// `secure`/`ciphertext` are the encrypted config shapes, outside this encoding.
export function isPulumiSecret(value: unknown): boolean {
  if (!isRecord(value)) return false;
  if ("secure" in value || "ciphertext" in value) return true;
  const signature = pulumiSignatureOf(value);
  if (signature === null) return false;
  switch (signature) {
    case PULUMI_SECRET_SIG:
      return true;
    case PULUMI_OUTPUT_VALUE_SIG:
      return value.secret === true;
    case PULUMI_RESOURCE_REFERENCE_SIG:
    case PULUMI_ASSET_SIG:
    case PULUMI_ARCHIVE_SIG:
    case PULUMI_BYTE_STRING_SIG:
      return false;
    default:
      return true;
  }
}
