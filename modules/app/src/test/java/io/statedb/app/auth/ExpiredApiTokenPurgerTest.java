package io.statedb.app.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.auth.ApiToken;
import io.statedb.persistence.auth.ExpiredApiTokenPurger;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ExpiredApiTokenPurgerTest {

  @Inject ExpiredApiTokenPurger purger;

  private UUID orgId;
  private UUID userId;

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              if (orgId != null) Organization.deleteById(orgId);
              if (userId != null) User.deleteById(userId);
            });
  }

  @Test
  void retainsExpiredServiceCredentialsButPurgesPersonalTokens() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
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
            });

    purger.purge();

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              assertEquals(0L, ApiToken.count("tokenHash", "oidc-old"));
              assertEquals(1L, ApiToken.count("tokenHash", "service-old"));
            });
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
