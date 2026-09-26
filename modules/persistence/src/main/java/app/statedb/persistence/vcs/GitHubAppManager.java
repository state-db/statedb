package app.statedb.persistence.vcs;

import app.statedb.persistence.vcs.VcsAppRegistry.AppStatus;
import java.net.URI;
import java.util.Optional;

/** Runtime management seam for the deployment-owned GitHub App. */
public interface GitHubAppManager {

  ManifestSubmission manifest(URI publicBaseUri, String state, String githubOrganization);

  AppStatus completeManifest(String code);

  Optional<AppStatus> status();

  Installation verifyInstallation(long installationId);

  URI installationUrl(String state);

  URI authorizationUrl(URI callback, String state, String verifier);

  Installation completeConnection(
      String code, URI callback, String verifier, long installationId, int appVersion);

  record ManifestSubmission(String actionUrl, String manifest) {}

  record Installation(
      long id,
      String accountLogin,
      String accountType,
      String repositorySelection,
      String managementUrl) {}
}
