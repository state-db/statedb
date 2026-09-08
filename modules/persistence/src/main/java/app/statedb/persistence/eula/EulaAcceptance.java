package app.statedb.persistence.eula;

import app.statedb.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One site administrator's acceptance of one EULA version. Append-only legal evidence: rows are
 * never updated or deleted, and superseded versions are retained.
 */
@Entity
@Table(name = "eula_acceptances")
public class EulaAcceptance extends BaseEntity {

  @Column(name = "version", nullable = false, updatable = false)
  public String version;

  @Column(name = "document_sha256", nullable = false, updatable = false)
  public String documentSha256;

  /** How the acceptance was made — see {@link Channel}. */
  @Enumerated(EnumType.STRING)
  @Column(name = "accepted_via", nullable = false, updatable = false)
  public Channel acceptedVia;

  /** The accepting site administrator; null only for a {@link Channel#configuration} acceptance. */
  @Column(name = "accepted_by_user_id", updatable = false)
  public UUID acceptedByUserId;

  /** Client address the acceptance arrived from, when the request exposed one. */
  @Column(name = "accepted_from", updatable = false)
  public String acceptedFrom;

  /** Acceptance channel. Wire and column values are the lowercase constant names. */
  public enum Channel {
    /** A named site administrator accepted in the web console. */
    console,
    /** The operator set {@code statedb.eula.accepted-version} on the deployment itself. */
    configuration
  }
}
