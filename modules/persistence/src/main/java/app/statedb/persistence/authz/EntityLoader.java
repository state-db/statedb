package app.statedb.persistence.authz;

import app.statedb.persistence.auth.AuthenticatedPrincipal;
import java.util.Optional;

/**
 * Resolves the resource an {@code @Authorize}d method targets, plus the principal's role in that
 * resource's org.
 *
 * <p><b>Denial status codes.</b> Every loader returns empty both when the resource does not exist
 * and when the principal has no role in its org (and is not admin), so the two cases are
 * indistinguishable to the caller — a denial never confirms that the resource exists in another
 * tenant. Which status {@link #notFound()} maps that to is per route family, pinned by contract
 * tests: stack routes collapse to 404 (Pulumi's own "unknown stack" shape), org routes to a uniform
 * 403.
 */
public interface EntityLoader<T> {

  Optional<LoadedResource> load(AuthorizationPath path, AuthenticatedPrincipal principal);

  RuntimeException notFound();
}
