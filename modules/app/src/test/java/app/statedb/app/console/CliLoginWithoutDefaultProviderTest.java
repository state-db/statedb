package app.statedb.app.console;

import static io.restassured.RestAssured.given;

import app.statedb.persistence.identity.OidcProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * With several providers and no {@code statedb.oidc.default-provider}, the server cannot pick an
 * IdP, so an anonymous {@code pulumi login} navigation goes to the console sign-in screen.
 */
@QuarkusTest
@TestProfile(CliLoginWithoutDefaultProviderTest.Profile.class)
class CliLoginWithoutDefaultProviderTest {

  private static final List<String> PROVIDERS = List.of("cli-idp-a", "cli-idp-b");

  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("statedb.oidc.default-provider", "");
    }
  }

  @BeforeEach
  void seedProviders() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              OidcProvider.delete("name in ?1", PROVIDERS);
              for (String name : PROVIDERS) {
                OidcProvider p = new OidcProvider();
                p.name = name;
                p.issuerUrl = "https://" + name + ".example";
                p.clientId = "client";
                p.clientSecret = "secret";
                p.scopes = List.of("openid");
                p.enabled = true;
                p.persist();
              }
            });
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew().run(() -> OidcProvider.delete("name in ?1", PROVIDERS));
  }

  @Test
  void anonymousHandlerRedirectsToSignInScreen() {
    CliLoginFlowTest.assertRedirectsToSignInScreen(
        given()
            .redirects()
            .follow(false)
            .accept("text/html")
            .queryParam("cliSessionPort", "59123")
            .queryParam("cliSessionNonce", "nonce-abc")
            .when()
            .get("/api/auth/cli-login"));
  }
}
