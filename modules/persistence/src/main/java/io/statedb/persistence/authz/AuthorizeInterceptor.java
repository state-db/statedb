package io.statedb.persistence.authz;

import io.statedb.persistence.auth.AuthenticatedPrincipal;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.lang.reflect.Method;

@Authorize(action = "", loader = NoopEntityLoader.class)
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class AuthorizeInterceptor {

  @Inject CurrentPrincipal currentPrincipal;

  @Inject Authorizer authorizer;

  @Inject Instance<EntityLoader<?>> loaders;

  @AroundInvoke
  public Object authorize(InvocationContext ctx) throws Exception {
    Authorize authorize = binding(ctx.getMethod());
    EntityLoader<?> loader = loader(authorize.loader());
    AuthenticatedPrincipal principal = currentPrincipal.get();
    LoadedResource loaded = loader.load(ctx, principal).orElseThrow(loader::notFound);

    AuthorizationDecision decision =
        authorizer.authorize(principal, authorize.action(), loaded.cedar());
    if (!decision.allowed()) {
      throw new AuthorizationDeniedException(authorize.action());
    }

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
