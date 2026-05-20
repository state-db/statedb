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

**Module placement.** DB-shaped code (entities, finders, queries, journal
assembly, repository-style services) lives in `persistence/`. HTTP-shaped
code (JAX-RS resources, filters, exception mappers, wire DTOs) lives in
`pulumi-api/` or `console-api/`. Pure-domain/policy logic that isn't bound
to either — Cedar authorizer, adapter SPIs — lives in `core/` (introduced
by Slice 18). When in doubt: if both HTTP modules would need it, put it in
`persistence/`. Duplicating a tenant-isolated query across both modules is
a security risk, not a cosmetic one.

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

**Why now.** Pull lookup logic out of every future Pulumi endpoint. Console-api will need the same JOIN in Phase 8, so the resolver lives in `persistence/` — duplicating a tenant-isolated query across HTTP modules is a security risk.

**Scope**

- New `persistence/src/main/java/io/statedb/persistence/stack/`:
  - `StackRef` record `{ stackId, workspaceId, orgId, orgName, projectName, stackName, version, activeUpdateId }`.
  - `StackRefResolver` (`@ApplicationScoped`) with `Optional<StackRef> resolve(String org, String project, String stack)` joining `stacks → projects → organizations` by name via HQL.
  - `StackNotFoundException` (domain shorthand, throwable from either HTTP module).
- New `pulumi-api/src/main/java/io/statedb/pulumiapi/stack/StackNotFoundExceptionMapper.java` mapping the exception to a Pulumi-shaped 404. Console-api writes its own mapper when it adopts the pattern — wire formats deliberately diverge.

**Out of scope.** Auth filter wiring; using the resolver from real endpoints.

**Tests.** `@QuarkusTest` populating one org/project/stack and asserting (a) successful resolution, (b) miss returns empty, (c) wrong org name returns empty.

**Manual verification.** None needed beyond `mvn test`; pure internal API.

---

### Slice 4.5 · Bootstrap admin + default org + initial API token

**Why now.** Slice 5 needs real `users`, `organizations`, `org_members`, `api_tokens` rows to validate tokens against. This slice is the production onboarding path; without it, the only way to a usable deployment is hand-rolled SQL.

**Scope**

- `persistence/identity/BootstrapProvisioner` (`@ApplicationScoped`, fired from `@Startup` after Flyway). Idempotent across boots.
- Env (or `state-db.bootstrap.*` config keys): `STATE_DB_BOOTSTRAP_ORG_NAME` (operators migrating off Pulumi Cloud point this at the same org name they used there), `STATE_DB_BOOTSTRAP_ORG_DISPLAY_NAME`, `STATE_DB_BOOTSTRAP_ADMIN_EMAIL`, `STATE_DB_BOOTSTRAP_ADMIN_DISPLAY_NAME`, `STATE_DB_BOOTSTRAP_ADMIN_TOKEN_HASH` (sha256 of an operator-chosen token; raw never reaches state-db).
- Skip cleanly if `STATE_DB_BOOTSTRAP_ORG_NAME` is unset; fail fast if partial.
- One transaction:
  - `INSERT organizations ... ON CONFLICT (name) DO UPDATE SET display_name`.
  - `INSERT users(iss='local:bootstrap', sub='admin', ...) ON CONFLICT (iss, sub) DO UPDATE`. `iss='local:bootstrap'` is the reserved namespace for non-OIDC bootstrap users; existing `users` schema is sufficient.
  - `INSERT org_members(role='owner') ON CONFLICT DO NOTHING`.
  - `INSERT api_tokens(kind='service_account', description='bootstrap', expires_at = now() + interval '1 year') ON CONFLICT (token_hash) DO NOTHING`.
- `STATE_DB_ADMIN_SUBJECTS` semantics: `local:bootstrap:admin` is implicitly admin.
- Boot log: `Bootstrap admin "<email>" provisioned in org "<orgName>"` or `Bootstrap skipped`.

**Out of scope.** Console password-based break-glass — deferred to Slice 25 because no `/api/*` endpoint reads a session cookie before Slice 23 wires OIDC, and Slice 23 replaces any hand-rolled session mint with `quarkus-oidc`'s primitive. The bootstrap **API token** provisioned here is sufficient for Phase 2 (`pulumi login` / `pulumi whoami`), which targets `/pulumi/api/*` and uses `Authorization: token <raw>`, not cookies. Multiple orgs or users at bootstrap. Token rotation that auto-revokes prior tokens — re-running with a new hash *adds* a row; revoke manually via `psql` until Slice 26.

**Tests**

- Cold boot with all env set creates exactly one row in each of the four tables.
- Identical re-boot is a no-op.
- Re-boot with a changed `STATE_DB_BOOTSTRAP_ORG_DISPLAY_NAME` updates in place.
- Partial env fails before any INSERT.
- No `STATE_DB_BOOTSTRAP_*` env → no writes; `Bootstrap skipped` logged.

**Manual verification**

```
TOKEN=my-bootstrap-token
STATE_DB_BOOTSTRAP_ORG_NAME=acme \
STATE_DB_BOOTSTRAP_ORG_DISPLAY_NAME='Acme Inc' \
STATE_DB_BOOTSTRAP_ADMIN_EMAIL=ops@acme.example \
STATE_DB_BOOTSTRAP_ADMIN_DISPLAY_NAME='Acme Ops' \
STATE_DB_BOOTSTRAP_ADMIN_TOKEN_HASH=$(printf %s "$TOKEN" | shasum -a 256 | cut -d' ' -f1) \
./bin/in-container mvn -f modules/pom.xml -pl app -am quarkus:dev
# psql: SELECT (SELECT count(*) FROM organizations), (SELECT count(*) FROM users), (SELECT count(*) FROM org_members), (SELECT count(*) FROM api_tokens); expect 1,1,1,1.
```

---

### Slice 5 · TokenAuthFilter + login endpoints (Phase 2 — `pulumi login` succeeds)

**Why now.** Every Pulumi route needs authn before it can return real data, and the three endpoints `pulumi login` calls (`/api/user`, `/api/user/organizations/default`, `/api/capabilities`) are read-only — they share the same auth surface. Shipping them together in one PR keeps the "first verifiable end-to-end CLI call" as a single reviewable unit; what was originally planned as Slices 5 and 6 collapses here.

**Scope**

- `pulumi-api/auth/TokenAuthFilter.java` — global `@Provider` `@Blocking` filter (priority `AUTHENTICATION`) gating a hard-coded prefix list (`/pulumi/api/user`, `/pulumi/api/user/organizations/default`, `/pulumi/api/capabilities`, `/pulumi/api/stacks`).
  - The filter is *not* `@PreMatching`: Quarkus REST does not dispatch `@PreMatching` filters to worker threads, but the token lookup is a blocking JDBC call. A post-match `@Blocking` filter has functionally equivalent coverage because every Pulumi-surface route is a mapped resource — unauthenticated requests to unmapped paths still 404, which is the correct default.
- Header parse (`Authorization: token <raw>`), sha256-hex digest, lookup against `api_tokens × organizations × org_members` via new `persistence/identity/ApiTokenFinder` — same DB shape is touched by Slice 26 (`last_used_at` bookkeeping, console list/revoke), so the query lives in `persistence/` from day one. Membership is part of the same JOIN, not a follow-up check.
- `persistence/identity/AuthenticatedPrincipal` record `{userId, orgId, orgName, kind}` returned by the finder.
- `pulumi-api/auth/CurrentPrincipal` `@RequestScoped` bean populated by the filter; injected by downstream handlers.
- `pulumi-api/user/UserResource` — replaces placeholder with fixture-shaped DTO (`email`, `githubLogin`, `name`, `organizations[]`) populated from the principal + a follow-up `User.findById`. `siteAdmin` is omitted: the recorded fixture omits it and the CLI accepts the response as-is. DTOs have explicit `@JsonProperty` names.
- `pulumi-api/user/UserDefaultOrgResource` — `GET /api/user/organizations/default` → `{ organization: { githubLogin, name } }` from the principal's org.
- `pulumi-api/capabilities/CapabilitiesResource` — `GET /api/capabilities` → static `{ capabilities: [{ name: "delta-checkpoint-updates", version: 1 }] }`, pinned by the recorded fixture.
- Tests rely on Slice 4.5's bootstrap mechanism (env-driven); no separate dev-seed flag.

**Out of scope.** Cedar authorization, lease-token routes, `last_used_at` bookkeeping (Slice 26), multi-org users, GitHub-specific User fields beyond what the CLI reads.

**Tests**

- `ApiTokenFinderTest` (DB-level): expiry gating (active / NULL / past), unknown hash, missing membership.
- `TokenAuthFilterTest` (HTTP-level, RestAssured): 401 on missing header, wrong scheme, unknown token, expired token, revoked membership; 200 + principal-shaped body when valid; coverage of the `/pulumi/api/capabilities` and `/pulumi/api/stacks/*` prefixes.
- `LoginContractTest`: pins the JSON shape of all three login endpoints against the recorded fixtures under `tools/pulumi/recorder/fixtures/by-command/01-login/`. A precursor to the full replay harness (Slice 11).

**Manual verification**

```
./bin/in-container mvn -f modules/pom.xml -pl app -am quarkus:dev
# In another shell, with bootstrap env set as in Slice 4.5:
curl -i -H 'Authorization: token <bootstrap-token>' http://localhost:8080/pulumi/api/user
# Expect 200 with email/githubLogin/name/organizations.
pulumi login http://localhost:8080/pulumi
PULUMI_ACCESS_TOKEN=<bootstrap-token> pulumi whoami
# Expect the bootstrap admin printed.
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

- A new `persistence/stack/StackHeadFinder` returning the joined `{ orgName, projectName, stackName, tags, version, lastUpdate, resourceCount }` projection. Slice 29 (console drilldown) consumes the same finder behind a different wire DTO.
- Pulumi-shaped resource maps `StackHeadFinder` output to the fixture payload (`currentOperation` null for now, `lastUpdate` null until Slice 12, `resourceCount` zero until Slice 17.5).
- 404 collapse: if caller's org doesn't match the path, return 404 (not 403) — pre-Cedar; matches the design rule that cross-org reads collapse to 404.

**Out of scope.** Resource count from snapshot/journal (Slice 17.5), `lastUpdate` (Slice 11), `currentOperation` (Slice 12).

**Tests.** Fixture contract test for `GET /api/stacks/{org}/{project}/{stack}` post-stack-init (resource_count == 0).

**Manual verification**

```
PULUMI_ACCESS_TOKEN=<tok> pulumi stack output --show-name
# Or hit the URL directly with curl; expect populated JSON.
```

---

### Slice 9 · `GET /api/user/stacks` — caller's stack list

**Scope**

- A new `persistence/stack/StackListFinder` doing the `workspaces → stacks → projects → organizations` JOIN filtered by caller's `org_id` (per architecture: bare `stack_id` from external input is never trusted). Slice 28 (`/api/workspaces` console read) consumes the same finder behind a different wire DTO.
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

Each slice in this phase moves one step closer to a real `pulumi up` cycle succeeding. After Slice 17.5 the full lifecycle works against the recorder's real resource program, including preview, refresh, destroy, secrets, journaling, completion, and export.

### Slice 12 · `stack_updates` + start-op endpoints

**Scope**

- Migration `V003__updates.sql`: `stack_updates` per `db-schema.md` (with `idx_updates_idempotency`, `idx_updates_in_progress`, terminal-status guard trigger). Adds composite-FK target.
- `pulumi-api/update/UpdatesResource`: `POST .../update`, `POST .../preview`, `POST .../refresh`, `POST .../destroy`. Insert `stack_updates` row with `status='in-progress'`, return `{updateID, version}`.
- Store the requested `kind` accurately. The recorder shows refresh and destroy each run a preview phase and an actual phase; both actual phases reuse the same update-scoped machinery as `up`.
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
  - Successful return → emit the recorded `AppStartUpdateResponse`: `{token, tokenExpiration, version, journalVersion}`. Echo `journalVersion: 1` when the request negotiated journaling; omitting it makes the CLI fall back to checkpoint writes.
  - Same tx: `UPDATE stacks SET active_update_id = ?`.
- `DELETE /api/stacks/{org}/{project}/{stack}/update/{updateID}`: explicit cancel.
- `LeaseTokenFilter` bound to lease-scoped routes (PATCH `/journalentries`, PATCH `/checkpoint*`, POST `/events/batch`, POST `/complete`, POST `/renew_lease`, DELETE `/update/{id}`):
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

### Slice 14.5 · Service secrets endpoints

**Scope**

- Add the Pulumi service secrets endpoints for a stack:
  - `POST /api/stacks/{org}/{project}/{stack}/encrypt`
  - `POST /api/stacks/{org}/{project}/{stack}/decrypt`
  - `POST /api/stacks/{org}/{project}/{stack}/batch-decrypt`
- Request/response shapes pinned by fixtures:
  - `/encrypt`: request `{plaintext}` → response `{ciphertext}`
  - `/batch-decrypt`: request `{ciphertexts:[...]}` → response `{plaintexts:{ciphertext: plaintext}}`
  - `/decrypt`: single-value counterpart for compatibility, even though the current recorder only exercised batch decrypt.
- Implement a Wave 1 service-managed symmetric scheme. The exact crypto primitive belongs in the implementation PR, but the API must not be an identity passthrough outside the recorder.
- The encryption key is stack-scoped (deterministic per `(org, project, stack)`): ciphertext written during one update must decrypt during a later update from the same stack, and must not decrypt under a different stack's key. The wire `secretsProvider.state` (`owner`, `project`, `stack`, `url`) identifies the provider; the key derivation must match that identity.
- The stack's `SecretsManager` journal entry carries `secretsProvider: {type:"service", state:{owner, project, stack, url}}`; no new schema table is required for this slice.

**Out of scope.** AWS KMS, Azure Key Vault, and other delegated providers.

**Tests**

- Fixture replay for `/encrypt` and `/batch-decrypt`.
- Round-trip encrypt then decrypt returns the original plaintext.
- Batch decrypt preserves one result per unique ciphertext.

**Manual verification.** Run the recorder fixture sequence through the contract harness and confirm preview/refresh/destroy no longer fail at `batch-decrypt`.

---

### Slice 15 · `journal_entries` + `PATCH /journalentries`

**Scope**

- Migration `V005__journal.sql`: `journal_entries` per `db-schema.md` (numeric `kind`, BIGINT `operation_id`, replay index columns, composite FK to `stack_updates`) + `trg_journal_invalidates_cache`.
- `PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries`:
  - Lease filter has already validated; body is gzip-compressed JSON shaped as `{"entries":[AppJournalEntry...]}`.
  - A single PATCH may carry multiple entries; the first recorded `up`, `refresh`, and `destroy` batches each carry two entries.
  - Current preview fixtures do not write journal entries; actual `up`, actual `refresh`, and actual `destroy` do.
  - Single tx:
    1. `SELECT journal_seq FROM stack_state WHERE stack_id = ? FOR UPDATE`.
    2. Validate the request's wire `sequenceID` values are contiguous for the `update_id` and greater than the previous wire sequence for that update. The wire value resets per update, so do not use it as the stack-global replay key.
    3. For each entry in order, INSERT `journal_entries` with `sequence_id = stack_state.journal_seq + N`, `wire_sequence_id = entry.sequenceID`, `kind`, `operationID`, `version`, nullable index fields, `isRefresh`, denormalized `resource_urn`, and remaining nested fields in `payload`.
    4. Upsert/prune `pending_operations` for Begin/Success/Failure/RefreshSuccess entries where the wire entry supplies enough resource context.
    5. `UPDATE stack_state SET journal_seq = journal_seq + N, journal_entries_since_compaction = journal_entries_since_compaction + N`.
  - Invalid gzip / invalid JSON → 400 with a clear error body.
- Return 501 for `/checkpoint`, `/checkpointdelta`, and `/checkpointverbatim` in Wave 1. The OpenAPI paths exist, but the recorder shows Pulumi v3.242.0 chooses `/journalentries` once `journalVersion: 1` is echoed by start-update.

**Out of scope.** Snapshot writes (those come with compaction in Slice 21); `write`-kind entries with `newSnapshot` are stored verbatim as journal entries — Slice 17.5's `/export` will treat them correctly.

**Tests**

- Fixture replay for recorded `up`, actual `refresh`, and actual `destroy` journal batches.
- Single PATCH appends N entries with monotonically increasing server `sequence_id` and preserves each entry's wire `sequenceID`.
- Two concurrent PATCHes from two threads serialize on `FOR UPDATE`; sequence integrity preserved.
- A skipped or duplicate wire `sequenceID` within the same update returns 409 and writes no rows.
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

### Slice 17 · `POST /complete`

**Scope**

- `POST /complete`:
  - Single tx: mark `stack_updates.status` (`succeeded`/`failed`), DELETE `stack_locks`, UPDATE `stacks SET version = version + 1, active_update_id = NULL`. Preview never bumps `version`.
- Set `stack_updates.end_journal_seq` from `stack_state.journal_seq` inside the same transaction.
- Preview completion records terminal status and clears the lock, but does not bump `stacks.version`.

**Out of scope.** Export and reconstruction (next slice); compaction (Slice 21).

**Tests**

- `complete` is atomic: a forced failure mid-tx rolls back all three rows.
- Preview completion leaves `stacks.version` unchanged.

**Manual verification.** `pulumi preview`, `pulumi up`, `pulumi refresh`, and `pulumi destroy` all reach `complete` and leave no active lock.

---

### Slice 17.5 · `StackAssembler` + `GET /export`

**Scope**

- New `persistence/stack/StackAssembler` — pure persistence service that, given a `stackId`, reads the base snapshot from `stack_state` and replays `journal_entries WHERE sequence_id > base` per `db-schema.md` "State Reconstruction" rules.
- The reducer is a port of Pulumi's journal-apply behavior, not prose-derived logic. Reference implementation lives in `pulumi/pulumi` — search for `applyJournalEntry` / `AppJournalEntry` in `pkg/engine` and `pkg/backend/httpstate`. The port must handle all numeric kinds 0..7, `SecretsManager`, `removeOld`/`removeNew` as resource-list indexes, `deleteOld`/`deleteNew` for in-place deletion bookkeeping, `pendingReplacementOld`/`pendingReplacementNew` for the replace-then-delete pending list, Failure entries that clear a pending op without appending state, deletion Success entries with no `state`, `Outputs` mutating the synthetic stack resource, refresh replacement, and `Write` entries with `newSnapshot`.
- `GET /api/stacks/{org}/{project}/{stack}/export`:
  - Calls `StackAssembler.assemble(stackId)` and returns `{ version: 3, deployment: <reconstructed DeploymentV3> }`.
- This is the only place reconstruction logic lives; Slice 21 (compaction worker) and Slice 29 (console stack drilldown) both call it. Drift between callers would produce different current-state answers for the same stack.

**Out of scope.** Compaction (Slice 21); the assembler runs over the full journal every export until then.

**Tests**

- `export` reconstructs a hand-authored journal fixture byte-for-byte (allowing field masks for timestamps and encrypted values).
- Fixture-derived setup: insert the recorded `up` journal entries, export and assert seven resources; then insert the recorded actual `destroy` journal entries, export and assert no deployment resources.
- Refresh assembly covers the recorded actual refresh flow: Begin entries without `operation`, RefreshSuccess with `isRefresh=true`, and the final RebuiltBaseState marker.
- Failure assembly: a Begin entry seeds a pending op; a subsequent kind=2 Failure entry clears it and leaves the resource list unchanged.
- `pendingReplacement` assembly: a Success entry with `pendingReplacementOld`/`pendingReplacementNew` moves the indexed resource into the deployment's pending-replacement list rather than appending to the live resource list.
- `delete` assembly: a Success entry with `deleteOld`/`deleteNew` (no `state`) removes the indexed resource without appending a replacement.
- Composite assembly: a `Write` entry replaces the snapshot; subsequent Success entries operate on the replaced resource list.

**Manual verification**

```
pulumi up
pulumi stack export | jq .
pulumi destroy --yes
pulumi stack export | jq '.deployment.resources // []'
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

**Manual verification.** Insert a non-admin user via `psql` (`iss='local:test'`, `sub='viewer-1'`, `org_members.role='viewer'`) and mint them a token. `pulumi stack init` with that token returns 403; flip the role to `member` and it succeeds. The bootstrap admin can't be used here — it's implicitly admin via the `local:bootstrap:admin` allow-list and would bypass via `admin-escape`.

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
- `persistence/stack/CompactionWorker` `@Scheduled`: polls for `status='pending'`, `SELECT ... FOR UPDATE` on `stack_state`, calls Slice 17.5's `StackAssembler` to build the snapshot (same code path as `/export` — drift here would split-brain compaction vs. read), INSERT `snapshots`, point `stack_state.base_snapshot_id` at it, reset `journal_entries_since_compaction`, mark job `completed`.
- Failure path: mark `failed` with `error_message`, retry capped at 3 attempts (`attempts` column on the job table — add to migration).

**Tests**

- Threshold trigger enqueues exactly one job.
- Compaction collapses N journal entries into one snapshot; `/export` after compaction returns the same bytes as before.
- A worker holding `FOR UPDATE` blocks a concurrent PATCH `/journalentries`; PATCH succeeds after compaction commits.

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

**Why now.** This is also the mechanism for pre-OIDC console access (the role originally proposed as Slice 4.6, then deferred when we noticed the cookie had no consumer until this phase). It covers both first-touch console login on a fresh deployment with no IdP configured *and* IdP-down recovery on an established deployment.

**Scope**

- `/api/auth/local` endpoint:
  - `LocalGlassFilter` (`@PreMatching`) gates the route on two conditions; either failing → 404:
    1. **Loopback.** Remote address must be loopback via `InetAddress.isLoopbackAddress()` on the raw socket. `X-Forwarded-For` is never honored.
    2. **No working IdP.** `SELECT count(*) FROM oidc_providers WHERE enabled = true` must be 0. Tying the gate to system state rather than a profile flag means operators can't accidentally lock themselves out by forgetting a profile, and the route auto-disappears once OIDC is set up.
  - Verifies POSTed password against `STATE_DB_LOCAL_GLASS_PASSWORD_HASH` (argon2).
  - On success, mints a `quarkus-oidc` session bound to the first `<iss>:<sub>` from `STATE_DB_ADMIN_SUBJECTS`, falling back to the bootstrap admin (`local:bootstrap:admin`) if no explicit subjects are configured.
- Boot WARN log line when the route is live (i.e., zero enabled providers).

**Tests.** Unit test the `LocalGlassFilter` with a mocked request context and a stubbed enabled-provider counter: non-loopback → abort 404; loopback + 0 providers → pass-through; loopback + ≥1 enabled provider → abort 404. `@QuarkusTest` covers happy-path (200 + cookie), wrong password (401, timing-tolerant), unset hash (503), and the OIDC-provider-present case (404).

**Manual verification.** With no `oidc_providers` rows: `curl -d '{"password":"..."}' http://localhost:8080/api/auth/local` → session cookie. Insert an `enabled=true` provider row, retry → 404. Same call through a non-loopback ingress → 404.

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

- Reuses Slice 9's `persistence/stack/StackListFinder` (workspaces ↔ stacks 1:1, so the same JOIN serves both endpoints). Console-api adds a thin DTO mapping to the response shape: `[{ id, kind, org, project, stack, version, lastUpdate, resourceCount }]`. `kind='pulumi'` for now; Tofu adds rows later.
- Stable ordering by `(org name, project name, stack name)`.

**Tests.** Org scoping. Stable ordering across multiple requests.

**Manual verification.** Browser hits `/api/workspaces` and sees expected rows.

---

### Slice 29 · Console stack drilldown + history

**Scope**

- `GET /api/pulumi/stacks/{ref}`: reuses Slice 8's `StackHeadFinder` for the head and Slice 17.5's `StackAssembler` for the parsed resource list; console-api maps to its own DTO. No reconstruction logic in this module.
- `GET /api/pulumi/stacks/{ref}/updates`: new `persistence/update/UpdateHistoryFinder` (will also back any Pulumi-side history endpoint that lands later) — paginated by `started_at`.
- `GET /api/pulumi/stacks/{ref}/updates/{id}/events`: new `persistence/update/UpdateEventsFinder` paginating `update_events`.

**Tests.** History paging boundaries. Cross-org returns 404 (loader).

**Manual verification.** Browser hits the drilldown URL and sees resources from an `up`'d stack.

---

## Phase 9 — Validation and forward compat

### Slice 30 · Fixture-driven E2E flow

**Scope**

- Promote the contract harness from per-endpoint replays to full command sequences:
  `login → stack-init → up → preview → refresh → stack-ls → stack-export → destroy → stack-export-after-destroy → stack-rm`.
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

## Phase 10 — Licensing and commercial readiness

The cap in `LICENSE` (Free Edition Use Limit, ten active stacks per deployment) is currently legal-text-only. This phase makes it operational and prepares the repo for external contributions and paid customers.

### Slice 32 · Per-file license headers + `license-maven-plugin`

**Why now.** ELv2's Notices clause requires anyone receiving any part of the software to also receive the license terms. Per-file headers make that concrete and let license scanners (FOSSA, ScanCode, AWS Marketplace tooling) classify files correctly — important before any external distribution channel.

**Scope**

- Add `license-maven-plugin` to `modules/pom.xml` parent config.
- Header file `etc/header.txt` containing the copyright line, an ELv2-with-Additional-Limitations notice, and `SPDX-License-Identifier: LicenseRef-state-db`.
- One-time `mvn license:format` to apply headers to every existing `.java` file.
- Bind `mvn license:check` to the `verify` phase so missing or stale headers fail the build.
- Add `LICENSES/LicenseRef-state-db.txt` (a copy of `LICENSE`) so SPDX-aware scanners can resolve the `LicenseRef-`.

**Out of scope.** Headers in other languages — none yet; revisit when the web console adds JS/TS.

**Tests.** Add a deliberately unheaded source file in a scratch commit; `mvn verify` fails; revert.

**Manual verification**

```
./bin/in-container mvn -f modules/pom.xml license:check
```

---

### Slice 34 · License-key verifier

**Why now.** The Free Edition Use Limit needs a way to recognize a paid commercial license. Decision: signed Ed25519 token carrying `{ sub, exp, max_stacks }`, verified offline against a public key bundled in the binary; the private key is held by the licensor and never ships.

**Scope**

- New module `modules/licensing/`:
  - `LicenseKey` record + parser.
  - `LicenseKeyVerifier` using a bundled Ed25519 public key (classpath resource).
  - Boot-time load from `STATE_DB_LICENSE_KEY` env var, falling back to `/etc/state-db/license.key`.
  - `LicenseState` `@ApplicationScoped` CDI bean exposing `{ active, maxStacks, expiresAt, subject }`. Default with no key: `{ active: false, maxStacks: 10 }`.
- Boot log line stating Free Edition or commercial subject + expiry.

**Out of scope.** Online key revocation. AWS Marketplace metering. Customer-facing UX in the console. The key-minting CLI (held by the licensor only) is a separate follow-up — the verifier ships first.

**Tests**

- Valid key parses and verifies.
- Expired key is rejected with a clear error.
- Wrong-signature key is rejected.
- Missing key leaves `LicenseState.active=false, maxStacks=10` (Free Edition default).

**Manual verification.** Set `STATE_DB_LICENSE_KEY=<test-key>` and inspect the boot log; expect the active license fields to be printed.

---

### Slice 35 · Free Edition Use Limit enforcement

**Why now.** This is where the legal cap in `LICENSE` reaches actual customer-visible behavior. The 11th stack must refuse without a commercial key.

**Scope**

- Modify the stack-create handler (Slice 7) to count active stacks across the deployment: `SELECT count(*) FROM stacks`. The count is **global** to the database — `LICENSE` defines a "deployment" as "a single running installation backed by a single database," so no scoping by org or project.
- If `count + 1 > LicenseState.maxStacks`, return **`402 Payment Required`** with a JSON body referencing `COMMERCIAL.md`.
- Deletion (Slice 10) frees a slot immediately — no soft-deleted rows counted.
- Boot-time log: print Free Edition or commercial license status so operators see it.

**Out of scope.** Console UI for license status (Wave 2 console phase).

**Tests**

- 10 stacks create OK; 11th returns 402 without a key.
- With a key carrying `max_stacks=50`, the 50th stack creates OK; 51st returns 402.
- Deleting a stack frees a slot.
- An expired key behaves like no key (drops back to free-tier 10).
- 402 response body shape pinned by a contract test so customers can parse the error.

**Manual verification**

```
# Without a license key set on the server:
for i in $(seq 1 11); do pulumi stack init test-$i; done
# Expect the first 10 to succeed and the 11th to fail with "Free Edition Use Limit reached".
```

---

### Slice 36 · Third-party dependency notices in release artifacts

**Why now.** AWS Marketplace and most enterprise procurement processes check shipped artifacts for bundled-dependency license attribution. Generate this automatically so it always reflects what was actually shipped.

**Scope**

- Configure `license-maven-plugin`'s `aggregate-add-third-party` goal to produce `THIRD-PARTY-NOTICES.txt` at the aggregate level.
- Include `THIRD-PARTY-NOTICES.txt` in the runnable JAR and any container image we build.
- Fail the build if any dependency reports an unresolved license name — force explicit override in `etc/license-mapping.xml` rather than silent fallthrough.
- AGPL / GPL detection: build fails if any direct or transitive dependency is AGPL or GPL — these are incompatible with the commercial relicensing path. See the project's existing license-compatibility stance for the same reason jOOQ was avoided.

**Out of scope.** Full SBOM (CycloneDX / SPDX-Document) — useful for AWS Marketplace; doable when listing for real.

**Tests.** Build produces `THIRD-PARTY-NOTICES.txt`. Adding a fake AGPL dependency in a scratch commit fails the build.

**Manual verification**

```
./bin/in-container mvn -f modules/pom.xml package
# Confirm THIRD-PARTY-NOTICES.txt is present in the built JAR.
```

---

## PR cadence reminder

For every slice:

1. Migration + entity mappings (if needed).
2. Repository / service / domain logic.
3. JAX-RS resource + filter wiring.
4. Tests — at minimum one happy path and one failure path.
5. A `Manual verification` line in the PR description that the reviewer can paste into the dev container.

When a slice changes wire shape, the contract harness must be the thing that catches a regression — not someone running `pulumi up` by hand.
