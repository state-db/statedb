package app.statedb.persistence.authz;

import app.statedb.persistence.auth.AuthenticatedPrincipal;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.stack.StackRefResolver;
import app.statedb.persistence.update.StackUpdate;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a route keyed by an update id alone, for endpoints that address a run rather than a
 * stack — a run is globally identified by its id, and a URL that repeats its stack's names to say
 * so is a URL a caller cannot construct from a run id it already holds.
 *
 * <p>Authorization is unchanged by that: the update resolves to its stack, and the stack decides.
 * {@link StackReadAuthorizer} applies the same read-visibility predicate after either loader has
 * resolved its stack — an update must not become a way to read a stack the principal cannot see,
 * and a second, paraphrased copy of the predicate is exactly how that would happen. {@link
 * StackEntityLoader#stackResource} is shared for the same reason, so a Cedar policy written against
 * a stack applies here without being rewritten.
 *
 * <p>Like every loader, a resource that does not exist and one the principal may not read are
 * indistinguishable to the caller. Both are empty, and {@link #notFound()} is a plain 404 rather
 * than the Pulumi-shaped stack error: the request named no stack, and naming one in the response
 * would be the disclosure the predicate just prevented.
 */
@RequestScoped
public class UpdateEntityLoader implements EntityLoader<StackRef> {

  /** The URI template variable this loader reads. */
  public static final String UPDATE_ID = "updateId";

  @Inject StackRefResolver resolver;

  @Inject StackReadAuthorizer readAuthorizer;

  @Override
  public Optional<LoadedResource> load(AuthorizationPath path, AuthenticatedPrincipal principal) {
    UUID updateId;
    try {
      updateId = UUID.fromString(path.value(UPDATE_ID));
    } catch (IllegalArgumentException malformed) {
      return Optional.empty();
    }

    Optional<StackUpdate> update =
        StackUpdate.<StackUpdate>find("id", updateId).firstResultOptional();
    if (update.isEmpty()) {
      return Optional.empty();
    }
    Optional<StackRef> ref = resolver.resolveByStackId(update.get().stackId);
    if (ref.isEmpty()) {
      return Optional.empty();
    }

    return readAuthorizer.visibleResource(ref.get(), principal);
  }

  @Override
  public RuntimeException notFound() {
    return new NotFoundException();
  }
}
