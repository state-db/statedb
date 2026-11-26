package app.statedb.app.report;

import static app.statedb.testsupport.TestTransactions.read;
import static app.statedb.testsupport.TestTransactions.tx;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.RequestValidationException;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.secrets.ServiceSecretsAead;
import app.statedb.persistence.vcs.VcsAppRegistration;
import app.statedb.persistence.vcs.VcsAppRegistry;
import app.statedb.persistence.vcs.VcsAppRegistry.AppRegistration;
import app.statedb.persistence.vcs.VcsAuthFlow;
import app.statedb.persistence.vcs.VcsAuthFlow.Purpose;
import app.statedb.persistence.vcs.VcsAuthFlows;
import app.statedb.persistence.vcs.VcsConnection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class VcsCredentialStoreTest {

  @Inject VcsAppRegistry registrations;
  @Inject VcsAuthFlows flows;
  @Inject ServiceSecretsAead secretsAead;
  @Inject EntityManager em;

  @AfterEach
  void cleanup() {
    tx(
        () -> {
          VcsAuthFlow.deleteAll();
          Organization.delete("name", "vcs-auth-org");
          VcsAppRegistration.deleteAll();
          User.delete("iss", "https://vcs-credentials.test");
        });
  }

  @Test
  void appPrivateKeyIsEncryptedAndVersionBound() {
    String privateKey = "-----BEGIN PRIVATE KEY-----\nsecret-one\n-----END PRIVATE KEY-----";
    registrations.replace(
        new AppRegistration(
            "github.com",
            123,
            "state-db-one",
            "state-db",
            "https://github.com/apps/state-db-one",
            privateKey,
            "client-one",
            "client-secret-one"));

    byte[] ciphertext =
        read(
            () ->
                (byte[])
                    em.createNativeQuery(
                            "SELECT private_key_ciphertext FROM vcs_app_registrations WHERE"
                                + " host = 'github.com'")
                        .getSingleResult());
    assertFalse(java.util.Arrays.equals(privateKey.getBytes(StandardCharsets.UTF_8), ciphertext));
    assertEquals(privateKey, registrations.credentials("github.com").orElseThrow().privateKeyPem());

    String replacement = "-----BEGIN PRIVATE KEY-----\nsecret-two\n-----END PRIVATE KEY-----";
    registrations.replace(
        new AppRegistration(
            "github.com",
            456,
            "state-db-two",
            "state-db",
            "https://github.com/apps/state-db-two",
            replacement,
            "client-two",
            "client-secret-two"));
    var credentials = registrations.credentials("github.com").orElseThrow();
    assertEquals(2, credentials.version());
    assertEquals(replacement, credentials.privateKeyPem());
    assertEquals("client-two", credentials.clientId());
    assertEquals("client-secret-two", credentials.clientSecret());
    byte[] oauthCiphertext =
        read(
            () ->
                (byte[])
                    em.createNativeQuery(
                            "SELECT client_secret_ciphertext FROM vcs_app_registrations WHERE"
                                + " host = 'github.com'")
                        .getSingleResult());
    assertFalse(
        java.util.Arrays.equals(
            "client-secret-two".getBytes(StandardCharsets.UTF_8), oauthCiphertext));
  }

  @Test
  void manifestStateIsOneTimeAndBoundToItsAdministrator() {
    UUID userId = user();
    String state = flows.beginManifest(userId);

    assertTrue(flows.consumeManifest(state, userId));
    assertFalse(flows.consumeManifest(state, userId));
    assertFalse(flows.consumeManifest("wrong", userId));
  }

  @Test
  void connectionStateBindsUserOrgPurposeAndExpires() {
    UUID userId = user();
    UUID orgId = organization();
    String state = flows.beginOAuth(userId, orgId, 7, 9876L, "verifier");
    assertThrows(
        jakarta.ws.rs.BadRequestException.class,
        () -> flows.consume(state, UUID.randomUUID(), orgId, Purpose.oauth));
    assertThrows(
        jakarta.ws.rs.BadRequestException.class,
        () -> flows.consume(state, userId, UUID.randomUUID(), Purpose.oauth));
    assertThrows(
        jakarta.ws.rs.BadRequestException.class,
        () -> flows.consume(state, userId, orgId, Purpose.install));
    assertFalse(flows.consumeManifest(state, userId));
    var consumed = flows.consume(state, userId, orgId, Purpose.oauth);
    assertEquals(7, consumed.appVersion());
    assertEquals("verifier", consumed.codeVerifier());
    assertThrows(
        jakarta.ws.rs.BadRequestException.class,
        () -> flows.consume(state, userId, orgId, Purpose.oauth));
    String expired = flows.beginInstall(userId, orgId, 7);
    expireAuthorizationFlows();
    assertThrows(
        jakarta.ws.rs.BadRequestException.class,
        () -> flows.consume(expired, userId, orgId, Purpose.install));
  }

  @Test
  void appCredentialsUseTheSharedKeysetUnderTheirOwnAssociatedData() throws Exception {
    register(123, "private-key-one", "client-secret-one");
    Object[] ciphertexts =
        read(
            () ->
                (Object[])
                    em.createNativeQuery(
                            "SELECT private_key_ciphertext, client_secret_ciphertext FROM"
                                + " vcs_app_registrations WHERE host = 'github.com'")
                        .getSingleResult());
    byte[] privateKey = (byte[]) ciphertexts[0];
    byte[] clientSecret = (byte[]) ciphertexts[1];
    var aead = secretsAead.aead();

    assertEquals(
        "private-key-one", new String(aead.decrypt(privateKey, aad("github.com:1")), UTF_8));
    assertEquals(
        "client-secret-one",
        new String(aead.decrypt(clientSecret, aad("github.com:oauth:1")), UTF_8));
    // Swapped columns or a stale version must not decrypt.
    assertThrows(
        GeneralSecurityException.class, () -> aead.decrypt(privateKey, aad("github.com:oauth:1")));
    assertThrows(
        GeneralSecurityException.class, () -> aead.decrypt(clientSecret, aad("github.com:1")));
    assertThrows(
        GeneralSecurityException.class, () -> aead.decrypt(privateKey, aad("github.com:2")));
  }

  @Test
  void replacingTheAppDisablesConnectionsAndRejectsOldVersionHandshakes() {
    UUID orgId = organization();
    register(123, "private-key-one", "client-secret-one");
    assertTrue(registrations.connectGitHub(orgId, 111, 1).enabled);

    register(456, "private-key-two", "client-secret-two");
    assertFalse(connection(orgId).enabled);
    assertThrows(
        RequestValidationException.class, () -> registrations.connectGitHub(orgId, 222, 1));
    assertFalse(connection(orgId).enabled);

    registrations.connectGitHub(orgId, 222, 2);
    VcsConnection reconnected = connection(orgId);
    assertTrue(reconnected.enabled);
    assertEquals(222L, reconnected.installationId);
  }

  private void register(long appId, String privateKey, String clientSecret) {
    registrations.replace(
        new AppRegistration(
            "github.com",
            appId,
            "state-db-" + appId,
            "state-db",
            "https://github.com/apps/state-db-" + appId,
            privateKey,
            "client-" + appId,
            clientSecret));
  }

  private VcsConnection connection(UUID orgId) {
    em.clear();
    return VcsConnection.<VcsConnection>find("orgId", orgId).singleResult();
  }

  private static byte[] aad(String suffix) {
    return ("vcs-app:" + suffix).getBytes(UTF_8);
  }

  private UUID organization() {
    return read(
        () -> {
          Organization org = new Organization();
          org.name = "vcs-auth-org";
          org.displayName = "VCS auth";
          org.persistAndFlush();
          return org.id;
        });
  }

  private UUID user() {
    return read(
        () -> {
          User user = new User();
          user.iss = "https://vcs-credentials.test";
          user.sub = UUID.randomUUID().toString();
          user.persistAndFlush();
          return user.id;
        });
  }

  private void expireAuthorizationFlows() {
    tx(() -> VcsAuthFlow.update("expiresAt = ?1", java.time.Instant.EPOCH));
  }
}
