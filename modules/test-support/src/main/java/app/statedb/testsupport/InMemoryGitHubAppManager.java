package app.statedb.testsupport;

import app.statedb.persistence.vcs.GitHubAppManager;
import app.statedb.persistence.vcs.VcsAppRegistry.AppStatus;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.net.URI;
import java.util.Optional;

/** Network-free GitHub adapter with an explicit user-access boundary for console tests. */
@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryGitHubAppManager implements GitHubAppManager {

  private volatile AppStatus status;

  public void reset() {
    status = null;
  }

  @Override
  public ManifestSubmission manifest(URI publicBaseUri, String state, String githubOrganization) {
    return new ManifestSubmission(
        "https://github.test/settings/apps/new?state=" + state,
        "{\"redirect_url\":\""
            + publicBaseUri.resolve("api/admin/vcs/github/manifest/callback")
            + "\"}");
  }

  @Override
  public AppStatus completeManifest(String code) {
    status =
        new AppStatus(
            "github.com",
            12345,
            "state-db-test",
            "state-db",
            "https://github.test/apps/state-db-test",
            1);
    return status;
  }

  @Override
  public Optional<AppStatus> status() {
    return Optional.ofNullable(status);
  }

  @Override
  public Installation verifyInstallation(long id) {
    return new Installation(
        id,
        "state-db",
        "Organization",
        "selected",
        "https://github.test/organizations/state-db/settings/installations/" + id);
  }

  @Override
  public URI installationUrl(String state) {
    return URI.create("https://github.test/apps/state-db-test/installations/new?state=" + state);
  }

  @Override
  public URI authorizationUrl(URI callback, String state, String verifier) {
    return URI.create("https://github.test/login/oauth/authorize?state=" + state);
  }

  @Override
  public Installation completeConnection(
      String code, URI callback, String verifier, long id, int version) {
    if (!"valid-code".equals(code) || id != 9876 || version != 1 || verifier == null) {
      throw new IllegalArgumentException("The GitHub user cannot access this installation");
    }
    return verifyInstallation(id);
  }
}
