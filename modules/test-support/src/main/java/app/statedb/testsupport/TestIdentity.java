package app.statedb.testsupport;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Seeds and cleans up a canonical {@code testorg / Test User} identity row set used by both the
 * contract replay tests in {@code state-db-app} and the real-CLI smoke IT in {@code
 * state-db-pulumi-cli-smoke}. Keep these tests pointing at one definition so a schema change only
 * has to be reflected once.
 */
public final class TestIdentity {

  public static final String ORG = "testorg";
  public static final String USER_ISS = "local:test";
  public static final String USER_SUB = "testorg";

  private TestIdentity() {}

  /** Wipes the canonical identity and seeds it fresh. Idempotent. */
  public static void seed(EntityManager em, String rawToken) {
    cleanup(em);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = new Organization();
              org.name = ORG;
              org.displayName = ORG;
              org.persistAndFlush();

              User user = new User();
              user.iss = USER_ISS;
              user.sub = USER_SUB;
              user.email = "test@example.com";
              user.displayName = "Test User";
              user.persistAndFlush();

              OrgMember member = new OrgMember();
              member.orgId = org.id;
              member.userId = user.id;
              member.role = OrgMember.Role.owner;
              member.persistAndFlush();

              ApiToken token = new ApiToken();
              token.tokenHash = sha256Hex(rawToken);
              token.userId = user.id;
              token.orgId = org.id;
              token.kind = ApiToken.Kind.service_account;
              token.expiresAt = java.time.Instant.now().plusSeconds(60 * 60);
              token.description = "test fixture";
              token.persistAndFlush();
            });
  }

  /** Deletes the canonical identity rows. Safe to call when nothing has been seeded yet. */
  public static void cleanup(EntityManager em) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                  .setParameter(1, ORG)
                  .executeUpdate();
              em.createNativeQuery("DELETE FROM users WHERE iss = ?1 AND sub = ?2")
                  .setParameter(1, USER_ISS)
                  .setParameter(2, USER_SUB)
                  .executeUpdate();
            });
  }

  public static String sha256Hex(String input) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
