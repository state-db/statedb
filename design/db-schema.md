# state-db: PostgreSQL Schema Design

## Context

This schema supports the Wave 1 HTTP state backend for Pulumi and the identity/membership tables that the Wave 2 console needs to authenticate users and mint API tokens. The Pulumi CLI speaks a REST-ish API when `PULUMI_BACKEND_URL` is set to a custom HTTP endpoint; this service implements that protocol. Wire-shape ground truth is the recorded fixture catalog at `tools/pulumi/recorder/fixtures/` (Pulumi CLI v3.242.0 against a stub backend); when fixtures and Pulumi documentation disagree, fixtures win.

The design must support the `delta-checkpoint-updates` journaling protocol (capability version 1), which is what `journalVersion: 1` in the start-update request activates. Without journaling, the checkpoint endpoints can carry full or delta snapshots; with journaling enabled, the Pulumi CLI sends gzip-compressed `{"entries":[...]}` batches to `/journalentries`.

Forward compatibility for OpenTofu: the `workspaces` parent table provides type-erased identity from day one. Pulumi stacks are 1:1 with a `kind='pulumi'` workspace today. When Tofu support lands in Wave 3, a separate `tofu_workspaces` extension table joins to the same `workspaces` parent, leaving Pulumi-specific state tables untouched.

---

## Key Design Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | **UUIDv7 PKs, generated app-side by Hibernate** | Orgs, projects, stacks can all be renamed, so FK relationships must not break — UUIDs survive renames. UUIDv7's millisecond-timestamp prefix preserves insert locality on append-heavy tables (`journal_entries`, `update_events`, `snapshots`), avoiding the page-split churn of random v4. Generation in the app (Hibernate `@UuidGenerator(style = TIME)`) keeps the schema portable across Postgres versions and makes IDs available pre-insert for correlation/logging. |
| 2 | **No denormalized `stack_ref`; resolve `(org, project, stack)` via JOIN at read time** | The 3-table JOIN is microseconds on small FK-indexed parent tables. Denormalizing would buy nothing at our scale and cost three rename triggers, a PL/pgSQL helper, and a `@Generated` round-trip on every stack write. Pulumi-API response strings of the form `org/project/stack` are assembled in the DTO layer from data the request already loaded. |
| 3 | **Checkpoint as JSONB** | Resource structure varies wildly per provider; Pulumi expects exact JSON back |
| 4 | **Separate `snapshots` + `journal_entries`** | Different access patterns: snapshots are infrequent large writes; journal entries are frequent small appends |
| 5 | **`stack_state` pointer table** | Single source of truth for current snapshot + journal position per stack |
| 6 | **Journal replay owns pending operations** | `deployment.pending_operations` is reconstructed from ordered journal entries; the pre-release SQL projection was write-only and could become stale |
| 7 | **Lock expiry + heartbeat** | Stale lock recovery when client crashes; bearer hash must match to unlock |
| 8 | **Immutable journal entries** | Append-only matches Pulumi journaling semantics; compaction creates new snapshot |
| 9 | **Async compaction job queue** | Compaction is slow; shouldn't block API responses; can be retried |
| 10 | **`api_tokens.kind` discriminator (`oidc` vs `service_account`)** | OIDC-derived tokens are short-lived (24h default); each mint requires a fresh OIDC handshake (`prompt=none` re-auth), so IdP disablement propagates within the TTL through OIDC primitives alone. Service-account tokens are long-lived for CI/CD. Every token has a mandatory `expires_at`, enforced by the database. Both share the wire format (`Authorization: token <tok>`) — the distinction is policy, not protocol. |
| 11 | **`workspaces` parent table** | Type-erased identity for cross-tool listing (Pulumi today, Tofu in Wave 3); 1:1 with type-specific row; deletion cascades down the type-specific tree |
| 12 | **`update_events` first-class** | Event payloads from `events/batch` are decoded and queryable per update; BRIN index on `created_at` enables cheap time-bounded vacuum |
| 13 | **OIDC users + `org_members`** | Console authn via configurable OIDC providers; org membership decoupled from token ownership; admin set is env-driven, not stored in DB |
| 14 | **Surrogate UUIDv7 PK on every mutable entity** | All mutable entities carry their own `id UUID PRIMARY KEY` and extend `BaseEntity` / `TimestampedEntity`. 1:1 lifecycle binding to a parent is expressed via `UNIQUE FK + ON DELETE CASCADE`, never a shared PK. Keeps the entity layer uniform (no `@MapsId` edge cases) at the cost of one extra UUID column on `stack_state` / `stack_locks`. `org_members` remains the only exception — composite-PK association tables fit nothing else. |

---

## Tables

| Table | Purpose |
|-------|---------|
| `organizations` | Top-level tenant boundary |
| `projects` | Belongs to org, renameable |
| `stacks` | Belongs to project, renameable |
| `api_tokens` | Authenticates Pulumi CLI requests (hashed); `kind` separates short-lived OIDC tokens from long-lived service-account tokens |
| `snapshots` | Full DeploymentV3 checkpoint at compaction points |
| `stack_state` | Per-stack pointer to the current snapshot and journal position |
| `stack_updates` | One row per operation (preview/update/destroy/refresh/import) |
| `journal_entries` | Append-only delta entries per update |
| `stack_locks` | Distributed lock with expiry and heartbeat |
| `compaction_jobs` | Background job queue for snapshot compaction |
| `oidc_providers` | Configurable OIDC issuers; env-bootstrapped at startup, additional providers manageable via console |
| `users` | OIDC-authenticated console users; natural key `(iss, sub)` |
| `org_members` | User → org membership with role (owner/member/viewer/service/guest) |
| `workspaces` | Type-erased parent for state-managing entities (Pulumi stacks today, Tofu workspaces in Wave 3) |
| `update_events` | Decoded events from `events/batch`; queryable per update; BRIN-indexed for time-based vacuum |
| `update_event_archives` | Compressed event stream for one terminal update |
| `policy_grants` | User grants for stack-specific read and write access |
| `service_secrets_keyset` | Singleton serialized Tink `AES256_GCM` keyset for the `service` secrets provider; seeded by `BootstrapProvisioner` on first start (see [`secrets.md`](secrets.md)) |

---

## Current SQL Schema

[`schema.sql`](schema.sql) is the generated current-state view. Flyway migrations remain the executable schema authority.

Run `./dev schema` after a schema change. The final verification fails when the generated file is stale.

Keep design rationale and invariants in this document. Do not copy generated DDL into this document.

### Schema rationale annotations

- The application generates every UUID primary key. Database defaults do not generate UUID values.
- PostgreSQL does not create indexes for foreign keys. Each uncovered foreign key has an explicit index for cascades and joins.
- Hibernate maintains `updated_at`. The database default protects direct inserts, but it does not update an existing row.
- `stacks.org_id` lets a composite foreign key prevent a stack from joining a project and workspace from different organizations.
- `stack_locks.stack_id` is unique. This index makes lock acquisition and expired-lock replacement one atomic upsert.
- The database stores only a SHA-256 hash of each update lease. A database read does not reveal a usable lease.
- `stack_updates.dry_run` rows use version zero. Only applied updates participate in the per-stack version index.
- Stack visibility uses `internal` and `private`. The SQL read predicate mirrors the Cedar authorization rule.
- Event archives preserve the complete ordered stream. Summary events remain as rows because frequent list reads must not decompress an archive.
- Archived event data uses external TOAST storage. A second compression pass wastes CPU on an already-compressed value.
- The service keyset starts during application bootstrap. Flyway never generates secret key material.

---

## API → Schema Mapping

| Pulumi API Call | Tables Written | Tables Read |
|----------------|----------------|-------------|
| `GET /api/user` | — | `api_tokens`, `organizations` |
| `GET /api/user/organizations/default` | — | `organizations` |
| `GET /api/user/stacks?project=X` | — | `stacks`, `projects`, `organizations` |
| `POST /api/stacks/{org}/{project}` | `workspaces`, `stacks`, `stack_state` | `projects`, `organizations` |
| `GET /api/stacks/{org}/{project}/{stack}` | — | `stacks`, `projects`, `organizations` |
| `DELETE /api/stacks/{org}/{project}/{stack}` | DELETE `workspaces` (cascades to `stacks` and all state) | `stack_state` (check resources == 0) |
| `GET /api/stacks/{org}/{project}/{stack}/export` | — | `stack_state` → `snapshots` + `journal_entries` |
| `POST .../update\|preview\|refresh\|destroy` | INSERT `stack_updates` | `stacks` |
| `POST .../update/{updateID}` | INSERT `stack_locks`, UPDATE `stacks.active_update_id` | `stack_updates` |
| `PATCH .../update/{updateID}/journalentries` | INSERT `journal_entries`, UPDATE `stack_state.journal_seq` | `stack_locks` (validate token) |
| `PATCH .../update/{updateID}/checkpoint`, `/checkpointdelta`, `/checkpointverbatim` | 501 in Wave 1 when journaling is negotiated | `stack_locks` (validate token) |
| `POST .../update/{updateID}/events/batch` | INSERT `update_events` (one row per decoded event) | `stack_locks` (validate token) |
| `POST .../update/{updateID}/complete` | UPDATE `stack_updates.status`, DELETE `stack_locks`, UPDATE `stacks.version` + `active_update_id = null` | `stack_updates` |
| `POST .../{stack}/encrypt`, `/decrypt`, `/batch-decrypt` | — | `service_secrets_keyset` (singleton master key, loaded at startup); per-request HKDF derivation against the resolved `stacks.id`. Full design: [`secrets.md`](secrets.md). |

---

## Journal Entry Payload Structures

Each entry uses the same row shape. The wire discriminator is numeric
`kind`, not a text enum:

```
0 Begin            → payload.operation = { resource: ResourceV3, type: string }
1 Success          → optional payload.state = ResourceV3; remove_* fields may be set
2 Failure          → clears a pending operation; payload is usually empty
3 RefreshSuccess   → payload.state = ResourceV3; is_refresh and remove_old are commonly set
4 Outputs          → payload.state = synthetic pulumi:pulumi:Stack resource
5 Write            → payload.newSnapshot = DeploymentV3
6 SecretsManager   → payload.secretsProvider = AppSecretsProvidersV1; operation_id is 0
7 RebuiltBaseState → dependency-rebuild marker; payload is usually empty
```

The index/operation fields are six distinct nullable int64s:

| Field | Replay meaning |
|-------|----------------|
| `removeOld` | Index into the base snapshot's `resources` list; skip that base slot during materialisation. |
| `removeNew` | Operation ID for a resource created earlier in the current replayer; skip the matching new resource. |
| `deleteOld` | Index into the base snapshot's `resources` list; mark that base resource with `delete: true`. |
| `deleteNew` | Operation ID for a resource created earlier in the current replayer; mark that new resource with `delete: true`. |
| `pendingReplacementOld` | Index into the base snapshot's `resources` list; mark that base resource with `pendingReplacement: true`. |
| `pendingReplacementNew` | Operation ID for a resource created earlier in the current replayer; mark that new resource with `pendingReplacement: true`. |

In short: `*Old` fields are base-resource indexes; `*New` fields are operation
IDs. Boolean fields such as `isRefresh` are omit-default-false on the wire.

Pulumi's wire `sequenceID` resets for each update. It is stored as
`wire_sequence_id`; the server-assigned `sequence_id` is the stack-global
replay order used by export and compaction.

The CLI assigns `wire_sequenceID` from a single atomic counter at entry-creation
time, but concurrent resource workers (under `--parallel > 1`, the default) may
push entries to the journal channel out of counter order. Across PATCHes a
later batch can therefore carry a wire ID lower than the previous batch's max.
To keep `ORDER BY sequence_id` consistent with the CLI's intended replay order,
the server records a per-update offset on `stack_updates.start_journal_seq` —
captured atomically on the update's first PATCH as the current value of
`stack_state.journal_seq` — and assigns each entry's server `sequence_id` as
`start_journal_seq + wire_sequenceID`. Replay then sorts by `sequence_id` and
gets back the wire order regardless of arrival order. Duplicate wire IDs are
caught by `UNIQUE (update_id, wire_sequence_id)` and surface as a 409.

---

## State Reconstruction (for `GET /export`)

```
1. Load stack_state WHERE stack_id = $id
2. Load snapshot WHERE id = stack_state.base_snapshot_id, or start from an
   empty DeploymentV3 if there is no base snapshot yet
3. SELECT FROM journal_entries
      WHERE stack_id = $id
        AND sequence_id > COALESCE(snapshot.compacted_from_journal_seq, 0)
      ORDER BY sequence_id ASC
4. Replay with a port of Pulumi's journal-apply logic, not a hand-rolled
   simplification:
     Begin              → record a pending operation when operation.resource exists
     Success            → clear pending op; apply removeOld/removeNew/delete*/pendingReplacement*;
                          append state when present
     Failure            → clear pending op without appending state
     RefreshSuccess     → replace/remove the base-indexed or operation-ID-indexed resource
                          without appending to the new resource list
     Outputs            → replace the indexed synthetic pulumi:pulumi:Stack resource in place
     Write              → replace the whole working deployment with newSnapshot
     SecretsManager     → update deployment secretsProvider
     RebuiltBaseState   → promote the materialised deployment to the working base
5. Rebuild stale dependency references after refresh, normalize URN aliases, and
   return the UntypedDeployment shape:
     { version: 3|4, deployment: <reconstructed DeploymentV3>, features?: [...] }
```

---

## Correctness Invariants

The tables below split what the schema enforces from what application code must guarantee.

### Handled by schema

| # | Concern | Mechanism |
|---|---------|-----------|
| 1 | Orphaned records | `ON DELETE CASCADE` on all child tables; `ON DELETE RESTRICT` on `stack_state.base_snapshot_id` (prevents deleting active base snapshot); `ON DELETE SET NULL` on `compaction_jobs.new_snapshot_id`; default `NO ACTION` on `stack_locks` composite FK (forces app to DELETE the lock before deleting its parent update) |
| 2 | Cross-entity mismatch | Composite FK `(update_id, stack_id) → stack_updates(id, stack_id)` on `journal_entries`, `update_events`, and `stack_locks`; DB rejects rows whose `update_id` belongs to a different stack |
| 6 | Duplicate singletons | `stack_state` and `stack_locks` both carry `UNIQUE (stack_id)` (the surrogate PK is the row's own `id`) |
| 7 | Invalid status transitions | `trg_update_status_guard` rejects any UPDATE that tries to leave a terminal status or revert to `in-progress` |
| 11 | Stale current-state reads | Reads assemble from `base_snapshot_id` + journal replay past `compacted_from_journal_seq`; no separate read cache can become stale |
| 13 | Idempotent update creation | `client_request_id TEXT` + partial unique index `(stack_id, client_request_id) WHERE client_request_id IS NOT NULL`; app uses `INSERT ... ON CONFLICT DO NOTHING` |
| 15 | Orphan/cleanup | Cascades cover the full tree; `stack_state.base_snapshot_id ON DELETE RESTRICT` prevents silent data loss from premature snapshot deletion |

### Application invariants (schema provides mechanism; app must use it correctly)

| # | Concern | Required app behavior |
|---|---------|----------------------|
| 5 | Multi-table partial failure | Every write that touches multiple tables must run inside a single transaction (`BEGIN` / `COMMIT`). Notably: create update + set `stacks.active_update_id`; complete update + delete lock + increment `stacks.version`. |
| 8 | Lost update / version races | `stacks.version` increment and `stack_updates.version` assignment must use `SELECT ... FOR UPDATE` on the `stacks` row. Since the lock prevents concurrent writers on the same stack, races only arise between the lock-acquire step and version increment — serialize these in the same transaction. |
| 9 | Journal sequence ordering | Treat `journal_entries.sequence_id` as the server-assigned stack-global replay order, computed as `stack_updates.start_journal_seq + wire_sequenceID`. The Pulumi wire `sequenceID` resets per update. On the update's first PATCH, atomically capture the offset (`UPDATE stack_updates SET start_journal_seq = (SELECT journal_seq FROM stack_state ...) WHERE id = ? AND start_journal_seq IS NULL`); every subsequent PATCH for that update reads the captured offset. The CLI assigns wire IDs from a monotonic counter but parallel resource workers may push them to the channel out of order, so do not enforce contiguity or across-PATCH monotonicity on `wire_sequence_id` — uniqueness is the only invariant (`UNIQUE (update_id, wire_sequence_id)` catches duplicate retries as 409). Insert every entry from one `{"entries":[...]}` batch and advance `stack_state.journal_seq` to `GREATEST(journal_seq, offset + max(wire_sequenceID) + 1)` in a single transaction holding `SELECT ... FOR UPDATE` on `stack_state` — the lock serializes appenders across tasks so the offset capture is consistent. |
| 10 | Lease token validation | Lease-scoped operations (`PATCH .../journalentries`, `PATCH .../checkpoint*`, `POST .../events/batch`, `POST .../complete`, `POST .../renew_lease`, `DELETE .../update/{id}`) carry `Authorization: update-token <leaseTok>` instead of `token <apiTok>`. Verify by hashing `<leaseTok>` (SHA-256 of its canonical UUID string) and matching against `stack_locks.lock_token_hash` AND `stack_locks.expires_at > now()`. Reject with 409 if mismatched or expired. Inside lease-scoped mutating transactions, re-check the lease with `FOR UPDATE` + `clock_timestamp()` after taking the primary row lock — the filter's check cannot prevent expiry or cancel during the dispatch gap. Renewal rotates the bearer. The lease-token and api-token paths are independent and never overlap on the same request. |
| 12 | Compaction races | The compactor must hold a `SELECT ... FOR UPDATE` on `stack_state` for the duration of its read → compute → commit cycle. This blocks concurrent journal appends (which also lock `stack_state`) and ensures `to_journal_seq` remains consistent at commit time. |
| 14 | Tenant isolation | Every query that retrieves stacks or state must JOIN to `projects` / `organizations` (or filter by `stacks.project_id → projects.org_id`) to verify the org matches the authenticated token's `org_id`. Never accept a bare `stack_id` from external input without confirming ownership. |
| 17 | Workspace lifecycle | Stack creation must `INSERT workspaces` then `INSERT stacks` (with `workspace_id` set) inside one transaction. Stack deletion must `DELETE FROM workspaces` (which cascades). Never `DELETE FROM stacks` directly — the workspace row would be orphaned. |
| 18 | Token authorization scope | Every Pulumi CLI request must verify (a) the token's hash exists and is unexpired, (b) `api_tokens.org_id` matches the target stack's org, and (c) `org_members` row still exists for `(token.org_id, token.user_id)`. Removing a user from an org must invalidate their tokens for that org without deleting the row. |

### Non-issues

| # | Concern | Why not applicable |
|---|---------|-------------------|
| 16 | Portability | Deliberately Postgres-only: `JSONB`, `TIMESTAMPTZ`, `DEFERRABLE FK`, partial indexes, BRIN, `FOR UPDATE`. No abstraction layer is planned. Minimum server is PostgreSQL 14 with lz4 support, enforced by the `beforeMigrate` Flyway callback. |

---

## Gaps / Out of Scope (Wave 1)

| Feature | Note |
|---------|------|
| Stack rename (`POST .../rename`) | Single-row `UPDATE stacks SET name = ?`; lookups always resolve through the JOIN, so no derived data to maintain |
| History endpoint (`GET .../updates`) | Reads `stack_updates`; no new table needed |
| Stack config (`PUT .../config`) | Separate per-stack config storage not required for Wave 1 |
| Non-journal checkpoint writes | `/checkpoint`, `/checkpointdelta`, and `/checkpointverbatim` are real OpenAPI paths, but Wave 1 negotiates `journalVersion: 1` and returns 501 for those fallback paths |
| Custom KMS-backed secrets providers | Wave 1 must implement the Pulumi service secrets endpoints for `type: service`; provider-specific delegation (AWS KMS, Azure Key Vault, etc.) can layer on later. Service-managed encryption necessarily receives plaintext on `/encrypt` and returns ciphertext stored opaquely inside checkpoint JSONB. |
| OIDC identity linking | One user = one `(iss, sub)`. Linking multiple IdP identities to a single user (e.g., "I have Google and Okta accounts") is future work. |
| `statedb-cli` companion | A small CLI helper to automate the daily OIDC re-auth and write the resulting token into `~/.pulumi/credentials.json` (AWS-SSO-style). Wave 1 ships console-paste only; helper is later UX polish. |
| Tofu workspace extension | `workspaces` parent ships in Wave 1 so identity is stable from day one. Type-specific `tofu_workspaces` extension and Tofu state tables ship in Wave 3. |
| Event vacuum scheduler | `update_events` retention is enforced by an app-level job that deletes by `created_at`. The job itself (cadence, threshold) is config, not schema. |

---

## Verification (when implementing)

1. `pulumi login http://localhost:<port>` → validates `/api/user`, `/api/user/organizations/default`
2. `pulumi stack init dev` → `stacks` row inserted; `stack_state` row created; `(org, project, stack)` lookup returns the new stack
3. `pulumi up` → full update lifecycle: preview + actual `stack_updates`, `stack_locks`, `journal_entries`, `/encrypt`; `stacks.version` increments after the actual update; compaction installs a fresh base snapshot (with `resource_count`) after completion
4. `pulumi stack export` → reconstructs checkpoint from `stack_state` + `journal_entries`; diff against expected output
5. `pulumi destroy` → new `stack_updates` rows; exported `deployment.pending_operations` cleared; `stacks.version` increments
6. `pulumi stack rm` → all rows cascade-deleted; verify nothing remains
7. Concurrent `pulumi up` from two terminals → second returns lock conflict (409)
8. Duplicate `POST /update` with same `client_request_id` → returns existing row, no duplicate
9. `UPDATE stack_updates SET status = 'in-progress' WHERE status = 'succeeded'` → trigger raises exception
10. Rename org → subsequent `(new_org_name, project, stack)` lookups resolve correctly; no derived data needs rewriting
