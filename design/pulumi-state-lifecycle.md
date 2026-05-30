# Pulumi State Lifecycle: CLI, Journal, Events, and DB State

This note captures how Pulumi CLI state moves through state-db, based on the
workbench run against `workbench/pulumi` and the current schema/code. It is a
companion to [`db-schema.md`](db-schema.md) and
[`slice-17.5-stack-assembler.md`](slice-17.5-stack-assembler.md): those docs
define the model; this one explains the operational story.

## Mental Model

Pulumi sends three related streams of information, and state-db maintains a few
derived control tables around them:

| Table | Meaning | Used for |
|---|---|---|
| `stack_updates` | One row per Pulumi run: preview, update, refresh, destroy, etc. | History list, status, timestamps, journal bounds |
| `journal_entries` | Append-only state journal for a run | Reconstructing `GET /export` state |
| `update_events` | Pulumi engine event stream | UI timeline, preview/change display, diagnostics |
| `pending_operations` | Resource operations that began but have not terminally completed | Crash recovery and `deployment.pending_operations` |
| `stack_state` | One small mutable head row per stack | Current journal sequence, active snapshot pointer, per-stack serialization lock |
| `snapshots` | Compacted DeploymentV3 checkpoints | Fast `/export` baseline after compaction |
| `compaction_jobs` | Async worker queue | Multi-pod safe snapshot generation |

The key split is:

- `journal_entries` is the source of truth for checkpoint reconstruction.
- `update_events` is the human-facing event/progress stream.
- `stack_updates` frames each operation and ties those streams together.
- `stack_state` is not the checkpoint itself; it is the current head pointer
  for one stack.

## What the Workbench Created

After `pulumi preview`, `pulumi up --skip-preview`, and `pulumi refresh
--skip-preview`, the workbench stack had three `stack_updates` rows:

| Version | Kind | Status | Journal range | Meaning |
|---:|---|---|---|---|
| 1 | `preview` | `succeeded` | none, `end_journal_seq=0` | Planned six creates |
| 2 | `update` | `succeeded` | `1..16` | Created the stack/provider/resources |
| 3 | `refresh` | `succeeded` | `18..33` | Re-read current provider state, no changes |

The stack's `stack_state.journal_seq` advanced to `34`, meaning the next
server-assigned journal sequence starts there. `cache_valid=false` is expected:
journal inserts invalidate the read cache. Snapshot compaction uses
`base_snapshot_id` rather than this older cache field.

## Stack State, Snapshots, and the Head Pointer

`stack_state` has exactly one row per stack. It is the mutable control row that
answers, "where is the current state head for this stack?"

Important columns:

| Column | Meaning |
|---|---|
| `stack_id` | The stack this head row belongs to |
| `journal_seq` | The next stack-global journal sequence number to allocate |
| `base_snapshot_id` | FK to `snapshots.id`, or `NULL` before first compaction |
| `base_snapshot_version` | Monotonic snapshot version for the stack |
| `journal_entries_since_compaction` | Counter reset after compaction |
| `cached_checkpoint`, `cached_at`, `cache_valid` | Older read-cache fields, invalidated on journal insert |

The table is also the per-stack serialization point. Journal appends, update
completion, and compaction all lock the same `stack_state` row before changing
sequence numbers or snapshot pointers. That keeps these races safe across pods:

- two journal appenders cannot allocate overlapping `sequence_id` ranges,
- compaction cannot snapshot while a journal append is advancing the stack,
- `/complete` cannot mark the update boundary while compaction is moving the
  base snapshot pointer.

`snapshots` stores the compacted checkpoint JSON. A row contains:

| Column | Meaning |
|---|---|
| `id` | Snapshot UUID; this is what `stack_state.base_snapshot_id` points at |
| `stack_id` | Owning stack |
| `version` | Stack-local snapshot version |
| `checkpoint` | The compacted DeploymentV3 deployment JSON |
| `compacted_from_journal_seq` | Highest journal sequence included in the snapshot |

So a workbench value like:

```text
stack_state.base_snapshot_id = ac110002-9e71-114f-819e-7683aef8001f
```

means:

```sql
SELECT *
FROM snapshots
WHERE id = 'ac110002-9e71-114f-819e-7683aef8001f';
```

That snapshot row is the baseline for future `/export` calls. It is not stored
inside `stack_state`; `stack_state` only points to it.

## Update Start and Completion

When Pulumi starts a run, state-db inserts a `stack_updates` row:

- `kind`: `preview`, `update`, `refresh`, `destroy`, etc.
- `status`: starts as `in-progress`.
- `version`: monotonically increasing per stack.
- `config`, `metadata`, `options`, `description`, `runtime`, `pulumi_version`,
  `message`, `tags`: request metadata from the CLI.
- `started_by_*`: durable attribution to user/token.

For journaling runs, the first journal PATCH captures:

- `start_journal_seq`: the current `stack_state.journal_seq`.
- each row's `sequence_id = start_journal_seq + wire_sequence_id`.

On complete, state-db:

- locks `stack_state` to serialize against in-flight journal appends,
- records `end_journal_seq`,
- moves `status` to `succeeded` or `failed`,
- deletes the stack lock,
- clears `stacks.active_update_id`,
- increments `stacks.version` for non-preview operations.

## Journal Entries

Pulumi sends journal batches to:

```text
PATCH /pulumi/api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
```

The wire field `sequenceID` is stored as `wire_sequence_id`. It is unique only
within a single Pulumi update and resets on each run. state-db derives the
stack-global `sequence_id` by adding the update's `start_journal_seq`.

This means:

```text
wire_sequence_id = Pulumi's per-update entry number
sequence_id      = state-db replay order for the whole stack
```

Pulumi never knows `sequence_id`; it only sends `sequenceID`. The DB uses
`UNIQUE(update_id, wire_sequence_id)` to catch duplicate retries.

### Journal Kinds Seen in the Workbench

The initial `up` wrote:

| Kind | Meaning | Workbench use |
|---:|---|---|
| 0 | Begin | Start creating a resource operation |
| 1 | Success | Persist the resulting resource state |
| 4 | Outputs | Replace stack resource state with final outputs |
| 6 | SecretsProvider | Record service secrets provider |

The later `refresh` wrote:

| Kind | Meaning | Workbench use |
|---:|---|---|
| 0 | Begin | Empty refresh placeholders, payload `{}` |
| 3 | RefreshSuccess | Replace existing base resources by index |
| 6 | SecretsProvider | Re-record service secrets provider |
| 7 | RebuiltBaseState | Promote replayed state to the in-memory base |

Kind `5` (`Write`) is implemented and tested but was not observed in the live
workbench. It carries `payload.newSnapshot` and replaces the working base
deployment wholesale during replay.

## The `up` Story

The `up` journal started from an empty stack:

1. Kind `6`: set the service secrets provider.
2. Begin/success for the logical stack resource.
3. Begin/success for the default `random` provider.
4. Begin/success for `randomPet` `pet`.
5. Begin/success for `randomString` `word`.
6. Begin/success for `randomInteger` `pin`.
7. Begin/success for `randomPassword` `password`.
8. Begin/success for `randomId` `token`.
9. Kind `4`: replace the stack resource with final stack outputs.

Every successful resource row carries a full `payload.state` JSON object. That
is the data `GET /export` later collects into `deployment.resources`.

## The `refresh` Story

After `up`, `GET /export` returns a `deployment.resources` array. In the
workbench it was:

```text
resources[0] = stack resource
resources[1] = random provider
resources[2] = pet
resources[3] = word
resources[4] = pin
resources[5] = password
resources[6] = token
```

During refresh, Pulumi refers back to that exported resource array using
`removeOld`. For example:

```text
remove_old=2 -> replace deployment.resources[2], the pet
remove_old=6 -> replace deployment.resources[6], the token
```

These are not journal row numbers. They are indexes into the base checkpoint's
`deployment.resources` array as seen by the Pulumi CLI.

The refresh emitted duplicate resource URNs in `journal_entries`, one state row
from the original `up` and one from `refresh`. That is expected because the
journal is an event log. `StackAssembler` reduces those entries back to one
current resource per resource slot.

## Export and State Reconstruction

Pulumi loads stack state through:

```text
GET /pulumi/api/stacks/{org}/{project}/{stack}/export
```

The current implementation:

1. resolves `(org, project, stack)` to `stack_id`,
2. calls `StackAssembler.assemble(stackId)`,
3. starts from `snapshots.checkpoint` when `stack_state.base_snapshot_id` is
   set, or an empty deployment if there is no base snapshot yet,
4. streams `journal_entries` ordered by `sequence_id`, starting after the
   snapshot boundary,
5. applies each entry through `JournalReplayer`,
6. returns:

```json
{
  "version": 3,
  "deployment": {
    "secrets_providers": { "...": "..." },
    "resources": [ "... current resources ..." ]
  }
}
```

If there is no compacted snapshot yet, `stack_state.base_snapshot_id` is `NULL`
and export replays the whole journal from sequence `0`. After compaction,
export loads:

```text
base = snapshots.checkpoint
baseSeq = snapshots.compacted_from_journal_seq
tail = journal_entries where sequence_id > baseSeq
```

The same `StackAssembler` code path is used by `/export` and the compaction
worker, so a compacted snapshot should be byte-equivalent to the state that
`/export` would have returned immediately before compaction.

### How Duplicate URNs Are Avoided

The assembler does not deduplicate by URN. It replays Pulumi's reducer rules.

For an `up`:

- kind `1` Success appends `payload.state` into `newResources`.

When a new update starts:

- `StackAssembler` detects the update boundary,
- materializes the prior replayer's deployment,
- uses that as the next update's `base`.

For a `refresh`:

- kind `3` RefreshSuccess with `removeOld=N` stores
  `toReplaceInSnapshot[N] = payload.state`.
- final materialization iterates `base.resources` by index.
- if a replacement exists for that index, it emits the replacement instead of
  the old base resource.

Kind `7` then promotes the materialized deployment to the current in-memory
base and clears transient replay maps. This is a replay optimization/semantic
marker, not persisted compaction.

## Pending Operations

`pending_operations` is populated from journal Begin entries only when the Begin
payload includes enough resource context:

- `operation.resource.urn`
- `operation.resource.type`
- `operation.type` in `creating`, `updating`, `deleting`, `reading`, `importing`

Terminal journal entries for the same `(update_id, operation_id)` remove the
row:

- kind `1`: Success
- kind `2`: Failure
- kind `3`: RefreshSuccess

An empty table is the healthy finished state. Rows mean an operation started but
did not terminally complete, or a terminal entry arrived out of order and
compaction has not swept the stale row yet.

Refresh Begin entries in the workbench had payload `{}`, so they did not create
pending operations.

## Update Events

Pulumi sends decoded event batches to:

```text
POST /pulumi/api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch
```

These rows are observability data. They preserve the raw event payload and
promote only:

- `sequence`
- `kind`
- diagnostic `severity`

Useful event kinds observed:

| Event kind | Meaning |
|---|---|
| `prelude` | run setup/config |
| `resourcePre` | Pulumi is about to act on a resource |
| `resOutputs` | Pulumi has planned/current output metadata |
| `summary` | final result and resource-change counts |

The workbench summaries were:

```text
preview: {"create": 6}
update:  {"create": 6}
refresh: {"same": 6}
```

For a console UI, `update_events` is the best source for a run timeline and
preview/change rendering. It includes per-resource metadata:

- `metadata.op`
- `metadata.old`
- `metadata.new`
- `metadata.detailedDiff`
- `summaryEvent.resourceChanges`

By contrast, `journal_entries` is for reconstructing state and should not be
treated as the UI event stream.

## Snapshot Compaction

Snapshot generation is asynchronous and does not run in the request path.
`POST /complete` enqueues a compaction job after a completed update; the
database queue coalesces bursts so each stack has at most one pending/running
job.

The worker:

1. claims a `compaction_jobs` row with `FOR UPDATE SKIP LOCKED`,
2. locks the stack's `stack_state` row,
3. calls `StackAssembler.assemble(stackId)`, the same code used by `/export`,
4. inserts a `snapshots` row with the assembled checkpoint and current
   `stack_state.journal_seq`,
5. updates `stack_state.base_snapshot_id` and `base_snapshot_version`,
6. resets `journal_entries_since_compaction`,
7. marks the job `completed`.

`journal_entries` are not deleted by compaction today. They remain the detailed
append-only history, but `/export` skips entries at or before
`snapshots.compacted_from_journal_seq`. This keeps reads fast but does not yet
bound table size.

Journal pruning needs an explicit retention policy before it is safe to add.
The key product question is whether state-db must preserve full historical
reconstruction or only current-state export correctness. A likely policy is:

- keep the latest one or two snapshots per stack,
- delete `journal_entries` for that stack where
  `sequence_id <= active_snapshot.compacted_from_journal_seq`,
- preserve `stack_updates` metadata for the history list,
- make the retention window/config explicit if operators need longer audit
  history.

## Practical Query Map

History list:

```sql
SELECT version, kind, status, started_at, ended_at, start_journal_seq, end_journal_seq
FROM stack_updates
WHERE stack_id = :stack_id
ORDER BY version;
```

Current-state journal tail:

```sql
SELECT *
FROM journal_entries
WHERE stack_id = :stack_id
  AND sequence_id > :base_sequence
ORDER BY sequence_id;
```

Active snapshot:

```sql
SELECT s.*
FROM stack_state st
JOIN snapshots s ON s.id = st.base_snapshot_id
WHERE st.stack_id = :stack_id;
```

Run timeline:

```sql
SELECT sequence, kind, severity, payload
FROM update_events
WHERE update_id = :update_id
ORDER BY sequence;
```

Crash-recovery operations:

```sql
SELECT resource_urn, resource_type, operation_type, begin_journal_seq
FROM pending_operations
WHERE stack_id = :stack_id
ORDER BY created_at;
```
