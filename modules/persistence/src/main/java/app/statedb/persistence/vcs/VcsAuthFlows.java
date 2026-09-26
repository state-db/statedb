package app.statedb.persistence.vcs;

import app.statedb.persistence.auth.RawApiTokens;
import app.statedb.persistence.vcs.VcsAuthFlow.Purpose;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Expiring, user/org/purpose-bound handshakes. Only a hash of each public state is stored. */
@ApplicationScoped
public class VcsAuthFlows {

  @Transactional
  public String beginManifest(UUID userId) {
    return begin(userId, null, Purpose.manifest, null, null, null);
  }

  @Transactional
  public String beginInstall(UUID userId, UUID orgId, int appVersion) {
    return begin(userId, orgId, Purpose.install, appVersion, null, null);
  }

  @Transactional
  public String beginOAuth(
      UUID userId, UUID orgId, int appVersion, long installationId, String verifier) {
    return begin(userId, orgId, Purpose.oauth, appVersion, installationId, verifier);
  }

  /** Returns false rather than throwing so the manifest callback can choose its own error. */
  @Transactional
  public boolean consumeManifest(String state, UUID userId) {
    if (!wellFormed(state)) return false;
    return VcsAuthFlow.delete(
            "stateHash = ?1 and initiatedByUserId = ?2 and purpose = ?3 and expiresAt >= ?4",
            hash(state),
            userId,
            Purpose.manifest,
            Instant.now())
        == 1;
  }

  /** Callback routing reveals only the initiating user's already-bound organization. */
  @Transactional
  public UUID organization(String state, UUID userId, Purpose purpose) {
    return valid(state, userId, purpose).orgId;
  }

  @Transactional
  public Handshake consume(String state, UUID userId, UUID orgId, Purpose purpose) {
    VcsAuthFlow flow = valid(state, userId, purpose);
    if (!orgId.equals(flow.orgId)) throw invalid();
    Handshake handshake = new Handshake(flow.appVersion, flow.installationId, flow.codeVerifier);
    flow.delete();
    return handshake;
  }

  private String begin(
      UUID userId,
      UUID orgId,
      Purpose purpose,
      Integer appVersion,
      Long installationId,
      String verifier) {
    VcsAuthFlow.delete("expiresAt < ?1", Instant.now());
    String state = RawApiTokens.generate();
    VcsAuthFlow flow = new VcsAuthFlow();
    flow.stateHash = hash(state);
    flow.initiatedByUserId = userId;
    flow.orgId = orgId;
    flow.purpose = purpose;
    flow.appVersion = appVersion;
    flow.installationId = installationId;
    flow.codeVerifier = verifier;
    flow.expiresAt = Instant.now().plus(Duration.ofMinutes(15));
    flow.persist();
    return state;
  }

  private VcsAuthFlow valid(String state, UUID userId, Purpose purpose) {
    if (!wellFormed(state)) throw invalid();
    VcsAuthFlow flow =
        VcsAuthFlow.<VcsAuthFlow>find(
                "stateHash = ?1 and initiatedByUserId = ?2 and purpose = ?3 and expiresAt >= ?4",
                hash(state),
                userId,
                purpose,
                Instant.now())
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .firstResult();
    if (flow == null || flow.orgId == null) throw invalid();
    return flow;
  }

  private static boolean wellFormed(String state) {
    return state != null && state.length() <= 128 && !state.isBlank();
  }

  private static BadRequestException invalid() {
    return new BadRequestException(
        "GitHub connection expired or is invalid. Start Connect GitHub again.");
  }

  private static byte[] hash(String state) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 unavailable", failure);
    }
  }

  public record Handshake(int appVersion, Long installationId, String codeVerifier) {}
}
