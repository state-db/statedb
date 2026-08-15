# state-db: Authentication & Authorization

Companion to [`architecture.md`](./architecture.md) and [`db-schema.md`](./db-schema.md). The architecture doc fixes the two HTTP surfaces and the identity tables; this doc fills in:

- the exact authentication implementation per surface,
- the single authorization decision point every protected handler funnels through,
- the policy model — Cedar — and the entity, action, and schema vocabulary feeding it.

---

## Goals

- **One PDP for everything.** Every authorization decision — Pulumi CLI request, console request, admin operation — is made by the same `Authorizer` against the same Cedar policy set. No per-handler `if (role == "owner")` scattered through the codebase.
- **Standard libraries, no rolled-our-own crypto.** `quarkus-oidc` for the SPA backend-for-frontend session, `cedar-java` for policy evaluation, OS-provided primitives elsewhere.
- **Cedar entities mirror schema entities.** A `Stack` Cedar entity carries the same `org/project/stack` identity assembled from `stacks` + `projects` + `organizations`. No second source of truth.
- **Policies are reviewable.** Policy text lives in `git` for Wave 1, with a per-deployment override table available in DB. The console policy editor is Wave 2.5.
- **Decisions are auditable.** Every authorization check writes a structured decision record (allow/deny + matching policy ids) to the request log. This is useful in production and essential for debugging policy regressions.

## Non-goals

- **Per-resource ACLs in the console UI** for Wave 1. The *engine* supports them now — resource and managed (per-principal) policies are implemented via templates + `policy_grants` (see "User and resource policies") — but the admin screens to create and revoke grants are Wave 2. Grants are seeded directly today.
- **Conditional access** (time-of-day, IP allow-lists, MFA-step-up). Cedar handles these as policy `when` clauses, but Wave 1 doesn't ship example policies that use them.
- **Pluggable PDP.** OPA, AVP, Permit.io are *not* on the list. We commit to Cedar embedded as a library and own its JNI/reachability integration in the native image.
- **Policy-driven admin elevation.** Admin status is read from `STATEDB_ADMIN_SUBJECTS` env at boot. The allow-list makes a principal a member of the global `Role::"admin"`; the admin policy references it. Storing admin status in the DB stays out of scope.

---

## Principals

Every authenticated request resolves to exactly one of these:

| Kind | Identifier | Source | Authn surface |
|------|------------|--------|---------------|
| `User` | `<iss>:<sub>` (e.g. `https://accounts.google.com:115...`) | `users` table; created on first OIDC login | Console (session cookie) **or** Pulumi CLI (OIDC-derived `api_token` row, `kind='oidc'`) |
| Service account | `local:service:<sub>` | Machine `users` row (reserved issuer `local:service`) + `org_members` row with `role='service'` + org-pinned `api_tokens` row, `kind='service_account'` | Pulumi CLI only |
| `Lease` | `lease:<stack_id>:<update_id>` | `stack_locks` row | Pulumi CLI lease-scoped routes only |
| `BreakGlass` | Bootstrap admin (`local:bootstrap:admin`) | env hash (bcrypt) + loopback (or explicit remote opt-in), only while zero OIDC providers are enabled | `/api/auth/local` only |

Users and service accounts go through the full Cedar PDP. `Lease` does not — see "Lease-token bypass" below. `BreakGlass` authenticates as the reserved bootstrap admin identity and from there is indistinguishable from a regular admin login.

> **Implementation note.** A service account is a *machine user*, not a separate Cedar principal
> type: a `users` row under the reserved `local:service` issuer whose org membership carries the
> `service` role. Reusing the User entity type keeps every action's `appliesTo` untouched and reuses
> the whole token/membership/role-resolution machinery; the distinct-grant-set requirement is met by
> `Role::"service"` standing outside the viewer ⊂ member ⊂ owner ladder with its own policy
> (`policies/service.cedar`). Minting/list/revoke is owner-only via the `serviceaccount:*` actions
> (`POST/GET/DELETE /api/orgs/{org}/service-accounts`); deleting the machine user cascades the
> membership and token away.

### Principal resolution

A request enters the authentication mechanism for its surface:

```
/pulumi/api/* (non-lease)  →  TokenAuthFilter   → Principal{User|ServiceAccount}
/pulumi/api/* (lease)      →  LeaseTokenFilter  → Principal{Lease}
/api/*                     →  Quarkus HTTP permission + quarkus-oidc session
```

The Pulumi filters publish an application principal for Cedar authorization. Console authentication
stays in Quarkus's `SecurityIdentity`; console resources resolve application data from its verified
OIDC issuer and subject only when they need it.

---

## Authentication

### Pulumi CLI: `Authorization: token <tok>`

- Header parsed in `TokenAuthFilter`. Scheme MUST be `token`.
- Raw token sha256'd; lookup against `api_tokens.token_hash`.
- Joined to `organizations` to materialize `Subject{user_id, org_id, org_name, kind}`.
- `org_members` re-checked on every request — revoking a user's org membership instantly invalidates all their tokens for that org.
- The filter authenticates only. Org-scope and action-level decisions are made downstream — the `PrincipalRoleResolver` attaches the role the principal holds in the resource's org, and the Cedar PDP evaluates the role policies against it.
- 401 on miss/expired/disabled. 403 is reserved for authorization-layer denials and always comes from the PDP.

### Pulumi CLI: `Authorization: update-token <leaseTok>`

- `LeaseTokenFilter` validates the token against `stack_locks` (`lock_token` match AND `expires_at > now()`).
- Verifies the `{stack}` path segment resolves to the same `stack_id` as the lock row.
- Sets `LeaseContext{stack_id, update_id}` on the `SecurityContext`.
- Does not invoke the PDP. Authority for lease-scoped operations was established at lock acquire (which went through full token + Cedar authz). Once held, the lease is sufficient — see "Lease-token bypass".

### Console: SPA + BFF with `quarkus-oidc`

The console uses the **backend-for-frontend (BFF)** pattern. `quarkus-oidc` runs in `web-app` mode in Quarkus; the SPA never sees access or id tokens, only an HttpOnly session cookie issued by the backend.

This gets us, from a single extension:

- JWKS rotation, refresh-token rotation, OIDC discovery, end-session endpoints, `state` and `nonce` validation, PKCE.
- Multi-provider via `TenantConfigResolver`. We register one `TenantConfigResolver` that reads `oidc_providers` rows.
- A signed/encrypted session cookie generated and verified by the extension; we never roll our own session crypto.

The SPA contract is: HttpOnly cookie scoped to `/api/`, opaque to JavaScript. From the SPA's perspective, "logged in" means "fetch /api/user succeeded".

#### Login flow

```
SPA loads → fetch /api/user
  ├─ 200 → render console
  └─ 401 → location = /api/auth/login
            → /api/auth/login selects STATEDB_OIDC_DEFAULT_PROVIDER,
              validates it (unknown/disabled → 404),
              then 303 → /api/auth/flow/<provider_name>
            → authenticated HTTP permission on /api/auth/flow → Quarkus redirect to IdP
            → IdP → /api/auth/callback/<provider_name>
            → Quarkus exchanges the code
            → AuthenticationCompletionAction upserts the user
            → Quarkus sets the session cookie
            → 302 → /api/auth/flow/<provider_name>
            → 303 → /  (SPA reloads; /api/user succeeds)
```

Authentication runs with `quarkus.http.auth.proactive=false`. Quarkus HTTP permissions permit only
`/api/auth/login` and `/api/auth/login/*`; every other `/api/*` path requires an authenticated
identity. With no tenant selected for an anonymous data request, Quarkus returns **401** rather than
choosing an IdP. The public login route validates the provider before redirecting to the protected
`/api/auth/flow/{provider}` route, whose challenge starts the code flow. The callback is owned by the
OIDC mechanism. An `AuthenticationCompletionAction` upserts the user after token validation and
before the session cookie is issued, so provisioning failure fails authentication. Tenants are
**dynamic**: a `TenantConfigResolver` builds a `web-app` tenant from the matching enabled
`oidc_providers` row, using the path on `/flow` and `/callback` and the session cookie on data routes.
The default tenant is disabled (`quarkus.oidc.tenant-enabled=false`).

The common entrypoint is `/api/auth/login`, which selects the provider named by
`STATEDB_OIDC_DEFAULT_PROVIDER`. Provider-specific links use `/api/auth/login/{provider}` directly;
this supports organization or enterprise SSO links without making the SPA infer tenancy. A provider
chooser can be added later if deployments with multiple interactive providers become common.

#### Cookie semantics

The session cookie is generated and verified by `quarkus-oidc`. It is HttpOnly, Secure, SameSite=Lax (Lax not Strict — Strict breaks the IdP redirect-back), Path=`/api/`. The `Path=/api/` scope is critical: it ensures the cookie is **never** sent to `/pulumi/api/*`, preserving the no-overlap property between the two surfaces.

`quarkus-oidc` rotates the underlying JWE key from `quarkus.oidc.token-state-manager.encryption-secret` (env-supplied). Rotation is the standard redeploy-with-overlap dance.

#### CLI token mint — native `pulumi login`

We do **not** ship a bespoke login CLI or drive a second OIDC round trip. Pulumi's own
`pulumi login <backend>` already implements the AWS-SSO-style browser flow against *any* backend
(not just `app.pulumi.com`): it starts a loopback listener on `127.0.0.1:<port>`, generates a
nonce, opens the browser to `<console>/cli-login?cliSessionPort=&cliSessionNonce=`, then captures
the token from the loopback redirect and writes its own `credentials.json`. We implement the
console side of that contract (`pkg/backend/httpstate/backend.go` — `loginWithBrowser` /
`serveBrowserLoginServer`), so `pulumi login` works natively. The token's freshness and MFA come
from the SSO session itself — re-running `pulumi login` re-establishes both — exactly as
`aws sso login` gates short-lived credentials behind a daily, MFA'd SSO session.

Three routes cooperate, because the session cookie is scoped to `/api/` (so Pulumi's root-level
`/cli-login` cannot see it):

- **`GET /cli-login`** — the entry point Pulumi opens (console root). Public; it validates
  `cliSessionPort`/`cliSessionNonce` and 303-redirects into the `/api/` space so the session cookie
  is sent and quarkus-oidc can challenge an anonymous caller.
- **`GET /api/auth/cli-login`** — the authenticated handler. The `/api/*` HTTP permission plus
  `ProviderTenantConfigResolver`'s self-start drive a fresh SSO login if there is no session
  (quarkus-oidc restores this URL after the callback); with a session it renders a human approval
  page and does not mint a token.
- **`POST /api/auth/cli-login/approve`** — validates the short-lived signed approval request, binds
  it to the current OIDC user, and only then mints an account-scoped token and 302s to
  `http://127.0.0.1:<port>/?accessToken=&nonce=`. The redirect host is hard-coded to `127.0.0.1` —
  only the port varies — so a crafted link cannot point the token at an external host. The token in
  the loopback URL is the same pattern Pulumi Cloud itself uses; the listener is the user's own
  ephemeral, single-use, loopback-only process.
- **`GET /welcome/cli`** — the cosmetic page Pulumi's listener bounces the browser to after it has
  captured the token (`no-store`, `no-referrer`).

The minted token carries a **credential scope** of `all_current_memberships`
(`api_tokens.kind='oidc'`, `org_id` NULL): it authenticates the actor, and authorization is resolved
per request against the resource's org — the same path a console session takes (see "Role
resolution"). This matches Pulumi's own token model, where the org is chosen by the stack path, not
the token, so minting needs no org argument and no Cedar gate (it can never grant more than the
user's live memberships allow). No mint secret and no second OIDC round trip are required; the live
console session plus an explicit "Allow access" click are the gates. The approval prevents token
minting on a mere top-level GET and matches the user checkpoint in flows such as `aws sso login`,
without claiming to defend against a compromised local machine.

#### Logout

`GET /api/auth/logout` (public; a top-level browser navigation, so it clears cookies and 303-redirects to `/console/login` rather than returning JSON). It is idempotent — an already-anonymous hit still redirects and never triggers an SSO challenge.

- Expires both console session cookies in the browser: the quarkus-oidc session cookie (any tenant suffix or chunk, scoped to `/api/`) and the break-glass form-auth cookie (`state-db-glass`, scoped to `/`). One endpoint covers both login flavours.
- **Local logout only.** The IdP session is left intact, so re-signing in is a silent SSO round trip. RP-initiated (`end_session_endpoint`) logout is deliberately not done — for a self-hosted backend, signing the user out of their IdP entirely is surprising, and it would require resolving `end_session` per dynamic tenant.
- **Stateless limitation.** Both cookies are stateless encrypted cookies with no server-side record, so logout can only instruct the browser to drop them. It cannot revoke a cookie value already copied elsewhere; such a copy stays valid until the cookie's own expiry. The only "kill every session now" lever is rotating `STATEDB_SESSION_ENCRYPTION_SECRET`, which invalidates all outstanding cookies at once. Server-side session invalidation would require moving to server-side token state (`quarkus-oidc-db-token-state-manager`), a larger change not taken here.

The CLI has no server-side logout to mirror: `pulumi logout` only deletes the token from `~/.pulumi/credentials.json` and never calls the backend. The server-side counterpart to invalidating a CLI credential is revoking its `api_tokens` row via the tokens UI/API, which is independent of this console flow.

#### Local break-glass

`quarkus-oidc` has no primitive for minting its session cookie outside a real IdP code flow, so break-glass does not touch it. Instead it is Quarkus **form authentication** in SPA mode (`quarkus.http.auth.form`, all page locations empty → bare 401/200 status codes): the login POST to `/api/auth/local` is verified by `LocalGlassIdentityProvider` (bcrypt against `STATEDB_LOCAL_GLASS_PASSWORD_HASH`, loopback/remote-opt-in and zero-enabled-providers gate), and on success the mechanism stores the principal in its own AES-256-encrypted cookie (`state-db-glass`), keyed by the same `STATEDB_SESSION_ENCRYPTION_SECRET` that encrypts the OIDC session cookie, so it is stable across replicas. On every cookie restore `LocalGlassTrustedIdentityProvider` re-checks the zero-providers gate — enabling OIDC invalidates all outstanding break-glass sessions immediately. The resulting identity carries a synthetic `JsonWebToken` principal for the bootstrap admin (`local:bootstrap:admin`, `users` row created idempotently at login), so `SessionPrincipalFilter`, Cedar role resolution and the console API treat it exactly like an OIDC session. Gating rationale per `architecture.md`.

#### CSRF protection

Cookie-authenticated state-changing requests under `/api/*` are gated by a `CsrfFilter` bound `@PreMatching` to all non-`GET`/`HEAD`/`OPTIONS` methods on the `/api/*` surface. The filter enforces, in order:

1. **`Content-Type` MUST be `application/json`** (or `application/json; charset=...`). Any other content type → 415. This forces a CORS preflight on every cross-origin browser fetch and rules out the classic `<form>`-submission CSRF vector entirely (HTML forms cannot send JSON).
2. **`Origin` header MUST match `STATEDB_PUBLIC_ORIGIN`** (env-supplied; e.g. `https://statedb.example.com`). Missing or mismatched → 403. `Origin` is set automatically by browsers on every fetch and cannot be forged from JavaScript.
3. **`Referer` is checked as a fallback** when `Origin` is absent (rare; some legacy proxies strip it). Same match rule as Origin; missing both → 403.

The filter does *not* implement double-submit-cookie or synchronizer tokens. The Origin/Referer + JSON-content-type combination is sufficient for an SPA-only consumer and avoids token-management complexity.

`/pulumi/api/*` is not subject to CSRF protection: it has no ambient credential. The CLI sends `Authorization: token <tok>` explicitly on every request; a browser cannot replay it cross-site without the JS already having the token, at which point CSRF is irrelevant.

`/api/auth/login/*` and `/api/auth/callback/*` are exempt — the OIDC redirect-back is by definition cross-site and is protected by the OIDC `state` and `nonce` parameters that `quarkus-oidc` validates.

---

## Authorization model: Cedar

Every protected operation is a Cedar `(principal, action, resource, context)` request. The Cedar engine answers `Allow` or `Deny`; the request handler proceeds or returns 403.

### Why Cedar

Cedar gives us:

- Formal evaluation semantics (no surprises in policy interactions).
- A schema system that catches typos and impossible policies at policy-load time, not at request time.
- A vocabulary (principal/action/resource/context, entity hierarchy, attribute access) that maps cleanly to our domain.
- Open source, vendor-neutral, embeddable as a library.

Cedar over OPA (Rego is more expressive but harder to reason about and lacks a schema), AVP (forces an AWS dependency on a self-hostable product), and pure-Java rules (doesn't scale to per-stack grants in Wave 2+).

### Action vocabulary

Actions are named IAM-style as `resource:Operation` (lowercase service prefix = resource type, e.g. `stack:Read`). Wave 1 set:

| Action | Bound to (route examples) | Notes |
|--------|---------------------------|-------|
| `stack:Read` | `GET /pulumi/api/stacks/{ref}`, `GET /api/pulumi/stacks/{ref}` | Includes export/checkpoint download |
| `stack:Create` | `POST /pulumi/api/stacks/{org}/{project}` | |
| `stack:Delete` | `DELETE /pulumi/api/stacks/{ref}` | |
| `stack:Lock` | `POST /pulumi/api/.../update/{id}` (acquire), `POST /pulumi/api/.../update/{id}/cancel` | CLI update control: acquire a lease, or force-cancel the active update from a second CLI using a normal API token; the lease itself bypasses Cedar afterward |
| `stack:EncryptSecrets` | `POST /pulumi/api/stacks/{ref}/encrypt` | Encrypt secret values during an update |
| `stack:DecryptSecrets` | `POST /pulumi/api/stacks/{ref}/decrypt`, `.../batch-decrypt` | Decrypt for read / preview flows |
| `stack:Unlock` | `DELETE /api/admin/stacks/{ref}/lock` | Admin-only manual unlock |
| `stack:ReadHistory` | `GET /api/pulumi/stacks/{ref}/updates` | |
| `stack:ReadEvents` | `GET /api/pulumi/stacks/{ref}/updates/{id}/events` | |
| `project:Create` | First `POST /pulumi/api/stacks/{org}/{project}/{stack}` for a new project | App-level lifecycle; not a separate route |
| `project:Delete` | (No direct route in Wave 1; project disappears when last stack does) | Reserved for future explicit project lifecycle |
| `org:Read` | `GET /api/orgs/{name}` | Lists projects, stacks, members |
| `org:Admin` | `/api/admin/orgs/...`, member management | |
| `org:AddMember`, `org:RemoveMember` | Admin endpoints | |
| `token:MintServiceAccount` | `POST /api/admin/tokens` | Admin-only |
| `token:List` | `GET /api/tokens` | Own tokens; admin sees all |
| `token:Revoke` | `DELETE /api/tokens/{id}` | Owner or admin |
| `provider:Manage` | `POST/PUT/DELETE /api/admin/providers` | Admin-only |
| `compaction:Trigger` | `POST /api/admin/stacks/{ref}/compact` | Admin-only |
| `user:ReadOthers` | `GET /api/admin/users` | Admin-only |

Pulumi-internal identity endpoints are protected by token authentication. They return only the
identity and organization already bound to the validated token, so there is no caller-selected
resource requiring a Cedar decision.

The action set is **closed** for Wave 1. Adding an action requires a schema bump and a policy update; this is intentional friction.

#### Action bundles

Two schema-declared **bundle actions** group the leaf actions so a policy grants a capability in one token instead of re-listing members (the IAM `AmazonS3ReadOnlyAccess`-style pre-canned bundle):

| Bundle | Members |
|--------|---------|
| `stack:ReadOnlyAccess` | `stack:Read`, `stack:DecryptSecrets` |
| `stack:WriteAccess` | `stack:Create`, `stack:Lock`, `stack:Delete`, `stack:EncryptSecrets` |
| `stack:*` | both bundles above (all stack actions — the IAM `"Action": "stack:*"` wildcard) |

`stack:*` is the all-of-service wildcard: the two `*Access` bundles are themselves members of it, so
`action in Action::"stack:*"` matches every stack action (used by `StackFullAccess`, the owner
policy). Membership is declared on each leaf/bundle in `schema.cedar` (`action "stack:Create" in ["stack:WriteAccess"] …`, `action "stack:WriteAccess" in ["stack:*"]`). Because the engine never sees the schema at evaluation time, `PolicySetCache` derives the action entities (with their `memberOf` parents) from the schema JSON at startup and includes them in every request's entity slice — so `action in Action::"stack:WriteAccess"` resolves. The schema stays the single source of truth; there is no second hand-maintained list.

### Cedar entity model

```
User::"<users.id>"          (the global user UUID — matches policy_grants.principal_user_id)
  parents:
    - Role::"owner" | Role::"member" | Role::"viewer" | Role::"guest"   (the role the user holds in
      THIS request's resource org, resolved per request — see "Role resolution"; absent if not a member)
    - Role::"admin"   (only if the principal is in STATEDB_ADMIN_SUBJECTS)
  attributes: (none)

ServiceAccount::"service:<token_id>"
  parents:
    - Role::"service"      (single role per service account)
  attributes:
    description: String
    owner_user: String     (the admin who minted it; advisory)

Lease::"lease:<stack_id>:<update_id>"
  attributes:
    stack: Stack           (reference)
    update_id: String

Stack::"statedb:stack:<org>/<project>/<stack>"
  parents:
    - Project::"statedb:project:<org>/<project>"
        parents:
            - Org::"statedb:org:<org_name>"
  attributes:
    id: String             (== the entity id; lets policies pattern-match it IAM-style,
                            e.g. resource.id like "statedb:stack:*/prod")
    visibility: String     ("internal" | "private" — viewer.cedar gates read on it;
                            see design/stack-sharing.md)

Org::"statedb:org:<org_name>"
  attributes:
    id: String

Project::"statedb:project:<org>/<project>"
  (structural parent; carries id: String)

Token::"<token_id>"
  attributes:
    owner: User | ServiceAccount
    org: Org
    kind: String           ("oidc" | "service_account")

Provider::"<provider_name>"
  attributes:
    issuer: String
```

The user is a **global identity** (`User::<users.id>`), not an org-scoped one — a user may belong to
several orgs with a different role in each. Roles are **global** entities (`Role::"member"`, not
`OrgRole::"acme:member"`) and inherit: `Role::"owner"` ⊃ `Role::"member"` ⊃ `Role::"viewer"` ⊃
`Role::"guest"`. The
principal carries exactly the role it holds **in the resource's org**, resolved per request and
attached as a parent. So the role policies are pure RBAC — `permit(principal in Role::"member", …)`
with no org clause, because the attached role is already org-correct. Resource entity ids are
**IAM-style fully-qualified names** (`statedb:<service>:<org>/<project>/<stack>` — the same shape as
an AWS ARN but state-db-native, no Amazon branding), and each resource carries that name as an `id`
attribute so a policy can pattern-match it the IAM way (`resource.id like …`); the Stack → Project →
Org hierarchy is kept so managed grants can scope through it. Tenancy is still the resolved role, not
a stored field — the name is for resource-pattern matching only.

```
Role::"guest"   ←   Role::"viewer"   ←   Role::"member"   ←   Role::"owner"

Role::"service"  (inherits from nothing — its own grant set)
Role::"admin"    (inherits from nothing — uniformly allowed by the admin policy)
```

`Role::"guest"` is the base of the human ladder and matches **no static policy**: a bare guest is a
member (tenancy, revocation, `pulumi login`) with zero default permissions, whose access is exactly
the sum of their `policy_grants` rows. `principal in Role::"guest"` means "any human member,
including guests" — the share-template guard keys on it — while `principal in Role::"viewer"`
continues to exclude guests. See `design/stack-sharing.md`.

`Role::"service"` inherits from nothing: service accounts get their own grant set so a viewer can't
accidentally do what a CI token can or vice versa. `Role::"admin"` likewise stands alone and is the
only role not bounded by org membership.

### Role resolution

`PrincipalRoleResolver` is the single tenancy boundary — `(actor, credentialScope, resourceOrgId) →
Optional<Role>`:

- **Actor** (`userId`, `iss`, `sub`) says who is acting. It never carries an org.
- **Credential scope** says which orgs this credential may be used against.
- **Resource org** comes from the target stack/org row or path resolution, never from the credential.

Credential scopes:

- **`single_org`** (`service_account`; persisted as `api_tokens.org_id`) → the credential's cached
  role iff `resourceOrgId == credential.org`, else none. This enforces token org-scoping: a CI token
  grants nothing outside its org even if the user is a member elsewhere.
- **`all_current_memberships`** (console session, account-scoped `oidc` CLI token) →
  `org_members(userId, resourceOrgId).role`, else none. Membership is re-checked on every request,
  so revoking it instantly narrows what the credential can do.

The loader resolves the role once (it already holds the resource's org id from the
`stacks JOIN projects JOIN organizations` resolution), uses it for the hard org boundary — *no role
and not admin ⇒ `Optional.empty()` ⇒ 404* — and passes it to the `Authorizer`, which attaches
`Role::<role>` to the principal entity.

### Schema declaration

A single Cedar schema file (`policies/schema.cedar` in `persistence` resources) declares the entity types, action types, and `appliesTo` constraints. Loaded at startup and validated against the policy set; any mismatch fails app startup loud, before the first request.

The schema is checked into git. Changing the schema is a code change with normal review; changing a *policy* without a schema change is a hot-reloadable operation (Wave 2 — see "Policy storage").

### Resource construction

Per request, the `@Authorize` interceptor (next section) loads the resource entity. For most operations this is one Stack; the entity loader resolves `stacks JOIN projects JOIN organizations` once and constructs the Stack + Project + Org entities and their parents, each carrying its resource name as an `id` attribute (see "Role resolution").

For collection-level operations (`stack:Create`), the resource is the Org. Cedar evaluates the member policy — `permit(principal in Role::"member", …)` — against that Org, where the principal's `Role::"member"` is the role resolved for that Org.

### Lease-token bypass

Lease-scoped routes (`PATCH /checkpoint`, `POST /events/batch`, `POST /complete`, `POST /renew_lease`, `DELETE /update/{id}`) **do not invoke the PDP**. Reasoning:

- The token represents *possession of a held lock on a stack*, not a general identity.
- Authority was already established when the lock was acquired: that request went through TokenAuthFilter → `stack:Lock` Cedar check → INSERT into `stack_locks`.
- Re-running Cedar on every lease-scoped request would either be redundant (the principal hasn't changed) or wrong (the principal in `stack_updates.user_id` may have lost org membership mid-update; we deliberately do not interrupt an in-flight `pulumi up`).
- Cedar policies cannot reason cleanly about "the holder of lease X is allowed to mutate stack Y" without making the lease itself a principal — possible, but adds complexity for no practical gain.

`LeaseTokenFilter` does its full validation (token match, stack match, expiry) and then admits the request directly. This is the one explicit deviation from the "every operation goes through the PDP" rule.

The `lock_token` is consumed only by `LeaseTokenFilter`. Downstream — principal id, MDC `subject`, traces, error bodies — the lease is identified by `lease:<stack_id>:<update_id>`, derived from the lock row at validation time.

---

## PDP architecture

### Component layout

```
modules/persistence/auth/
├── ApiTokenFinder.java          ← token lookup across users, orgs, memberships
└── AuthenticatedPrincipal.java  ← user id, subject, org, role, token kind

modules/persistence/authz/
├── Authorizer.java              ← single PDP entry point; owns cedar-java calls
├── AuthorizationDecision.java   ← allow/deny result
├── Authorize.java               ← CDI interceptor binding for protected routes
├── AuthorizeInterceptor.java    ← loads resource, calls Authorizer, proceeds or denies
├── CurrentPrincipal.java        ← request-scoped authenticated principal
├── EntityLoader.java            ← SPI for request target → Cedar resource entity
├── PrincipalRoleResolver.java   ← (principal, resourceOrg) → role; the tenancy seam
├── OrgEntityLoader.java         ← loader for org-scoped collection operations
└── StackEntityLoader.java       ← loader for stack-scoped operations

modules/persistence/policy/
├── PolicyStore.java             ← interface (static policies, templates, schema)
├── ClasspathPolicyStore.java    ← Wave 1 default; reads policies/*.cedar + templates/*.cedar
├── PolicyGrant.java             ← policy_grants row (FK to org / user / stack)
├── GrantLinkFinder.java         ← joins grant rows to the names that form their Cedar ids
└── PolicySetCache.java          ← assembles statics + templates + grant links; reload()
```

`Authorizer` is the only thing that talks to `cedar-java`. HTTP modules call it through `@Authorize` or a focused explicit authorization path; policy loading, resource construction, and principal shape stay in `persistence/` because they depend on the persisted identity and stack model.

### Policy storage

Wave 1: classpath for policy *text* (static policies, templates, schema — bundled into the JAR; changing them requires a redeploy), plus a DB table (`policy_grants`) for template *attachments*. The grant rows are data, not code, so adding a managed-policy attachment or a stack share is a DB write + `PolicySetCache.reload()`, no redeploy. See "User and resource policies" for the assembly.

Wave 2: optional DB-stored overlay for policy *text* too. Schema:

```sql
CREATE TABLE policies (
    id          UUID        PRIMARY KEY,
    name        TEXT        NOT NULL UNIQUE,
    body        TEXT        NOT NULL,
    enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`DbPolicyStore` reads the union of classpath + DB policies on every reload. Reload is triggered by:
- App start.
- A row change to `policies` (poll on a 30s timer; `LISTEN/NOTIFY` later).
- An admin-triggered `POST /api/admin/policies/reload`.

Compilation failure during reload **does not unload the previous good set** — the broken policy is logged and the app keeps serving with the prior set. This is critical: a typo in an admin-edited policy must not bring authorization down.

### Decision auditing

`Authorizer.authorize()` returns `AuthorizationDecision`. Every call writes one structured log entry:

```json
{
  "ts": "2026-...",
  "request_id": "...",
  "principal": "https://accounts.google.com:115...",
  "action": "stack:Create",
  "resource": "statedb:org:acme",
  "decision": "allow",
  "matched": ["PowerUserAccess"]
}
```

Denials carry the same shape with `"decision": "deny"` and an empty `matched` (unless a `forbid` policy fires, in which case its id is included). This is searchable in the structured log pipeline; no separate audit table for Wave 1.

### Caching

Two layers:

- **Compiled policy set** — held in memory; rebuilt on policy reload. Cedar's policy compilation is fast but not free; we don't want it on the hot path.
- **Principal entities** — the User entity (with the `Role` resolved for the resource's org and, for admins, `Role::"admin"`) is reconstructed on every request. For session principals the role resolution is one `org_members` lookup — the same cost as the existing membership re-check, so no regression; for token principals it is free (the role is already on the token).

We do **not** cache decisions across requests. Cedar evaluation against a few hundred policies and an entity set with a handful of members is microseconds; caching invites correctness bugs (what's the cache key? what invalidates on `org_members` change?).

---

## Integration with JAX-RS

### `@Authorize` annotation

```java
@Authorize(action = "stack:Read", loader = StackEntityLoader.class)
@GET
@Path("/api/pulumi/stacks/{org}/{project}/{stack}")
public StackDto getStack(@PathParam("org") String org,
                         @PathParam("project") String project,
                         @PathParam("stack") String stack) { ... }
```

The `loader` resolves the request's path params to the Cedar resource entity; the action names the operation.

A CDI interceptor `@AuthorizeInterceptor`:

1. Resolves the principal from `@CurrentPrincipal`.
2. Resolves the resource by calling `EntityLoader<Stack>` with the parameter values pulled from path/query/body.
3. Builds the Cedar request and calls `Authorizer.authorize`.
4. On `Allow`, proceeds to the handler.
5. On `Deny`, throws `ForbiddenException` (mapped to 403).

For endpoints where the resource isn't easily derivable from path params (e.g. `POST /update` where the body has the stack ref), the handler annotates with `@AuthorizeExplicit("<reason>")` and calls `Authorizer.authorize(...)` itself. The reason string is mandatory and surfaces in code review.

### Coverage enforcement

Authorization is fail-closed by construction: a JAX-RS method on a protected route that lacks both `@Authorize` and `@AuthorizeExplicit` does not ship.

**Build-time check.** A `@QuarkusTest` (`AuthorizationCoverageTest`) walks the Jandex index at test time and asserts every JAX-RS resource method whose route starts with `/api/` or `/pulumi/api/` carries exactly one of:

- `@Authorize(action=..., resource=...)`, or
- `@AuthorizeExplicit("<reason>")`.

Methods on routes explicitly listed in an exemption set (`/api/auth/login/*`, `/api/auth/callback/*`, `/api/auth/local`, `/api/auth/cli-login`, `/api/auth/cli-login/approve`, `/api/auth/logout`) are skipped — these are pre-authentication or self-authenticating by definition (`/api/auth/cli-login` requires a session and only renders approval; `/api/auth/cli-login/approve` requires a session and a signed, user-bound approval request before minting). The exemption set is hard-coded in the test and adding to it requires a code change with security review.

**Runtime safety net.** A request-scoped `AuthorizationContext` bean carries a single boolean `authorized`. `AuthorizeInterceptor` and any explicit `Authorizer.authorize(...)` call set it to `true` on `Allow`. `AuthorizationCoverageGuard` is a JAX-RS `ContainerResponseFilter` that, on every protected route, asserts `authorized == true` before the response is written. If the flag is unset, the guard logs an error (`request_id`, route, method) and overwrites the response with 500.

### `EntityLoader<T>` SPI

```java
public interface EntityLoader<T> {
    Class<T> resourceType();
    Optional<EntityValue> load(Map<String, String> idParams);
}
```

One implementation per resource type, registered as a CDI bean and discovered via `Instance<EntityLoader<?>>`. The Stack loader joins `stacks → projects → organizations` and produces the Stack entity plus its parent chain.

Loaders take the request's principal as a parameter. The status code distinction follows the principal's visibility into the resource's org, not the resource's existence:

- If the resource's containing org is not the principal's `org`, the loader returns `Optional.empty()` regardless of whether the resource exists. The interceptor maps this to 404.
- If the resource is in a visible org but does not exist, the loader also returns `Optional.empty()` → 404.
- If the resource exists but the principal could not **read** it — a guest without a grant, or a
  `private` stack and the principal is not owner/service/grantee — the loader likewise returns
  `Optional.empty()` → 404, applying the same read-visibility predicate as the list finders
  (see `design/stack-sharing.md` §3/§9). A 403 here would leak the stack's existence.
- If the resource is in a visible org and exists, the loader returns the entity; the PDP then runs, and a denial on the specific action becomes 403.

403 thus only ever reflects *within-visible-scope* denials — a viewer attempting to delete a stack they can read, for example.

For admin principals (`principal in Role::"admin"`), the loader skips the org gate; admins see all orgs by definition.

### Filter ordering

```
1. PreMatchingFilter (path routing decisions: which surface)
2. CsrfFilter                (only on /api/* non-GET; rejects before any auth work)
3. Quarkus OIDC | TokenAuthFilter | LeaseTokenFilter   (selected by path and credentials)
4. (resource method dispatched)
5. AuthorizeInterceptor      (CDI interceptor on the resource method; wraps invocation)
6. AuthorizationCoverageGuard (response filter; see below)
7. Resource method body
8. ExceptionMapper (403 / 404 / 409 mapping)
```

The interceptor runs *after* the method is dispatched (and after request body parsing) so it can inspect bound parameters. It runs *before* `@Transactional` opens the DB tx — denied requests do not open Postgres transactions.

---

## Policy set

One policy per file under `policies/`: the role policies (each reads top-down like an IAM statement
and is **pure RBAC** — a role on the principal, the actions, no org clause, because the principal
already carries only the role it holds in the resource's org), plus an explicit-Deny guardrail. The
`@id`s mirror AWS managed-policy names.

```cedar
// admin.cedar — AWS AdministratorAccess analog: every action, every resource, no
// org boundary. An explicit Deny still overrides it (see guardrails.cedar).
@id("AdministratorAccess")
permit(principal in Role::"admin", action, resource);

// owner.cedar — AWS service-FullAccess analog (≈ AmazonS3FullAccess): full access
// to the stack "service" in one statement via the stack:* action wildcard.
@id("StackFullAccess")
permit(principal in Role::"owner", action in Action::"stack:*", resource);

// member.cedar — AWS PowerUserAccess analog: the write bundle (read is inherited
// from viewer; owners inherit this via member ⊂ owner).
@id("PowerUserAccess")
permit(principal in Role::"member", action in Action::"stack:WriteAccess", resource);

// viewer.cedar — AWS ReadOnlyAccess analog: read stacks (and decrypt their
// secrets). Members and owners inherit this via viewer ⊂ member ⊂ owner. The
// `when` gates the org-wide read to internal stacks: private stacks are readable
// only by owners, service accounts, and explicit grantees (design/stack-sharing.md).
@id("ReadOnlyAccess")
permit(principal in Role::"viewer", action in Action::"stack:ReadOnlyAccess", resource)
when { resource.visibility == "internal" };

```

**Explicit Deny.** A `forbid` is IAM's signature deny-overrides-allow rule: it wins over every
`permit`, including `AdministratorAccess`. The Wave 1 guardrail blocks deleting a protected stack,
matched by resource-name pattern the IAM way (not by role):

```cedar
// guardrails.cedar — org-wide guardrail (≈ an AWS SCP / permission-boundary Deny):
// no principal, whatever its role or grants, may delete a stack named "prod".
@id("DenyDeleteProtectedStacks")
forbid(principal, action == Action::"stack:Delete", resource)
when { resource.id like "statedb:stack:*/prod" };
```

Authoring a new role rule is mechanical: pick a role, grant the actions. Adding a finer grant — or a
new guardrail Deny — is an additive file in the same shape. A future service-account grant set the
model already supports:

```cedar
// service-account-scope: service accounts get their own grant set.
@id("service-account-scope")
permit(principal in Role::"service", action in [Action::"stack:Read", Action::"stack:Lock"], resource);
```

### User and resource policies (templates + grants)

The role policies above are static and identity-based. Two further IAM concepts — **identity-based policies attached to a specific principal** ("managed policies") and **resource-based policies** (per-resource grants, ≈ an S3 bucket policy) — are implemented with Cedar **policy templates** plus a DB table of attachments.

Templates live under `policies/templates/` and carry `?principal` / `?resource` slots. The
role guard means "the grantee is a member of the resource's org": it enforces that
managed/resource grants only ever apply to org members, and neutralizes a grant the moment the
user leaves the org (there is no FK from `policy_grants` to `org_members`). The per-stack share
templates guard on `Role::"guest"` (any human tier, guests included); the managed org-wide
template guards on `Role::"viewer"` — its `?resource` is the Org, so it matches `stack:Create`
where no stack-read 404 gate applies, and a guest-inclusive guard would let guests create stacks.
Service accounts stand outside the guest chain, so grants naming them are inert.

```cedar
// StackWriteAccess — a managed (identity-based) policy: grant the write bundle across one
// org. ?resource is the Org; `resource in ?resource` matches the org and every stack under
// it via the entity hierarchy. Deliberately write-only: bundling read here would bypass
// `private` visibility org-wide. Guards on viewer, not guest: org-wide write (incl.
// stack:Create) is a viewer+ elevation, never grantable to guests.
permit(principal == ?principal, action in Action::"stack:WriteAccess", resource in ?resource)
when { principal in Role::"viewer" };

// StackReadResourcePolicy — resource-based policy: grant the read bundle on ONE stack.
// ?resource is that Stack (exact match).
permit(principal == ?principal, action in Action::"stack:ReadOnlyAccess", resource == ?resource)
when { principal in Role::"guest" };

// StackWriteResourcePolicy — read + write on ONE stack. Carries the read bundle too, so a
// write share is self-contained: its grantee may be a guest (no inherited read) or the stack
// may be private, and whoever can `pulumi up` a stack must be able to read its checkpoint.
permit(principal == ?principal,
       action in [Action::"stack:ReadOnlyAccess", Action::"stack:WriteAccess"],
       resource == ?resource)
when { principal in Role::"guest" };
```

Attachments are rows in `policy_grants`, referenced by **DB-native FK** (not Cedar strings) so they
are org-scoped and cascade cleanly:

```sql
CREATE TABLE policy_grants (
    id                UUID PRIMARY KEY,
    template_id       TEXT NOT NULL,
    org_id            UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    principal_user_id UUID NOT NULL REFERENCES users(id)         ON DELETE CASCADE,
    resource_stack_id UUID,                                            -- null = managed (per-org)
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- known template + the matching ?resource shape (also closes the template set)
    CONSTRAINT policy_grants_template_shape CHECK (
        (template_id = 'StackWriteAccess'                                       AND resource_stack_id IS NULL)
     OR (template_id IN ('StackReadResourcePolicy','StackWriteResourcePolicy')  AND resource_stack_id IS NOT NULL)),
    -- a share's stack must belong to the grant's org (composite FK, like stacks(project_id, org_id));
    -- skipped for managed grants where resource_stack_id is null
    CONSTRAINT policy_grants_stack_in_org
        FOREIGN KEY (resource_stack_id, org_id) REFERENCES stacks (id, org_id) ON DELETE CASCADE
);
```

The DB invariants are load-bearing: PolicySetCache turns each row straight into a `TemplateLink`, so
a malformed row (unknown template, wrong slot, or a stack tenanted under the wrong org) would either
fail the whole reload — silently freezing the policy set on the prior version — or bind `?resource`
to another org's stack. The `CHECK` enforces a known template with the matching `?resource`
nullability; the composite FK to `stacks(id, org_id)` (the same pattern V002 uses to stop stacks
straddling orgs) guarantees a shared stack lives in the grant's org. So every row that exists assembles
into a valid, correctly tenanted link.

A managed grant leaves `resource_stack_id` null (→ `?resource` = the org); a per-stack share sets it
(→ `?resource` = that stack). `GrantLinkFinder` joins each row to the names that form its Cedar
ids; `PolicySetCache` assembles the live set as `new PolicySet(staticPolicies, templates, links)`,
one `TemplateLink` per row (`result id = "link:<grant_id>"`). The assembled set is validated against
the schema before it goes live. `reload()` rebuilds it after a grant changes; **a failed reload logs
and keeps the prior good set** as a backstop. The same machinery is the foundation for the Wave 2 DB
policy overlay ("Policy storage").

> **Wave 1 scope:** the engine and storage are in place; the admin API/console to create and revoke grants is Wave 2 (see "Non-goals: per-resource ACLs in the console"). Grants are seeded directly today.

---

## Native image

`cedar-java` uses JNI bindings into a Rust core (`cedar-policy` crate). GraalVM native image *can* handle JNI, but it requires native shared libraries for `linux-amd64` and `linux-arm64`, per-target reflection registration, resource configuration declaring the loaded `.so`/`.dylib` files, and validation that no Cedar entrypoint hits dynamic class loading at runtime. This is real work and an ongoing maintenance burden tracking upstream `cedar-java` releases.

Wave 1 ships only a Mandrel native image. State-db owns Cedar's JNI extraction, integrity check,
reachability mapping, startup self-test, and architecture-specific release gates until that support is
available upstream. See [`native-image.md`](./native-image.md).

---

## Open items

- **Org renaming and policy stability.** Roles are global and resources carry no attributes, so a rename touches neither. It does change the org name embedded in resource names (`statedb:stack:<org>/...`, `statedb:org:<org>`), but those are rebuilt per-request from the DB; grant links are likewise rebuilt by name from FK ids on reload, so a rename takes effect with no stale-id reconstruction.
- **Service-account scoping below the org.** Wave 1 service accounts are org-scoped (matches `api_tokens.org_id`). Future: project- and stack-scoped service accounts. The Cedar model supports it (add a `scope: Project | Stack` attribute on ServiceAccount); the schema and mint UI don't yet.
- **Policy editor UX.** Wave 2 console policy editor needs schema-aware autocompletion + `cedar-java` validation pre-save. Dependency on `cedar-java`'s wasm bindings or a server-side validation endpoint.
- **Decision logs as an indexable surface.** Currently structured-log only. If admins want "who denied/allowed what" as a console screen, we need a decisions table and a write path. Skipped for Wave 1; revisit when admin demand is real.
- **OIDC tenant reload on `oidc_providers` change.** Quarkus `TenantConfigResolver` is queried per request, so adding a provider through the admin API takes effect immediately; removing one takes effect on the next request that doesn't have an active session. Verify the no-session-loss behavior with `quarkus-oidc` in integration tests.

---

## Decisions

| Decision | Rationale |
|----------|-----------|
| Single Cedar PDP for all surfaces | One source of truth for "who can do what"; no per-handler authz drift |
| `cedar-java` embedded, not OPA / AVP / Permit.io | Cedar's semantics + schema are the right fit; AVP forces AWS dependency on a self-hostable product; OPA's Rego trades reasoning for power; Permit.io is vendor lock-in |
| Full ABAC from Wave 1 | Per-stack and per-project grants are likely in Wave 2; better to model the entity hierarchy now than retrofit |
| `quarkus-oidc` web-app session via BFF | Standard library; SPA gets HttpOnly session cookie. No rolled-our-own session crypto |
| `Path=/api/` cookie scope | Defense in depth: console session physically cannot reach `/pulumi/api/*` |
| Lease-token bypass of PDP | Lease possession is its own authority; re-running Cedar adds no signal and complicates the entity model |
| Global roles + per-request role resolution against the resource's org | A user is a global identity who may be in many orgs; resolving "their role in the resource's org" in one seam (`PrincipalRoleResolver`) keeps the role policies pure RBAC and IAM-readable, and enforces token org-scoping (a token grants nothing outside its org) |
| Service-account role distinct from viewer/member/owner | CI tokens get a separate grant set so refining service permissions doesn't accidentally reshape user permissions |
| Classpath policies in Wave 1, DB overlay in Wave 2 | Avoid the "edit prod policy at 3am, broken regex, auth dies" failure mode until we have the operational tooling for it |
| Policy reload preserves the prior set on compile failure | Authorization stays up even if an admin edits a broken policy |
| No decision caching | Cedar is fast; cache invalidation is hard; correctness > microseconds |
| Native-only Wave 1 | State-db owns Cedar JNI/reachability compatibility and gates it on both release architectures |
| Org-scope enforced by resolving the principal's role in the resource's org, not a separate filter | One tenancy seam feeds both the hard 404 boundary and the Cedar role; no per-filter authz logic |
| Admin status from env, mirrored as `Role::"admin"` membership | No DB column for admin; env stays the source of truth and the role membership is rebuilt from it on each request |
| `Lease` principal id is `lease:<stack_id>:<update_id>`, never the raw `lock_token` | The lock_token is a wire-level bearer secret; it must not appear in entity ids, log MDC, traces, or audit records |
| CSRF: Origin/Referer + JSON-only `Content-Type` on `/api/*` non-GET | SPA-only consumer; avoids token-management complexity of double-submit while blocking both form-based and cross-origin fetch CSRF |
| Authorization coverage is fail-closed (build-time scan + runtime guard) | Forgetting `@Authorize` on a new endpoint must not silently ship an unauthorized route |
| Cross-org reads collapse to 404, not 403 | 403 reveals existence; collapsing prevents enumeration of stacks/projects across tenant boundaries |
| Action bundles (`stack:WriteAccess`) and the `stack:*` wildcard as schema action groups, entities derived from the schema at startup | Policies grant a capability in one token and read like IAM (`action in Action::"stack:*"`); the schema stays the single source of truth (no hand-maintained Java action list) since the engine resolves groups from the entity slice, not the schema |
| IAM-style fully-qualified resource names (`statedb:<service>:<path>`) + an `id` attribute on every resource | Borrows AWS's ARN *shape* (hierarchical, glob-matchable) without the Amazon-specific `arn:` token or empty region/account slots, so policies pattern-match resources the IAM way (`resource.id like "statedb:stack:*/prod"`) while staying state-db-native |
| Explicit Deny (`forbid`) guardrails alongside the role `permit`s | Cedar `forbid` overrides every `permit` (even `AdministratorAccess`), giving IAM's deny-overrides-allow / SCP-style guardrails as first-class policy text |
| User/resource policies as Cedar templates + a `policy_grants` link table | Per-principal "managed" grants and per-resource shares are data, not redeploys; reuses the library's first-class template-link mechanism and the prior-good-set reload guard |
| `guest` role + `internal`/`private` stack visibility | Additive-only membership (contractor sees only granted stacks) and per-stack retraction of org-wide read; both SQL-expressible so listing stays predicate-driven, with within-org 404 collapse for unreadable stacks. See `design/stack-sharing.md` |
