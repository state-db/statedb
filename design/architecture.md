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
| `/api/auth/local` | Break-glass local login | Username/password from local DB table | Only enabled when (a) Quarkus profile is not `prod` AND (b) request originates from `127.0.0.1`/`::1`. Both checks; either fail = 404. |
| `/assets/*`, `/favicon.ico`, etc. | Static SPA bundle | None | Served from `META-INF/resources/` populated by the bun build. |
| Anything else | SPA fallback → `index.html` | None | Catches client-side router paths. Filter explicitly does NOT match `/api/*` or `/pulumi/*`. |

The `/pulumi` prefix is mandatory in `PULUMI_BACKEND_URL` because Pulumi's CLI reserves `/api/`. Users will configure `pulumi login https://state-db.example.com/pulumi`; the CLI then constructs `https://state-db.example.com/pulumi/api/user`, etc.

---

## Module layout

Maven multi-module project. Each module a separate JAR; `app/` is the only deployable.

```
state-db/
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
│   ├── filter/             TokenAuthFilter, OrgScopeFilter
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

The contract is captured in `tools/pulumi/recorder/fixtures/`. Each fixture is one (request, response) pair recorded against Pulumi CLI v3.234.0. The fixture catalog is the source of truth for wire shape; if a fixture and Pulumi documentation disagree, the fixture wins.

### Request lifecycle

```
TokenAuthFilter
  ├─ parse Authorization header
  ├─ sha256 the raw token
  ├─ SELECT api_tokens WHERE token_hash = ? AND (expires_at IS NULL OR expires_at > now())
  ├─ if miss → 401
  └─ attach Subject{user_id, org_id} to SecurityContext

OrgScopeFilter   (only on /pulumi/api/stacks/{org}/...)
  ├─ if {org} != Subject.org_name → 403
  └─ also verify org_members row exists for (Subject.user_id, Subject.org_id) → else 403

→ Resource method (transactional via @Transactional)
```

The `org_members` re-check on every request is intentional. It means revoking a user's org membership instantly invalidates all their tokens for that org without touching the tokens table. Cost is one indexed PK lookup; we'll cache later if profiling demands.

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

Multi-provider Quarkus integration uses `TenantConfigResolver`. Login URL is `/api/auth/login/{provider_name}`; the resolver returns the config for that row. Callback at `/api/auth/callback/{provider_name}`. On success, we upsert `users` keyed by `(iss, sub)` and issue a session cookie.

### Session storage

Signed-and-encrypted cookie carrying `{user_id, iss, sub, exp}`. No server-side session store — keeps horizontal scaling stateless. Cookie key from env (`STATE_DB_SESSION_KEY`, 32-byte base64). Rotate by setting an `OLD` key alongside; both accepted on read, only new key signs new cookies.

Cookie is `HttpOnly; Secure; SameSite=Strict; Path=/api/`. Not sent to `/pulumi/api/` because path scope excludes it. Defense in depth.

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

### Console API surface

Read-heavy. Notable resources:

- `GET /api/workspaces` — list across kinds; joins through `workspaces` parent.
- `GET /api/pulumi/stacks/{ref}` — single stack: current resources (reconstructed checkpoint), tags, version.
- `GET /api/pulumi/stacks/{ref}/updates` — history from `stack_updates`.
- `GET /api/pulumi/stacks/{ref}/updates/{id}/events` — paginated `update_events`.
- `POST /api/tokens` — mint a new token. Body `{description, org_id, expires_at?}`. Returns the raw token *once*; afterwards only the hash is stored.
- `DELETE /api/tokens/{id}` — revoke.
- `GET /api/me` — current user + admin status + org memberships.

Admin:
- `GET/POST /api/admin/providers` — manage `oidc_providers`.
- `GET /api/admin/users` — list users for support/auditing.
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

Cross-org data leakage protection is enforced in `OrgScopeFilter` and in every console-api query that resolves a stack — every such query JOINs `stacks → projects → organizations` and filters by the SecurityContext's `org_id`. A bare `stack_id` from external input is never trusted (db-schema.md invariant #14).

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

Single Postgres, version 14+. `pgcrypto` extension required (for `gen_random_uuid`). Encryption at rest is the operator's responsibility — the DB will hold OIDC client secrets and (by reference) tokens. Document this as a deployment requirement.

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

JVM is the default deployable. Native is the target before Wave 1 GA.

Known reflection sources to register:
- All `@Entity` classes in `persistence/entity/` (Quarkus Hibernate extension auto-registers most; verify with `mvn package -Pnative -Dquarkus.native.enable-reports=true`).
- The JSONB `AttributeConverter` and any custom `JsonDeserializer`s.
- DTO classes used in JAX-RS request/response bodies (Quarkus REST extension auto-registers).

Native build is gated in CI on a separate job (5–10 min) so PR feedback isn't blocked. The release pipeline produces both JVM and native images; default to native in published Docker tags, ship JVM as a fallback variant.

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

Things we've explicitly deferred. Each is solvable inside Wave 1's architecture without breaking changes.

- **OIDC identity linking** — one user, multiple IdPs. Add `user_identities` table and a "link account" flow.
- **Token rotation policy** — currently tokens have an optional `expires_at` and no scheduled rotation. Will bolt on later.
- **Session re-read on admin-list change** — admin allow-list is read at boot; rotating admins requires redeploy. SIGHUP re-read is small future work.
- **Rate limiting** — not implemented; revisit when metrics show abuse.
- **Long-running event streaming** — currently `update_events` is poll-based for the console. SSE/WebSocket for live update tailing is Wave 2.5.
- **Org-scoped tokens vs cross-org tokens** — a token belongs to one org today. If users want a single token to read multiple orgs, redesign needed.

---

## Decisions log (this conversation)

For posterity, the choices that shaped this doc:

| Decision | Rationale |
|----------|-----------|
| `/pulumi/...` prefix for backend | Keeps `/api/` for console; users configure `pulumi login https://host/pulumi` |
| Self-service token issuance | Tokens minted from console after OIDC login; never out-of-band |
| OIDC + local break-glass | OIDC is primary; local users only on loopback in non-prod profiles |
| Multi-tenant, single-tenant default | First admin login auto-creates `default` org |
| Postgres for blobs (no S3) | Per-resource journal entries are small; checkpoints periodically compacted |
| Tofu via shared `workspaces` parent + separate state tables | Identity unified; state model type-specific because Pulumi/Tofu fundamentally differ |
| Hibernate ORM + Panache | jOOQ ruled out (license); native queries for hot paths |
| Quarkus imperative (not reactive) | Short request-response, no streaming, simpler SQL paths |
| Flyway migrations | No auto-migrate in prod; CI step before image rollout |
| Bun build, copied into Quarkus resources | Dev: bun proxies to Quarkus. Prod: single static-served bundle |
| Native image as Wave 1 GA target | JVM default during dev; native validated before launch |
| App-level workspace lifecycle | Trigger-based auto-create rejected for clarity; app must INSERT workspace then stack in one tx |
| No org-membership cache initially | Defer until profiling shows it matters |
