package app.statedb.persistence.entitlement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.statedb.persistence.update.StackUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LicenseVerifierTest {
  private static final String KEY_ID = "test-only-2026-01";
  private static final String PUBLIC_KEY =
      "MCowBQYDK2VwAyEAiSHHpJIUQJ9bCE9T8Lxc38K++DSqfh1eoI0HroncPSo=";
  private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

  @TempDir Path tempDir;

  @Test
  void missingLicenseIsCommunity() {
    EffectiveEntitlement result = entitlements(Optional.empty(), Optional.empty()).effective();
    assertEquals(EffectiveEntitlement.Tier.COMMUNITY, result.tier());
    assertEquals(LicenseStatus.MISSING, result.status());
  }

  @Test
  void validSignatureIsPaid() throws Exception {
    EffectiveEntitlement result =
        verifier(NOW)
            .verify(
                signed(
                    payload(
                        KEY_ID, "state-db", "2026-08-01T00:00:00Z", "2026-09-10T00:00:00Z", null)));
    assertEquals(EffectiveEntitlement.Tier.PAID, result.tier());
    assertEquals(LicenseStatus.VALID, result.status());
    assertEquals(Instant.parse("2026-09-10T00:00:00Z"), result.expiresAt());
  }

  @Test
  void modifiedPayloadAndSignatureAreRejected() throws Exception {
    String artifact =
        signed(payload(KEY_ID, "state-db", "2026-08-01T00:00:00Z", "2026-09-10T00:00:00Z", null));
    Envelope envelope = new ObjectMapper().readValue(artifact, Envelope.class);
    byte[] payload = Base64.getUrlDecoder().decode(envelope.payload);
    String modified = new String(payload, StandardCharsets.UTF_8).replace("state-db", "other-db");
    assertEquals(
        LicenseStatus.INVALID,
        verifier(NOW)
            .verify(
                envelope(
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(modified.getBytes(StandardCharsets.UTF_8)),
                    envelope.signature))
            .status());
    String badSignature = envelope.signature.substring(0, envelope.signature.length() - 2) + "AA";
    assertEquals(
        LicenseStatus.INVALID,
        verifier(NOW).verify(envelope(envelope.payload, badSignature)).status());
  }

  @Test
  void rejectsUnknownKeyWrongProductAndFutureLicense() throws Exception {
    assertEquals(
        LicenseStatus.INVALID,
        verifier(NOW)
            .verify(
                signed(
                    payload(
                            KEY_ID,
                            "state-db",
                            "2026-08-01T00:00:00Z",
                            "2026-09-10T00:00:00Z",
                            null)
                        .replace("\"schema\":1", "\"schema\":2")))
            .status());
    assertEquals(
        LicenseStatus.INVALID,
        verifier(NOW)
            .verify(
                signed(
                    payload(
                        "unknown",
                        "state-db",
                        "2026-08-01T00:00:00Z",
                        "2026-09-10T00:00:00Z",
                        null)))
            .status());
    assertEquals(
        LicenseStatus.INVALID,
        verifier(NOW)
            .verify(
                signed(
                    payload(KEY_ID, "other", "2026-08-01T00:00:00Z", "2026-09-10T00:00:00Z", null)))
            .status());
    assertEquals(
        LicenseStatus.NOT_YET_VALID,
        verifier(NOW)
            .verify(
                signed(
                    payload(
                        KEY_ID, "state-db", "2026-08-11T00:00:00Z", "2026-09-10T00:00:00Z", null)))
            .status());
  }

  @Test
  void validityBoundariesAreInclusiveAtNotBeforeAndExclusiveAtExpiry() throws Exception {
    String artifact =
        signed(payload(KEY_ID, "state-db", NOW.toString(), "2026-09-10T00:00:00Z", null));
    assertEquals(LicenseStatus.VALID, verifier(NOW).verify(artifact).status());
    assertEquals(
        LicenseStatus.EXPIRED,
        verifier(Instant.parse("2026-09-10T00:00:00Z")).verify(artifact).status());
  }

  @Test
  void monthlyAndAnnualRangesUseTheSameTimestampRules() throws Exception {
    String monthly =
        signed(payload(KEY_ID, "state-db", "2026-08-01T00:00:00Z", "2026-09-10T00:00:00Z", null));
    String annual =
        signed(payload(KEY_ID, "state-db", "2026-08-01T00:00:00Z", "2027-08-15T00:00:00Z", null));
    assertEquals(LicenseStatus.VALID, verifier(NOW).verify(monthly).status());
    assertEquals(LicenseStatus.VALID, verifier(NOW).verify(annual).status());
  }

  @Test
  void validatesOptionalDeploymentBinding() throws Exception {
    String artifact =
        signed(
            payload(
                KEY_ID, "state-db", "2026-08-01T00:00:00Z", "2026-09-10T00:00:00Z", "production"));
    assertEquals(
        LicenseStatus.VALID,
        new LicenseVerifier(Map.of(KEY_ID, PUBLIC_KEY), clock(NOW), "production")
            .verify(artifact)
            .status());
    assertEquals(
        LicenseStatus.INVALID,
        new LicenseVerifier(Map.of(KEY_ID, PUBLIC_KEY), clock(NOW), "staging")
            .verify(artifact)
            .status());
    assertEquals(
        LicenseStatus.INVALID,
        new LicenseVerifier(Map.of(KEY_ID, PUBLIC_KEY), clock(NOW), null)
            .verify(artifact)
            .status());
  }

  @Test
  void oversizedLicenseFileFailsClosed() throws Exception {
    Path file = tempDir.resolve("oversized-license.json");
    Files.writeString(file, "x".repeat(65_537));
    EffectiveEntitlement result =
        entitlements(Optional.of(file.toString()), Optional.empty()).effective();
    assertEquals(LicenseStatus.INVALID, result.status());
    assertEquals("configured license exceeds 64 KiB", result.validationFailure());
  }

  @Test
  void oversizedInlineLicenseFailsClosed() {
    EffectiveEntitlement result =
        entitlements(Optional.empty(), Optional.of("{" + " ".repeat(65_536))).effective();
    assertEquals(LicenseStatus.INVALID, result.status());
    assertEquals("license artifact exceeds 64 KiB", result.validationFailure());
  }

  @Test
  void fileTakesPrecedenceAndIsReloaded() throws Exception {
    String valid =
        signed(payload(KEY_ID, "state-db", "2026-08-01T00:00:00Z", "2026-09-10T00:00:00Z", null));
    Path file = tempDir.resolve("license.json");
    Files.writeString(file, valid);
    Entitlements entitlements =
        new Entitlements(
            Optional.of(file.toString()), Optional.of("invalid-inline"), verifier(NOW));
    assertEquals(LicenseStatus.VALID, entitlements.effective().status());
    Files.writeString(file, "invalid replacement");
    assertEquals(LicenseStatus.INVALID, entitlements.effective().status());
  }

  @Test
  void enforcementClassifiesPulumiOperations() throws Exception {
    Entitlements community = entitlements(Optional.empty(), Optional.empty());
    assertDoesNotThrow(() -> community.requireStackCreationAllowed(9));
    assertThrows(EntitlementDeniedException.class, () -> community.requireStackCreationAllowed(10));
    assertDoesNotThrow(() -> community.requireUpdateAllowed(10, StackUpdate.Kind.UPDATE, false));
    assertThrows(
        EntitlementDeniedException.class,
        () -> community.requireUpdateAllowed(11, StackUpdate.Kind.UPDATE, false));
    assertThrows(
        EntitlementDeniedException.class,
        () -> community.requireUpdateAllowed(11, StackUpdate.Kind.IMPORT, false));
    for (StackUpdate.Kind allowed :
        new StackUpdate.Kind[] {
          StackUpdate.Kind.PREVIEW,
          StackUpdate.Kind.REFRESH,
          StackUpdate.Kind.DESTROY,
          StackUpdate.Kind.RENAME
        }) {
      assertDoesNotThrow(() -> community.requireUpdateAllowed(11, allowed, false));
    }
    assertDoesNotThrow(() -> community.requireUpdateAllowed(11, StackUpdate.Kind.UPDATE, true));

    String valid =
        signed(payload(KEY_ID, "state-db", "2026-08-01T00:00:00Z", "2026-09-10T00:00:00Z", null));
    Entitlements paid = entitlements(Optional.empty(), Optional.of(valid));
    assertDoesNotThrow(() -> paid.requireStackCreationAllowed(10_000));
    assertDoesNotThrow(() -> paid.requireUpdateAllowed(10_000, StackUpdate.Kind.UPDATE, false));
  }

  private static Entitlements entitlements(Optional<String> path, Optional<String> inline) {
    return new Entitlements(path, inline, verifier(NOW));
  }

  private static LicenseVerifier verifier(Instant now) {
    return new LicenseVerifier(Map.of(KEY_ID, PUBLIC_KEY), clock(now), null);
  }

  private static Clock clock(Instant now) {
    return Clock.fixed(now, ZoneOffset.UTC);
  }

  private static String payload(
      String keyId, String product, String notBefore, String expiresAt, String deploymentId) {
    String deployment = deploymentId == null ? "" : ",\"deploymentId\":\"" + deploymentId + "\"";
    return "{\"schema\":1,\"keyId\":\"%s\",\"licenseId\":\"lic_test\",\"customerId\":\"cus_test\",\"product\":\"%s\",\"edition\":\"paid\",\"issuedAt\":\"2026-08-01T00:00:00Z\",\"notBefore\":\"%s\",\"expiresAt\":\"%s\"%s}"
        .formatted(keyId, product, notBefore, expiresAt, deployment);
  }

  private static String signed(String payload) throws Exception {
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(privateKey());
    signer.update(bytes);
    return envelope(
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
        Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
  }

  private static String envelope(String payload, String signature) {
    return "{\"payload\":\"" + payload + "\",\"signature\":\"" + signature + "\"}";
  }

  private static PrivateKey privateKey() throws Exception {
    String pem =
        new String(
            LicenseVerifierTest.class
                .getResourceAsStream("/license-test-only-private.pem")
                .readAllBytes(),
            StandardCharsets.UTF_8);
    String encoded =
        pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    return KeyFactory.getInstance("Ed25519")
        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
  }

  private static class Envelope {
    public String payload;
    public String signature;
  }
}
