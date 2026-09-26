package app.statedb.persistence.vcs;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.NotFoundException;
import java.net.URI;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The configured public origin and path prefix used by GitHub callbacks and published links.
 *
 * <p>GitHub publishing is disabled when no URL is configured; a malformed URL fails startup.
 */
@Startup
@ApplicationScoped
public class VcsPublicUrl {

  @ConfigProperty(name = "statedb.reports.public-base-url")
  Optional<URI> configured;

  private URI base;

  @PostConstruct
  void validate() {
    base = configured.map(VcsPublicUrl::normalize).orElse(null);
  }

  public boolean enabled() {
    return base != null;
  }

  public URI base() {
    if (base == null) {
      throw new NotFoundException(
          "GitHub publishing is disabled: statedb.reports.public-base-url is not set");
    }
    return base;
  }

  public URI resolve(String relativePath) {
    return base().resolve(relativePath);
  }

  private static URI normalize(URI u) {
    if (!u.isAbsolute()
        || u.getHost() == null
        || !("https".equals(u.getScheme()) || "http".equals(u.getScheme()))
        || u.getUserInfo() != null
        || u.getQuery() != null
        || u.getFragment() != null) {
      throw new IllegalStateException(
          "statedb.reports.public-base-url must be an http(s) origin and path without"
              + " credentials, query or fragment");
    }
    return URI.create(u.toString().replaceAll("/+$", "") + "/");
  }
}
