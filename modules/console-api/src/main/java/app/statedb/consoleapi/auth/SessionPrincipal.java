package app.statedb.consoleapi.auth;

import jakarta.ws.rs.NameBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a console route whose authorization goes through the Cedar PDP ({@code @Authorize}). The
 * name-bound {@link SessionPrincipalFilter} translates the quarkus-oidc session into the {@code
 * CurrentPrincipal} the interceptor expects — only on routes that carry this annotation, so plain
 * console reads keep resolving identity lazily from {@code SecurityIdentity}.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface SessionPrincipal {}
