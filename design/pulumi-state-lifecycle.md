# Pulumi state lifecycle

Pulumi sends state journals and engine events through separate endpoints. The
journal is the source of truth for reconstructing a deployment; events describe
a run to people. `stack_updates` frames each operation and links both streams to it.

The implementation lives in the `update`, `lock`, `journal`, `events`, and `stack`
packages under [persistence](../modules/persistence/src/main/java/app/statedb/persistence/).
Pulumi routes live in [pulumi-api](../modules/pulumi-api/src/main/java/app/statedb/pulumiapi/).

## The stack_state row lock

`stack_state` holds one mutable row per stack: the next journal sequence, the
current snapshot pointer, and the snapshot version. It is also the per-stack
serialization point. Lease acquisition, journal appends, completion, compaction,
import, rename, and stack deletion all take `SELECT ... FOR UPDATE` on this row
before they change sequence numbers, snapshot pointers, or the active update.
This keeps two appenders from allocating overlapping sequences, keeps compaction
from snapshotting while an append or a lease acquisition is in flight, and makes
completion read its journal boundary after every in-flight batch has committed.

Paths that also touch the lease row lock `stack_state` first and `stack_locks`
second. `JournalAppender` and `StackUpdateCompleter` share that order, so they
cannot deadlock.

Engine events take no `stack_state` lock. They carry no ordering invariant
against state, so event writes and archiving never contend with updates or compaction.

## Start and lease

The CLI first creates an update record, then starts that update to acquire its
lease. These are separate requests, and an update record alone does not reserve
the stack. A partial unique index on `client_request_id` makes a retried create
return the existing record.

`StackLockAcquirer` locks `stack_state`, then takes the lease with one
`INSERT ... ON CONFLICT (stack_id) DO UPDATE ... WHERE expires_at < now()`
statement. A live lease leaves the result empty and the caller returns 409. An
expired lease is replaced in place, and the same transaction marks the displaced
active update as failed and sets `active_update_id` to the new update.

Before it takes the lease, the acquirer waits up to `statedb.lock.compaction-wait`
(30 seconds by default) for a queued compaction job to finish, so back-to-back
updates cannot postpone compaction indefinitely. Correctness does not depend on
this wait; the worker refuses to snapshot during an update.

The raw lease token goes to the CLI once, and PostgreSQL stores only its SHA-256
hash. Lease-scoped requests use `Authorization: update-token <token>`; ordinary
requests use an API token.

The initial lease lasts `statedb.lock.lease-ttl` (five minutes by default), which
matches the CLI's own assumption: it renews after two and a half minutes whatever
expiry the server returns. Renewal rotates the token and clamps the requested
duration to between 30 seconds and the lease TTL. It never extends past
`max_expires_at`, which acquisition sets from `statedb.lock.max-lease-lifetime`
(24 hours by default). The lease lives in the database, so it does not depend on
a particular replica.

## Append a journal batch

The capabilities endpoint advertises `delta-checkpoint-updates` version 1, and
the CLI then requests `journalVersion: 1` when it starts an update. Journaling is
required: the server rejects recognized CLI versions older than 3.225.0 before it
creates an update, and the legacy checkpoint, checkpoint-delta, and
verbatim-checkpoint endpoints return 501.

The CLI sends batches to:

```text
PATCH /pulumi/api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
```

`JournalAppender` validates the whole batch before writing, locks `stack_state`,
and then revalidates the lease inside the write transaction. The HTTP filter has
already checked the token, but a concurrent completion or cancellation could
delete the lease after that check. Revalidation locks the lease row `FOR SHARE`
until commit and compares the expiry against `clock_timestamp()`, so no journal
entry can land past the update's recorded end. Appenders that share a lease do
not block each other on the share lock.

Each wire sequence maps to a stack-global sequence through a fixed per-update
offset, so arrival order does not determine replay order. A batch commits as one
transaction: a crash never leaves part of a batch behind, and a retry of a
committed batch is a no-op. A retry whose content differs returns 409. Dry runs
cannot append journal entries. [Database model](db-schema.md#journaling) gives the
sequence formula and the journal entry kinds.

## Complete or cancel

`StackUpdateCompleter` takes the same `stack_state` lock as journal writes. In one
transaction it records the terminal status and `end_journal_seq`, deletes the
lease, clears `active_update_id`, and, for applied updates, increments
`stacks.version`. The same transaction queues a compaction job and any eligible
GitHub report, so a rollback discards all of these together. If a concurrent
cancellation already ended the update, completion still releases the lease but
leaves the status and version alone.

Graceful cancellation and forced unlock run their own transactions. They delete
the lease, mark an in-progress update `cancelled`, and clear `active_update_id`
without discarding committed journal entries. A later export can therefore
contain pending operations from the interrupted run.

Nothing sweeps abandoned update records periodically. Takeover fails the update
that held the expired lease, but a record created before lease acquisition can
stay `in-progress` if its client disappears.

## Export

```text
GET /pulumi/api/stacks/{org}/{project}/{stack}/export
```

`StackAssembler` loads the current base snapshot, or an empty deployment before
the first snapshot, and streams journal rows after its boundary in `sequence_id`
order. It reads the pointer and the snapshot in one statement so a concurrent
pruner cannot leave the pointer resolving to nothing. The export uses deployment
version 3, or version 4 with a feature list when replay finds features such as
hooks or views.

`JournalReplayer` ports Pulumi's reducer rules and does not deduplicate resources
by URN. A refresh, for example, writes a second state row for an existing URN,
and the reducer replaces the base resource at the referenced index instead of
appending. At each update boundary the assembler materializes the deployment and
starts a new reducer with it as the base, because operation IDs are scoped to one
update.

A Begin entry with an `operation` payload records a pending operation. Success,
Failure, and RefreshSuccess for the same operation ID remove it. Operations that
remain unresolved appear in the exported `pending_operations` for CLI recovery.

The assembler reads journal rows as scalar projections rather than ORM entities,
but the assembled deployment still lives in memory as a JSON tree. Export cost
depends on both deployment size and replay length.

## Historical exports, import, and rename

`StackAssembler.assembleAt` reconstructs a completed applied version at its
`end_journal_seq`. It picks the newest retained snapshot at or below that boundary
and replays the remaining journal up to it, starting from the empty deployment
when no snapshot qualifies.

Position alone cannot choose the base. Import and rename install snapshots without
adding journal entries, so a later lineage's snapshot can share the target's
boundary. Those operations stamp `stack_updates.base_snapshot_version`, and the
earliest stamp after the requested version fences the lookup: only older
snapshot versions are candidates.

Import and rename are lease-less server-side operations. Each locks the `stacks`
and `stack_state` rows, refuses to run while `active_update_id` is set, installs a
new base snapshot, and records an already-succeeded update of kind `import` or
`rename`. Import accepts a version-3 deployment without feature flags and checks
the envelope, not full resource-graph integrity. Rename rewrites the URNs and
`service` secrets-provider state baked into the checkpoint, so decryption and the
next update see the new stack identity.

## Compaction and pruning

Compaction runs in `CompactionWorker`, outside the HTTP request path. Completion
enqueues a job with an upsert against the partial unique index, which allows one
pending or running job per stack. Workers poll every
`statedb.compaction.poll-interval` (10 seconds) and claim jobs with
`FOR UPDATE SKIP LOCKED`.

The worker locks `stack_state` and checks `stacks.active_update_id`. A snapshot
taken mid-update would let replayed entries land ahead of the provider they
reference, so while an update is active the worker returns the job to pending
without spending a retry. Lease acquisition takes the same row lock, so no update
can start between the check and the write. Otherwise the worker assembles the
deployment through the same `StackAssembler` path as export, strips stack traces,
writes a snapshot at the current `journal_seq`, and moves the base pointer in one
transaction.

The worker flushes the new pointer before pruning; otherwise the bulk delete could
trip the `stack_state` foreign key on the previous base. `SnapshotPruner` keeps:

- the live base and every import or rename lineage snapshot,
- the newest `statedb.compaction.prune.newest-snapshots` (10) snapshots,
- an older rung once either budget is spent since the last kept rung:
  `ladder-journal-spacing` (5,000 journal positions) or `ladder-update-spacing`
  (50 distinct update boundaries).

The two budgets bound the two costs of a historical export: rows replayed and
whole-deployment rebuilds, which happen once per update boundary crossed. The walk
starts from the oldest snapshot, so it reproduces the same rungs on every run.
Setting `newest-snapshots` to 0 disables pruning; setting either spacing to 0
disables that budget.

A failed attempt returns the job to pending until `statedb.compaction.max-attempts`
(3). A claim older than `statedb.compaction.claim-timeout` (5 minutes) returns to
the queue, or fails once the job has used its attempts.

Journal rows are never pruned, so historical correctness does not depend on any
derived snapshot. Lineage snapshots are the exception: they hold state that replay
alone cannot recover, so pruning always keeps them.

## Engine events

```text
POST /pulumi/api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch
```

`EventBatchAppender` stores each event's sequence, kind, severity, and raw payload
after revalidating the lease in its transaction. A duplicate sequence for the same
update returns 409. Console history, resource changes, diagnostics, and reports
read events through `UpdateEventReader`.

`EventArchiveFolder` folds a terminal update's events into one zstd archive after
`statedb.events.archive.grace-period` (one hour), which leaves live console tails
paging over rows. It locks only the `stack_updates` row it folds, with
`SKIP LOCKED`. The fold checks that the archive holds every row before it deletes
them, and `summary` rows stay in `update_events` for cheap history queries.
`UpdateEventReader` reads both forms, and `EventArchiveFolder#unfold` restores
rows transactionally. Archiving does not expire events or change their sequence
numbers.

## Verification

The application suite tests sequence conflicts, replay, history, compaction,
imports, renames, archives, and concurrency. The
[real-CLI suite](../modules/pulumi-cli-smoke/README.md) adds crashes, lease
renewal, concurrent updates, cancellation, failed deployments, and secrets
durability.

`./dev protocol` runs the real-CLI checks. State lifecycle changes also require
`./dev quick` during iteration and `./dev check` before handoff.
