package io.statedb.persistence.authz;

import jakarta.enterprise.context.RequestScoped;

/**
 * The domain object ({@code StackRef}, {@code Organization}, …) that the {@code @Authorize} entity
 * loader resolved for this request. Populated by {@link AuthorizeInterceptor} only after the
 * authorization decision allowed the request, so handlers can reuse the already-resolved resource
 * instead of re-running the same lookup.
 */
@RequestScoped
public class AuthorizedResource {

  private Object subject;

  // Package-private: only AuthorizeInterceptor writes.
  void set(Object subject) {
    this.subject = subject;
  }

  public <T> T subject(Class<T> type) {
    if (!type.isInstance(subject)) {
      throw new IllegalStateException(
          "no authorized "
              + type.getSimpleName()
              + " in request scope — is the method missing @Authorize with the matching loader?");
    }
    return type.cast(subject);
  }
}
