package app.statedb.persistence.authz;

import app.statedb.persistence.auth.AuthenticatedPrincipal;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.core.UriInfo;
import java.lang.reflect.Method;

@Authorize(action = "", loader = NoopEntityLoader.class)
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class AuthorizeInterceptor {

  @Inject CurrentPrincipal currentPrincipal;

  @Inject Authorizer authorizer;

  @Inject AuthorizedResource authorizedResource;

  @Inject Instance<EntityLoader<?>> loaders;

  @Inject UriInfo uriInfo;

  @AroundInvoke
  public Object authorize(InvocationContext ctx) throws Exception {
    Authorize authorize = binding(ctx.getMethod());
    EntityLoader<?> loader = loader(authorize.loader());
    AuthenticatedPrincipal principal = currentPrincipal.get();
    if (principal == null) {
      // Fail closed with a diagnosis: @Authorize only works behind a filter that populates
      // CurrentPrincipal (the Pulumi token filter). Console routes authenticate via
      // SecurityIdentity and must not use this annotation until they set the principal too.
      throw new IllegalStateException(
          "@Authorize invoked with no authenticated principal in request scope on "
              + ctx.getMethod());
    }
    AuthorizationPath path =
        new AuthorizationPath(uriInfo.getPathParameters(), ctx.getMethod(), authorize.loader());
    LoadedResource loaded = loader.load(path, principal).orElseThrow(loader::notFound);

    AuthorizationDecision decision =
        authorizer.authorize(principal, loaded.role(), authorize.action(), loaded.cedar());
    if (!decision.allowed()) {
      throw new AuthorizationDeniedException(authorize.action());
    }

    authorizedResource.set(loaded.subject());
    return ctx.proceed();
  }

  private static Authorize binding(Method method) {
    Authorize authorize = method.getAnnotation(Authorize.class);
    if (authorize != null) {
      return authorize;
    }
    authorize = method.getDeclaringClass().getAnnotation(Authorize.class);
    if (authorize != null) {
      return authorize;
    }
    throw new IllegalStateException("@Authorize binding missing");
  }

  private EntityLoader<?> loader(Class<? extends EntityLoader<?>> type) {
    for (EntityLoader<?> loader : loaders) {
      if (type.isInstance(loader)) {
        return loader;
      }
    }
    throw new IllegalStateException("no EntityLoader registered for " + type.getName());
  }
}
