# state-db Implementation Slices — v2

A more granular plan than `implementation-slices.md`. Each slice is a single PR:
one migration (at most), one focused code change, the tests that prove it, and
a step a human can run by hand to see it work.

Slices marked ✅ have shipped. Before them came:

- `23eede3` scaffold
- `27f80ab` persistence: identity tables + base entities + constraint tests

Work that shipped beyond this plan (no slice number): stack rename with
checkpoint rewrite, stack tags, stack history endpoint, stack import, 501
stubs for schedules/webhooks/unsupported features, the CLI recorder and
Pulumi integrity verifier, the `pulumi-cli-smoke` module (smoke tier plus
opt-in fault-injection tier emitting a State Safety Report), org service
accounts, the embedded TanStack web console scaffold, and the recast of
Cedar authorization into the AWS IAM model (see Slice 20's status note).

Conventions for every slice below:

- **Scope** — what changes. Bias toward one migration + a thin slice of code.
- **Out of scope** — what is deliberately deferred to a later slice.
- **Tests** — what is added to the automated suite. Required if the slice
  changes wire shape or DB state.
- **Manual verification** — one command (or short sequence) a reviewer can run
  through the pinned local toolchain to convince themselves the slice works.

If a slice adds or changes a Pulumi-protocol endpoint, it must add a
fixture-driven contract test in the same PR.

Migration filenames are illustrative; renumber if a prior slice has shipped
ahead.

**Module placement.** DB-shaped code (entities, finders, queries, journal
assembly, repository-style services) lives in `persistence/`. HTTP-shaped
code (JAX-RS resources, filters, exception mappers, wire DTOs) lives in
`pulumi-api/` or `console-api/`. Shared domain logic that is coupled to the
database identity model — Cedar authorizer, policy loading, stack/entity
loaders, tenant-isolated resolvers, journal assembly — lives in `persistence/`.
`persistence/` is the domain substrate because org membership, stack identity,
policy entities, and tenant isolation are grounded in the persisted schema. When
in doubt: if both HTTP modules would need it, put it in `persistence/`.
Duplicating a tenant-isolated query across both modules is a security risk, not
a cosmetic one.

---

## Phase 1 — Foundations for stack identity

### Slice 3 · Stack-tree schema + entities ✅

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
./dev -- modules/mvnw -f modules/pom.xml -pl app -am test
# In dev: connect to the dev-services DB and check pg_dump shows V002 applied.
```

---

### Slice 4 · Stack reference resolver ✅

**Why now.** Pull lookup logic out of every future Pulumi endpoint. Console-api will need the same JOIN in Phase 8, so the resolver lives in `persistence/` — duplicating a tenant-isolated query across HTTP modules is a security risk.

**Scope**

- New `persistence/src/main/java/app/statedb/persistence/stack/`:
  - `StackRef` record `{ stackId, workspaceId, orgId, orgName, projectName, stackName, version, activeUpdateId }`.
  - `StackRefResolver` (`@ApplicationScoped`) with `Optional<StackRef> resolve(String org, String project, String stack)` joining `stacks → projects → organizations` by name via HQL.
  - `StackNotFoundException` (domain shorthand, throwable from either HTTP module).
- New `pulumi-api/src/main/java/app/statedb/pulumiapi/stack/StackNotFoundExceptionMapper.java` mapping the exception to a Pulumi-shaped 404. Console-api writes its own mapper when it adopts the pattern — wire formats deliberately diverge.

**Out of scope.** Auth filter wiring; using the resolver from real endpoints.

**Tests.** `@QuarkusTest` populating one org/project/stack and asserting (a) successful resolution, (b) miss returns empty, (c) wrong org name returns empty.

**Manual verification.** None needed beyond `mvn test`; pure internal API.

---

### Slice 4.5 · Bootstrap admin + default org + initial API token ✅

> **Superseded in part by Slice 25.** The env-provisioned API token (`STATEDB_BOOTSTRAP_ADMIN_TOKEN_HASH`, plus the admin email/display-name vars) was removed once break-glass landed: the break-glass console session mints CLI tokens through the standard `pulumi login` browser flow, so one human credential covers the whole pre-OIDC story. Bootstrap now seeds only the org + bootstrap-admin user + membership from `STATEDB_BOOTSTRAP_ORG_NAME` (+ optional `_ORG_DISPLAY_NAME`, defaulting to the name). The workbench, which needs a non-interactive CLI token, seeds one directly via `seed-console.sh`. The spec below is preserved as written.

**Why now.** Slice 5 needs real `users`, `organizations`, `org_members`, `api_tokens` rows to validate tokens against. This slice is the production onboarding path; without it, the only way to a usable deployment is hand-rolled SQL.

**Scope**

- `persistence/identity/BootstrapProvisioner` (`@ApplicationScoped`, fired from `@Startup` after Flyway). Idempotent across boots.
- Env (or `statedb.bootstrap.*` config keys): `STATEDB_BOOTSTRAP_ORG_NAME` (operators migrating off Pulumi Cloud point this at the same org name they used there), `STATEDB_BOOTSTRAP_ORG_DISPLAY_NAME`, `STATEDB_BOOTSTRAP_ADMIN_EMAIL`, `STATEDB_BOOTSTRAP_ADMIN_DISPLAY_NAME`, `STATEDB_BOOTSTRAP_ADMIN_TOKEN_HASH` (sha256 of an operator-chosen token; raw never reaches state-db).
- Skip cleanly if `STATEDB_BOOTSTRAP_ORG_NAME` is unset; fail fast if partial.
- One transaction:
  - `INSERT organizations ... ON CONFLICT (name) DO UPDATE SET display_name`.
  - `INSERT users(iss='local:bootstrap', sub='admin', ...) ON CONFLICT (iss, sub) DO UPDATE`. `iss='local:bootstrap'` is the reserved namespace for non-OIDC bootstrap users; existing `users` schema is sufficient.
  - `INSERT org_members(role='owner') ON CONFLICT DO NOTHING`.
  - `INSERT api_tokens(kind='service_account', description='bootstrap', expires_at = now() + interval '1 year') ON CONFLICT (token_hash) DO NOTHING`.
- `STATEDB_ADMIN_SUBJECTS` semantics: `local:bootstrap:admin` is implicitly admin.
- Boot log: `Bootstrap admin "<email>" provisioned in org "<orgName>"` or `Bootstrap skipped`.

**Out of scope.** Console password-based break-glass — deferred to Slice 25 because no `/api/*` endpoint reads a session cookie before Slice 23 wires OIDC, and Slice 23 replaces any hand-rolled session mint with `quarkus-oidc`'s primitive. The bootstrap **API token** provisioned here is sufficient for Phase 2 (`pulumi login` / `pulumi whoami`), which targets `/pulumi/api/*` and uses `Authorization: token <raw>`, not cookies. Multiple orgs or users at bootstrap. Token rotation that auto-revokes prior tokens — re-running with a new hash *adds* a row; revoke manually via `psql` until Slice 26.

**Tests**

- Cold boot with all env set creates exactly one row in each of the four tables.
- Identical re-boot is a no-op.
- Re-boot with a changed `STATEDB_BOOTSTRAP_ORG_DISPLAY_NAME` updates in place.
- Partial env fails before any INSERT.
- No `STATEDB_BOOTSTRAP_*` env → no writes; `Bootstrap skipped` logged.

**Manual verification**

```
TOKEN=my-bootstrap-token
STATEDB_BOOTSTRAP_ORG_NAME=acme \
STATEDB_BOOTSTRAP_ORG_DISPLAY_NAME='Acme Inc' \
STATEDB_BOOTSTRAP_ADMIN_EMAIL=ops@acme.example \
STATEDB_BOOTSTRAP_ADMIN_DISPLAY_NAME='Acme Ops' \
STATEDB_BOOTSTRAP_ADMIN_TOKEN_HASH=$(printf %s "$TOKEN" | shasum -a 256 | cut -d' ' -f1) \
./dev -- modules/mvnw -f modules/pom.xml -pl app -am quarkus:dev
# psql: SELECT (SELECT count(*) FROM organizations), (SELECT count(*) FROM users), (SELECT count(*) FROM org_members), (SELECT count(*) FROM api_tokens); expect 1,1,1,1.
```

---

### Slice 5 · TokenAuthFilter + login endpoints (Phase 2 — `pulumi login` succeeds) ✅

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
./dev -- modules/mvnw -f modules/pom.xml -pl app -am quarkus:dev
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

### Slice 7 · `POST /api/stacks/{org}/{project}` — stack create ✅

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

### Slice 8 · `GET /api/stacks/{org}/{project}/{stack}` — stack head ✅

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

### Slice 9 · `GET /api/user/stacks` — caller's stack list ✅

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

### Slice 10 · `DELETE /api/stacks/{org}/{project}/{stack}` — stack rm ✅

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

### Slice 11 · Fixture replay harness ✅

**Why now.** Phase 5 is several endpoints whose wire shape is non-obvious. Investing in the harness once pays off through every subsequent slice.

**Scope**

- `app/src/test/java/app/statedb/app/contract/FixtureReplay.java`:
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
./dev -- modules/mvnw -f modules/pom.xml -pl app -am -Dtest='*ContractTest' test
```

---

## Phase 5 — `pulumi up` end to end

Each slice in this phase moves one step closer to a real `pulumi up` cycle succeeding. After Slice 17.5 the full lifecycle works against the recorder's real resource program, including preview, refresh, destroy, secrets, journaling, completion, and export.

### Slice 12 · `stack_updates` + start-op endpoints ✅

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

### Slice 13 · `stack_locks` + acquire/release + LeaseTokenFilter ✅

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

### Slice 14 · Renew + cancel polish ✅

**Scope**

- `POST .../update/{updateID}/renew_lease`: `UPDATE stack_locks SET expires_at = now() + $ttl, heartbeat_at = now() WHERE lock_token = ?`. Empty rowcount → 409.
- Cancel path (`DELETE .../update/{id}`): delete the lock + update row in one tx; status flips to `cancelled` (subject to terminal-status guard).
- Config: `statedb.lock.lease-ttl=1h`, `statedb.lock.heartbeat-interval=30s` in `application.yml`.

**Tests.** Renew with wrong token → 409. Renew advances `expires_at`. Cancel transitions `status='cancelled'` and removes the lock atomically.

**Manual verification.** `pulumi cancel` releases a stuck lease (after starting a fake long-running preview).

---

### Slice 14.5 · Service secrets endpoints ✅

**Scope**

- Add the Pulumi service secrets endpoints for a stack:
  - `POST /api/stacks/{org}/{project}/{stack}/encrypt`
  - `POST /api/stacks/{org}/{project}/{stack}/decrypt`
  - `POST /api/stacks/{org}/{project}/{stack}/batch-decrypt`
- Request/response shapes pinned by fixtures:
  - `/encrypt`: request `{plaintext}` → response `{ciphertext}`
  - `/batch-decrypt`: request `{ciphertexts:[...]}` → response `{plaintexts:{ciphertext: plaintext}}`
  - `/decrypt`: single-value counterpart for compatibility, even though the current recorder only exercised batch decrypt.
- Use [Google Tink](https://developers.google.com/tink) (`com.google.crypto.tink:tink`) for all cryptographic operations — `Aead` primitive with `AES256_GCM` parameters. No hand-rolled crypto, no JCA `Cipher` calls in our code. Full design in [`design/secrets.md`](secrets.md). The API must not be an identity passthrough outside the recorder.
- Cross-stack isolation is enforced by passing the resolved `stack.id` (UUIDv7, 16 bytes) as the AAD to every `Aead.encrypt` / `Aead.decrypt` call. Tag failure on AAD mismatch is AES-GCM's defining guarantee; we rely on it rather than per-stack key derivation. Binding to `stack.id` (not the wire `(owner, project, stack, url)` tuple) survives `pulumi stack rename`, operator URL changes, and instance migration, and forbids reuse on delete-and-recreate of a same-named stack.
- The stack's `SecretsManager` journal entry carries `secretsProvider: {type:"service", state:{owner, project, stack, url}}`. A singleton table `service_secrets_keyset (id, key_material, created_at)` holds a serialized Tink `AES256_GCM` keyset, seeded once by `BootstrapProvisioner` under advisory lock — no env-supplied or config-supplied keyset.

**Out of scope.** AWS KMS, Azure Key Vault, and other delegated providers.

**Tests**

- Fixture replay for `/encrypt` and `/batch-decrypt`. Recorder payloads are redacted (`[secret]`), so replay asserts HTTP shape and status only — crypto behavior is asserted by the unit/integration tests below.
- Unit: round-trip encrypt then decrypt returns the original plaintext; two encrypts of the same plaintext yield distinct ciphertexts.
- Unit: tampered base64 → decrypt 400; ciphertext encrypted under stack A's AAD fails 400 when decrypted under stack B's AAD.
- Integration: ciphertext produced under stack A's path returns 400 when posted to stack B's `/decrypt` (cross-stack isolation via `stack.id` AAD).
- Integration: `/batch-decrypt` deduplicates repeated input ciphertexts; any single bad entry fails the whole batch with 400.
- Integration: bootstrap seeds exactly one `service_secrets_keyset` row; second boot is idempotent under the advisory lock.
- Tink's own primitive correctness is not retested; we rely on upstream test coverage.

**Manual verification.** Run the recorder fixture sequence through the contract harness and confirm preview/refresh/destroy no longer fail at `batch-decrypt`.

---

### Slice 15 · `journal_entries` + `PATCH /journalentries` ✅

**Scope**

- Migration `V006__journal.sql`: `journal_entries` and `pending_operations` per `db-schema.md` (numeric `kind`, BIGINT `operation_id`, replay index columns, composite FK to `stack_updates`) + `trg_journal_invalidates_cache`. `stack_updates.start_journal_seq` is already in V003 and is reused as the per-update offset; no further migration to `stack_updates` is needed.
- `PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries`:
  - Lease filter has already validated; body is gzip-compressed JSON shaped as `{"entries":[AppJournalEntry...]}`. Quarkus's `enable-decompression: true` handles the gzip envelope before the handler runs.
  - A single PATCH may carry multiple entries; the first recorded `up`, `refresh`, and `destroy` batches each carry two entries.
  - Current preview fixtures do not write journal entries; actual `up`, actual `refresh`, and actual `destroy` do.
  - Single tx:
    1. Validate every entry: `kind ∈ [0,7]`, `wire_sequenceID > 0`. Any violation → 400 with `{code, message}`. No DB writes if validation fails.
    2. `SELECT journal_seq FROM stack_state WHERE stack_id = ? FOR UPDATE`. Serializes appenders cross-pod and blocks `/complete` from racing.
    3. Capture or read the per-update offset atomically: `UPDATE stack_updates SET start_journal_seq = ?1 WHERE id = ?2 AND start_journal_seq IS NULL` with `?1 = current stack_state.journal_seq`; then `SELECT start_journal_seq FROM stack_updates WHERE id = ?` to get the definitive value (whether captured just now or by an earlier PATCH).
    4. For each entry in arrival order, INSERT `journal_entries` with `sequence_id = offset + entry.sequenceID`, `wire_sequence_id = entry.sequenceID`, `kind`, `operationID`, `version`, nullable index fields, `isRefresh`, denormalized `resource_urn`, and remaining nested fields in `payload`. Insertion order need not match `sequence_id` order — `ORDER BY sequence_id` reconstructs replay order. `em.flush()` once at the end so a `UNIQUE(update_id, wire_sequence_id)` violation surfaces here and is mapped to 409.
    5. Upsert/prune `pending_operations` for Begin/Success/Failure/RefreshSuccess entries where the wire entry supplies enough resource context. Begin: `INSERT ... ON CONFLICT (update_id, operation_id) DO NOTHING`. Terminal kinds (1/2/3): `DELETE WHERE update_id = ? AND operation_id = ?`. Out-of-order arrival (Success before Begin) is tolerated: the DELETE no-ops and the late Begin leaves a stale row that compaction sweeps for terminated updates.
    6. `UPDATE stack_state SET journal_seq = GREATEST(journal_seq, offset + max_wire_in_batch + 1), journal_entries_since_compaction = journal_entries_since_compaction + N`. `GREATEST` because earlier PATCHes for the same update may have already advanced past this batch's max under out-of-order arrival.
  - Malformed body (null/missing `entries`, including silently-dropped gzip envelopes from Vert.x's decompressor) → 400 with `{code, message}`.
- Return 501 for `/checkpoint`, `/checkpointdelta`, and `/checkpointverbatim` in Wave 1. The OpenAPI paths exist, but the recorder shows Pulumi v3.242.0 chooses `/journalentries` once `journalVersion: 1` is echoed by start-update. The 501 stubs are documentation in code, not a placeholder — these endpoints are permanently skipped.

**Replay-key contract.** Server `sequence_id` is a deterministic function of the wire ID: `start_journal_seq + wire_sequenceID`. The CLI's atomic wire-ID counter is incremented at entry-creation time by parallel resource workers, but `BatchEncrypt` latency and channel scheduling mean a worker that drew a lower ID can push to the channel later — so wire IDs may arrive at the server out of order across PATCHes. The offset scheme makes `ORDER BY sequence_id` consistent with the CLI's intended replay order regardless of arrival order. See `db-schema.md` invariant #9.

**Out of scope.** Snapshot writes (those come with compaction in Slice 21); `write`-kind entries with `newSnapshot` are stored verbatim as journal entries — Slice 17.5's `/export` will treat them correctly.

**Tests**

- Fixture replay for recorded `up`, actual `refresh`, and actual `destroy` journal batches — send the gzipped bodies through the live HTTP handler, assert row count, `MAX(sequence_id) == offset + max wire seq`, distinct wire-ID count, at least one Begin entry, and `journal_entries_since_compaction` equals total rows appended.
- Single PATCH appends N entries with `sequence_id = offset + wire_sequenceID` for each, preserving the wire IDs verbatim.
- Out-of-order wire IDs within a batch (e.g. `[6, 5, 7]`) all insert; `ORDER BY sequence_id` returns them in wire order.
- Out-of-order wire IDs across PATCHes (PATCH 1: `[3, 5]`, PATCH 2: `[4, 6]`) all insert; replay reconstructs `[3, 4, 5, 6]`.
- Two concurrent PATCHes from two threads serialize on `FOR UPDATE`; offset capture is atomic and rows from both threads persist.
- A duplicate wire `sequenceID` within the same update returns 409 and writes no rows from the duplicate batch.
- Invalid `kind` (outside `[0,7]`) returns 400 and writes no rows.
- Malformed gzip envelope returns 400 (not a silent 200 — Vert.x's decompressor drops the body to null on garbage input).
- Second update on the same stack: its offset equals the first update's final `journal_seq`; `sequence_id` continues monotonically with no cross-update collision.
- `pending_operations` lifecycle: Begin inserts the row with `begin_journal_seq = offset + wire_seq`; matching Success/Failure/RefreshSuccess removes it.

**Manual verification.** Send a recorded PATCH body via curl and inspect `journal_entries`.

---

### Slice 16 · `update_events` + `POST /events/batch` ✅

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

### Slice 17 · `POST /complete` ✅

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

### Slice 17.5 · `StackAssembler` + `GET /export` ✅

**Scope**

- New `persistence/stack/StackAssembler` — persistence service that, given a `stackId`, reads the base snapshot from `stack_state` and replays `journal_entries WHERE sequence_id > base` per `db-schema.md` "State Reconstruction" rules.
- The reducer is a port of Pulumi's journal-apply behavior, not prose-derived logic. Reference implementation lives in `pulumi/pulumi` under `pkg/backend/journal.go` and `sdk/go/common/apitype/journal.go`. The port handles all numeric kinds 0..7, `SecretsManager`, `*Old` fields as base-resource indexes, `*New` fields as current-replayer operation IDs, `deleteOld`/`deleteNew` in-place deletion marks, `pendingReplacementOld`/`pendingReplacementNew` in-place pending-replacement marks, Failure entries that clear a pending op without appending state, deletion Success entries with no `state`, `Outputs` replacing the indexed synthetic stack resource in place, refresh replacement/removal without appending, and `Write` entries with `newSnapshot`.
- `GET /pulumi/api/stacks/{org}/{project}/{stack}/export`:
  - Calls `StackAssembler.assemble(stackId)` and returns the Pulumi `UntypedDeployment` shape `{ version: 3|4, deployment: <reconstructed DeploymentV3>, features?: [...] }`.
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

### Slice 18 · Cedar PDP wiring + minimal action set ✅

**Scope**

- `persistence/authz/Authorizer`, `persistence/policy/PolicyStore` (classpath-only), `persistence/policy/PolicySetCache`.
- `cedar-java` dependency in `persistence/`.
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

**Status.** Partially superseded. Authorization was recast into the AWS IAM
model (`stack:*` / `serviceaccount:*` action namespaces, role policies per
org role, managed/resource policies via Cedar templates, grants in
`policy_grants`). Stack and service-account actions are covered; the
org/provider/token/compaction actions below have not landed and need
re-scoping against the IAM model before implementation.

**Scope**

- Add the rest of the `auth.md` action set: `Stack::Unlock`, `Stack::ReadHistory`, `Stack::ReadEvents`, `Token::*`, `Provider::Manage`, `Compaction::Trigger`, `Org::*`, `User::ReadOthers`.
- Add `org-owner-only-delete` and `service-account-scope` policies.
- `EntityLoader<Project>`, `EntityLoader<Org>`, `EntityLoader<Token>` as needed.

**Tests.** One allow + one deny test per new action.

**Manual verification.** None new; covered by tests.

---

## Phase 7 — Maintenance jobs

### Slice 21 · Snapshots + compaction queue + worker ✅

**Scope**

- Migration `V008__compaction.sql`: `snapshots`, `compaction_jobs`, FK from `stack_state.base_snapshot_id`.
- Enqueue a compaction job inside the `POST /complete` transaction after every completed update. The partial unique index on `compaction_jobs(stack_id) WHERE status IN ('pending','running')` coalesces bursts so a stack has at most one active job.
- `persistence/stack/CompactionWorker` `@Scheduled`: claims jobs with `FOR UPDATE SKIP LOCKED`, locks `stack_state`, calls Slice 17.5's `StackAssembler` to build the snapshot (same code path as `/export` — drift here would split-brain compaction vs. read), INSERT `snapshots`, point `stack_state.base_snapshot_id` at it, reset `journal_entries_since_compaction`, mark job `completed`.
- Failure path: mark failed/retryable with `error_message`, retry capped by `statedb.compaction.max-attempts`; reaper returns timed-out `running` jobs to `pending` or terminal `failed`.
- Add `quarkus-scheduler` and config: `poll-interval`, `per-tick-cap`, `claim-timeout`, `max-attempts`.
- Wire `StackAssembler` to load `snapshots.checkpoint` as the base and replay only `journal_entries.sequence_id > snapshots.compacted_from_journal_seq`.

**Tests**

- `POST /complete` enqueues exactly one active job per stack; a second completed update while one is pending/running coalesces.
- Compaction collapses N journal entries into one snapshot; `/export` after compaction returns the same bytes as before.
- A worker holding `FOR UPDATE` blocks a concurrent PATCH `/journalentries`; PATCH succeeds after compaction commits.
- Reaper reclaims old `running` jobs and fails exhausted jobs.

**Out of scope.** Physical deletion of old `journal_entries` and old `snapshots`; compaction only moves the `/export` replay boundary. Retention is tracked explicitly in Slices 21.1 and 21.2.

**Manual verification.** Run six `pulumi up` cycles, then inspect `snapshots`, `stack_state.base_snapshot_id`, and `compaction_jobs`.

---

### Slice 21.1 · Journal retention after compaction

**Scope**

- Add an app-level retention job or compaction follow-up step that physically deletes compacted journal rows only after a successful snapshot is active.
- Default policy: keep current-state correctness, not full historical replay. Delete rows for a stack where `journal_entries.sequence_id <= snapshots.compacted_from_journal_seq` for the active base snapshot.
- Preserve `stack_updates` metadata so history lists still show completed operations even when their bulky journal rows have been pruned.
- Make the behavior explicit in config:
  - `statedb.journal-retention.enabled` default `false` until operators opt in.
  - `statedb.journal-retention.keep-after-compaction` default `0` rows/updates unless a stronger audit requirement appears.
  - Optional minimum age such as `statedb.journal-retention.min-age` so a just-compacted stack is not pruned immediately in debugging environments.
- Deletion must lock the stack's `stack_state` row or otherwise run from a snapshot boundary that cannot move underneath it.
- Use a database-native bulk delete; this is a legitimate native SQL path because retention is set-based cleanup, not entity business logic.

**Tests**

- Given a stack with an active snapshot at sequence `N`, pruning deletes journal rows with `sequence_id <= N` and keeps rows with `sequence_id > N`.
- `/export` before and after pruning returns the same checkpoint bytes.
- Pruning one stack never deletes rows for another stack.
- If `stack_state.base_snapshot_id IS NULL`, pruning no-ops.
- If retention is disabled, pruning no-ops.

**Manual verification.** Enable journal retention, run several `pulumi up` cycles until snapshots advance, run the retention job, then compare `pulumi stack export` before/after and inspect `journal_entries` row counts.

---

### Slice 21.2 · Snapshot retention

**Scope**

- Delete old `snapshots` rows once newer snapshots have become the active base for a stack.
- Default policy: keep the active snapshot plus one previous snapshot per stack (`statedb.snapshot-retention.keep=2`) for debugging/rollback safety.
- Never delete `stack_state.base_snapshot_id`; the FK protects this, but the job should filter it out deliberately.
- Delete `completed` `compaction_jobs` older than a configurable age after any referenced snapshot retention is complete. Keep `failed` jobs longer or indefinitely until there is an operator-visible failure surface.
- Coordinate ordering with journal retention:
  - journal retention uses the active snapshot boundary,
  - snapshot retention must not delete the active snapshot,
  - old snapshots can be removed after no live pointer or retained diagnostic window needs them.

**Tests**

- With three snapshots for one stack and `keep=2`, retention deletes only the oldest and keeps the active snapshot.
- Snapshot retention across multiple stacks is independent.
- A snapshot referenced by `stack_state.base_snapshot_id` is never deleted even if it would otherwise fall outside `keep`.
- Old completed compaction jobs are pruned according to config; failed jobs remain.

**Manual verification.** Set `statedb.snapshot-retention.keep=1`, run several compactions, run the retention job, and confirm the active snapshot remains while older unreferenced snapshots disappear.

---

### Slice 22 · Event retention scheduler

**Scope**

- `@Scheduled(every="1h")` job in `pulumi-api`:
  - Reads `statedb.events.retention-days` (default 30).
  - `DELETE FROM update_events WHERE created_at < now() - interval`.
  - Logs row count.
- Test profile overrides the schedule via `quarkus.scheduler.enabled=false` plus a manual trigger.

**Tests.** Inject a clock; assert events older than the threshold are deleted and newer ones survive.

**Manual verification.** Set retention to `0` days, run the job manually via `/q/scheduler/jobs`, watch the table drain.

---

## Phase 8 — Console (Wave 2 begins)

### Slice 23 · OIDC console foundation + `/api/user` ✅

**Scope**

- `quarkus-oidc` dependency in `console-api/` in `web-app` mode.
- `TenantConfigResolver` reading `oidc_providers` at request time.
- Bootstrap upsert from `STATEDB_OIDC_BOOTSTRAP_*` env at boot.
- Default `/api/auth/login`, explicit `/api/auth/login/{provider}`, and `/api/auth/callback/{provider}` wired through Quarkus.
- Current-user `/api/user`: returns `{ id, email, name, siteAdmin, organizations[] }`.
- Session cookie scoped to `Path=/api/`.

**Out of scope.** CSRF filter (next slice); break-glass (Slice 25); CLI token mint (Slice 27).

**Tests.** Integration test using a fake OIDC provider container (Keycloak in Testcontainers, or `quarkus-oidc`'s test harness). Login + `/api/user` round-trips.

**Manual verification.** Configure bootstrap/default-provider env to point at Google or a local Keycloak; open browser at `/api/user` → 401 → `/api/auth/login` → return → `/api/user` 200.

---

### Slice 24 · CSRF filter for `/api/*`

**Scope**

- `console-api/filter/CsrfFilter.java`:
  - `@PreMatching` on non-`GET`/`HEAD`/`OPTIONS` `/api/*` requests.
  - Enforces `Content-Type: application/json[; charset=...]` → 415 otherwise.
  - Enforces `Origin` matches `STATEDB_PUBLIC_ORIGIN`; fallback `Referer` → 403 otherwise.
  - Skips `/api/auth/login/*`, `/api/auth/callback/*`.

**Tests.** GET unaffected. POST with wrong Origin → 403. POST with form content-type → 415. POST with correct headers passes.

**Manual verification.** From a browser console on a different origin, attempt to call `POST /api/tokens`; expect 403.

---

### Slice 25 · Local break-glass ✅

**Why now.** This is also the mechanism for pre-OIDC console access (the role originally proposed as Slice 4.6, then deferred when we noticed the cookie had no consumer until this phase). It covers both first-touch console login on a fresh deployment with no IdP configured *and* IdP-down recovery on an established deployment. Without it, a zero-IdP deployment has a console that is 100% inaccessible.

**Mechanism decision.** `quarkus-oidc` exposes no API to mint its session cookie outside a real IdP code flow, so break-glass is built on Quarkus **form authentication** in SPA mode (the Quarkus team's recommended primitive for IdP-less session login): `quarkus.http.auth.form` with `post-location: /api/auth/local` and empty `login-page`/`error-page`/`landing-page`, so the mechanism answers with bare status codes (200 + cookie / 401) instead of redirects. The cookie (`state-db-glass`, HttpOnly, SameSite=Strict, 12h timeout) is AES-256-encrypted with `quarkus.http.auth.session.encryption-key`, wired to the existing `STATEDB_SESSION_ENCRYPTION_SECRET` so sessions are stable across replicas. Quarkus owns cookie crypto, expiry, and renewal; we own only policy:

- `LocalGlass` — shared gate + bcrypt verify + idempotent bootstrap-admin `users`-row creation. Gate (any failure → 401): (1) `STATEDB_LOCAL_GLASS_PASSWORD_HASH` set (bcrypt MCF); (2) zero enabled `oidc_providers` rows — state-gated, not profile-gated, so the route self-destructs once OIDC exists and IdP-down recovery re-opens it by disabling the provider row; (3) raw TCP source is loopback (`X-Forwarded-For` never honored) unless `STATEDB_LOCAL_GLASS_ALLOW_REMOTE=true` (the `docker run -p` evaluation path — published ports arrive from the bridge gateway, not loopback).
- `LocalGlassIdentityProvider` (`UsernamePasswordAuthenticationRequest`) — login-time check; all failure modes are the same 401. Success always authenticates as the bootstrap admin (`local:bootstrap:admin`) — never `STATEDB_ADMIN_SUBJECTS` entries, which have no `users` row while zero providers exist.
- `LocalGlassTrustedIdentityProvider` (`TrustedAuthenticationRequest`) — cookie restore; re-checks the zero-providers gate on every request, so enabling OIDC kills outstanding break-glass sessions immediately.
- `LocalGlassPrincipal` — synthetic `JsonWebToken` carrying the bootstrap `iss`/`sub`, so `SessionPrincipalFilter`, Cedar, and `/api/user` treat the session exactly like an OIDC one.
- `LocalGlassStartupValidator` — boot fails if the hash is set without the session secret; WARN log when break-glass is configured.
- `GET /api/auth/providers` returns `{ providers, breakGlass }`; the SPA only renders the form when `breakGlass` is true.

**Tests.** `LocalGlassLoginTest` (`@QuarkusTest`, profile sets the hash): happy path 200 + cookie → `/api/user` resolves the bootstrap admin; wrong password → 401, no cookie; enabled provider row → new login 401 **and** existing cookie 401 **and** `breakGlass: false`. `LocalGlassDisabledTest`: unset hash → 401, `breakGlass: false`. `LocalGlassLoopbackTest`: unit-tests the loopback check with non-loopback addresses (RestAssured always connects over loopback, so the negative path can't be an integration test).

**Manual verification.** With no `oidc_providers` rows and the hash set: `curl -i -d 'username=admin&password=...' http://localhost:8080/api/auth/local` → 200 + `state-db-glass` cookie; same call against the container's non-loopback interface (`curl http://$(hostname -i):8080/...`) → 401; insert an `enabled=true` provider row → 401 with the old cookie too.

**Addendum — bootstrap token removed.** With break-glass in place, the env-provisioned bootstrap API token (Slice 4.5) became a redundant second credential for the same identity and was removed. The break-glass session carries a `JsonWebToken` principal, so the existing `/api/auth/cli-login` browser flow works off it unchanged: the pre-OIDC CLI story is break-glass sign-in → `pulumi login` → approve → 24h token, and the only bootstrap env left is org seeding (`STATEDB_BOOTSTRAP_ORG_NAME`, optional display name). Deliberately **not** unified further: the glass password never authenticates `/pulumi/api/*` directly — low-entropy secret, bcrypt on a hot path, no revocation story. The workbench seeds its static non-interactive CLI token straight into `api_tokens` via `seed-console.sh`.

---

### Slice 26 · Console token list + revoke + `last_used_at` bookkeeping ✅

**Scope**

- `GET /api/tokens`: caller's tokens (id, description, kind, expires_at, last_used_at, created_at). Admins see all when `?all=true`.
- `DELETE /api/tokens/{id}`: owner OR admin (via Cedar `Token::Revoke`).
- Update `api_tokens.last_used_at` from `TokenAuthFilter` on every successful Pulumi-surface authenticated request. Async write (fire-and-forget on a small bounded executor) to keep request latency unaffected.

**Tests.** List shows own tokens only by default. Revoke-then-replay returns 401 on the next Pulumi request. `last_used_at` advances on use.

**Manual verification.** Mint a token via `psql` insert (until Slice 27), use it once, hit `/api/tokens`, see `last_used_at` populated.

---

### Slice 27 · CLI token mint via OIDC re-auth ✅

**Status.** Shipped in a different shape: CLI SSO auth with credential
scopes and single-use browser approvals (`console-api/auth/CliLoginResource`
and friends), rather than the `prompt=none` OIDC re-auth mint described
below. The scope below is kept for the record; treat the implementation as
the source of truth.

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

### Slice 27.5 · Console sign-out ✅

**Scope**

- `GET /api/auth/logout` (public, added to the `console-public` HTTP permission): a top-level browser navigation that expires the console session cookies and 303-redirects to `/console/login`. Idempotent — an anonymous hit redirects without an SSO challenge.
- Expires both session cookies: the quarkus-oidc cookie (name prefix `q_session`, any tenant suffix/chunk, path `/api/`) and the break-glass form-auth cookie (`state-db-glass`, path `/`). Cookie names/paths are read from the incoming request; deletion cookies match name + path.
- SPA "Sign out" menu item navigates to the endpoint and clears the `state-db-selected-org` localStorage key.
- Local sign-out only; no RP-initiated (`end_session_endpoint`) IdP logout. Sessions are stateless encrypted cookies, so logout drops the browser cookie but cannot revoke a copied value — see `auth.md`.

**Out of scope.** Server-side session/token-state storage (`quarkus-oidc-db-token-state-manager`); RP-initiated SSO logout; any CLI-side logout (`pulumi logout` is client-only and never calls the backend).

**Tests.** OIDC session → 303 to `/console/login`, `Set-Cookie` expires `q_session*` at path `/api/` (Max-Age=0). Break-glass session → expires `state-db-glass` at path `/`. Anonymous caller → still 303, no cookies emitted.

---

### Slice 28 · `/api/workspaces` console read ✅

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

### Slice 29.5 · Org lifecycle + membership admin (S11 + S17, full-stack)

**Scope**

- **New Cedar action `org:ManageMembers`** — `schema.cedar` (`appliesTo { resource: [Org] }`, sibling of the `serviceaccount:*` org actions), permitted to `Role::"owner"` in `owner.cedar`; `admin.cedar`'s unconstrained permit already covers it. Extends the Slice 19 coverage gate (every action has a policy) and the Slice 20 action matrix.
- **Tenant lifecycle (admin-only, `AdminFilter`)** — `GET/POST /api/admin/orgs` and `DELETE /api/admin/orgs/{id}`. Create validates name against the frozen reserved list (§2.2 of `console-ux.md`) and the V011 name grammar; adds **no** members. Delete is **empty-only** — rejects (409) if any stack exists in the org, mirroring the Slice 10 stack-delete guard; the `organizations` cascade is never the deletion path for populated tenants. List DTO carries `members` / `stacks` count aggregates.
- **Membership CRUD (owner-or-admin)** — `GET /api/orgs/{id}/members` + `POST/PATCH/DELETE /api/orgs/{id}/members`, all `@Authorize(action = "org:ManageMembers", loader = OrgEntityLoader)`. Writes go through a command service in `persistence/identity` (`OrgMembershipWriter`-style), humans only (`role IN owner/member/viewer/guest`; `service` rows are S13's, excluded). **Last-owner guard is a server-side invariant** — reject demote/remove that would leave an org with zero owners (independent of the S17 UI soft-block). Guest-grant listing per member reads `policy_grants` (read-only; grant *minting* stays on the S7 shares API, `stack-sharing.md` §7).
- **Console** — S11 (`/console/admin/orgs`: admin-only create + empty-only delete)
  and S17 (`/console/admin/orgs/{org}`: add/role/remove + read-only guest grants).
  The S17 detail route deliberately sits outside the site-admin-only route
  wrapper and admits either an owner of that org or a site admin; other roles
  return to `/console/{org}`. The org-scoped stacks home remains stacks-only and
  shows eligible users a "Manage members" action. The org switcher enumerates
  **all** orgs when `user.siteAdmin` (sourced from `GET /api/admin/orgs`) and
  navigates to `/console/{org}`; an admin can continue through its management
  action, while the S11 members count links directly to the S17 detail.

**Tests.** New action passes the coverage gate. Empty-only org delete: 409 with a stack present, 200 when empty. Last-owner: server rejects removing/demoting the final owner. Owner of org A gets 403/404 managing org B; site admin manages any org without membership. Reserved-name and grammar rejection at create. Add-as-`guest` then a Slice-A share (`stack-sharing.md`) makes exactly that stack visible.

**Manual verification.** As a non-admin owner, open "Manage members" from
`/console/{org}`, add a `guest`, and change a member's role; confirm a non-owner
cannot open the detail and the owner cannot touch another org. As a site admin,
create an org (zero members), switch to its scoped stacks home, follow "Manage
members", and assign its first owner; also confirm the S11 members-count link
opens the same detail page.

---

## Phase 9 — Validation and forward compat

### Slice 30 · Fixture-driven E2E flow ✅

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

> The full Tofu implementation spec — protocol contract, settled design
> decisions, schema, and the Slice 31.1–31.7 breakdown that follows this
> slice — lives in `design/tofu-backend.md`. Read it before starting.

**Scope**

- New module `modules/tofu-api/` with route prefix `/tofu/...`.
- Adapter SPI: `WorkspaceAdapter` interface in `persistence/workspace/`; Pulumi adapter wired in `pulumi-api/`; Tofu adapter is a stub returning 501 for every route.
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
- Add `LICENSES/LicenseRef-statedb.txt` (a copy of `LICENSE`) so SPDX-aware scanners can resolve the `LicenseRef-`.

**Out of scope.** Headers in other languages — none yet; revisit when the web console adds JS/TS.

**Tests.** Add a deliberately unheaded source file in a scratch commit; `mvn verify` fails; revert.

**Manual verification**

```
./dev -- modules/mvnw -f modules/pom.xml license:check
```

---

### Slice 34 · License-key verifier

**Why now.** The Free Edition Use Limit needs a way to recognize a paid commercial license. Decision: signed Ed25519 token carrying `{ sub, exp, max_stacks }`, verified offline against a public key bundled in the binary; the private key is held by the licensor and never ships.

**Scope**

- New module `modules/licensing/`:
  - `LicenseKey` record + parser.
  - `LicenseKeyVerifier` using a bundled Ed25519 public key (classpath resource).
  - Boot-time load from `STATEDB_LICENSE_KEY` env var, falling back to `/etc/state-db/license.key`.
  - `LicenseState` `@ApplicationScoped` CDI bean exposing `{ active, maxStacks, expiresAt, subject }`. Default with no key: `{ active: false, maxStacks: 10 }`.
- Boot log line stating Free Edition or commercial subject + expiry.

**Out of scope.** Online key revocation. AWS Marketplace metering. Customer-facing UX in the console. The key-minting CLI (held by the licensor only) is a separate follow-up — the verifier ships first.

**Tests**

- Valid key parses and verifies.
- Expired key is rejected with a clear error.
- Wrong-signature key is rejected.
- Missing key leaves `LicenseState.active=false, maxStacks=10` (Free Edition default).

**Manual verification.** Set `STATEDB_LICENSE_KEY=<test-key>` and inspect the boot log; expect the active license fields to be printed.

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
./dev -- modules/mvnw -f modules/pom.xml package
# Confirm THIRD-PARTY-NOTICES.txt is present in the built JAR.
```

---

## PR cadence reminder

For every slice:

1. Migration + entity mappings (if needed).
2. Repository / service / domain logic.
3. JAX-RS resource + filter wiring.
4. Tests — at minimum one happy path and one failure path.
5. A `Manual verification` line in the PR description that the reviewer can paste into a shell.

When a slice changes wire shape, the contract harness must be the thing that catches a regression — not someone running `pulumi up` by hand.
