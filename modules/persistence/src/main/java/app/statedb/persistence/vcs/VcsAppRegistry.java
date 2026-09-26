package app.statedb.persistence.vcs;

import app.statedb.persistence.RequestValidationException;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.secrets.ServiceSecretsAead;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Optional;
import java.util.UUID;

/**
 * The encrypted App identity per VCS host and the org connections bound to it. Replacement write-
 * locks the registration row and connection read-locks it, so a connection never pairs an
 * installation with a different App's credentials.
 */
@ApplicationScoped
public class VcsAppRegistry {

  private static final String GITHUB_HOST = "github.com";

  // Shares the Pulumi secrets keyset; the vcs-app associated data keeps the two uses apart.
  @Inject ServiceSecretsAead secretsAead;

  @Transactional
  public AppStatus replace(AppRegistration replacement) {
    VcsAppRegistration registration =
        VcsAppRegistration.<VcsAppRegistration>find("host", replacement.host())
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .firstResult();
    if (registration == null) {
      registration = new VcsAppRegistration();
      registration.host = replacement.host();
      registration.version = 1;
    } else {
      registration.version++;
      // Installation ids belong to one App identity. Replacing that identity makes every old
      // installation unverifiable, so require each organization to install the new App.
      VcsConnection.update("enabled = false where host = ?1", replacement.host());
    }
    registration.appId = replacement.appId();
    registration.clientId = replacement.clientId();
    registration.clientSecretCiphertext =
        encrypt(
            replacement.clientSecret(), aad(registration.host + ":oauth", registration.version));
    registration.slug = replacement.slug();
    registration.owner = replacement.owner();
    registration.htmlUrl = replacement.htmlUrl();
    registration.privateKeyCiphertext =
        encrypt(replacement.privateKeyPem(), aad(registration.host, registration.version));
    if (!registration.isPersistent()) {
      registration.persist();
    }
    return status(registration);
  }

  @Transactional
  public Optional<AppCredentials> credentials(String host) {
    VcsAppRegistration registration =
        VcsAppRegistration.<VcsAppRegistration>find("host", host).firstResult();
    if (registration == null) {
      return Optional.empty();
    }
    return Optional.of(
        new AppCredentials(
            registration.appId,
            decrypt(
                registration.privateKeyCiphertext, aad(registration.host, registration.version)),
            registration.version,
            status(registration),
            registration.clientId,
            decrypt(
                registration.clientSecretCiphertext,
                aad(registration.host + ":oauth", registration.version))));
  }

  @Transactional
  public Optional<AppStatus> status(String host) {
    VcsAppRegistration registration =
        VcsAppRegistration.<VcsAppRegistration>find("host", host).firstResult();
    return Optional.ofNullable(registration).map(VcsAppRegistry::status);
  }

  /** Creates or rotates one organization's installation connection after host verification. */
  @Transactional
  public VcsConnection connectGitHub(UUID orgId, long installationId, int expectedAppVersion) {
    VcsAppRegistration registration =
        VcsAppRegistration.<VcsAppRegistration>find("host", GITHUB_HOST)
            .withLock(LockModeType.PESSIMISTIC_READ)
            .firstResult();
    if (registration == null || registration.version != expectedAppVersion) {
      throw new RequestValidationException(
          "GitHub App changed during authorization. Connect again.");
    }
    // Serialize the create-if-missing path as well as updates to an existing connection.
    Organization.findById(orgId, LockModeType.PESSIMISTIC_WRITE);
    VcsConnection connection =
        VcsConnection.<VcsConnection>find("orgId = ?1 and host = ?2", orgId, GITHUB_HOST)
            .firstResult();
    if (connection == null) {
      connection = new VcsConnection();
      connection.orgId = orgId;
      connection.kind = "github";
      connection.host = GITHUB_HOST;
      connection.installationId = installationId;
      connection.persist();
    } else {
      connection.installationId = installationId;
      connection.enabled = true;
      connection.version++;
    }
    return connection;
  }

  @Transactional
  public void disconnectGitHub(UUID orgId) {
    Organization.findById(orgId, LockModeType.PESSIMISTIC_WRITE);
    VcsConnection.update(
        "enabled = false, version = version + 1 where orgId = ?1 and host = ?2",
        orgId,
        GITHUB_HOST);
    VcsAuthFlow.delete("orgId", orgId);
  }

  private byte[] encrypt(String plaintext, byte[] aad) {
    try {
      return secretsAead.aead().encrypt(plaintext.getBytes(StandardCharsets.UTF_8), aad);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("Could not encrypt VCS App credentials", failure);
    }
  }

  private String decrypt(byte[] ciphertext, byte[] aad) {
    try {
      return new String(secretsAead.aead().decrypt(ciphertext, aad), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("Could not decrypt VCS App credentials", failure);
    }
  }

  private static byte[] aad(String host, int version) {
    return ("vcs-app:" + host + ":" + version).getBytes(StandardCharsets.UTF_8);
  }

  private static AppStatus status(VcsAppRegistration registration) {
    return new AppStatus(
        registration.host,
        registration.appId,
        registration.slug,
        registration.owner,
        registration.htmlUrl,
        registration.version);
  }

  public record AppRegistration(
      String host,
      long appId,
      String slug,
      String owner,
      String htmlUrl,
      String privateKeyPem,
      String clientId,
      String clientSecret) {}

  public record AppCredentials(
      long appId,
      String privateKeyPem,
      int version,
      AppStatus status,
      String clientId,
      String clientSecret) {}

  public record AppStatus(
      String host, long appId, String slug, String owner, String htmlUrl, int version) {}
}
