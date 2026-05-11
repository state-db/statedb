# state-db Implementation Slices — v2

A more granular plan than `implementation-slices.md`. Each slice is a single PR:
one migration (at most), one focused code change, the tests that prove it, and
a step a human can run by hand to see it work.

Already shipped:

- `23eede3` scaffold
- `27f80ab` persistence: identity tables + base entities + constraint tests

Conventions for every slice below:

- **Scope** — what changes. Bias toward one migration + a thin slice of code.
- **Out of scope** — what is deliberately deferred to a later slice.
- **Tests** — what is added to the automated suite. Required if the slice
  changes wire shape or DB state.
- **Manual verification** — one command (or short sequence) a reviewer can run
  inside the dev container to convince themselves the slice works.

If a slice adds or changes a Pulumi-protocol endpoint, it must add a
fixture-driven contract test in the same PR.

Migration filenames are illustrative; renumber if a prior slice has shipped
ahead.

---

## Phase 1 — Foundations for stack identity

### Slice 3 · Stack-tree schema + entities

**Why now.** Every Pulumi endpoint resolves a stack from `{org}/{project}/{stack}`. The first thing missing after identity is the stack tree itself.

**Scope**

- Migration `V002__stack_tree.sql` adding: `workspaces`, `projects`, `stacks` (with `version`, `tags`; deferred FK to `stack_updates.id`), `stack_state`.
- No rename triggers and no denormalized `stack_ref` — `(org, project, stack)` lookups JOIN through `projects` and `organizations` at read time (db-schema.md Decision #2).
- Entities under `persistence/stack/`: `Workspace`, `Project`, `Stack`, `StackState`.
- `stacks.active_update_id` is a plain UUID column for now — composite FK to `stack_updates` lands when that table lands.

**Out of scope.** Resolver utility, any HTTP route changes.

**Tests**

- Schema-contract tests in the style of `IdentityConstraintsTest`:
  - `UNIQUE (org_id, name)` on projects; `UNIQUE (project_id, name)` on stacks.
  - `UNIQUE (stack_id)` on `stack_state` (singleton-per-stack).
  - `UNIQUE (workspace_id)` on stacks (1:1 with workspaces).
  - `CHECK (kind IN ('pulumi', 'tofu'))` on workspaces.
  - Cascade: deleting a workspace removes its stack and `stack_state`.
  - Cascade: deleting an org removes its projects/stacks/workspaces.

**Manual verification**

```
./bin/in-container mvn -f modules/pom.xml -pl app -am test
# In dev: connect to the dev-services DB and check pg_dump shows V002 applied.
```

---

### Slice 4 · Stack reference resolver

**Why now.** Pull lookup logic out of every future Pulumi endpoint.

**Scope**

- New `pulumi-api/src/main/java/io/statedb/pulumiapi/stack/StackRefResolver.java`:
  - `Optional<StackRef> resolve(String org, String project, String stack)` joining `stacks → projects → organizations` by name.
  - Returns a small DTO `StackRef { stackId, workspaceId, orgId, orgName, projectName, stackName, version, activeUpdateId }`.
- A typed `StackNotFoundException` mapped to 404 by a JAX-RS `ExceptionMapper`.

**Out of scope.** Auth filter wiring; using the resolver from real endpoints.

**Tests.** `@QuarkusTest` populating one org/project/stack and asserting (a) successful resolution, (b) miss returns empty, (c) wrong org name returns empty.

**Manual verification.** None needed beyond `mvn test`; pure internal API.

---

## Phase 2 — Make `pulumi login` succeed

After this phase, `pulumi login http://localhost:8080/pulumi` followed by `pulumi whoami` should return the seeded user.

### Slice 5 · TokenAuthFilter (read-only routes only)

**Why now.** Every Pulumi route needs authn before it can return real data.

**Scope**

- `pulumi-api/filter/TokenAuthFilter.java` bound `@PreMatching` to a hard-coded prefix list (`/pulumi/api/user`, `/pulumi/api/user/organizations/default`, `/pulumi/api/capabilities`, `/pulumi/api/stacks`).
- Header parse, sha256, lookup against `api_tokens` joined with `organizations`.
- Membership re-check via `org_members`.
- Sets a request-scoped `@CurrentPrincipal` CDI bean carrying `{userId, orgId, orgName, kind}`.
- Dev helper: a `@QuarkusTest` resource installer that inserts one user + org + membership + token at boot when `STATE_DB_DEV_SEED=true`. Keeps the manual-verification step one curl.

**Out of scope.** Cedar authorization, lease-token routes, last-used-at bookkeeping (Slice 20).

**Tests**

- 401 on missing header, unknown token, expired token.
- 401 when membership row is gone but token row remains.
- 200 when valid; principal carries the expected org name.

**Manual verification**

```
./bin/in-container mvn -f modules/pom.xml -pl app -am quarkus:dev
# In another shell:
curl -i -H 'Authorization: token <seeded-token>' http://localhost:8080/pulumi/api/user
# Expect 200; remove header → 401.
```

---

### Slice 6 · `/api/user` + `/api/user/organizations/default` + `/api/capabilities`

**Why now.** These three are the entire `pulumi login` call sequence in `api-snapshots/pulumi/catalog.md`.

**Scope**

- Replace `UserResource` placeholder with fixture-shaped DTO (`githubLogin`, `name`, `email`, `organizations[]`, `siteAdmin`).
- Add `UserDefaultOrgResource` returning `{ name, displayName, githubLogin }` from the principal's org.
- Add `CapabilitiesResource` returning the JSON Pulumi expects for `journalVersion: 1` advertisement. Static for now; shape pinned by fixture.
- DTOs have explicit `@JsonProperty` names; never rely on default field-name mapping.

**Out of scope.** Multi-org users, GitHub-specific fields beyond what the CLI reads.

**Tests.** Contract tests against the three matching files under `tools/pulumi/recorder/fixtures/by-command/01-login/`. Assert status + JSON-shape equality (modulo timestamps).

**Manual verification**

```
pulumi login http://localhost:8080/pulumi
PULUMI_ACCESS_TOKEN=<seeded-token> pulumi whoami
# Expect the seeded user printed.
```

---

## Phase 3 — Stack CRUD (no updates, no locking)

After this phase, `pulumi stack init`, `pulumi stack ls`, and `pulumi stack rm` work against an empty stack.

### Slice 7 · `POST /api/stacks/{org}/{project}` — stack create

**Scope**

- One-transaction handler:
  1. Resolve/create `projects` row (`INSERT ... ON CONFLICT (org_id, name) DO NOTHING RETURNING id`, then `SELECT`).
  2. `INSERT workspaces (kind='pulumi')`.
  3. `INSERT stacks` with `workspace_id` and `version=0`.
  4. `INSERT stack_state` row with `journal_seq=0`.
- Org-name path segment compared against the principal's `orgName`; mismatch returns 403 (no Cedar yet — direct comparison).
- Pulumi expects an empty `200` response shape on create — confirm against fixture.

**Out of scope.** Idempotent create (Pulumi CLI doesn't retry stack-init; revisit if a fixture proves otherwise).

**Tests**

- Happy-path contract test against fixture `02-stack-init/POST_api_stacks_*`.
- 409/400 (whichever Pulumi expects) when stack already exists.
- Org mismatch → 403.

**Manual verification**

```
pulumi stack init dev   # in a Pulumi project pointed at the backend
# Then in psql: SELECT name, version FROM stacks;
```

---

### Slice 8 · `GET /api/stacks/{org}/{project}/{stack}` — stack head

**Scope**

- Stack detail payload from fixture: `orgName`, `projectName`, `stackName`, `currentOperation` (null for now), `tags`, `version`, `lastUpdate`, `resourceCount` (0 for now).
- 404 collapse: if caller's org doesn't match the path, return 404 (not 403) — pre-Cedar; matches the design rule that cross-org reads collapse to 404.

**Out of scope.** Resource count from snapshot/journal (Slice 17), `lastUpdate` (Slice 11), `currentOperation` (Slice 12).

**Tests.** Fixture contract test for `GET /api/stacks/{org}/{project}/{stack}` post-stack-init (resource_count == 0).

**Manual verification**

```
PULUMI_ACCESS_TOKEN=<tok> pulumi stack output --show-name
# Or hit the URL directly with curl; expect populated JSON.
```

---

### Slice 9 · `GET /api/user/stacks` — caller's stack list

**Scope**

- Native query filtering by caller's `org_id` (per architecture: bare `stack_id` from external input is never trusted).
- Stable ordering by `(org name, project name, stack name)` for deterministic output.
- Optional `project` query param filter (Pulumi CLI sometimes passes it).

**Out of scope.** Pagination — Pulumi CLI doesn't request it; ship without.

**Tests.** Two stacks in org A, one in org B; token for A returns 2 rows.

**Manual verification**

```
pulumi stack ls
# Expect dev + any others you created.
```

---

### Slice 10 · `DELETE /api/stacks/{org}/{project}/{stack}` — stack rm

**Scope**

- Delete via `DELETE FROM workspaces WHERE id = ?` so the cascade is the only path that wipes state.
- 409 if `stack_state.cached_checkpoint` shows non-zero resources (defer to `journal_seq > 0` check until journaling lands; for now: 409 if any `stack_state.journal_seq > 0`).
- Force flag (`?force=true`) bypasses the resource check, per Pulumi CLI semantics.

**Out of scope.** Project cleanup when empty — leave the project row.

**Tests**

- Delete propagates: `workspaces`, `stacks`, `stack_state` all gone.
- Non-empty stack returns 409 without `force=true`.
- Fixture replay of `08-stack-rm`.

**Manual verification**

```
pulumi stack rm dev
pulumi stack ls
# Expect dev gone.
```

---

## Phase 4 — Contract test harness

### Slice 11 · Fixture replay harness

**Why now.** Phase 5 is several endpoints whose wire shape is non-obvious. Investing in the harness once pays off through every subsequent slice.

**Scope**

- `app/src/test/java/io/statedb/app/contract/FixtureReplay.java`:
  - Walks `tools/pulumi/recorder/fixtures/by-command/<command>/`.
  - For each `<method>_<url-encoded-path>__<nonce>.{req,resp}` pair, replays the request against the running `@QuarkusTest` server.
  - Asserts status code matches.
  - Asserts response JSON matches modulo a small allow-list of variable fields (`updateID`, `lockToken`, timestamps, request ids).
- Drive the harness from one JUnit class per command (`LoginContractTest`, `StackInitContractTest`, …) so failures point at a CLI command.
- Centralize the field-mask config in one file: `app/src/test/resources/contract/field-masks.yaml`.

**Out of scope.** Recording new fixtures (that's `tools/pulumi/recorder`'s job).

**Tests.** The harness itself is exercised by re-running the Phase 2/3 fixtures green. If a fixture passes today and then a future PR changes wire shape, the harness fails loudly.

**Manual verification**

```
./bin/in-container mvn -f modules/pom.xml -pl app -am -Dtest='*ContractTest' test
```

---

## Phase 5 — `pulumi up` end to end

Each slice in this phase moves one step closer to a real `pulumi up` cycle succeeding. After Slice 17 the full lifecycle works on a single-resource program.

### Slice 12 · `stack_updates` + start-op endpoints

**Scope**

- Migration `V003__updates.sql`: `stack_updates` per `db-schema.md` (with `idx_updates_idempotency`, `idx_updates_in_progress`, terminal-status guard trigger). Adds composite-FK target.
- `pulumi-api/update/UpdatesResource`: `POST .../update`, `POST .../preview`, `POST .../destroy`. Insert `stack_updates` row with `status='in-progress'`, return `{updateID, version}` (the lease token comes later, in Slice 13).
- Idempotency: `INSERT ... ON CONFLICT (stack_id, client_request_id) DO NOTHING`; on conflict, SELECT the existing row.

**Out of scope.** Lock acquire (next slice); `version` assignment is recorded but no `stacks.version` bump until complete.

**Tests**

- Repeated POST with same `client_request_id` returns one row.
- Terminal-status guard rejects an UPDATE that flips a `succeeded` row back to `in-progress`.

**Manual verification.** `pulumi preview` reaches the lock step and fails there — that's the next slice. Confirm a `stack_updates` row was inserted.

---

### Slice 13 · `stack_locks` + acquire/release + LeaseTokenFilter

**Scope**

- Migration `V004__locks.sql`: `stack_locks` per `db-schema.md`.
- `POST /api/stacks/{org}/{project}/{stack}/update/{updateID}`:
  - Single SQL: `INSERT ... ON CONFLICT (stack_id) DO UPDATE SET ... WHERE expires_at < now() RETURNING ...`.
  - Empty return → 409 (active lock held).
  - Successful return → emit lease envelope `{token, expiresAt}`.
  - Same tx: `UPDATE stacks SET active_update_id = ?`.
- `DELETE /api/stacks/{org}/{project}/{stack}/update/{updateID}`: explicit cancel.
- `LeaseTokenFilter` bound to lease-scoped routes (PATCH `/checkpoint`, POST `/events/batch`, POST `/complete`, POST `/renew_lease`, DELETE `/update/{id}`):
  - Validates `Authorization: update-token` against `stack_locks.lock_token` AND `expires_at > now()`.
  - Verifies path's `{stack}` resolves to the lock's `stack_id`.
  - Sets `LeaseContext{stack_id, update_id}`. Does **not** invoke Cedar.

**Out of scope.** Renew endpoint (next slice).

**Tests**

- Conflict path returns 409 when an active lock exists.
- Expired lock is stolen by a fresh acquire.
- Lease token mismatch returns 409 on a lease-scoped route.
- Path stack mismatch (`{stack}` resolves to a different `stack_id`) returns 409.

**Manual verification.** Two terminals: one `pulumi preview` blocked on a `sleep`; second `pulumi up` returns "conflict: stack is locked".

---

### Slice 14 · Renew + cancel polish

**Scope**

- `POST .../update/{updateID}/renew_lease`: `UPDATE stack_locks SET expires_at = now() + $ttl, heartbeat_at = now() WHERE lock_token = ?`. Empty rowcount → 409.
- Cancel path (`DELETE .../update/{id}`): delete the lock + update row in one tx; status flips to `cancelled` (subject to terminal-status guard).
- Config: `state-db.lock.lease-ttl=1h`, `state-db.lock.heartbeat-interval=30s` in `application.yml`.

**Tests.** Renew with wrong token → 409. Renew advances `expires_at`. Cancel transitions `status='cancelled'` and removes the lock atomically.

**Manual verification.** `pulumi cancel` releases a stuck lease (after starting a fake long-running preview).

---

### Slice 15 · `journal_entries` + `PATCH /checkpoint`

**Scope**

- Migration `V005__journal.sql`: `journal_entries` (with composite FK to `stack_updates`) + `trg_journal_invalidates_cache`.
- `PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/checkpoint`:
  - Lease filter has already validated; body is gzip'd JSON.
  - Single tx:
    1. `SELECT journal_seq FROM stack_state WHERE stack_id = ? FOR UPDATE`.
    2. For each entry in the request body, INSERT `journal_entries` with `sequence_id = journal_seq + N`.
    3. `UPDATE stack_state SET journal_seq = journal_seq + N, journal_entries_since_compaction = journal_entries_since_compaction + N`.
  - Invalid gzip / invalid JSON → 400 with a clear error body.

**Out of scope.** Snapshot writes (those come with compaction in Slice 21); `write`-type entries with `newSnapshot` are stored verbatim as journal entries — Slice 17's `/export` will treat them correctly.

**Tests**

- Single PATCH appends N entries with monotonically increasing `sequence_id`.
- Two concurrent PATCHes from two threads serialize on `FOR UPDATE`; sequence integrity preserved.
- Invalid gzip envelope returns 400.

**Manual verification.** Send a recorded PATCH body via curl and inspect `journal_entries`.

---

### Slice 16 · `update_events` + `POST /events/batch`

**Scope**

- Migration `V006__events.sql`: `update_events` per `db-schema.md`, with BRIN index.
- Handler: lease-validated, gzip-decode, JSON-array parse, INSERT one row per event with preserved `sequence`. Composite FK rejects events whose `update_id` belongs to a different stack.

**Out of scope.** Retention job (Slice 22).

**Tests**

- Fixture replay of recorded events batch.
- Unique-per-`(update_id, sequence)` is enforced.
- Cross-stack event injection is rejected by FK.

**Manual verification.** After `pulumi preview`, `SELECT count(*) FROM update_events WHERE update_id = ?` matches the event count printed by the CLI.

---

### Slice 17 · `POST /complete` + `GET /export`

**Why combined.** `complete` and `export` are the two paths that close the loop: complete persists the terminal state of an update; export reads it back. Shipping them together lets one fixture run cover both.

**Scope**

- `POST /complete`:
  - Single tx: mark `stack_updates.status` (`succeeded`/`failed`), DELETE `stack_locks`, UPDATE `stacks SET version = version + 1, active_update_id = NULL`. Preview never bumps `version`.
- `GET /api/stacks/{org}/{project}/{stack}/export`:
  - Reads `stack_state` to find base snapshot (may be NULL — no snapshot until compaction).
  - Streams `journal_entries WHERE stack_id = ? AND sequence_id > base` in order.
  - Applies entries per `db-schema.md` "State Reconstruction" rules (`begin`/`success`/`failure`/`refresh_success`/`outputs`/`write`/`rebuilt_base_state`).
  - Returns `{ version: 3, deployment: <reconstructed DeploymentV3> }`.

**Out of scope.** Compaction (Slice 21); the assembler runs over the full journal every export until then.

**Tests**

- `complete` is atomic: a forced failure mid-tx rolls back all three rows.
- Preview completion leaves `stacks.version` unchanged.
- `export` reconstructs a known fixture byte-for-byte (allowing field mask for timestamps).
- Composite assembly: a `write` entry replaces the snapshot; subsequent `success` entries operate on the replaced resource list.

**Manual verification**

```
pulumi up
pulumi stack export | jq .
# Compare against expected output.
```

---

## Phase 6 — Authorization

### Slice 18 · Cedar PDP wiring + minimal action set

**Scope**

- `core/identity/Authorizer`, `core/policy/PolicyStore` (classpath-only), `core/policy/PolicySetCache`.
- `cedar-java` dependency in `core/`.
- Schema file `policies/schema.cedar` declaring entities + actions for the minimum set: `User::ReadSelf`, `Stack::Read`, `Stack::Lock`, `Stack::Create`, `Stack::Delete`.
- Three policies: `admin-escape`, `org-members-can-read`, `org-write` (no service-account or owner-only yet).
- `@Authorize` annotation + `AuthorizeInterceptor` + `EntityLoader<Stack>` implementation.
- Replace the direct org-name comparison in Slices 7/8/10 with `@Authorize` calls.

**Out of scope.** Coverage gate, full action matrix, DB-overlay policies.

**Tests**

- Admin principal allowed everywhere.
- Viewer denied on `Stack::Create`; member allowed.
- Cross-org read returns 404 (loader returns empty), not 403.

**Manual verification.** Demote the seeded user from `member` to `viewer` in `org_members`; `pulumi stack init` returns 403; revert and it succeeds.

---

### Slice 19 · Authorization coverage gate

**Scope**

- Build-time `AuthorizationCoverageTest`: Jandex walk asserting every `/api/*` and `/pulumi/api/*` method carries `@Authorize` or `@AuthorizeExplicit("<reason>")`.
- Runtime `AuthorizationCoverageGuard` filter checking the `authorized` flag, returning 500 if unset on a protected route.
- Exemption list hard-coded in the test (`/api/auth/login/*`, `/api/auth/callback/*`, `/api/auth/local`, `/api/auth/cli-mint*`, `/api/auth/logout`).

**Tests.** Add a deliberately-unannotated route in a test source set; assert the build-time test fails on it. Add a route annotated but that never invokes the interceptor; assert the runtime guard returns 500.

**Manual verification.** Remove an `@Authorize` from a real endpoint in a scratch branch and watch `mvn test` fail; revert.

---

### Slice 20 · Remaining action matrix + policies

**Scope**

- Add the rest of the `auth.md` action set: `Stack::Unlock`, `Stack::ReadHistory`, `Stack::ReadEvents`, `Token::*`, `Provider::Manage`, `Compaction::Trigger`, `Org::*`, `User::ReadOthers`.
- Add `org-owner-only-delete` and `service-account-scope` policies.
- `EntityLoader<Project>`, `EntityLoader<Org>`, `EntityLoader<Token>` as needed.

**Tests.** One allow + one deny test per new action.

**Manual verification.** None new; covered by tests.

---

## Phase 7 — Maintenance jobs

### Slice 21 · Snapshots + compaction queue + worker

**Scope**

- Migration `V007__compaction.sql`: `snapshots`, `compaction_jobs`, FK from `stack_state.base_snapshot_id`.
- Enqueue trigger inside the `POST /complete` transaction when `journal_entries_since_compaction >= auto_compact_threshold`.
- `CompactionWorker` `@Scheduled`: poll for `status='pending'`, `SELECT ... FOR UPDATE` on `stack_state`, build snapshot by replaying journal, INSERT `snapshots`, point `stack_state.base_snapshot_id` at it, reset `journal_entries_since_compaction`, mark job `completed`.
- Failure path: mark `failed` with `error_message`, retry capped at 3 attempts (`attempts` column on the job table — add to migration).

**Tests**

- Threshold trigger enqueues exactly one job.
- Compaction collapses N journal entries into one snapshot; `/export` after compaction returns the same bytes as before.
- A worker holding `FOR UPDATE` blocks a concurrent PATCH `/checkpoint`; PATCH succeeds after compaction commits.

**Manual verification.** Set `auto_compact_threshold=5` in `application.yml`, run six `pulumi up` cycles, then `SELECT * FROM snapshots`.

---

### Slice 22 · Event retention scheduler

**Scope**

- `@Scheduled(every="1h")` job in `pulumi-api`:
  - Reads `state-db.events.retention-days` (default 30).
  - `DELETE FROM update_events WHERE created_at < now() - interval`.
  - Logs row count.
- Test profile overrides the schedule via `quarkus.scheduler.enabled=false` plus a manual trigger.

**Tests.** Inject a clock; assert events older than the threshold are deleted and newer ones survive.

**Manual verification.** Set retention to `0` days, run the job manually via `/q/scheduler/jobs`, watch the table drain.

---

## Phase 8 — Console (Wave 2 begins)

### Slice 23 · OIDC console foundation + `/api/me`

**Scope**

- `quarkus-oidc` dependency in `console-api/` in `web-app` mode.
- `TenantConfigResolver` reading `oidc_providers` at request time.
- Bootstrap upsert from `STATE_DB_OIDC_BOOTSTRAP_*` env at boot.
- `/api/auth/login/{provider}` + `/api/auth/callback/{provider}` wired through Quarkus.
- Real `/api/me` replacing the placeholder: returns `{ id, email, displayName, isAdmin, orgs[] }`.
- Session cookie scoped to `Path=/api/`.

**Out of scope.** CSRF filter (next slice); break-glass (Slice 25); CLI token mint (Slice 27).

**Tests.** Integration test using a fake OIDC provider container (Keycloak in Testcontainers, or `quarkus-oidc`'s test harness). Login + `/api/me` round-trips.

**Manual verification.** Configure bootstrap env to point at Google or a local Keycloak; open browser at `/api/me` → 401 → `/api/auth/login/google` → return → `/api/me` 200.

---

### Slice 24 · CSRF filter for `/api/*`

**Scope**

- `console-api/filter/CsrfFilter.java`:
  - `@PreMatching` on non-`GET`/`HEAD`/`OPTIONS` `/api/*` requests.
  - Enforces `Content-Type: application/json[; charset=...]` → 415 otherwise.
  - Enforces `Origin` matches `STATE_DB_PUBLIC_ORIGIN`; fallback `Referer` → 403 otherwise.
  - Skips `/api/auth/login/*`, `/api/auth/callback/*`.

**Tests.** GET unaffected. POST with wrong Origin → 403. POST with form content-type → 415. POST with correct headers passes.

**Manual verification.** From a browser console on a different origin, attempt to call `POST /api/tokens`; expect 403.

---

### Slice 25 · Local break-glass

**Scope**

- `/api/auth/local` endpoint:
  - `LocalGlassFilter` checks `InetAddress.isLoopbackAddress()` on the request remote address; else 404.
  - Profile gate: route excluded from JAX-RS scan unless profile is `dev` or `local-glass`.
  - Verifies POSTed password against `STATE_DB_LOCAL_GLASS_PASSWORD_HASH` (argon2).
  - On success, mints a `quarkus-oidc` session bound to the first `<iss>:<sub>` from `STATE_DB_ADMIN_SUBJECTS`.

**Tests.** Loopback + correct password → session. Non-loopback → 404 regardless of password. Prod profile → 404 regardless of source.

**Manual verification.** `curl -d 'password=...' http://localhost:8080/api/auth/local` → session cookie; same call through a forwarded proxy IP → 404.

---

### Slice 26 · Console token list + revoke + `last_used_at` bookkeeping

**Scope**

- `GET /api/tokens`: caller's tokens (id, description, kind, expires_at, last_used_at, created_at). Admins see all when `?all=true`.
- `DELETE /api/tokens/{id}`: owner OR admin (via Cedar `Token::Revoke`).
- Update `api_tokens.last_used_at` from `TokenAuthFilter` on every successful Pulumi-surface authenticated request. Async write (fire-and-forget on a small bounded executor) to keep request latency unaffected.

**Tests.** List shows own tokens only by default. Revoke-then-replay returns 401 on the next Pulumi request. `last_used_at` advances on use.

**Manual verification.** Mint a token via `psql` insert (until Slice 27), use it once, hit `/api/tokens`, see `last_used_at` populated.

---

### Slice 27 · CLI token mint via OIDC re-auth

**Scope**

- `GET /api/auth/cli-mint`:
  - Constructs OIDC authz URL with `prompt=none` and signed `state={intent:'cli-mint'}`.
  - 302 to IdP.
- `GET /api/auth/cli-mint/callback`:
  - Exchanges code via `quarkus-oidc`'s `OidcProviderClient`.
  - Verifies the returned `(iss, sub)` matches the console-session principal.
  - INSERT `api_tokens(kind='oidc', expires_at=now()+24h)`.
  - Renders a one-time-display page returning the raw token plus copy-paste hints.

**Tests**

- Round-trip with the Keycloak test container.
- `(iss, sub)` mismatch on the callback aborts with 403 — guard against session-cookie hijack minting a token against a different IdP session.
- `error=login_required` path renders the "your IdP session expired" view.

**Manual verification.** Click "Get CLI token" in the console; copy the displayed token; `pulumi whoami` succeeds with it.

---

### Slice 28 · `/api/workspaces` console read

**Scope**

- Native query joining `workspaces → stacks → projects → organizations` filtered by caller's `org_id`.
- Response shape: `[{ id, kind, org, project, stack, version, lastUpdate, resourceCount }]`. `kind='pulumi'` for now; Tofu adds rows later.
- Stable ordering by `(org name, project name, stack name)`.

**Tests.** Org scoping. Stable ordering across multiple requests.

**Manual verification.** Browser hits `/api/workspaces` and sees expected rows.

---

### Slice 29 · Console stack drilldown + history

**Scope**

- `GET /api/pulumi/stacks/{ref}`: stack head + parsed resource list from the assembled checkpoint + tags + active update if any.
- `GET /api/pulumi/stacks/{ref}/updates`: `stack_updates` history, paginated by `started_at`.
- `GET /api/pulumi/stacks/{ref}/updates/{id}/events`: paginated `update_events`.

**Tests.** History paging boundaries. Cross-org returns 404 (loader).

**Manual verification.** Browser hits the drilldown URL and sees resources from an `up`'d stack.

---

## Phase 9 — Validation and forward compat

### Slice 30 · Fixture-driven E2E flow

**Scope**

- Promote the contract harness from per-endpoint replays to full command sequences:
  `login → stack-init → preview → up → stack-ls → stack-export → destroy → stack-rm`.
- Each command's recorded sequence runs in order against one Quarkus instance.
- Failure messages include a unified diff of expected vs actual response body, with field masks already applied.

**Out of scope.** Performance/load testing.

**Tests.** Already the goal; the harness change *is* the test.

**Manual verification.** `mvn -Dtest=PulumiE2EReplayTest test`.

---

### Slice 31 · OpenTofu adapter bootstrap

**Scope**

- New module `modules/tofu-api/` with route prefix `/tofu/...`.
- Adapter SPI: `WorkspaceAdapter` interface in `core/`; Pulumi adapter wired in `pulumi-api/`; Tofu adapter is a stub returning 501 for every route.
- Tofu workspace identity mapped onto the existing `workspaces` table by adding `kind='tofu'` rows when stubs are first exercised.
- No new state tables yet; the migration is deferred until functional Tofu lands.

**Tests.** Tofu route returns 501. Adding a `kind='tofu'` workspace doesn't disturb any Pulumi query.

**Manual verification.** `curl http://localhost:8080/tofu/foo` returns 501 with a clear `Not implemented` body.

---

## PR cadence reminder

For every slice:

1. Migration + entity mappings (if needed).
2. Repository / service / domain logic.
3. JAX-RS resource + filter wiring.
4. Tests — at minimum one happy path and one failure path.
5. A `Manual verification` line in the PR description that the reviewer can paste into the dev container.

When a slice changes wire shape, the contract harness must be the thing that catches a regression — not someone running `pulumi up` by hand.
