# Database model

The [Flyway migrations](../modules/persistence/src/main/resources/db/migration/)
define the database, and [schema.sql](schema.sql) shows the resulting schema.
This document explains the relationships and invariants that the DDL alone does
not make obvious.

state-db requires PostgreSQL 14 or newer built with LZ4 support. The
`beforeMigrate.sql` callback checks this on every startup, before any migration
runs, so an unsupported server fails with a clear error instead of stopping
half-migrated.

## Conventions

- The application generates every primary key as a UUIDv7
  (`@UuidGenerator(style = TIME)` on `BaseEntity`). Organizations, projects, and
  stacks can be renamed, so relationships never key on names, and the time-ordered
  prefix keeps inserts local on append-heavy tables such as `journal_entries`,
  `update_events`, and `snapshots`. Database defaults never generate UUIDs.
- PostgreSQL does not index foreign keys automatically. Each foreign key that no
  other index covers has an explicit index for joins and cascades.
- Hibernate maintains `updated_at`. The column default covers direct inserts but
  does not change an existing row.
- Every mutable entity has its own UUID key. A one-to-one child such as
  `stack_state` or `stack_locks` uses a unique foreign key with `ON DELETE CASCADE`
  rather than sharing its parent's key. `org_members` is the only composite-key table.

## Identity and access

| Table | Role |
| --- | --- |
| `users` | Global identities, including OIDC users and service accounts |
| `organizations` | Tenant boundary |
| `org_members` | User role within one organization |
| `api_tokens` | Hashed credentials, kind, expiry, revocation, and access profile |
| `oidc_providers` | Runtime OIDC provider configuration |
| `policy_grants` | Cedar template links for organization-wide or per-stack access |
| `service_secrets_keyset` | Singleton Tink keyset for the `service` secrets provider |

A user is identified by OIDC issuer and subject (`UNIQUE (iss, sub)`). Email is
descriptive only. A user can hold a different role in each organization. Site
administrator status comes from the `STATEDB_ADMIN_SUBJECTS` configuration, not
from a column.

A check constraint ties token kind to scope. OIDC tokens have no organization ID,
and authorization resolves the user's current membership in the target
organization on each request. Service-account tokens carry exactly one
organization ID and require a live membership there. Removing a member therefore
revokes access without deleting token rows; no cascade runs from `org_members` to
`api_tokens`. Every token has a mandatory `expires_at`.

`api_tokens.access_profile` is `deploy` for every OIDC token. A service-account
token can instead carry `stack_lifecycle`, which adds stack creation and deletion
for migration and bootstrap workflows. The profile belongs to the credential
rather than the membership. [Authentication and authorization](auth.md) describes
the policy layer.

The service keyset is generated during application bootstrap by
`ServiceSecretsKeysetSeeder`. Flyway never generates secret key material.

## Stack identity

```text
organization → project → stack
organization → workspace → stack
```

A project groups Pulumi stacks. A workspace is the tool-neutral inventory
identity the console lists; each Pulumi stack has exactly one. The workspace kind
constraint reserves `tofu`, but no OpenTofu implementation exists.

`stacks.org_id` exists so composite foreign keys can keep a stack's project and
workspace in the same organization. Stack names are unique within a project.
Organization, project, and stack names follow the grammar in `NameGrammar`, which
the database repeats as check constraints. Deleting a stack deletes its workspace
row and relies on cascades for every child table.

Stack visibility is `internal` or `private`. The SQL read predicate in
`StackListFinder` mirrors the Cedar read policy so listing and detail
authorization agree.

`stacks.active_update_id` identifies the update that holds the stack, and
`stacks.version` counts applied updates. Dry runs have update records but do not
advance the version. `stack_state` holds the journal position and current
snapshot pointer.

## Updates and leases

| Table | Role |
| --- | --- |
| `stack_updates` | Run metadata, attribution, status, version, and journal boundaries |
| `stack_locks` | One renewable update lease per stack |
| `stack_state` | Mutable state head and the shared row lock for state changes |

Dry-run updates use version zero, and the unique index on `(stack_id, version)`
excludes them. A partial unique index on `(stack_id, client_request_id)` makes
update creation idempotent. The `trg_update_status_guard` trigger rejects any
change away from a terminal status and any return to `in-progress`. Composite
foreign keys on `(update_id, stack_id)` bind leases, journal rows, and events to
an update on the same stack.

`stack_locks.stack_id` is unique, which lets lease acquisition and expired-lease
takeover run as one `INSERT ... ON CONFLICT` statement. The table stores only a
SHA-256 hash of the lease token, plus the expiry, a hard `max_expires_at` ceiling,
the heartbeat, and the update ID. A lease row alone does not exclude compaction;
that exclusion comes from the `stack_state` row lock and `active_update_id`.

## Journaling

`journal_entries` stores the reducer fields as columns and the rest of each entry
as JSONB. Pulumi's `sequenceID` restarts for every update, so the server keeps it
as `wire_sequence_id` and derives a stack-global replay order. On an update's
first batch, the appender copies the current `stack_state.journal_seq` into
`stack_updates.start_journal_seq`, and every entry gets:

```text
sequence_id = start_journal_seq + wire_sequence_id
```

The CLI assigns wire IDs from one counter, but parallel resource workers can send
them out of order, so a later batch may carry lower IDs than an earlier one.
Because `sequence_id` is a function of the wire ID rather than of arrival order,
`ORDER BY sequence_id` always reproduces the CLI's order. The server does not
require wire IDs to be contiguous or increasing across batches. Unique constraints
cover `(update_id, wire_sequence_id)` and `(stack_id, sequence_id)`.

`stack_state.journal_seq` is the next sequence position, not a row count. The
appender advances it to at least the batch's largest sequence plus one, holding
the `stack_state` row lock for the whole batch. A retry with identical content is
a no-op; different content under an existing wire ID returns 409.

### Journal entry kinds

| Kind | Name | Replay effect |
| --- | --- | --- |
| 0 | Begin | Records `payload.operation` as pending under its operation ID |
| 1 | Success | Clears the pending operation, applies the index fields, and appends `payload.state` when present |
| 2 | Failure | Clears the pending operation without adding state |
| 3 | RefreshSuccess | Replaces or removes the indexed resource with the refreshed state |
| 4 | Outputs | Replaces the indexed stack resource in place with its final outputs |
| 5 | Write | Replaces the working deployment with `payload.newSnapshot` |
| 6 | SecretsManager | Sets the deployment's secrets provider from `payload.secretsProvider` |
| 7 | RebuiltBaseState | Promotes the replayed deployment to the working base and resets replay state |

Replay fails on any other kind.

The six index fields do not refer to journal rows. Each `*Old` field
(`removeOld`, `deleteOld`, `pendingReplacementOld`) is an index into the base
deployment's `resources` array. Each `*New` field is an operation ID for a
resource added earlier in the same update. The
[replayer](../modules/persistence/src/main/java/app/statedb/persistence/stack/JournalReplayer.java)
owns the detailed rules, which follow Pulumi's reducer.

## Snapshots and history

`snapshots` stores a deployment checkpoint, a stack-local snapshot version, the
journal boundary it covers (`compacted_from_journal_seq`), and a resource count.
`stack_state.base_snapshot_id` points to the current base, and
`stack_state.base_snapshot_version` is the latest snapshot version. Snapshot
version and applied stack version are separate counters. The foreign key from
`stack_state` keeps the live base from being deleted.

`compaction_jobs` is the snapshot work queue. A partial unique index allows at
most one pending or running job per stack, so repeated enqueues coalesce.

Most snapshots are caches of journal replay. Import and rename snapshots instead
start a new state lineage: they replace state without advancing the journal, so
their content is not the fold of any journal prefix. Those operations stamp
`stack_updates.base_snapshot_version` with the snapshot they install.

Snapshot pruning keeps the live base, every lineage snapshot, the newest
snapshots, and spaced older snapshots. Journal rows are never deleted, so any
pruned snapshot can be rebuilt by replay. The service has no journal retention.

## Events and reports

`update_events` stores engine events with their sequence, kind, severity, and JSON
payload. After a terminal run's grace period, its events move into one
`update_event_archives` row as a zstd-compressed blob. `summary` events also stay
as rows, because frequent history reads must not decompress an archive. The reader
merges both forms behind one interface, so archiving changes storage, not the
history users see.

Journal payloads, event payloads, and snapshot checkpoints use LZ4 TOAST
compression. Archive blobs use external TOAST storage, because compressing
zstd output again wastes CPU.

GitHub integration uses `vcs_app_registrations`, `vcs_auth_flows`,
`vcs_connections`, and `report_deliveries`. The delivery queue has no foreign key
to update history. [GitHub PR reports](pr-enrichment.md) explains its lifecycle.

## Correctness invariants

Database constraints enforce identity uniqueness, tenant relationships, sequence
uniqueness, and valid stored states. Application transactions enforce the rules
that span several tables:

- Update completion records the journal boundary, releases the lease, and clears
  `active_update_id` in one transaction.
- Journal appends, update completion, lease acquisition, compaction, import,
  rename, and stack deletion all take the `stack_state` row lock.
- Compaction refuses to snapshot while `active_update_id` is set.
- Import and rename keep the lineage snapshots that historical exports need.
- Membership changes keep at least one owner per organization, and access follows
  live role checks.
- Run-once startup tasks use named advisory locks and stay idempotent.

The [state lifecycle](pulumi-state-lifecycle.md) explains these transactions.
The [migration contract](migrations.md) explains how schema changes reach a
running deployment.
