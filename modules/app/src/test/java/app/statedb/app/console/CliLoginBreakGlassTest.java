package app.statedb.app.console;

import static app.statedb.app.console.LocalGlassTestSupport.COOKIE;
import static app.statedb.app.console.LocalGlassTestSupport.PASSWORD;
import static app.statedb.app.console.LocalGlassTestSupport.login;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.Cookie;
import io.restassured.response.Response;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code pulumi login} on a break-glass-only install: the anonymous navigation goes to the sign-in
 * screen, and the break-glass session it returns with can approve and mint a CLI token.
 */
@QuarkusTest
@TestProfile(LocalGlassTestSupport.Profile.class)
class CliLoginBreakGlassTest {

  @Test
  void breakGlassSignInCompletesCliLogin() {
    Response anonymous =
        given()
            .redirects()
            .follow(false)
            .accept("text/html")
            .queryParam("cliSessionPort", "59123")
            .queryParam("cliSessionNonce", "nonce-glass")
            .when()
            .get("/api/auth/cli-login");
    CliLoginFlowTest.assertRedirectsToSignInScreen(anonymous);
    String returnTo = CliLoginFlowTest.queryParams(anonymous.getHeader("Location")).get("returnTo");

    Cookie glass =
        new Cookie.Builder(COOKIE, login(PASSWORD).statusCode(200).extract().cookie(COOKIE))
            .build();
    Response confirmation =
        given().redirects().follow(false).cookie(glass).accept("text/html").when().get(returnTo);
    confirmation.then().statusCode(200).body(containsString("Allow CLI access?"));

    Response mint =
        CliLoginFlowTest.approvalPost(glass, confirmation)
            .formParam("request", CliLoginFlowTest.hiddenInput(confirmation.asString(), "request"))
            .formParam("decision", "allow")
            .when()
            .post("/api/auth/cli-login/approve");
    mint.then().statusCode(302);
    URI loopback = URI.create(mint.getHeader("Location"));
    assertEquals("127.0.0.1", loopback.getHost());
    Map<String, String> q = CliLoginFlowTest.queryParams(loopback.toString());
    assertEquals("nonce-glass", q.get("nonce"));
    assertNotNull(q.get("accessToken"));

    given()
        .header("Authorization", "token " + q.get("accessToken"))
        .header("Accept", "application/vnd.pulumi+9")
        .when()
        .get("/pulumi/api/user")
        .then()
        .statusCode(200);
  }
}
