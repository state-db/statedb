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
- **Pluggable PDP.** OPA, AVP, Permit.io are *not* on the list. We commit to Cedar embedded as a library and accept the JVM-only consequence on native image.
- **Policy-driven admin elevation.** Admin status is read from `STATE_DB_ADMIN_SUBJECTS` env at boot. The allow-list makes a principal a member of the global `Role::"admin"`; the admin policy references it. Storing admin status in the DB stays out of scope.

---

## Principals

Every authenticated request resolves to exactly one of these:

| Kind | Identifier | Source | Authn surface |
|------|------------|--------|---------------|
| `User` | `<iss>:<sub>` (e.g. `https://accounts.google.com:115...`) | `users` table; created on first OIDC login | Console (session cookie) **or** Pulumi CLI (OIDC-derived `api_token` row, `kind='oidc'`) |
| `ServiceAccount` | `service:<token_id>` | `api_tokens` row, `kind='service_account'` | Pulumi CLI only |
| `Lease` | `lease:<stack_id>:<update_id>` | `stack_locks` row | Pulumi CLI lease-scoped routes only |
| `BreakGlass` | First `<iss>:<sub>` from `STATE_DB_ADMIN_SUBJECTS` | env hash + loopback | `/api/auth/local` only |

`User` and `ServiceAccount` go through the full Cedar PDP. `Lease` does not — see "Lease-token bypass" below. `BreakGlass` mints a session as the impersonated admin user and from there is indistinguishable from a regular admin login.

### Principal resolution

A request enters one of three filters depending on path; each constructs the principal and hands off to the PDP:

```
/pulumi/api/* (non-lease)  →  TokenAuthFilter   → Principal{User|ServiceAccount}
/pulumi/api/* (lease)      →  LeaseTokenFilter  → Principal{Lease}
/api/*                     →  OidcSessionFilter → Principal{User}
```

The principal is set on the JAX-RS `SecurityContext` and on a request-scoped `@CurrentPrincipal` CDI bean. Handlers and the authorization interceptor read from the bean.

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

The SPA contract is: HttpOnly cookie scoped to `/api/`, opaque to JavaScript. From the SPA's perspective, "logged in" means "fetch /api/me succeeded".

#### Login flow

```
SPA loads → fetch /api/me
  ├─ 200 → render console
  └─ 401 → location = /api/auth/login/<provider_name>
            → Quarkus redirect to IdP
            → IdP → /api/auth/callback/<provider_name>
            → Quarkus sets session cookie
            → 302 → /  (SPA reloads; /api/me succeeds)
```

`/api/auth/login/{provider_name}` and `/api/auth/callback/{provider_name}` are handled by `quarkus-oidc`. We register one tenant per `oidc_providers` row at startup, with a `TenantConfigResolver` that picks the tenant by path.

#### Cookie semantics

The session cookie is generated and verified by `quarkus-oidc`. It is HttpOnly, Secure, SameSite=Lax (Lax not Strict — Strict breaks the IdP redirect-back), Path=`/api/`. The `Path=/api/` scope is critical: it ensures the cookie is **never** sent to `/pulumi/api/*`, preserving the no-overlap property between the two surfaces.

`quarkus-oidc` rotates the underlying JWE key from `quarkus.oidc.token-state-manager.encryption-secret` (env-supplied). Rotation is the standard redeploy-with-overlap dance.

#### CLI token mint

The cli-mint flow uses `quarkus-oidc`'s `OidcConfigurationMetadata` and `OidcClientFactory` to construct an authz URL with `prompt=none` and a custom `state` payload (`intent=cli-mint`, signed via the same key as session state). The callback at `/api/auth/cli-mint/callback` is a regular JAX-RS endpoint — it does not go through the extension's callback route, because we want to inspect the result and write a row to `api_tokens` rather than mint a session. The OIDC token validation in the callback uses `OidcProviderClient` to exchange the code and verify the resulting id_token. End-to-end semantics (silent re-auth, IdP-disablement propagation within the TTL, one-time-display of the raw token) are described in `architecture.md` "CLI token issuance".

#### Logout

`POST /api/auth/logout`:
- Clears the session cookie.
- If the IdP advertises `end_session_endpoint`, redirect there. Otherwise just drop the cookie locally.

#### Local break-glass

The handler at `/api/auth/local` verifies the env-stored hash, then constructs a session for the first allow-listed admin via the same `quarkus-oidc` session-mint primitive that the regular OIDC callback uses. Loopback + non-prod-profile gating per `architecture.md`.

#### CSRF protection

Cookie-authenticated state-changing requests under `/api/*` are gated by a `CsrfFilter` bound `@PreMatching` to all non-`GET`/`HEAD`/`OPTIONS` methods on the `/api/*` surface. The filter enforces, in order:

1. **`Content-Type` MUST be `application/json`** (or `application/json; charset=...`). Any other content type → 415. This forces a CORS preflight on every cross-origin browser fetch and rules out the classic `<form>`-submission CSRF vector entirely (HTML forms cannot send JSON).
2. **`Origin` header MUST match `STATE_DB_PUBLIC_ORIGIN`** (env-supplied; e.g. `https://state-db.example.com`). Missing or mismatched → 403. `Origin` is set automatically by browsers on every fetch and cannot be forged from JavaScript.
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
| `stack:Lock` | `POST /pulumi/api/.../update/{id}` (acquire) | Lease creation; the lease itself bypasses Cedar afterward |
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
| `token:MintOidc` | `GET /api/auth/cli-mint` | User mints for self via OIDC re-auth |
| `token:MintServiceAccount` | `POST /api/admin/tokens` | Admin-only |
| `token:List` | `GET /api/tokens` | Own tokens; admin sees all |
| `token:Revoke` | `DELETE /api/tokens/{id}` | Owner or admin |
| `provider:Manage` | `POST/PUT/DELETE /api/admin/providers` | Admin-only |
| `compaction:Trigger` | `POST /api/admin/stacks/{ref}/compact` | Admin-only |
| `user:ReadSelf` | `GET /api/me` | Permitted to any authenticated principal |
| `user:ReadOthers` | `GET /api/admin/users` | Admin-only |

Pulumi-internal endpoints not listed (`GET /api/user`, `GET /api/user/organizations/default`) are protected with `user:ReadSelf` against the calling user.

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
    - Role::"owner" | Role::"member" | Role::"viewer"   (the role the user holds in THIS request's
      resource org, resolved per request — see "Role resolution"; absent if not a member)
    - Role::"admin"   (only if the principal is in STATE_DB_ADMIN_SUBJECTS)
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
`OrgRole::"acme:member"`) and inherit: `Role::"owner"` ⊃ `Role::"member"` ⊃ `Role::"viewer"`. The
principal carries exactly the role it holds **in the resource's org**, resolved per request and
attached as a parent. So the role policies are pure RBAC — `permit(principal in Role::"member", …)`
with no org clause, because the attached role is already org-correct. Resource entity ids are
**IAM-style fully-qualified names** (`statedb:<service>:<org>/<project>/<stack>` — the same shape as
an AWS ARN but state-db-native, no Amazon branding), and each resource carries that name as an `id`
attribute so a policy can pattern-match it the IAM way (`resource.id like …`); the Stack → Project →
Org hierarchy is kept so managed grants can scope through it. Tenancy is still the resolved role, not
a stored field — the name is for resource-pattern matching only.

```
Role::"viewer"   ←   Role::"member"   ←   Role::"owner"

Role::"service"  (inherits from nothing — its own grant set)
Role::"admin"    (inherits from nothing — uniformly allowed by the admin policy)
```

`Role::"service"` inherits from nothing: service accounts get their own grant set so a viewer can't
accidentally do what a CI token can or vice versa. `Role::"admin"` likewise stands alone and is the
only role not bounded by org membership.

### Role resolution

`PrincipalRoleResolver` is the single tenancy seam — `(principal, resourceOrgId) → Optional<Role>`:

- **Token principal** (CLI; scoped to one org) → its role iff `resourceOrgId == token.org`, else none.
  This is what enforces token org-scoping: a token grants nothing outside its org even if the user
  is a member elsewhere.
- **Session principal** (console; org-agnostic) → `org_members(userId, resourceOrgId).role`, else none.

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

Methods on routes explicitly listed in an exemption set (`/api/auth/login/*`, `/api/auth/callback/*`, `/api/auth/local`, `/api/auth/cli-mint`, `/api/auth/cli-mint/callback`, `/api/auth/logout`) are skipped — these are pre-authentication or self-authenticating by definition. The exemption set is hard-coded in the test and adding to it requires a code change with security review.

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
- If the resource is in a visible org and exists, the loader returns the entity; the PDP then runs, and a denial on the specific action becomes 403.

403 thus only ever reflects *within-visible-scope* denials — a viewer attempting to delete a stack they can read, for example.

For admin principals (`principal in Role::"admin"`), the loader skips the org gate; admins see all orgs by definition.

### Filter ordering

```
1. PreMatchingFilter (path routing decisions: which surface)
2. CsrfFilter                (only on /api/* non-GET; rejects before any auth work)
3. TokenAuthFilter | OidcSessionFilter | LeaseTokenFilter   (mutually exclusive by path)
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
// secrets). Members and owners inherit this via viewer ⊂ member ⊂ owner.
@id("ReadOnlyAccess")
permit(principal in Role::"viewer", action in Action::"stack:ReadOnlyAccess", resource);

// self.cedar — any authenticated principal may read its own user record.
@id("users-can-read-self")
permit(principal, action == Action::"user:ReadSelf", resource)
when { principal == resource };
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
`principal in Role::"viewer"` guard means "the grantee is a member of the resource's org" (any role
⊆ viewer): it enforces that managed/resource grants only ever apply to org members, and neutralizes
a grant the moment the user leaves the org (there is no FK from `policy_grants` to `org_members`).

```cedar
// StackWriteAccess — a managed (identity-based) policy: grant the write bundle across one
// org. ?resource is the Org; `resource in ?resource` matches the org and every stack under
// it via the entity hierarchy.
permit(principal == ?principal, action in Action::"stack:WriteAccess", resource in ?resource)
when { principal in Role::"viewer" };

// StackReadResourcePolicy / StackWriteResourcePolicy — resource-based policies: grant the
// read/write bundle on ONE stack. ?resource is that Stack (exact match). The write variant
// elevates a viewer to write a single stack without org-wide write.
permit(principal == ?principal, action in Action::"stack:WriteAccess", resource == ?resource)
when { principal in Role::"viewer" };
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

Wave 1 ships JVM-only. JVM startup and footprint are acceptable for a self-hosted backend; native image is an optimization, not a correctness requirement. Native image build remains in CI as a non-blocking experimental job and is promoted to a release artifact when `cedar-java` native image support stabilizes.

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
| JVM-only Wave 1; native image experimental | `cedar-java` JNI/native image story is fragile; ship JVM and revisit when upstream support stabilizes |
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
