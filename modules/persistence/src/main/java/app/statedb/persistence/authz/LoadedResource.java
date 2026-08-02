package app.statedb.persistence.authz;

import app.statedb.persistence.identity.OrgMember;

/**
 * A resolved resource plus the role the principal holds in that resource's org (null if none — e.g.
 * an admin acting outside their memberships). The loader resolves the role once, while it already
 * has the resource's org in hand, and the interceptor hands it to the {@link Authorizer}.
 *
 * <p>{@code subject} is the domain object the loader resolved (e.g. a {@code StackRef} or an {@code
 * Organization}). After a successful authorization the interceptor publishes it via {@link
 * AuthorizedResource} so the handler can reuse it instead of resolving the same path again.
 */
public record LoadedResource(CedarResource cedar, OrgMember.Role role, Object subject) {}
