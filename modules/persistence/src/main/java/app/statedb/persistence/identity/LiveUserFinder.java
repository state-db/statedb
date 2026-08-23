package app.statedb.persistence.identity;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/** Resolves an active human user from the issuer and subject in an authenticated OIDC session. */
@ApplicationScoped
public class LiveUserFinder {

  public Optional<User> findBySubject(String iss, String sub) {
    return User.<User>find("iss = ?1 and sub = ?2 and deactivatedAt is null", iss, sub)
        .firstResultOptional();
  }
}
