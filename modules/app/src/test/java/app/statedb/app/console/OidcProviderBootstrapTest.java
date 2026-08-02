package app.statedb.app.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.bootstrap.OidcBootstrapConfig;
import app.statedb.persistence.bootstrap.OidcProviderBootstrap;
import app.statedb.persistence.identity.OidcProvider;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/** Boot-time provider upsert is idempotent and env wins on conflict (secret/issuer rotation). */
@QuarkusTest
class OidcProviderBootstrapTest {

  @Inject OidcProviderBootstrap bootstrap;

  @Test
  @TestTransaction
  void upsertIsIdempotentAndEnvWins() {
    bootstrap.upsert(
        new OidcBootstrapConfig("google", "https://old.example", "cid-old", "sec-old"));
    bootstrap.upsert(
        new OidcBootstrapConfig("google", "https://new.example", "cid-new", "sec-new"));

    assertEquals(1, OidcProvider.count("name", "google"), "one row per provider name");
    OidcProvider provider = OidcProvider.<OidcProvider>find("name", "google").firstResult();
    assertEquals(7, provider.id.version());
    assertEquals("https://new.example", provider.issuerUrl);
    assertEquals("cid-new", provider.clientId);
    assertEquals("sec-new", provider.clientSecret);
    assertTrue(provider.enabled, "re-running re-enables the provider");
  }

  @Test
  @TestTransaction
  void insertUsesScopeDefault() {
    bootstrap.upsert(new OidcBootstrapConfig("scoped", "https://idp.example", "cid", "sec"));

    OidcProvider provider = OidcProvider.<OidcProvider>find("name", "scoped").firstResult();
    assertEquals(java.util.List.of("openid", "profile", "email"), provider.scopes);
  }
}
