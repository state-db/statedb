# state-db storage — task breakdown for implementer models

Companion to `storage-options-analysis.md`. Tasks are ordered by
dependency; T1–T3 are independent of each other, T4–T7 depend as noted. Each is
sized for one sitting. All build/test commands go through `./bin/in-container`;
all soak-DB verification goes through
`docker exec state-db-soak-postgres psql -U statedb -d statedb -c "..."`.
Read `CLAUDE.md` in full before starting any task — dev-container-only
execution, Flyway owns the schema, Panache-first ORM idioms, and the
compaction/update mutual-exclusion invariant are all binding.

---

## T1 — Drop the two duplicate indexes

**Objective:** remove `idx_update_events_update` and `idx_journal_stack_seq`,
which byte-for-byte duplicate the indexes Postgres already maintains for
`update_events_update_id_sequence_key` and
`journal_entries_stack_id_sequence_id_key`.

**Context:** `V006__journal.sql:43`, `V007__events.sql:29` created them; the
UNIQUE constraints on the same column lists (`V006` line 37, `V007` line 23)
already provide identical indexes. New Flyway migration only; no Java changes.

**Acceptance criteria:**
- New migration drops exactly those two indexes; no other schema change.
- Full test suite passes.
- `EXPLAIN` for the two hot reads (journal replay ordered by
  `(stack_id, sequence_id)`; console events by `(update_id, sequence)`) shows
  an index scan on the UNIQUE-constraint indexes.

**How to verify:**
- `./bin/in-container mvn -f modules/pom.xml test`
- Against a dev database (not the soak DB — it must stay unmigrated evidence):
  `EXPLAIN SELECT * FROM journal_entries WHERE stack_id = '...' AND sequence_id > 0 ORDER BY sequence_id;`
  and the equivalent for `update_events`.

**Rollback:** migration recreating both indexes (`CREATE INDEX` statements are
in V006/V007 verbatim).

**Risk:** effectively none — an identical index remains for every plan. Watch
only that the migration doesn't get written as `DROP INDEX CONCURRENTLY`
(Flyway runs in a transaction; plain `DROP INDEX` is correct here).

---

## T2 — lz4 compression for new payload writes

**Objective:** newly written `update_events.payload`,
`journal_entries.payload`, and `snapshots.checkpoint` datums use lz4 instead of
pglz.

**Context:** Postgres 16, `default_toast_compression=pglz` today. Column-level
`SET COMPRESSION lz4` affects only new datums; existing rows stay pglz (both
codecs coexist per-datum). Measured: ~8% smaller, materially faster decompress
on `StackAssembler`'s streaming path. Flyway migration only.

**Acceptance criteria:**
- Migration applies `ALTER TABLE ... ALTER COLUMN ... SET COMPRESSION lz4` to
  the three columns.
- An integration test (or manual check in dev) shows
  `pg_column_compression(payload) = 'lz4'` for a freshly inserted large-enough
  journal row, and export of a stack with mixed pglz/lz4 rows succeeds.

**How to verify:**
- `./bin/in-container mvn -f modules/pom.xml test`
- Dev DB: insert via a normal update flow, then
  `SELECT pg_column_compression(payload), count(*) FROM journal_entries GROUP BY 1;`
- Beware the gotcha from the brief: `INSERT ... SELECT` of an existing datum
  preserves its old codec — test with genuinely fresh writes.

**Rollback:** migration setting the columns back to pglz; lz4 rows already
written remain readable forever.

**Risk:** minimal. Only failure mode is a Postgres build without lz4
(`--with-lz4`); the stock Debian image has it. A smoke check in CI that inserts
and reads back one row covers it.

---

## T3 — Prune superseded snapshots after compaction (ladder policy)

**Objective:** compaction stops accumulating dead snapshots; each stack retains
its live base, the newest K snapshots, and a history ladder spaced by G journal
entries; everything else is deleted.

**Context:** `CompactionWorker.compact()` inserts a new snapshot and repoints
`stack_state.base_snapshot_id`; nothing deletes. Measured: 1,890 of 1,894
snapshots (386 MB) unreferenced and unread by any current code path. Per-version
export (`StackAssembler.assembleAt`, `StackExportResource.exportVersion`) finds
its base **by position** (newest snapshot with `compacted_from_journal_seq <=
end_journal_seq`, capped by the `stack_updates.base_snapshot_version` lineage
fence — see §O3's lineage exception), so deleting compaction-produced snapshots
is safe — replay just starts from an older rung, or the empty base. **Exception:
snapshots installed by import/rename are lineage bases, not derived caches**
(their content is not the fold of the journal prefix; replay from an older rung
across one misses the state replacement). They are identified by their
`stack_updates.base_snapshot_version` stamp and must never be pruned. The FK
`stack_state.base_snapshot_id → snapshots` (NO ACTION) makes deleting the live
base physically impossible — keep that FK as the safety net. Deletion happens
inside the compaction transaction, where the `stack_state` row lock is already
held (multi-pod safety comes free; do not add new lock ordering). Invariants:
compaction/update mutual exclusion untouched (this deletes old snapshots, never
assembles anything). Policy rationale and latency math: options analysis §O3.

**Acceptance criteria:**
- Two config keys: recency count **K** (default ~10) and ladder spacing **G**
  in journal entries (default per §O3, ~1,000–10,000). A documented sentinel
  (e.g. `0`) disables pruning entirely.
- Retention rule: live base always survives; snapshots stamped in
  `stack_updates.base_snapshot_version` (import/rename lineage bases) always
  survive; newest K survive; walking older snapshots by
  `compacted_from_journal_seq`, a snapshot survives only if it is ≥ G entries
  above the nearest older survivor (greedy from oldest; the first snapshot
  always survives). Everything else is deleted.
- After compactions push a snapshot out of the recency window in a test, it is
  deleted unless it lands on a rung; the base remains; current export output
  is unchanged.
- Per-version export of a version whose snapshot was pruned returns the same
  deployment as before pruning (regression test: export version N, prune,
  export again, compare — modulo `manifest.time`).
- Deletion is idempotent and safe under concurrent workers (it runs under the
  `stack_state` PESSIMISTIC_WRITE lock `CompactionWorker` already takes).

**How to verify:**
- Focused tests:
  `./bin/in-container mvn -f modules/pom.xml -pl persistence -am -Dtest='Compaction*' -Dsurefire.failIfNoSpecifiedTests=false test`
- Integration: drive N updates + compactions, then assert via Panache
  (`Snapshot.count("stackId", ...)`) the retained count and that
  `stack_state.base_snapshot_id` still resolves.

**Rollback:** set keep-all sentinel. Already-deleted snapshots are rebuildable
by journal replay (document the statement of how in the config key's Javadoc,
no tooling required now).

**Risk:** deleting a snapshot some future code secretly needs. Mitigations: the
FK guard, and the reader inventory (only `base_snapshot_id` paths exist —
re-grep before merging). Watch soak/pulumi-compat suites for export diffs.

---

## T4 — Strip matched Begin payloads when an update reaches terminal status

**Objective:** after a `stack_updates` row reaches a terminal status, the
payloads of its kind-0 journal entries that have a matching terminal entry
(kind 1/2/3 with the same `operation_id`) are emptied, while the rows — and the
payloads of *unmatched* Begins — are preserved.

**Context:** `JournalReplayer.add()` case 0 puts the Begin payload into
`incompleteOps`; every terminal kind removes it. `StackAssembler` is the only
reader of `journal_entries.payload` in main code. For matched Begins in terminal
updates the payload is dead weight (measured: 284 MB, 20% of journal heap;
400,420/400,420 matched in the soak). Unmatched Begins become
`pending_operations` in the exported checkpoint (Pulumi's crash-recovery UX) —
their payloads are load-bearing and must be kept.

Note `JournalAppender.payloadEquals` retry idempotency: legal retries require a
live lease, and every path to terminal status deletes the `stack_locks` row in
the same transaction, so stripped rows are unreachable by retries — state this
in the Javadoc.

**Empty representation: `'{}'::jsonb`.** It keeps the NOT NULL constraint and
needs no migration, and the existing readers already tolerate it —
`payload.get("operation")` returns null, `nullable()` maps it to null, and
`generateDeployment` skips null ops. A nullable column would additionally break
`StackAssembler.entryRow`, which calls `mapper.readTree((String) row[12])`
unguarded.

**Trigger: a paced background sweeper over terminal updates, not a post-commit
hook.** `StackUpdateCompleter` is only one of three paths to terminal status —
`StackLockCanceller.markCancelled` (graceful and both force paths) and
`StackLockAcquirer`'s expired-lease takeover are the others, and cancelled
updates are precisely where unmatched Begins occur. `markCancelled` also never
stamps `end_journal_seq`, so the sweeper's eligibility predicate must key off
`stack_updates.status IN ('succeeded','failed','cancelled')`, never
`end_journal_seq IS NOT NULL`. The same sweeper backfills the existing
population; a post-commit hook on `/complete` is at most an optional latency
optimisation on top and must never run inside the completer's transaction.

**Pacing.** `SET payload = '{}'` rewrites each row and drops its toast chunks:
the backfill converts the whole 284 MB into dead tuples in both the heap and the
toast table, at full WAL, on the table `StackAssembler` streams in `sequence_id`
order. Give the sweeper a per-tick row cap and its own short transactions, as
`CompactionWorker` does — a single 400k-row `UPDATE` is not acceptable.

**Do not use the `pending_operations` table as the "unmatched?" index.** It has
no production reader today, and `JournalAppender` silently skips Begins whose
`operation_type`/`resource_urn`/`resource_type` are missing or outside
`VALID_OPERATION_TYPES` — so it under-reports unmatched Begins whose payloads
are still load-bearing. (Its stale-row comment is also out of date:
`CompactionWorker` never sweeps that table.) The self-join on `journal_entries`
is the only correct predicate.

**Depends on:** nothing, but coordinate with T6 (stripping shrinks archive
inputs). Sequence after T2 if both land: lz4 changes the `pg_column_size`
baseline, so measure before/after against one codec.

**Acceptance criteria:**
- A regression test that: runs an update to terminal, captures the assembled
  export, strips, re-assembles — identical deployment JSON modulo
  `manifest.time`.
- A test with an unmatched Begin (cancelled/failed mid-operation) proving its
  payload survives and still surfaces in `pending_operations` of the export.
- A test that a Begin is stripped after each of the three terminal transitions
  (`StackUpdateCompleter`, `StackLockCanceller`, expired-lease takeover).
- The match predicate requires the terminal entry to sort **after** the Begin
  (`t.sequence_id > j.sequence_id`), matching replay order — replay processes
  entries by `sequence_id`, so a terminal entry ahead of its Begin would leave
  the Begin live in `incompleteOps`.
- Stripping is idempotent and multi-pod safe (re-running on an already-stripped
  update is a no-op).
- No stripping of any entry belonging to an in-progress update, ever. Updates
  abandoned without cancellation stay `in-progress` and are never stripped —
  accepted, no savings there.

**How to verify:**
- `./bin/in-container mvn -f modules/pom.xml -pl persistence -am -Dtest='*Journal*,*Assembler*' -Dsurefire.failIfNoSpecifiedTests=false test`
- Candidate-bytes query for a before/after measurement on any dev dataset
  (terminal updates only — without the status filter this overcounts by
  including in-flight work that is never eligible):
  `SELECT pg_size_pretty(sum(pg_column_size(j.payload))::bigint) FROM journal_entries j JOIN stack_updates u ON u.id = j.update_id WHERE j.kind=0 AND u.status <> 'in-progress' AND EXISTS (SELECT 1 FROM journal_entries t WHERE t.update_id=j.update_id AND t.operation_id=j.operation_id AND t.sequence_id > j.sequence_id AND t.kind IN (1,2,3));`
- Run the pulumi-compat sweep (`make pulumi-compat-sweep`) — export shape must
  be untouched.

**Rollback:** disable the sweeper (config flag). Stripped payloads are not
recoverable — acceptable and documented: they duplicate data in the matching
terminal entries.

**Risk:** the matched-only predicate being subtly wrong (e.g. matching across
updates instead of within one). Pin it with the unmatched-Begin test.

Historical bounded replay is already proven safe and needs no re-check in T7:
`assembleAt` bounds the range at an update's `end_journal_seq`, and every entry
of an update lies at or below that update's own `end_journal_seq`, so any Begin
inside the range has its terminal entry inside the range too.

Note the saving is **not additive with T7**: nothing deletes journal rows today
(compaction only advances `stack_state.base_snapshot_id`), and the live
`assemble()` path already ignores everything below the compaction boundary, so
most of the 284 MB is cold tail that T7 would archive or drop wholesale. T4
remains worth doing — it is cheap, it thins T5/T7's inputs, and T7 is a spike
with a go/no-go — but do not count both savings twice.

---

## T5 — Event archival: fold terminal updates' event rows into compressed blobs

**Objective:** once an update is terminal (plus a grace period), its
`update_events` rows are replaced by a single compressed archive row that the
console events endpoint serves transparently, with `summary` (and `prelude`)
rows kept hot.

**Context:** `update_events` is 48% of the database; measured 7.6× shrink with
per-update gzip vs stored rows. Consumers: `ConsoleRunsResource.events` (paged
by `sequence`, 500/page) and its `response()` summary lookup;
`run-detail.tsx` consumes what the endpoint returns — response shape must not
change. Events are observability data with no ordering invariant against
`stack_state` (see `EventBatchAppender` Javadoc) — no interaction with the
compaction/update exclusion. Design elements the implementer owns: archive
table shape (one row per update; composite FK `(update_id, stack_id)` →
`stack_updates` mirroring `update_events`, ON DELETE CASCADE; codec tag;
`event_count`; `max_sequence`; `bytea` blob), serialization format (must
round-trip `sequence, kind, severity, payload` losslessly), codec (JDK
Deflater/gzip needs no new dependency; zstd-jni is allowed if ratio justifies
— acceptance only requires ≥ gzip), and a folder worker following the
`compaction_jobs` claim pattern (SKIP LOCKED or `AdvisoryLock`) so multi-pod is
safe. Fold transactionally: insert blob + delete rows in one transaction.
Grace period config (e.g. 1h) keeps live-tail pagination on rows until clients
are done.

**Depends on:** T1 (avoid re-tuning indexes that are about to shrink); design
should anticipate T6 reusing the pattern.

**Acceptance criteria:**
- Paging parity test: for an archived update, the events endpoint returns
  byte-identical pages (including `nextAfter` cursors and the `expired` flag
  semantics — a present blob is not expired) to what the row-backed version
  returned before folding.
- `summary` remains served without touching the blob.
- Folding is idempotent (unique per update; re-claim after crash re-folds or
  no-ops, never duplicates or loses events) and never touches in-progress or
  in-grace updates.
- An unfold routine (admin/test entry point) restores rows from a blob exactly;
  the parity test uses it.
- Measured size check in the test: blob ≤ 1/3 of `sum(pg_column_size(payload))`
  of the rows it replaces on the test corpus (recorder fixtures in
  `tools/pulumi/recorder/fixtures/` are realistic input).

**How to verify:**
- `./bin/in-container mvn -f modules/pom.xml -pl persistence,console-api -am -Dtest='*Event*,*Runs*' -Dsurefire.failIfNoSpecifiedTests=false test`
- Smoke: console run-detail page for an archived run through the workbench
  stack; then a fresh soak run and compare the storage report's
  bytes-per-resource-per-update against the ~4.2 KB event baseline.

**Rollback:** config flag to stop folding + serve-from-rows-first read path
(which exists by construction); bulk unfold tool restores any archived update.
Blob rows can then be dropped by migration.

**Risk:** losing events in the fold transaction (pin with count/max-sequence
checks inside the fold, compare before delete); cursor semantics drift between
row and blob paths (parity test is the guard); vacuum pressure from bulk
deletes at high rates (folder should pace itself like `CompactionWorker`'s
per-tick cap).

---

## T6 — Preview archive retention knob (optional, small)

**Objective:** an off-by-default config that ages out *archived preview* blobs
past a horizon, surfacing as the existing `expired` response for those runs.

**Context:** previews are 27% of event volume (soak-shaped; real share
unknown). The console API already models absent event streams
(`EventsResponse.expired`, 30-day constant in `ConsoleRunsResource`). Owner has
rejected retention as primary strategy — this is a deliberate, bounded
exception for what-if output, and it must ship default-off with the capability
loss documented on the config key.

**Depends on:** T5.

**Acceptance criteria:** default off = nothing deleted; enabled in a test =
only preview archives past the horizon deleted, real-update archives untouched,
endpoint returns `expired=true` for the aged preview; sweep is idempotent and
multi-pod safe.

**How to verify:** focused persistence/console-api tests as in T5.

**Rollback:** leave/return the flag to off (deleted blobs are gone — that is
the documented semantics of enabling it).

**Risk:** kind classification drift (rely on `stack_updates.kind='preview'`,
not event payloads).

---

## T7 — Design spike: cold journal segments

**Objective:** a written design (no implementation) for journal cold-segment
archival below the compaction boundary, with a go/no-go recommendation.

**Context:** per-version export is **implemented** —
`StackAssembler.assembleAt` (positional rung lookup + bounded replay, index in
`V018__snapshot_boundary_index.sql`) behind `GET .../export/{version}`
(`StackExportResource.exportVersion`, the path `pulumi stack export --version`
requests — protocol compatibility applies to it). It is the reader cold
segments must serve. Cold segments reuse T5's fold pattern on
`journal_entries` (measured 9.6× gzip on the soak sample). Hard constraints:
journal is the system of record; `JournalAppender` retry window; compaction
boundary monotonicity; crash recovery for in-flight updates; tenant-isolation
composite FKs; T3's ladder spacing G bounds how much cold journal any one
export decompresses.

**Depends on:** T3 (ladder), T5 (pattern evidence), T4 (stripping shrinks the
segments; matched-Begin safety under bounded replay is already proven in T4 and
needs no re-derivation here).

**Acceptance criteria:** a design doc answering: ladder spacing G vs
replay-latency budget re-measured on a large real stack (the 42 ms/425-entry
soak rate is small-stack-shaped); segment eligibility predicate and its proof
of safety against every current journal reader (`StackAssembler.assemble`,
`assembleAt`, `JournalAppender.missingEntries`); unfold/verification story;
explicit go/no-go with the evidence that would flip it, including what T5's
production behavior must have demonstrated first. Terse requirements style —
no "why now" narrative.

**How to verify:** review against the invariants section of the options
analysis; every quantitative claim labelled measured/estimated/unknown.

**Rollback:** n/a (document).

**Risk:** scope creep into implementation — the deliverable is the decision,
not code.
