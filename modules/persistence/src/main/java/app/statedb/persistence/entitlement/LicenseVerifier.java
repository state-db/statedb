package app.statedb.persistence.entitlement;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Verifies the exact payload bytes carried by a versioned Ed25519 signed envelope. */
public final class LicenseVerifier {
  private static final int SUPPORTED_SCHEMA = 1;
  private static final String PRODUCT = "state-db";
  private static final String PAID_EDITION = "paid";

  private final ObjectMapper json;
  private final Map<String, PublicKey> keys;
  private final Clock clock;
  private final String deploymentId;

  public LicenseVerifier(Map<String, String> encodedKeys, Clock clock, String deploymentId) {
    this.clock = clock;
    this.deploymentId = deploymentId;
    JsonFactory factory =
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    this.json =
        new ObjectMapper(factory)
            .registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    this.keys = decodeKeys(encodedKeys);
  }

  EffectiveEntitlement verify(String artifact) {
    try {
      Envelope envelope = readEnvelope(artifact);
      byte[] payload = Base64.getUrlDecoder().decode(envelope.payload());
      LicenseClaims claims = json.readValue(payload, LicenseClaims.class);
      PublicKey key = verificationKey(claims);

      verifySignature(payload, envelope.signature(), key);
      validateClaims(claims);
      return entitlementAt(claims, clock.instant());
    } catch (InvalidLicenseException e) {
      return invalid(e.getMessage());
    } catch (Exception e) {
      return invalid("malformed or unverifiable license");
    }
  }

  private Envelope readEnvelope(String artifact) throws Exception {
    String jsonArtifact = decodeArtifact(artifact);
    Envelope envelope = json.readValue(jsonArtifact, Envelope.class);
    require(
        envelope.payload() != null && envelope.signature() != null,
        "signed envelope fields are required");
    return envelope;
  }

  private static String decodeArtifact(String artifact) throws InvalidLicenseException {
    String value = artifact.strip();
    if (value.startsWith("{")) {
      requireSize(artifact.getBytes(StandardCharsets.UTF_8));
      return value;
    }

    byte[] decoded = Base64.getDecoder().decode(value);
    requireSize(decoded);
    return new String(decoded, StandardCharsets.UTF_8);
  }

  private PublicKey verificationKey(LicenseClaims claims) throws InvalidLicenseException {
    require(claims.schema() == SUPPORTED_SCHEMA, "unknown license schema");
    PublicKey key = keys.get(claims.keyId());
    require(key != null, "unknown signing key ID");
    return key;
  }

  private static void verifySignature(byte[] payload, String encodedSignature, PublicKey key)
      throws Exception {
    Signature signature = Signature.getInstance("Ed25519");
    signature.initVerify(key);
    signature.update(payload);
    require(signature.verify(Base64.getUrlDecoder().decode(encodedSignature)), "invalid signature");
  }

  private void validateClaims(LicenseClaims claims) throws InvalidLicenseException {
    require(PRODUCT.equals(claims.product()), "wrong product identifier");
    require(PAID_EDITION.equals(claims.edition()), "unsupported edition");
    require(
        !isBlank(claims.licenseId())
            && !isBlank(claims.customerId())
            && claims.issuedAt() != null
            && claims.notBefore() != null
            && claims.expiresAt() != null,
        "required license claim is missing");
    require(claims.expiresAt().isAfter(claims.notBefore()), "invalid validity interval");
    require(
        claims.deploymentId() == null || claims.deploymentId().equals(deploymentId),
        "deployment ID does not match");
  }

  private static EffectiveEntitlement entitlementAt(LicenseClaims claims, Instant now) {
    if (now.isBefore(claims.notBefore())) {
      return EffectiveEntitlement.community(
          LicenseStatus.NOT_YET_VALID, "license is not yet valid");
    }
    if (!now.isBefore(claims.expiresAt())) {
      return EffectiveEntitlement.community(LicenseStatus.EXPIRED, "license has expired");
    }
    return EffectiveEntitlement.paid(claims.expiresAt());
  }

  private static Map<String, PublicKey> decodeKeys(Map<String, String> encodedKeys) {
    Map<String, PublicKey> decoded = new HashMap<>();
    encodedKeys.forEach((id, key) -> decoded.put(id, decodeKey(key)));
    return Map.copyOf(decoded);
  }

  private static PublicKey decodeKey(String encoded) {
    try {
      return KeyFactory.getInstance("Ed25519")
          .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid embedded license verification key", e);
    }
  }

  private static EffectiveEntitlement invalid(String failure) {
    return EffectiveEntitlement.community(LicenseStatus.INVALID, failure);
  }

  private static void requireSize(byte[] artifact) throws InvalidLicenseException {
    require(artifact.length <= Entitlements.MAX_LICENSE_BYTES, "license artifact exceeds 64 KiB");
  }

  private static void require(boolean condition, String failure) throws InvalidLicenseException {
    if (!condition) throw new InvalidLicenseException(failure);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private record Envelope(String payload, String signature) {}

  private record LicenseClaims(
      int schema,
      String keyId,
      String licenseId,
      String customerId,
      String product,
      String edition,
      Instant issuedAt,
      Instant notBefore,
      Instant expiresAt,
      String subscriptionId,
      String deploymentId) {}

  private static final class InvalidLicenseException extends Exception {
    private InvalidLicenseException(String message) {
      super(message);
    }
  }
}
