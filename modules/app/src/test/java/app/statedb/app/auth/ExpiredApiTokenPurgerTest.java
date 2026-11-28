package app.statedb.app.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.auth.ExpiredApiTokenPurger;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ExpiredApiTokenPurgerTest extends DatabaseTest {

  @Inject ExpiredApiTokenPurger purger;

  @TestTransaction
  @Test
  void retainsExpiredServiceCredentialsButPurgesPersonalTokens() {
    Organization org = new Organization();
    org.name = "expired-token-purger";
    org.displayName = "Expired Token Purger";
    org.persist();

    User user = new User();
    user.iss = "local:purger-test";
    user.sub = "actor";
    user.persist();

    persistToken("oidc-old", user.id, ApiToken.Kind.oidc, null);
    persistToken("service-old", user.id, ApiToken.Kind.service_account, org.id);

    purger.purge();

    assertEquals(0L, ApiToken.count("tokenHash", "oidc-old"));
    assertEquals(1L, ApiToken.count("tokenHash", "service-old"));
  }

  private static void persistToken(String hash, UUID userId, ApiToken.Kind kind, UUID tokenOrgId) {
    ApiToken token = new ApiToken();
    token.tokenHash = hash;
    token.userId = userId;
    token.orgId = tokenOrgId;
    token.kind = kind;
    token.expiresAt = Instant.now().minus(31, ChronoUnit.DAYS);
    token.persist();
  }
}
