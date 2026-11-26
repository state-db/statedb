package app.statedb.app.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
class ExpiredApiTokenPurgerTest {

  @Inject ExpiredApiTokenPurger purger;

  private UUID orgId;
  private UUID userId;

  @TestTransaction
  @Test
  void retainsExpiredServiceCredentialsButPurgesPersonalTokens() {
    Organization org = new Organization();
    org.name = "expired-token-purger";
    org.displayName = "Expired Token Purger";
    org.persist();
    orgId = org.id;

    User user = new User();
    user.iss = "local:purger-test";
    user.sub = "actor";
    user.persist();
    userId = user.id;

    persistToken("oidc-old", ApiToken.Kind.oidc, null);
    persistToken("service-old", ApiToken.Kind.service_account, orgId);

    purger.purge();

    assertEquals(0L, ApiToken.count("tokenHash", "oidc-old"));
    assertEquals(1L, ApiToken.count("tokenHash", "service-old"));
  }

  private void persistToken(String hash, ApiToken.Kind kind, UUID tokenOrgId) {
    ApiToken token = new ApiToken();
    token.tokenHash = hash;
    token.userId = userId;
    token.orgId = tokenOrgId;
    token.kind = kind;
    token.expiresAt = Instant.now().minus(31, ChronoUnit.DAYS);
    token.persist();
  }
}
