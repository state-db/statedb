package app.statedb.app.console;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.persistence.identity.OidcProvider;
import app.statedb.persistence.identity.User;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.response.Response;
import jakarta.transaction.Transactional;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * With several providers and no {@code statedb.oidc.default-provider}, the server cannot pick an
 * IdP, so an anonymous {@code pulumi login} navigation goes to the console sign-in screen. Choosing
 * a provider completes SSO and preserves the CLI request through to approval.
 */
@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
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
  @Transactional
  void seedProviders() {
    OidcProvider.delete("name in ?1", PROVIDERS);
    for (String name : PROVIDERS) {
      OidcProvider p = new OidcProvider();
      p.name = name;
      p.issuerUrl =
          name.equals(PROVIDERS.get(1))
              ? ConfigProvider.getConfig().getValue("keycloak.url", String.class)
                  + "/realms/quarkus"
              : "https://" + name + ".example";
      p.clientId = "quarkus-app";
      p.clientSecret = "secret";
      p.scopes = List.of("openid");
      p.enabled = true;
      p.persist();
    }
  }

  @AfterEach
  @Transactional
  void cleanup() {
    OidcProvider.delete("name in ?1", PROVIDERS);
    User.delete("iss = ?1 and sub = ?2", "https://server.example.com", "123456");
  }

  @Test
  void choosingProviderWithoutDefaultReturnsToCliApproval() {
    String description = "my laptop & desktop";
    Response anonymous =
        given()
            .redirects()
            .follow(false)
            .accept("text/html")
            .queryParam("cliSessionPort", "59123")
            .queryParam("cliSessionNonce", "nonce-abc")
            .queryParam("cliSessionDescription", description)
            .when()
            .get("/api/auth/cli-login");
    CliLoginFlowTest.assertRedirectsToSignInScreen(anonymous);
    String returnTo = CliLoginFlowTest.queryParams(anonymous.getHeader("Location")).get("returnTo");
    assertEquals("nonce-abc", CliLoginFlowTest.queryParams(returnTo).get("cliSessionNonce"));
    assertEquals(description, CliLoginFlowTest.queryParams(returnTo).get("cliSessionDescription"));

    // The sign-in screen discovers both providers and links to the selected provider's login route.
    given()
        .when()
        .get("/api/auth/providers")
        .then()
        .statusCode(200)
        .body("providers.name", hasItems(PROVIDERS.get(0), PROVIDERS.get(1)))
        .body("breakGlass", equalTo(false));
    Response selected =
        given()
            .redirects()
            .follow(false)
            .accept("text/html")
            .queryParam("returnTo", returnTo)
            .when()
            .get("/api/auth/login/" + PROVIDERS.get(1));
    selected.then().statusCode(303);
    String flow = CliLoginFlowTest.pathWithQuery(selected.getHeader("Location"));
    assertEquals(returnTo, CliLoginFlowTest.queryParams(flow).get("returnTo"));
    assertEquals("/api/auth/flow/" + PROVIDERS.get(1), URI.create(flow).getPath());

    CliLoginFlowTest.SignIn signIn = CliLoginFlowTest.signIn(flow);
    Response restored =
        given()
            .urlEncodingEnabled(false)
            .redirects()
            .follow(false)
            .cookie(signIn.session())
            .accept("text/html")
            .when()
            .get(CliLoginFlowTest.pathWithQuery(signIn.callback().getHeader("Location")));
    restored.then().statusCode(303);
    assertEquals(returnTo, CliLoginFlowTest.pathWithQuery(restored.getHeader("Location")));

    given()
        .urlEncodingEnabled(false)
        .redirects()
        .follow(false)
        .cookie(signIn.session())
        .accept("text/html")
        .when()
        .get(CliLoginFlowTest.pathWithQuery(restored.getHeader("Location")))
        .then()
        .statusCode(200)
        .body(containsString("Allow CLI access?"))
        .body(containsString("my laptop &amp; desktop"));
  }
}
