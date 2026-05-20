# state-db: Application Architecture

Companion to [`db-schema.md`](./db-schema.md). The schema doc defines what we store and the invariants that hold; this doc defines the running service that maintains those invariants — the Quarkus application, the SPA, the request lifecycles, and the deployment.

---

## Goals

- One container per deployment: HTTP backend for Pulumi (and later OpenTofu) + read-oriented web console served from the same JVM/native process.
- Pulumi CLI compatibility is non-negotiable: if `pulumi up` breaks, the backend is wrong.
- Two security surfaces, one process: token-authenticated machine traffic on one URL prefix, OIDC-authenticated human traffic on another, with no shared filter logic.
- Forward-compatible with OpenTofu in Wave 3 without schema rewrites (see `workspaces` in db-schema.md).
- Optimized for self-hosting: minimal moving parts, defaults that work, clean upgrade story.

## Non-goals

- Replicating Pulumi Cloud's full feature set (policy-as-code, deployment runners, drift dashboards). We are the state backend plus a console, not a platform.
- Multi-region/multi-master operation. One Postgres, one (or N horizontally-scaled) app instances behind a load balancer.
- Acting as a secrets provider. Pulumi's CLI is configured against an external KMS (AWS, Azure, GCP, HashiCorp Vault, passphrase). The backend stores ciphertext opaquely inside checkpoint JSON and never sees plaintext.

---

## High-level shape

```
                    ┌─────────────────────────────────────────────────┐
                    │                state-db (one process)           │
                    │                                                 │
   pulumi CLI  ─────┤  /pulumi/api/...   token auth filter  ──┐       │
   (Authorization:  │                                          │       │
    token <tok>)    │                                          ▼       │
                    │                                  ┌──────────┐    │
   Browser     ─────┤  /api/...          OIDC/session  │ Domain   │    │
   (cookie session) │                    filter   ────►│ services │◄───┼─── Postgres
                    │                                  │ (core)   │    │
                    │  /assets, /index.html  static  ──┘          │    │
                    │  (SPA fallback to index.html)               │    │
                    │                                             │    │
                    └─────────────────────────────────────────────────┘
```

Two HTTP surfaces, one domain core, one DB. Same JAR. Same native binary.

---

## URL layout

| Prefix | Purpose | Authn | Notes |
|--------|---------|-------|-------|
| `/pulumi/api/...` | Pulumi backend protocol | `Authorization: token <tok>` | Users configure CLI as `pulumi login https://host/pulumi`. CLI then constructs `/pulumi/api/...`. Tofu ships under `/tofu/...` in Wave 3. |
| `/api/...` | Console REST API | OIDC session cookie | Used only by the SPA. Never exposed to the Pulumi CLI. |
| `/api/admin/...` | Admin-only console endpoints | OIDC session + admin allow-list | Subject `<iss>:<sub>` must be in `STATE_DB_ADMIN_SUBJECTS` env. |
| `/api/auth/login/*`, `/api/auth/callback/*` | OIDC login + callback (one route per configured provider) | None (initiates auth) | Sets the encrypted session cookie on success. |
| `/api/auth/cli-mint`, `/api/auth/cli-mint/callback` | OIDC `prompt=none` re-auth → mint a short-lived CLI token | OIDC session cookie | One-time-display page returns the raw token; see "CLI token issuance". |
| `/api/auth/local` | Break-glass local login | Static credential from env (`STATE_DB_LOCAL_GLASS_PASSWORD_HASH`) | Only enabled when (a) Quarkus profile is not `prod` AND (b) request originates from `127.0.0.1`/`::1`. Both checks; either fail = 404. |
| `/assets/*`, `/favicon.ico`, etc. | Static SPA bundle | None | Served from `META-INF/resources/` populated by the bun build. |
| Anything else | SPA fallback → `index.html` | None | Catches client-side router paths. Filter explicitly does NOT match `/api/*` or `/pulumi/*`. |

The `/pulumi` prefix is mandatory in `PULUMI_BACKEND_URL` because Pulumi's CLI reserves `/api/`. Users will configure `pulumi login https://state-db.example.com/pulumi`; the CLI then constructs `https://state-db.example.com/pulumi/api/user`, etc.

---

## Module layout

Maven multi-module project. All shipped code lives under `modules/` so it stays visually separate from `design/`, `api-snapshots/`, and `tools/` at the repo root. Each module is a separate JAR; `modules/app/` is the only deployable.

```
state-db/
├── design/                     architecture & schema docs (this file lives here)
├── api-snapshots/              versioned capture of the Pulumi protocol — the living spec
├── tools/                      Go-based extractor and recorder
└── modules/                    all shipped code; parent pom lives here
    ├── pom.xml
    ├── core/                   pure-Java domain types and logic, no Quarkus
    │   ├── checkpoint/         CheckpointAssembler, journal apply rules
    │   ├── update/             UpdateLifecycle state machine
    │   └── identity/           Subject, Role, AdminAllowList
    ├── persistence/            Quarkus + Hibernate ORM + Panache + Flyway
    │   ├── entity/             @Entity classes mapping db-schema.md
    │   ├── repo/               PanacheRepository<T> + native-query hot paths
    │   ├── jsonb/              AttributeConverter for JSONB ↔ JsonNode
    │   └── db/migration/       V1__init.sql, V2__..., V3__...
    ├── pulumi-api/             JAX-RS resources for /pulumi/api/*
    │   ├── filter/             TokenAuthFilter, LeaseTokenFilter
    │   ├── stack/              StackResource, StackEncryptionResource
    │   ├── update/             UpdateResource, LeaseResource, EventsResource
    │   └── error/              Pulumi-specific error mapping (409 lock conflict, etc.)
    ├── console-api/            JAX-RS resources for /api/*
    │   ├── filter/             SessionAuthFilter, AdminFilter, LocalGlassFilter
    │   ├── auth/               OIDC login/callback, multi-provider TenantConfigResolver
    │   ├── workspace/          WorkspacesResource (cross-kind list)
    │   ├── pulumi/             PulumiStackResource (drilldown), HistoryResource
    │   ├── token/              TokensResource (mint/list/revoke)
    │   └── admin/              ProvidersResource, UsersResource, OrgsResource
    ├── web/                    bun + TanStack SPA
    │   ├── src/                React + TanStack Router + TanStack Query
    │   ├── package.json
    │   ├── bunfig.toml         dev server proxy: /api, /pulumi → :8080
    │   └── (build output)      copied into app/ at package phase
    └── app/                    Quarkus assembly
        ├── pom.xml             depends on all above; bun build hooked here
        ├── src/main/resources/
        │   ├── application.yml
        │   └── META-INF/resources/   (populated by web build)
        └── Dockerfile
```

The split exists so `pulumi-api` and `console-api` cannot accidentally share security filters or JAX-RS providers. They each have their own filter chain and their own error mapping. `core` has no Quarkus annotations and is unit-testable without a runtime.

---

## Persistence layer

Hibernate ORM with Panache. Flyway-managed schema. JSONB via a custom `AttributeConverter<JsonNode, String>` registered globally. Postgres-only (no abstraction layer; see db-schema.md "Non-issues").

Hot paths drop to native SQL via `EntityManager.createNativeQuery`:

- **Journal apply (`GET /export`):** load base snapshot + replay journal tail. Single-statement query: `SELECT … FROM journal_entries WHERE stack_id = $1 AND sequence_id > $2 ORDER BY sequence_id`. Apply in-memory; no per-row entity hydration.
- **Sequence allocation:** `SELECT journal_seq + 1 FROM stack_state WHERE stack_id = $1 FOR UPDATE`, then `UPDATE stack_state SET journal_seq = journal_seq + 1 …`, both in one tx. Panache's optimistic versioning is too weak here.
- **Lock acquire:** `INSERT INTO stack_locks … ON CONFLICT (stack_id) DO UPDATE SET … WHERE expires_at < now()` for steal-on-stale semantics. Returns updated row; null result = lock conflict → 409.
- **Cross-tool workspace listing:** `SELECT … FROM workspaces w LEFT JOIN stacks s ON s.workspace_id = w.id WHERE w.org_id = $1` — too JOIN-heavy for Panache's collection mapping.

Everything else (CRUD on api_tokens, users, oidc_providers; reading stack metadata) goes through Panache repositories.

### JSONB handling

`payload`, `checkpoint`, `cached_checkpoint`, `tags`, etc. are typed as Jackson `JsonNode` in entities. The converter serializes via the same `ObjectMapper` Quarkus uses for REST. This keeps DB-shape and wire-shape identical, which matters because `GET /export` must return byte-stable JSON to satisfy Pulumi's expectations.

### Migrations

`V1__init.sql` is the schema in db-schema.md verbatim. Flyway runs at startup; production deployments use `flyway:migrate` as a separate step in CI before rolling the new image. No auto-migrate in prod.

---

## Pulumi backend (Wave 1)

The contract is captured in `tools/pulumi/recorder/fixtures/`. Each fixture is one (request, response) pair recorded against Pulumi CLI v3.242.0. The fixture catalog is the source of truth for wire shape; if a fixture and Pulumi documentation disagree, the fixture wins.

### Request lifecycle

Pulumi sends two distinct `Authorization` schemes depending on the operation:
- `Authorization: token <apiTok>` — every CLI request *except* the lease-scoped ones below.
- `Authorization: update-token <leaseTok>` — `PATCH /checkpoint`, `POST /events/batch`, `POST /complete`, `POST /renew_lease`, `DELETE /update/{id}`. The CLI swaps automatically; we never see both on one request.

Two filter chains, mutually exclusive by route:

```
TokenAuthFilter   (everything that isn't lease-scoped)
  ├─ parse Authorization header (must be scheme="token")
  ├─ sha256 the raw token
  ├─ SELECT t.user_id, t.org_id, o.name AS org_name
  │    FROM api_tokens t JOIN organizations o ON o.id = t.org_id
  │   WHERE t.token_hash = ? AND (t.expires_at IS NULL OR t.expires_at > now())
  ├─ if miss → 401
  ├─ verify org_members row exists for (Subject.user_id, Subject.org_id) → else 401
  └─ attach Subject{user_id, org_id, org_name} to SecurityContext

LeaseTokenFilter   (only on lease-scoped routes listed above)
  ├─ parse Authorization header (must be scheme="update-token")
  ├─ SELECT stack_id, update_id, expires_at FROM stack_locks WHERE lock_token = ?
  ├─ if miss OR expires_at <= now() → 409
  ├─ verify {stack} path segment resolves to the same stack_id as the lock row → else 409
  └─ attach LeaseContext{stack_id, update_id} to SecurityContext

→ AuthorizeInterceptor (Cedar PDP; see auth.md)
→ Resource method (transactional via @Transactional)
```

Subject carries `org_name` (resolved at auth time via the JOIN above) so the Cedar PDP can compare against the `{org}` path segment without a per-request DB lookup. Org-scope enforcement (`{org}` path == token's org) is a policy clause in Cedar, not a separate filter — see `auth.md`.

The `org_members` re-check on every TokenAuthFilter-protected request is intentional. It means revoking a user's org membership instantly invalidates all their tokens for that org without touching the tokens table. Cost is one indexed PK lookup; we'll cache later if profiling demands.

LeaseTokenFilter does NOT consult `api_tokens` or `org_members` and does NOT invoke the PDP — possession of a valid, unexpired `lock_token` is sufficient. Authority is established at lease-acquire time (which goes through TokenAuthFilter and the PDP); the lease token then carries that authority for the duration of the update.

### Update lifecycle (state machine)

Driven by Pulumi's API surface. Tracked in `stack_updates` per the schema:

```
        POST /update|preview|destroy            POST /update/{updateID}                 POST /complete
client ────────────────────────────► ─────────────────────────────────► ───────────────────────────────►
                  │                                  │                                       │
                  ▼                                  ▼                                       ▼
        INSERT stack_updates             INSERT stack_locks                 UPDATE stack_updates SET status=...,
        (status='in-progress',           UPDATE stacks                      DELETE stack_locks,
         client_request_id=...           SET active_update_id=...           UPDATE stacks SET version+=1, active_update_id=NULL,
         ON CONFLICT DO NOTHING)                                            (auto-trigger compaction if threshold crossed)
                  │                                  │                                       │
                  └──────── PATCH /checkpoint (journal entries) ────────────┘
                  └──────── POST /events/batch (decoded into update_events) ┘
```

All multi-table writes run in one transaction. The "complete" path is a single tx that flips status, deletes the lock, and bumps stack version. Failure of any step rolls all back.

### Locking

Per db-schema.md `stack_locks`. Lease lifecycle:

- **Acquire** (`POST /update/{updateID}`): `INSERT … ON CONFLICT (stack_id) DO UPDATE SET … WHERE expires_at < now() RETURNING …`. Empty return = held by someone live → 409.
- **Renew** (`POST /update/{updateID}/renew_lease`): `UPDATE stack_locks SET expires_at = now() + $1, heartbeat_at = now() WHERE stack_id = ? AND lock_token = ?`. Mismatch → 409.
- **Release** (implicit on `complete` or explicit on cancel): delete row. Lock token validated.
- **Stale recovery**: any acquire steals when `expires_at < now()`. TTL config `state-db.lock.lease-ttl=1h` (default), `state-db.lock.heartbeat-interval=30s` (advisory; CLI doesn't always renew).
- **Manual unlock**: admin-only console endpoint `DELETE /api/admin/stacks/{ref}/lock`. No CLI equivalent — Pulumi's `pulumi cancel` calls our `cancel_lease` endpoint which is the same thing scoped to the lease holder.

### Events

`POST /update/{updateID}/events/batch` body is a gzip'd JSON array of events. We:

1. Validate lock token.
2. Decompress.
3. Parse the JSON array.
4. INSERT one `update_events` row per event with the original `sequence` preserved.
5. Drop the gzip envelope; we don't store the raw bytes.

A periodic vacuum job (`@Scheduled(every="1h")`) deletes events older than `state-db.events.retention-days=30`. Configurable per deployment. The journal is the system of record for state; events are observability.

### Compaction

Async per db-schema.md `compaction_jobs`. A `@Scheduled` poller in `pulumi-api` (or its own `worker` module — TBD) reads `compaction_jobs WHERE status='pending'`, processes one stack at a time. Holds `SELECT … FOR UPDATE` on `stack_state` for the duration to serialize against journal appends.

Two trigger paths:
- **Threshold** — `stack_state.journal_entries_since_compaction >= auto_compact_threshold` enqueues a job at the end of an update.
- **Manual** — admin endpoint enqueues for a specific stack.

---

## Console surface (Wave 2)

The SPA never talks to `/pulumi/api/*`. It talks only to `/api/*`. This separation lets the Pulumi backend's filter chain stay simple (token only, no cookies, no CORS) and lets the console use sessions without contaminating the backend surface.

### OIDC

Multiple providers concurrently. One bootstrapped from env at startup so a fresh DB is usable; additional providers added via `/api/admin/providers` at runtime.

Bootstrap config (env):
```
STATE_DB_OIDC_BOOTSTRAP_NAME=google
STATE_DB_OIDC_BOOTSTRAP_ISSUER=https://accounts.google.com
STATE_DB_OIDC_BOOTSTRAP_CLIENT_ID=...
STATE_DB_OIDC_BOOTSTRAP_CLIENT_SECRET=...
```

At startup, the app upserts this row into `oidc_providers`. Env wins on conflict (so rotating client secrets via env redeploy works).

Multi-provider Quarkus integration uses `TenantConfigResolver`. Login URL is `/api/auth/login/{provider_name}`; the resolver returns the config for that row. Callback at `/api/auth/callback/{provider_name}`. On success, we upsert `users` keyed by `(iss, sub)` and the extension issues a session cookie.

### Session storage

The console runs the **backend-for-frontend (BFF)** pattern with `quarkus-oidc` in `web-app` mode. The SPA never sees access or id tokens — only an HttpOnly session cookie issued by the extension. We get JWKS rotation, refresh-token rotation, OIDC discovery, end-session endpoints, and `state`/`nonce`/PKCE handling without rolling our own.

The session cookie is generated and verified by `quarkus-oidc`. Encryption key from env (`quarkus.oidc.token-state-manager.encryption-secret`); rotation is the standard redeploy-with-overlap dance.

Cookie is `HttpOnly; Secure; SameSite=Lax; Path=/api/`. SameSite=Lax (not Strict) so the IdP redirect-back works. The `Path=/api/` scope is critical: the cookie is not sent to `/pulumi/api/*`, preserving the no-overlap property between the two surfaces.

See `auth.md` "Console: SPA + BFF with quarkus-oidc" for the full login/logout flow and integration notes.

### CLI token issuance

Pulumi CLI authenticates with `Authorization: token <tok>` and has no OIDC client of its own. To get IdP-driven access control on CLI traffic without forking the CLI, every CLI token is **bootstrapped from the user's OIDC session and issued with a short TTL** (24h default). Disablement at the IdP propagates within the TTL because the user can no longer mint a fresh token.

The mechanism is a standard OIDC trick: re-launching the authorization flow with `prompt=none`. The IdP redirects back immediately if the user's IdP session is still alive, or returns `error=login_required` if the session has been invalidated (logout, account disabled, MFA challenge required, conditional-access denial, etc.). The silent re-auth at mint time is sufficient as a liveness check — the entire mechanism rides on the same OIDC primitives we already use for login.

#### Mint flow

```
SPA "Get CLI token" button → GET /api/auth/cli-mint
  ↓
state-db builds OIDC authz URL with prompt=none, state=signed{intent=cli-mint}
state-db → 302 → IdP /authorize
  ↓
IdP — live session?
  ├─ yes → 302 back with code   (no UI shown)
  └─ no  → 302 back with error=login_required
            → state-db: "your IdP session expired, sign in again"
            → bounce through /api/auth/login/{provider} → return here
  ↓
state-db /api/auth/cli-mint/callback
  exchanges code → fresh id_token
  verifies (iss, sub) match the current console session's user
  INSERT api_tokens (kind='oidc', expires_at = now() + 24h)
  → renders one-time-display page: raw token + ready-to-paste hints
       pulumi login https://state-db.example.com/pulumi
       export PULUMI_ACCESS_TOKEN=<tok>
```

#### Daily lifecycle

- Around 24h after mint, `pulumi up` returns 401. The CLI prints "you must login again."
- User opens the console → "Get CLI token". If both the console session cookie and the IdP session are still alive, the entire mint round-trips silently — one click, one paste.
- If either session is gone, the user gets a real OIDC sign-in prompt before the silent mint completes.

#### Disabled-user propagation

- T+0: admin disables the user at the IdP. The existing CLI token still works.
- T+ε to T+24h: existing token expires; CLI gets 401.
- User attempts re-mint → `prompt=none` returns `login_required` → user is bounced to full sign-in → IdP rejects credentials. No new token is issued. CLI access is dead.

#### Implementation notes

- The OIDC `state` parameter on the cli-mint authz request encodes a signed `intent=cli-mint` so the callback can distinguish mint redirects from regular login redirects.
- The cli-mint callback verifies the freshly returned id_token's `(iss, sub)` matches the console session's `(iss, sub)`. This blocks an attacker who hijacked a session cookie from minting tokens against a different user's IdP session.
- Tokens are still hashed (sha256) before insert; the raw token is returned exactly once.
- The `kind` column on `api_tokens` distinguishes OIDC-bootstrapped tokens from service-account tokens (see db-schema.md decision #10).

### Service-account tokens (CI/CD)

Non-interactive callers cannot run an OIDC flow. They are served by a separate path:

- Minted by an admin via `POST /api/admin/tokens`.
- `api_tokens.kind = 'service_account'`. Owner is the minting admin's user (or a synthetic admin-owned user, depending on operational policy). `expires_at` is mandatory and capped at 1y in app code.
- Revocation is manual. Convention: one token per service, narrowly described (`description = "github-actions/state-db-deploy"`).

Both kinds share the wire format (`Authorization: token <tok>`) and the same `TokenAuthFilter`. The discriminator only affects mint policy and operational expectations, not protocol.

### Admin

`AdminAllowList` is a singleton bean reading `STATE_DB_ADMIN_SUBJECTS` at boot:
```
STATE_DB_ADMIN_SUBJECTS=https://accounts.google.com:115...,https://accounts.google.com:117...
```

Format: `<iss>:<sub>` per entry, comma-separated. The list is in-memory; rotating admins requires a redeploy (or a SIGHUP-triggered re-read — defer).

`AdminFilter` checks the SecurityContext subject against the list. No DB column. The schema doc doesn't have `users.is_admin` for exactly this reason.

### Local break-glass

Profile-gated. In `application-prod.yml` the local-glass route is excluded from the JAX-RS scan. In `dev` and a new `local-glass` profile, the route exists but is wrapped in:

```java
@Provider
@PreMatching
public class LocalGlassFilter implements ContainerRequestFilter {
    public void filter(ctx) {
        if (!ctx.getUriInfo().getPath().startsWith("api/auth/local")) return;
        var remote = ((HttpServerRequest) ctx.getProperty(...)).remoteAddress();
        if (!isLoopback(remote)) ctx.abortWith(404);
    }
}
```

Loopback check uses `InetAddress.isLoopbackAddress()`. Reverse proxies are not trusted — the request's TCP source must literally be loopback. This makes it usable for `pulumi login http://localhost:8080/pulumi` style local dev and for bare-metal recovery (`ssh server; curl localhost:8080/api/auth/local`). It is not usable through any deployed ingress.

**Credential source: env, never DB.** Recovery credentials live where the operator can read them while the DB is down. The route reads:

- `STATE_DB_LOCAL_GLASS_PASSWORD_HASH` — bcrypt/argon2 hash of the recovery password.

The handler verifies the POSTed password against the env hash. On success it signs a session cookie bound to the first `<iss>:<sub>` listed in `STATE_DB_ADMIN_SUBJECTS` — local-glass cannot escalate beyond the existing admin allow-list. Rotation is via redeploy.

### Console API surface

Read-heavy. Notable resources:

- `GET /api/workspaces` — list across kinds; joins through `workspaces` parent.
- `GET /api/pulumi/stacks/{ref}` — single stack: current resources (reconstructed checkpoint), tags, version.
- `GET /api/pulumi/stacks/{ref}/updates` — history from `stack_updates`.
- `GET /api/pulumi/stacks/{ref}/updates/{id}/events` — paginated `update_events`.
- `GET /api/auth/cli-mint` — interactive CLI token mint. Triggers OIDC `prompt=none` re-auth, then returns the raw token via a one-time-display page. `kind='oidc'`, 24h TTL. See "CLI token issuance".
- `GET /api/tokens` — list the current user's tokens (id, description, kind, expires_at, last_used_at).
- `DELETE /api/tokens/{id}` — revoke (owner or admin).
- `GET /api/me` — current user + admin status + org memberships.

Admin:
- `GET/POST /api/admin/providers` — manage `oidc_providers`.
- `GET /api/admin/users` — list users for support/auditing.
- `POST /api/admin/tokens` — mint a service-account token. Body `{description, org_id, expires_at}` (mandatory, ≤1y). `kind='service_account'`. Returns the raw token once. Used for CI/CD.
- `DELETE /api/admin/stacks/{ref}/lock` — manual unlock.

---

## SPA build & integration

### Layout

`web/` is a standalone bun project with TanStack Router and TanStack Query. No Java tooling in this directory.

```
web/
├── package.json           bun-managed; React, TanStack, Tailwind (TBD)
├── bunfig.toml            dev server config (proxy to :8080)
├── vite.config.ts         (or bun's bundler config; TBD which)
├── src/
│   ├── routes/            file-based or code-based; TanStack Router
│   ├── api/               typed client for /api/* (no codegen yet; manual types)
│   └── main.tsx
└── dist/                  build output, gitignored
```

### Dev mode

Two processes:
- `mvn quarkus:dev` on `:8080` — Java code, JAX-RS, DB.
- `bun dev` on `:5173` — SPA, HMR, proxies `/api/*` and `/pulumi/api/*` to `:8080`.

Developers point their browsers at `:5173`. Pulumi CLI dev testing points at `:8080/pulumi` directly (no SPA needed).

### Prod mode

Maven `package` invokes `bun build` (via `exec-maven-plugin` bound to `process-resources`), which writes to `web/dist/`. The Maven build then copies `web/dist/*` into `app/src/main/resources/META-INF/resources/`. Quarkus serves these as static assets automatically.

### SPA fallback routing

Client-side router needs `index.html` returned for unknown paths. We register a `RouteFilter` in `app/`:

```
priority: low
match:    not (/api or /pulumi or known static asset)
action:   serve META-INF/resources/index.html with 200
```

Filter explicitly excludes `/api/*` and `/pulumi/*` so unknown API paths return their normal 404 instead of an HTML page (which would confuse the Pulumi CLI badly).

---

## Multi-tenancy

Multi-tenant schema, but 99% of deployments will run with a single org. The console respects this:

- On first OIDC login, if zero orgs exist AND the user is in `STATE_DB_ADMIN_SUBJECTS`, auto-create a `default` org and add the user as `owner`. This makes a fresh deployment usable in three clicks.
- Subsequent admin-issued invites add users to orgs.
- Pulumi `{org}` path segment is checked against the token's org name (`organizations.name`), not its UUID. CLI users see human-readable refs.

Cross-org data leakage protection is enforced by the Cedar PDP (`auth.md`) and in every console-api query that resolves a stack — every such query JOINs `stacks → projects → organizations` and filters by the SecurityContext's `org_id`. A bare `stack_id` from external input is never trusted (db-schema.md invariant #14).

---

## Forward compat: OpenTofu (Wave 3)

The `workspaces` parent table provides type-erased identity from day one. When Tofu support lands:

- New URL prefix `/tofu/...` matching the OpenTofu HTTP backend protocol (`address`/`lock_address`/`unlock_address`).
- New Maven module `tofu-api/` with its own filter chain (Tofu uses HTTP basic or bearer; not our Pulumi token format directly — TBD).
- New schema migration `Vn__tofu.sql` adding `tofu_workspaces(workspace_id PK, …)`, `tofu_states(workspace_id, version, blob, …)`, `tofu_locks(…)`. None of this touches Pulumi tables.
- `WorkspacesResource` already returns mixed-kind results; adding a Tofu drilldown route is additive.

The Pulumi-specific update lifecycle, journaling, and event stream are intentionally **not** abstracted to workspace level. Tofu's protocol has none of these; trying to share would force compromises on both sides.

---

## Operational concerns

### Configuration

Quarkus profiles: `dev` (local Maven), `prod` (deployed), `local-glass` (admin-recovery shell on a real deployment). All non-secret config in `application.yml`. Secrets via env: DB password, OIDC client secret, session key, admin allow-list.

### Database

Single Postgres, version 14+. Encryption at rest is the operator's responsibility — the DB will hold OIDC client secrets and (by reference) tokens. Document this as a deployment requirement.

### Backups

`pg_dump`-friendly. No file/object dependencies. Restore is just `psql < dump.sql`; the app is stateless across restarts.

### Observability

- **Logs:** structured JSON (default Quarkus). One MDC entry per request with `request_id`, `subject`, `org_id`.
- **Metrics:** Micrometer + Prometheus endpoint at `/q/metrics`. Custom counters: `pulumi.update.started`, `pulumi.update.completed{result}`, `pulumi.lock.steal`, `compaction.duration`.
- **Health:** `/q/health/live`, `/q/health/ready` (DB connectivity).
- **Tracing:** OpenTelemetry hooks present, off by default; flip on with `quarkus.otel.enabled=true`.

### Rate limiting

Out of scope for Wave 1. The Pulumi CLI is well-behaved by default; abuse will come from the console direction first. Add per-user rate limiting in Wave 2.5 if it shows up in metrics.

---

## Native image

Wave 1 ships JVM-only. `cedar-java` (the policy engine; see `auth.md`) uses JNI bindings into a Rust core, and GraalVM native image support for that combination is fragile enough that we don't gate the release on it. JVM startup and footprint are acceptable for a self-hosted backend; native image is an optimization, not a correctness requirement.

Native build remains in CI as a non-blocking experimental job. When `cedar-java`'s native image story stabilizes, the experimental build is promoted to a release artifact alongside the JVM image.

Known reflection sources to register when native is promoted:
- All `@Entity` classes in `persistence/entity/` (Quarkus Hibernate extension auto-registers most; verify with `mvn package -Pnative -Dquarkus.native.enable-reports=true`).
- The JSONB `AttributeConverter` and any custom `JsonDeserializer`s.
- DTO classes used in JAX-RS request/response bodies (Quarkus REST extension auto-registers).
- `cedar-java`'s JNI entrypoints and the bundled Rust shared library per target arch.

---

## Testing

| Layer | Tool | What it covers |
|-------|------|----------------|
| Unit | JUnit 5 in `core/` | `CheckpointAssembler`, journal apply rules, `AdminAllowList` parsing — pure logic, no Quarkus |
| Integration | `@QuarkusTest` + Testcontainers Postgres | Repository transactions, trigger behavior, FK cascades, lock semantics |
| Contract | Fixture replay | Replay `tools/pulumi/recorder/fixtures/` through `pulumi-api` and assert byte-equal responses |
| E2E | Headless Pulumi CLI against a running container | `pulumi login`, `stack init`, `up`, `destroy`, `stack rm` end-to-end |

The fixture replay is the most important. Pulumi's wire format is undocumented for the journaling protocol; if a CLI minor version changes wire shape, fixture replay catches it. When upgrading the recorded CLI version, re-record and diff the fixture catalog before merging.

---

## Sequencing (Wave 1)

Rough order; each step gated on the previous passing tests.

1. `persistence/`: entities + Flyway V1 + repository smoke tests against Testcontainers.
2. `core/`: `CheckpointAssembler` (decompose checkpoint into journal-style entries; reassemble; round-trip property test).
3. `pulumi-api/` skeleton: token auth filter, `GET /api/user`, `GET /api/user/organizations/default`. Verify `pulumi login http://localhost:8080/pulumi` succeeds.
4. Stack CRUD: `POST/GET/DELETE /api/stacks/{org}/{project}/{stack}`. Verify `pulumi stack init`/`stack rm`.
5. Update lifecycle (no journaling): `POST /update`, `PATCH /checkpoint` (full snapshot variant), `POST /complete`. Verify `pulumi up` end-to-end on a no-op stack.
6. Locks: `POST /update/{id}` lock acquire, conflict path, stale recovery.
7. Journaling: `journalVersion: 1` handling, journal_entries inserts, reconstruction on `/export`. Verify `pulumi up` with journaling-enabled CLI.
8. Events: `events/batch` decode + `update_events` insert + retention job.
9. Compaction: scheduled job, threshold trigger, manual trigger.
10. Native image build green. Fixture replay passes on native.

Wave 2 (console) starts after step 7; OIDC + workspace listing can develop in parallel with steps 8–10.

---

## Open items

Future work. Each is additive — solvable inside Wave 1's architecture without breaking changes.

- **OIDC identity linking** — one user, multiple IdPs. Add `user_identities` table and a "link account" flow.
- **Token rotation policy** — OIDC-derived tokens auto-expire in 24h; service-account rotation is admin-driven. Automated rotation hooks (near-expiry webhooks, IdP-event-driven revocation) are future work.
- **`statedb-cli` companion** — small CLI to automate the daily OIDC re-auth and write the resulting token into `~/.pulumi/credentials.json` (AWS-SSO-style), removing the daily paste. Deferred; console-paste flow is acceptable for Wave 1.
- **Session re-read on admin-list change** — admin allow-list is read at boot; rotating admins requires redeploy. SIGHUP re-read is small future work.
- **Rate limiting** — not implemented; revisit when metrics show abuse.
- **Long-running event streaming** — currently `update_events` is poll-based for the console. SSE/WebSocket for live update tailing is Wave 2.5.
- **Org-scoped tokens vs cross-org tokens** — a token belongs to one org today. If users want a single token to read multiple orgs, redesign needed.

---

## Design decisions

| Decision | Rationale |
|----------|-----------|
| `/pulumi/...` prefix for backend | Keeps `/api/` for console; users configure `pulumi login https://host/pulumi` |
| Self-service token issuance | Tokens minted from console after OIDC login; never out-of-band |
| OIDC + loopback break-glass | OIDC is primary console authn; loopback-only env-credential fallback exists for admin recovery in non-prod profiles |
| Multi-tenant, single-tenant default | First admin login auto-creates `default` org |
| Postgres for blobs (no S3) | Per-resource journal entries are small; checkpoints periodically compacted |
| Tofu via shared `workspaces` parent + separate state tables | Identity unified; state model type-specific because Pulumi/Tofu fundamentally differ |
| Hibernate ORM + Panache | Panache for typical CRUD; native SQL drops in for hot paths (journal apply, sequence allocation, lock acquire) |
| Quarkus imperative (not reactive) | Short request-response, no streaming, simpler SQL paths |
| Flyway migrations | No auto-migrate in prod; CI step before image rollout |
| Bun build, copied into Quarkus resources | Dev: bun proxies to Quarkus. Prod: single static-served bundle |
| JVM-only Wave 1; native image experimental | `cedar-java` JNI/native image story is fragile; ship JVM and revisit when upstream support stabilizes |
| `quarkus-oidc` web-app session via BFF | Standard library; SPA gets HttpOnly session cookie. No rolled-our-own session crypto |
| Single Cedar PDP for authorization (see auth.md) | One source of truth for "who can do what"; org-scope and role checks fold into policy clauses rather than per-handler `if` |
| App-level workspace lifecycle | App INSERTs workspace then stack in one transaction; lifecycle stays explicit rather than hidden in triggers |
| No org-membership cache initially | Defer until profiling shows it matters |
| OIDC-bootstrapped CLI tokens, 24h TTL | Each mint round-trips through the IdP via `prompt=none`; IdP disablement propagates within TTL through the failed re-auth, with no extra IdP integration beyond what login already requires |
| `api_tokens.kind` discriminator | Single table, two policies — short-lived OIDC vs long-lived service-account; same wire format, same auth filter |
| Daily console paste over a companion CLI | Console-paste flow is acceptable for Wave 1; a `statedb-cli` to write `~/.pulumi/credentials.json` automatically (AWS-SSO-style) is later UX polish |
