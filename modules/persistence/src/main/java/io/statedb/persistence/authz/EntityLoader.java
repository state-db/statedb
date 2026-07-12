package io.statedb.persistence.authz;

import io.statedb.persistence.auth.AuthenticatedPrincipal;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.PathParam;
import java.lang.annotation.Annotation;
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

  Optional<LoadedResource> load(InvocationContext ctx, AuthenticatedPrincipal principal);

  RuntimeException notFound();

  /** The value of the intercepted method's {@code @PathParam(name)} argument. */
  static String pathParam(InvocationContext ctx, String name) {
    Annotation[][] annotations = ctx.getMethod().getParameterAnnotations();
    Object[] values = ctx.getParameters();
    for (int i = 0; i < annotations.length; i++) {
      for (Annotation annotation : annotations[i]) {
        if (annotation instanceof PathParam pathParam && name.equals(pathParam.value())) {
          return (String) values[i];
        }
      }
    }
    throw new IllegalStateException("missing @PathParam(\"" + name + "\")");
  }
}
