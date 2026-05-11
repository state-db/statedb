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

- **Per-resource ACLs in the console UI** for Wave 1. Cedar can express them; we don't yet have the screens to manage them. The *engine* supports it; the *product* doesn't expose it yet.
- **Conditional access** (time-of-day, IP allow-lists, MFA-step-up). Cedar handles these as policy `when` clauses, but Wave 1 doesn't ship example policies that use them.
- **Pluggable PDP.** OPA, AVP, Permit.io are *not* on the list. We commit to Cedar embedded as a library and accept the JVM-only consequence on native image.
- **Policy-driven admin elevation.** Admin status is read from `STATE_DB_ADMIN_SUBJECTS` env at boot. The allow-list seeds an `Admins` Cedar group; policies reference it. Storing admin status in the DB stays out of scope.

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
- The filter authenticates only. Org-scope and action-level decisions are made by the Cedar PDP downstream — `resource.org_name == principal.tokenOrgName` is a policy clause, not a separate filter.
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

Actions are namespaced by resource type. Wave 1 set:

| Action | Bound to (route examples) | Notes |
|--------|---------------------------|-------|
| `Stack::Read` | `GET /pulumi/api/stacks/{ref}`, `GET /api/pulumi/stacks/{ref}` | Includes export/checkpoint download |
| `Stack::Create` | `POST /pulumi/api/stacks/{org}/{project}` | |
| `Stack::Delete` | `DELETE /pulumi/api/stacks/{ref}` | |
| `Stack::Lock` | `POST /pulumi/api/.../update/{id}` (acquire) | Lease creation; the lease itself bypasses Cedar afterward |
| `Stack::Unlock` | `DELETE /api/admin/stacks/{ref}/lock` | Admin-only manual unlock |
| `Stack::ReadHistory` | `GET /api/pulumi/stacks/{ref}/updates` | |
| `Stack::ReadEvents` | `GET /api/pulumi/stacks/{ref}/updates/{id}/events` | |
| `Project::Create` | First `POST /pulumi/api/stacks/{org}/{project}/{stack}` for a new project | App-level lifecycle; not a separate route |
| `Project::Delete` | (No direct route in Wave 1; project disappears when last stack does) | Reserved for future explicit project lifecycle |
| `Org::Read` | `GET /api/orgs/{name}` | Lists projects, stacks, members |
| `Org::Admin` | `/api/admin/orgs/...`, member management | |
| `Org::AddMember`, `Org::RemoveMember` | Admin endpoints | |
| `Token::MintOidc` | `GET /api/auth/cli-mint` | User mints for self via OIDC re-auth |
| `Token::MintServiceAccount` | `POST /api/admin/tokens` | Admin-only |
| `Token::List` | `GET /api/tokens` | Own tokens; admin sees all |
| `Token::Revoke` | `DELETE /api/tokens/{id}` | Owner or admin |
| `Provider::Manage` | `POST/PUT/DELETE /api/admin/providers` | Admin-only |
| `Compaction::Trigger` | `POST /api/admin/stacks/{ref}/compact` | Admin-only |
| `User::ReadSelf` | `GET /api/me` | Permitted to any authenticated principal |
| `User::ReadOthers` | `GET /api/admin/users` | Admin-only |

Pulumi-internal endpoints not listed (`GET /api/user`, `GET /api/user/organizations/default`) are protected with `User::ReadSelf` against the calling user.

The action set is **closed** for Wave 1. Adding an action requires a schema bump and a policy update; this is intentional friction.

### Cedar entity model

```
User::"<iss>:<sub>"
  parents:
    - OrgRole::"<org_name>:owner" | OrgRole::"<org_name>:member" | OrgRole::"<org_name>:viewer"
      (one parent per (org, role) tuple; resolved from `org_members`)
    - Admins   (only if <iss>:<sub> ∈ STATE_DB_ADMIN_SUBJECTS)
  attributes:
    email: String          (advisory; not used in policy)
    orgs:  Set<String>     (org names where principal has any role; convenience)
    is_admin: Bool

ServiceAccount::"service:<token_id>"
  parents:
    - OrgRole::"<org_name>:service"  (single role per service account)
  attributes:
    org: String
    description: String
    owner_user: String     (the admin who minted it; advisory)

Lease::"lease:<stack_id>:<update_id>"
  attributes:
    stack: Stack           (reference)
    update_id: String

Stack::"<org>/<project>/<stack>"
  parents:
    - Project::"<org>/<project>"
        parents:
            - Org::"<org_name>"
  attributes:
    org_name: String       (denormalized for cheap policy access)
    project_name: String
    name: String

Org::"<org_name>"
  attributes:
    name: String

Project::"<org>/<project>"
  attributes:
    org: Org

Token::"<token_id>"
  attributes:
    owner: User | ServiceAccount
    org: Org
    kind: String           ("oidc" | "service_account")

Provider::"<provider_name>"
  attributes:
    issuer: String
```

`OrgRole::"acme:owner"` is itself an entity; it has parent `OrgRole::"acme:member"` which has parent `OrgRole::"acme:viewer"` — capability inheritance baked into the entity hierarchy. A policy that allows `OrgRole::"acme:viewer"` automatically permits owners and members; a policy gated on `OrgRole::"acme:owner"` does not permit lower roles. This avoids enumerating role tuples in every policy.

```
OrgRole::"acme:viewer"   ←   OrgRole::"acme:member"   ←   OrgRole::"acme:owner"
                                                          OrgRole::"acme:service"  (siblings of viewer; not inheriting)
                ↑
              Admins  (inherits from no role; uniformly allowed by the admin escape policy)
```

`service` is a sibling of `viewer`, not an ancestor — service accounts get their own grant set so a viewer can't accidentally do what a CI token can or vice versa. Both are leaves under the per-org membership umbrella.

### Schema declaration

A single Cedar schema file (`policies/schema.cedar` in `pulumi-api`'s resources) declares the entity types, action types, and `appliesTo` constraints. Loaded at startup and validated against the policy set; any mismatch fails app startup loud, before the first request.

The schema is checked into git. Changing the schema is a code change with normal review; changing a *policy* without a schema change is a hot-reloadable operation (Wave 2 — see "Policy storage").

### Resource construction

Per request, the `@Authorize` interceptor (next section) loads the resource entity. For most operations this is one Stack; the entity loader queries `stacks JOIN projects JOIN organizations` once and constructs the Stack + Project + Org entities and their parents. Cached request-scoped, so multiple authz checks against the same resource within a request reuse the result.

For collection-level operations (`Stack::Create`), the resource is the parent (`Project` or `Org`). Cedar evaluates `principal in OrgRole::"<org>:member"` against the proposed parent.

### Lease-token bypass

Lease-scoped routes (`PATCH /checkpoint`, `POST /events/batch`, `POST /complete`, `POST /renew_lease`, `DELETE /update/{id}`) **do not invoke the PDP**. Reasoning:

- The token represents *possession of a held lock on a stack*, not a general identity.
- Authority was already established when the lock was acquired: that request went through TokenAuthFilter → `Stack::Lock` Cedar check → INSERT into `stack_locks`.
- Re-running Cedar on every lease-scoped request would either be redundant (the principal hasn't changed) or wrong (the principal in `stack_updates.user_id` may have lost org membership mid-update; we deliberately do not interrupt an in-flight `pulumi up`).
- Cedar policies cannot reason cleanly about "the holder of lease X is allowed to mutate stack Y" without making the lease itself a principal — possible, but adds complexity for no practical gain.

`LeaseTokenFilter` does its full validation (token match, stack match, expiry) and then admits the request directly. This is the one explicit deviation from the "every operation goes through the PDP" rule.

The `lock_token` is consumed only by `LeaseTokenFilter`. Downstream — principal id, MDC `subject`, traces, error bodies — the lease is identified by `lease:<stack_id>:<update_id>`, derived from the lock row at validation time.

---

## PDP architecture

### Component layout

```
modules/core/identity/
├── Authorizer.java              ← single PDP entry point
├── AuthorizationDecision.java   ← {allow, denyReason, matchedPolicyIds}
├── PrincipalEntities.java       ← builds Cedar entities for User / ServiceAccount / Admin parents
└── EntityLoader.java            ← SPI for resource → Cedar entity

modules/core/policy/
├── PolicyStore.java             ← interface
├── ClasspathPolicyStore.java    ← Wave 1 default; reads policies/*.cedar from JAR
├── DbPolicyStore.java           ← Wave 2; reads policies + schema from a `policies` table
└── PolicySetCache.java          ← compiled policy set; hot-swap on PolicyStore reload event

modules/persistence/auth/
├── PrincipalLoader.java         ← User principal entities from `users` + `org_members` + admin allow-list
├── ResourceEntityLoaderImpl.java← stack/project/org loaders backed by Panache
└── PolicyEntity.java            ← @Entity for the `policies` table (Wave 2)
```

`Authorizer` is the only thing that talks to `cedar-java`. Everything else uses the abstraction. `core` has no Quarkus annotations — testable headless against fixture entities.

### Policy storage

Wave 1: classpath only. `policies/*.cedar` and `policies/schema.cedar` are bundled into the deployable JAR. Adding/changing policies requires a redeploy. This is fine for the initial policy set, which is small and stable.

Wave 2: optional DB-stored overlay. Schema:

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
  "principal": "User::https://accounts.google.com:115...",
  "action": "Action::Stack::Write",
  "resource": "Stack::acme/myproj/prod",
  "decision": "allow",
  "matched": ["org-members-can-write"]
}
```

Denials carry the same shape with `"decision": "deny"` and an empty `matched` (unless a `forbid` policy fires, in which case its id is included). This is searchable in the structured log pipeline; no separate audit table for Wave 1.

### Caching

Two layers:

- **Compiled policy set** — held in memory; rebuilt on policy reload. Cedar's policy compilation is fast but not free; we don't want it on the hot path.
- **Principal entities** — the User entity (with all `OrgRole` parents and the `Admins` membership) is reconstructed from DB on every request. This is the same cost as the existing `org_members` re-check, so no regression.

We do **not** cache decisions across requests. Cedar evaluation against a few hundred policies and an entity set with a handful of members is microseconds; caching invites correctness bugs (what's the cache key? what invalidates on `org_members` change?).

---

## Integration with JAX-RS

### `@Authorize` annotation

```java
@Authorize(
    action = "Stack::Read",
    resource = @Resource(type = "Stack", from = ResourceFrom.PATH_PARAMS,
                         params = {"org", "project", "stack"})
)
@GET
@Path("/api/pulumi/stacks/{org}/{project}/{stack}")
public StackDto getStack(@PathParam("org") String org,
                         @PathParam("project") String project,
                         @PathParam("stack") String stack) { ... }
```

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

- If the resource's containing org is not in `principal.orgs`, the loader returns `Optional.empty()` regardless of whether the resource exists. The interceptor maps this to 404.
- If the resource is in a visible org but does not exist, the loader also returns `Optional.empty()` → 404.
- If the resource is in a visible org and exists, the loader returns the entity; the PDP then runs, and a denial on the specific action becomes 403.

403 thus only ever reflects *within-visible-scope* denials — a viewer attempting to delete a stack they can read, for example.

For admin principals (`principal in Admins`), the loader skips the org gate; admins see all orgs by definition.

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

## Initial policy set

These are illustrative — actual text and ids will be finalized when the schema is locked in. Names indicate intent.

```cedar
// admin-escape: admins permitted on everything.
@id("admin-escape")
permit(principal in Admins, action, resource);

// org-members-can-read: any role in an org → read access for that org's stacks.
@id("org-members-can-read")
permit(
  principal,
  action in [
    Action::"Stack::Read",
    Action::"Stack::ReadHistory",
    Action::"Stack::ReadEvents",
    Action::"Org::Read",
    Action::"User::ReadSelf"
  ],
  resource
)
when {
  principal in OrgRole::(resource.org_name + ":viewer")
};

// org-write: members and owners can mutate stacks; viewers cannot.
@id("org-write")
permit(
  principal,
  action in [
    Action::"Stack::Create",
    Action::"Stack::Lock",
    Action::"Stack::Delete"  // overridden below for viewers/members
  ],
  resource
)
when {
  principal in OrgRole::(resource.org_name + ":member")
};

// org-owner-only: only owners can delete stacks.
@id("org-owner-only-delete")
forbid(
  principal,
  action == Action::"Stack::Delete",
  resource
)
unless {
  principal in OrgRole::(resource.org_name + ":owner")
};

// service-account-scope: a service-account principal acts only against its own org.
@id("service-account-scope")
permit(
  principal is ServiceAccount,
  action in [Action::"Stack::Read", Action::"Stack::Lock"],
  resource
)
when {
  resource.org_name == principal.org
};

// token-self: users mint and revoke their own tokens.
@id("token-self")
permit(
  principal,
  action in [Action::"Token::MintOidc", Action::"Token::List", Action::"Token::Revoke"],
  resource
)
when {
  resource.owner == principal
};
```

Cedar string-concatenation in `OrgRole::(resource.org_name + ":viewer")` is illustrative; the real implementation will use Cedar's templated entity types or precomputed entity sets — see "Open items".

---

## Native image

`cedar-java` uses JNI bindings into a Rust core (`cedar-policy` crate). GraalVM native image *can* handle JNI, but it requires native shared libraries for `linux-amd64` and `linux-arm64`, per-target reflection registration, resource configuration declaring the loaded `.so`/`.dylib` files, and validation that no Cedar entrypoint hits dynamic class loading at runtime. This is real work and an ongoing maintenance burden tracking upstream `cedar-java` releases.

Wave 1 ships JVM-only. JVM startup and footprint are acceptable for a self-hosted backend; native image is an optimization, not a correctness requirement. Native image build remains in CI as a non-blocking experimental job and is promoted to a release artifact when `cedar-java` native image support stabilizes.

---

## Open items

- **Cedar templated entities vs string-concat.** The illustrative policies above use `OrgRole::(resource.org_name + ":viewer")`. Cedar 4.x supports templated entities but with constraints; alternative is to precompute the principal's full set of OrgRole memberships as flat parents (which we're already doing) and write policies as `principal in resource.org.viewerRole` where `viewerRole` is an attribute on the Org entity. Decide before writing the production policy set.
- **Org renaming and policy stability.** Renaming an org changes `org_name` everywhere, including in OrgRole entity ids. Cedar entity ids are not relations — renames don't propagate. The `rename_org` tx must reconstruct OrgRole entity ids accordingly; principal entities are constructed per-request so renames take effect on the next request.
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
| `OrgRole::"<org>:owner"` parents as the inheritance backbone | Cedar entity hierarchy expresses owner ⊃ member ⊃ viewer cleanly without enumerating in every policy |
| Service-account role distinct from viewer/member/owner | CI tokens get a separate grant set so refining service permissions doesn't accidentally reshape user permissions |
| Classpath policies in Wave 1, DB overlay in Wave 2 | Avoid the "edit prod policy at 3am, broken regex, auth dies" failure mode until we have the operational tooling for it |
| Policy reload preserves the prior set on compile failure | Authorization stays up even if an admin edits a broken policy |
| No decision caching | Cedar is fast; cache invalidation is hard; correctness > microseconds |
| JVM-only Wave 1; native image experimental | `cedar-java` JNI/native image story is fragile; ship JVM and revisit when upstream support stabilizes |
| Org-scope enforced as a Cedar policy clause, not a separate filter | One PDP, one decision point; no per-filter authz logic |
| Admin status from env, mirrored as `Admins` group entity | No DB column for admin; env stays the source of truth and the Cedar group is rebuilt from it on each request |
| `Lease` principal id is `lease:<stack_id>:<update_id>`, never the raw `lock_token` | The lock_token is a wire-level bearer secret; it must not appear in entity ids, log MDC, traces, or audit records |
| CSRF: Origin/Referer + JSON-only `Content-Type` on `/api/*` non-GET | SPA-only consumer; avoids token-management complexity of double-submit while blocking both form-based and cross-origin fetch CSRF |
| Authorization coverage is fail-closed (build-time scan + runtime guard) | Forgetting `@Authorize` on a new endpoint must not silently ship an unauthorized route |
| Cross-org reads collapse to 404, not 403 | 403 reveals existence; collapsing prevents enumeration of stacks/projects across tenant boundaries |
