package app.statedb.persistence.vcs;

import app.statedb.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Site-wide identity of the GitHub App owned by this state-db deployment. */
@Entity
@Table(name = "vcs_app_registrations")
public class VcsAppRegistration extends TimestampedEntity {

  @Column(nullable = false, unique = true)
  public String host;

  @Column(name = "app_id", nullable = false)
  public long appId;

  @Column(nullable = false)
  public String slug;

  @Column(nullable = false)
  public String owner;

  @Column(name = "html_url", nullable = false)
  public String htmlUrl;

  @Column(name = "private_key_ciphertext", nullable = false)
  public byte[] privateKeyCiphertext;

  @Column(name = "client_id", nullable = false)
  public String clientId;

  @Column(name = "client_secret_ciphertext", nullable = false)
  public byte[] clientSecretCiphertext;

  @Column(nullable = false)
  public int version = 1;
}
