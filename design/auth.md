# Authentication and authorization

state-db keeps three kinds of credential apart: browser sessions, API tokens,
and update leases. Sessions and API tokens identify an actor, and Cedar decides
what that actor may do. A lease carries the authority of one update after Cedar
has approved it.

## Credentials and scope

| Credential | Used by | Scope |
| --- | --- | --- |
| OIDC or break-glass session | Console | The user's current organization memberships |
| Personal CLI token | Pulumi CLI | The user's current organization memberships |
| Service-account token | CI | One organization |
| Update lease token | Pulumi update requests | One stack and update, until expiry |

`TokenAuthFilter` reads `Authorization: token <token>`, hashes the raw value with
SHA-256, and loads the credential through `ApiTokenFinder`. Expired or revoked
tokens, deactivated users, and service-account tokens whose membership is gone
fail with 401. Raw tokens are shown once at creation; only their hashes are
stored.

Personal CLI tokens and console sessions identify the user without naming an
organization. For each request, `PrincipalRoleResolver` looks up the user's role
in the organization that owns the target resource, so removing a membership
takes effect on the next request. A service-account token is pinned to its
organization and grants nothing elsewhere, even if the same identity were a
member of another organization. Update leases keep their own authority and
expiry, described under [Update leases](#update-leases).

## Service accounts

A service account is a machine user under the reserved `local:service` issuer,
with a `service` membership in one organization and a single token pinned to
that organization. Organization owners and site administrators create, list,
and revoke them. The token's expiry is mandatory (30, 90, or 365 days), and its
access profile is fixed at creation; changing either means issuing a replacement.
Revocation is soft: the token and machine user are marked revoked and
deactivated, and both rows stay so past updates remain attributable.

## Console sessions

The console uses a backend-for-frontend design. Quarkus OIDC runs the
authorization-code flow and keeps the result in an encrypted, HttpOnly session
cookie, so the React application never handles OIDC tokens.

`ProviderTenantConfigResolver` builds an OIDC tenant per request from an enabled
`oidc_providers` row, which lets site administrators add and change providers at
runtime. The default static tenant is disabled. Bootstrap configuration
(`STATEDB_OIDC_BOOTSTRAP_*`) upserts one provider at startup and re-enables it on
every boot.

`/api/auth/login` redirects to the provider named by
`STATEDB_OIDC_DEFAULT_PROVIDER`, and `/api/auth/login/{provider}` serves
provider-specific links. Both reject unknown or disabled providers with 404
before any IdP redirect. The provider callback is `/api/auth/callback/{provider}`.
`OidcUserProvisioner` creates or updates the user by issuer and subject before
the session cookie is issued, so a provisioning failure fails the login.

The session cookie uses `Path=/api/` and `SameSite=Lax`. The path keeps the
cookie off `/pulumi/api/*`, so the Pulumi surface never sees an ambient browser
credential; Lax is needed for the IdP redirect back. All replicas must share
`STATEDB_SESSION_ENCRYPTION_SECRET` (at least 32 characters), which encrypts the
session and break-glass cookies and keys the CLI approval signer.

The HTTP permission layer requires authentication for `/api/*` except provider
discovery, login, and logout. On console routes that use Cedar,
`SessionPrincipalFilter` converts the session into the shared principal and
rejects sessions whose user row is missing or deactivated.

`GET /api/auth/logout` expires the session, OIDC flow-state, and break-glass
cookies and redirects to the sign-in page. It does not end the IdP session.
Sessions are stateless encrypted cookies with no server-side record, so logout
cannot revoke a copied cookie before it expires. Rotating
`STATEDB_SESSION_ENCRYPTION_SECRET` invalidates every outstanding session at
once.

## CLI login

`pulumi login` opens `/cli-login` with a loopback port and nonce. Because the
session cookie is scoped to `/api/`, that route only validates the parameters
and redirects to `/api/auth/cli-login`, where an anonymous browser is sent
through SSO with the default provider.

With a session, `/api/auth/cli-login` shows an approval page instead of minting
on a bare `GET`. The page cannot be framed or cached. Its POST checks a CSRF
token and a signed approval bound to the same user, then `CliTokenMinter`
creates a 24-hour personal token and redirects to
`http://127.0.0.1:<port>/` with the token and nonce. The host is fixed to
loopback, so a crafted link cannot send the token elsewhere. The Pulumi CLI
listener stores the credential.

The console can also mint, list, and revoke personal tokens. A personal token
stays valid until its 24-hour expiry or revocation, even after the user is
disabled at the IdP.

## Local break-glass

Break-glass login is Quarkus form authentication at `/api/auth/local`. It works
only while `STATEDB_LOCAL_GLASS_PASSWORD_HASH` is set (bcrypt, or the PBKDF2
format the AWS installer generates) and no OIDC provider is enabled. The
credential comes from the environment so it remains usable when the database
or IdP is the problem.

Login also requires a loopback TCP source unless
`STATEDB_LOCAL_GLASS_ALLOW_REMOTE=true`, and forwarded-client headers are
ignored. Container deployments set the override because published ports arrive
from the bridge gateway rather than loopback.

When a break-glass cookie is restored, the password and provider gates are
checked again, so enabling an OIDC provider ends every break-glass session. The
source-address check applies only at login. To reopen break-glass during an IdP
outage, disable the provider row; a provider supplied by bootstrap configuration
is re-enabled on the next start.

Break-glass signs in as the reserved bootstrap administrator
(`local:bootstrap:admin`). `AdminAllowList` treats that identity and the exact
`<issuer>:<subject>` entries in `STATEDB_ADMIN_SUBJECTS` as site administrators.
The list is read at startup, and there is no administrator flag in the database.

## Cedar decisions

`@Authorize` names an action and a resource loader. The loader resolves the
resource and the caller's role in its organization, and `Authorizer` submits the
resulting entities to Cedar. Roles are global Cedar entities attached to the
principal per request, so the role policies need no organization clause.

The human role hierarchy is `guest → viewer → member → owner`, where each role
inherits the one before it. Service accounts and site administrators have
separate roles that inherit nothing.

| Role | Default authority |
| --- | --- |
| Guest | Only stacks granted explicitly |
| Viewer | Read internal stacks and decrypt their service secrets |
| Member | Viewer access plus stack writes: create, lock, delete, rename, tag, and encrypt |
| Owner | All `stack:*` actions, plus member, service-account, and VCS connection management |
| Service | Read, decrypt, acquire update leases, and encrypt within its organization |
| Site administrator | Every action, including site administration, stack unlock, and manual compaction |

The optional service-account `stack_lifecycle` profile adds stack creation and
deletion. It does not add rename or tag management.

Cedar policies, templates, and schema live in
[policies](../modules/persistence/src/main/resources/policies/). At startup
`PolicySetCache` validates them against the schema and links the database
grants; a failure there stops startup. A failed later reload keeps the previous
valid policy set.

Explicit denies override every permit, including the administrator's. The policy
set forbids deleting any stack named `prod` and forbids site-administration
actions to anyone without the administrator role, so grants can never delegate
site administration.

A stack the caller cannot read resolves as absent and returns 404, which hides
its existence. A denied action on a readable stack returns 403.
[Stack visibility and grants](stack-sharing.md) describes private stacks, read
filtering, and grants.

## Update leases

`LeaseTokenFilter` reads `Authorization: update-token <token>` on lease-scoped
update routes: checkpoint and journal writes, event batches, lease renewal,
completion, and cancellation. It looks up the hashed token and checks its
expiry, stack, and update. It does not consult API tokens, organization
membership, or Cedar.

The caller establishes authority when it acquires the lease through the
ordinary token and Cedar path. Revoking that API token or removing the user's
membership does not interrupt an update already running. Cancellation, forced
unlock, or expiry ends lease authority.

Mutators revalidate the lease inside their database transaction. `LeaseRevalidator`
uses `FOR SHARE` and `clock_timestamp()`, so a request that waited on another
lock cannot rely on an earlier expiry check, and a concurrent cancel cannot
delete the lease mid-write.

## Source and tests

The implementation spans [console authentication](../modules/console-api/src/main/java/app/statedb/consoleapi/auth/),
[Pulumi authentication](../modules/pulumi-api/src/main/java/app/statedb/pulumiapi/auth/),
and [authorization](../modules/persistence/src/main/java/app/statedb/persistence/authz/).
The [application authorization tests](../modules/app/src/test/java/app/statedb/app/auth/)
cover role decisions, visibility, token scope, and session boundaries.
