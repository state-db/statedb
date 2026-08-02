package app.statedb.persistence.identity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.id.uuid.UuidVersion7Strategy;

/**
 * Provisions/refreshes the console {@code users} row from Quarkus's OIDC authentication-completion
 * hook, once per successful code flow and never from a data-request filter. Reads carry no write
 * and {@code last_login_at} means "last login".
 *
 * <p>A single {@code INSERT ... ON CONFLICT (iss, sub) DO UPDATE} creates the row on first login
 * and refreshes the profile + {@code last_login_at} on every login after, atomically — two
 * concurrent first logins for the same identity can't both insert and one lose to a
 * unique-constraint violation.
 *
 * <p>Hibernate does not express PostgreSQL's atomic {@code ON CONFLICT} operation. Because this
 * statement bypasses entity lifecycle callbacks, the update branch advances {@code updated_at}
 * explicitly and the insert branch supplies the same application-generated UUIDv7 used by entities.
 */
@ApplicationScoped
public class UserUpserter {

  @Inject EntityManager em;

  @Transactional
  public void upsertOnLogin(String iss, String sub, String email, String displayName) {
    em.createNativeQuery(
            """
            INSERT INTO users (id, iss, sub, email, display_name, last_login_at)
            VALUES (:id, :iss, :sub, :email, :display, now())
            ON CONFLICT (iss, sub) DO UPDATE
              SET email = EXCLUDED.email,
                  display_name = EXCLUDED.display_name,
                  last_login_at = now(),
                  updated_at = now()
            """)
        .setParameter("id", UuidVersion7Strategy.INSTANCE.generateUuid(null))
        .setParameter("iss", iss)
        .setParameter("sub", sub)
        .setParameter("email", email)
        .setParameter("display", displayName)
        .executeUpdate();
  }
}
