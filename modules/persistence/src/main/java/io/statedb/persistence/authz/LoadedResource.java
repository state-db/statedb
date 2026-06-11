package io.statedb.persistence.authz;

import io.statedb.persistence.identity.OrgMember;

/**
 * A resolved resource plus the role the principal holds in that resource's org (null if none — e.g.
 * an admin acting outside their memberships). The loader resolves the role once, while it already
 * has the resource's org in hand, and the interceptor hands it to the {@link Authorizer}.
 */
public record LoadedResource(CedarResource cedar, OrgMember.Role role) {}
