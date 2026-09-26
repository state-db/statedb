package app.statedb.vcs.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.RequestValidationException;
import app.statedb.persistence.vcs.VcsAppRegistry;
import app.statedb.persistence.vcs.VcsAppRegistry.AppCredentials;
import app.statedb.persistence.vcs.VcsAppRegistry.AppRegistration;
import app.statedb.persistence.vcs.VcsAppRegistry.AppStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubAppManagerImplTest {

  private final ObjectMapper json = new ObjectMapper();
  private HttpServer server;
  private FakeRegistrationStore registrations;
  private GitHubAppManagerImpl manager;
  private String httpBase;
  private boolean userHasAccess = true;
  private boolean suspended;
  private boolean writable = true;
  private boolean otherApp;
  private int exchanges;
  private int userRequests;
  private Map<String, String> tokenForm;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
    httpBase = "http://127.0.0.1:" + server.getAddress().getPort();
    registrations = new FakeRegistrationStore(privateKey());
    manager =
        new GitHubAppManagerImpl(
            HttpClient.newHttpClient(),
            json,
            registrations,
            URI.create(httpBase),
            URI.create(httpBase),
            Clock.systemUTC());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void manifestRegistersBothCallbacksUnderConfiguredPrefix() throws Exception {
    var submission =
        manager.manifest(URI.create("https://db.example/prefix/"), "csrf-state", "state-db");
    assertEquals(
        httpBase + "/organizations/state-db/settings/apps/new?state=csrf-state",
        submission.actionUrl());
    JsonNode manifest = json.readTree(submission.manifest());
    assertEquals(
        "https://db.example/prefix/api/admin/vcs/github/manifest/callback",
        manifest.path("redirect_url").asText());
    assertEquals(
        "https://db.example/prefix/api/vcs/github/setup", manifest.path("setup_url").asText());
    assertEquals(
        "https://db.example/prefix/api/vcs/github/callback",
        manifest.path("callback_urls").get(0).asText());
    assertFalse(manifest.path("request_oauth_on_install").asBoolean());
    assertFalse(manifest.has("hook_attributes"));
    assertEquals("write", manifest.path("default_permissions").path("pull_requests").asText());
  }

  @Test
  void manifestConversionKeepsOAuthCredentialsForEncryptedStorage() {
    manager.completeManifest("abcdefghijklmnopqrstuvwxyz123456");
    assertEquals("PRIVATE-KEY-FROM-GITHUB", registrations.replacement.privateKeyPem());
    assertEquals("client-id", registrations.replacement.clientId());
    assertEquals("client-secret", registrations.replacement.clientSecret());
  }

  @Test
  void authorizationUsesPkceAndExchangesSecretsOnlyInRequestBody() throws Exception {
    URI callback = URI.create("https://db.example/prefix/api/vcs/github/callback");
    String verifier = "v".repeat(43);
    Map<String, String> query =
        form(manager.authorizationUrl(callback, "one-use-state", verifier).getRawQuery());
    assertEquals("client-id", query.get("client_id"));
    assertEquals(callback.toString(), query.get("redirect_uri"));
    assertEquals("one-use-state", query.get("state"));
    assertEquals("S256", query.get("code_challenge_method"));
    assertEquals(
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII))),
        query.get("code_challenge"));
    var installation = manager.completeConnection("one-use-code", callback, verifier, 9876, 1);
    assertEquals(9876, installation.id());
    assertEquals("state-db", installation.accountLogin());
    assertEquals("selected", installation.repositorySelection());
    assertEquals(1, exchanges);
    assertEquals(1, userRequests);
    assertEquals("client-secret", tokenForm.get("client_secret"));
    assertEquals(verifier, tokenForm.get("code_verifier"));
    assertEquals(callback.toString(), tokenForm.get("redirect_uri"));
  }

  @Test
  void appInstallationLookupAloneDoesNotAuthorizeTheUser() {
    assertEquals(9876, manager.verifyInstallation(9876).id());
    userHasAccess = false;
    assertThrows(RequestValidationException.class, this::connect);
  }

  @Test
  void rejectsSuspendedForeignAndInsufficientPermissionInstallations() {
    suspended = true;
    assertThrows(RequestValidationException.class, this::connect);
    suspended = false;
    otherApp = true;
    assertThrows(RequestValidationException.class, this::connect);
    otherApp = false;
    writable = false;
    assertThrows(RequestValidationException.class, this::connect);
  }

  @Test
  void appReplacementInvalidatesAuthorizationBeforeCodeExchange() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            manager.completeConnection(
                "code",
                URI.create("https://db.example/api/vcs/github/callback"),
                "v".repeat(43),
                9876,
                2));
    assertEquals(0, exchanges);
  }

  private void connect() {
    manager.completeConnection(
        "code", URI.create("https://db.example/api/vcs/github/callback"), "v".repeat(43), 9876, 1);
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    ObjectNode response = json.createObjectNode();
    int status = 200;
    if (path.startsWith("/app-manifests/")) {
      response
          .put("id", 12345)
          .put("slug", "state-db-test")
          .put("html_url", "https://github.example/apps/state-db-test")
          .put("pem", "PRIVATE-KEY-FROM-GITHUB")
          .put("client_id", "client-id")
          .put("client_secret", "client-secret")
          .putObject("owner")
          .put("login", "state-db");
    } else if (path.equals("/login/oauth/access_token")) {
      assertEquals(null, exchange.getRequestURI().getQuery());
      tokenForm =
          form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      exchanges++;
      response.put("access_token", "user-token");
    } else if (path.equals("/user/installations")) {
      assertEquals("Bearer user-token", exchange.getRequestHeaders().getFirst("Authorization"));
      userRequests++;
      var items = response.putArray("installations");
      if (userHasAccess) items.add(installation());
    } else if (path.equals("/app/installations/9876")) {
      assertTrue(exchange.getRequestHeaders().getFirst("Authorization").startsWith("Bearer "));
      response = installation();
    } else {
      status = 404;
    }
    byte[] bytes = json.writeValueAsBytes(response);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private ObjectNode installation() {
    ObjectNode value =
        json.createObjectNode()
            .put("id", 9876)
            .put("app_id", otherApp ? 999 : 12345)
            .put("repository_selection", "selected");
    value.putObject("account").put("login", "state-db").put("type", "Organization");
    value.putObject("permissions").put("pull_requests", writable ? "write" : "read");
    if (suspended) value.put("suspended_at", "2026-09-24T00:00:00Z");
    else value.putNull("suspended_at");
    return value;
  }

  private static Map<String, String> form(String encoded) {
    Map<String, String> result = new HashMap<>();
    for (String pair : encoded.split("&")) {
      String[] parts = pair.split("=", 2);
      result.put(
          URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
          URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
    }
    return result;
  }

  private static String privateKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getMimeEncoder(64, new byte[] {'\n'})
            .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----";
  }

  private static final class FakeRegistrationStore extends VcsAppRegistry {
    private final AppCredentials credentials;
    private AppRegistration replacement;

    FakeRegistrationStore(String key) {
      AppStatus status =
          new AppStatus(
              "github.com",
              12345,
              "state-db-test",
              "state-db",
              "https://github.example/apps/state-db-test",
              1);
      credentials = new AppCredentials(12345, key, 1, status, "client-id", "client-secret");
    }

    @Override
    public AppStatus replace(AppRegistration replacement) {
      this.replacement = replacement;
      return credentials.status();
    }

    @Override
    public Optional<AppCredentials> credentials(String host) {
      return Optional.of(credentials);
    }

    @Override
    public Optional<AppStatus> status(String host) {
      return Optional.of(credentials.status());
    }
  }
}
