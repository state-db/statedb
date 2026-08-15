# Snapshot pruning (T3) — implementation notes

Records where the shipped `SnapshotPruner` differs from `storage-options-analysis.md`
§O3 and `storage-task-breakdown.md` T3. The retention strategy is unchanged;
the deviations are in the cost model behind G, and in one safety property the
design assumed but the code did not provide.

---

## D1 — Two ladder bounds, not one

**Design:** one spacing knob `G`, denominated in journal entries, default
1,000–10,000. Rationale: "replay cost is linear in entries", so
per-version export ≈ one rung load + `G × tens of µs`.

**Shipped:** two bounds. A rung survives once **either** budget is spent since
the previous rung.

| Key | Default | Bounds |
|---|---|---|
| `statedb.compaction.prune.ladder-journal-spacing` | 5000 | journal rows replayed |
| `statedb.compaction.prune.ladder-update-spacing` | 50 | whole-deployment rebuilds |

**Reason:** replay cost is not linear in entries. `StackAssembler.replay`
rebuilds the entire deployment at every *update boundary* it crosses, because
journal entries index into the base by array position as of the start of their
own update. Real cost is `E × per-entry + U × O(deployment)`, and `G` bounds
`E` but not `U`, where `U = G / (entries per update)`.

That ratio is the soak's most workload-specific number. §O3 calibrated on 425
entries/update, where G=5000 crosses ~12 boundaries and the second term is
invisible. §O3 also states that real journal volume scales with *changed*
resources while the soak changes every resource every update. At single-digit
entries per update, G=5000 crosses ~600 boundaries — each a full rebuild of the
deployment tree.

A single knob in journal entries is also mis-signed against storage cost:

| Workload | Updates per rung at G=5000 | Ladder bytes / 10k updates | Rebuilds per export |
|---|---|---|---|
| Soak-shaped (425 entries/update) | ~12 | ~175 MB | ~12 |
| Small changes (~8 entries/update) | ~625 | ~3 MB | ~625 |

It is generous exactly where snapshots are expensive and stingy exactly where
they are cheap. Lowering G alone fixes the second row by wrecking the first
(G=1000 retains ~40% of snapshots at soak cadence). `ladder-update-spacing`
binds only on the shape where the entry budget is too loose, so soak-shaped
retention is unchanged.

Update distance is counted from distinct `stack_updates.end_journal_seq` values.
An update that journals nothing shares its predecessor's boundary and costs
replay no rebuild, so distinct boundaries between two rungs is exactly the
rebuild count.

**Not changed:** `newest-snapshots` (K) stays at 10; the compaction cadence stays
one snapshot per update, so the head is always materialized.

## D2 — Publish the base pointer before deleting

**Design:** §O3 and T3 both treat the FK `stack_state.base_snapshot_id →
snapshots` as a safety net — "the live base physically cannot be deleted".

**Shipped:** `CompactionWorker.compact` flushes before calling the pruner.

**Reason:** the FK was a tripwire, not a net. The pruner's keep-set protects the
new base id in the persistence context, but the bulk `DELETE` touches only
`snapshots`, so Hibernate's space-based auto-flush leaves the pending
`stack_state` update unwritten and the delete runs against the *previous*
pointer. Reproduced at `newest-snapshots: 1`, where the previous base is no
longer covered by the recency tier:

```
ERROR: update or delete on table "snapshots" violates foreign key constraint
"fk_stack_state_base_snapshot" on table "stack_state"
```

The compaction transaction rolls back cleanly, but every retry and every later
job fails identically, so the stack stops compacting permanently. Guarded by
`SnapshotPrunerUpdateSpacingTest`, which runs at K=1.

At `K >= 2` the previous base is always second in `(seq, version)` order and was
protected by accident. The flush makes it an enforced invariant, so K=1 stays a
legal setting.

## D3 — Live base resolved in one statement

**Design:** not addressed.

**Shipped:** `StackAssembler.loadBaseSnapshotOrEmpty` resolves the base through a
subquery instead of reading `stack_state.base_snapshot_id` and then loading that
row.

**Reason:** the two reads straddle a prune commit under READ COMMITTED, so a live
`pulumi stack export` could observe a base id that no longer resolves. One
statement puts both on the same read snapshot. The FK makes an empty result mean
"no base yet", never a dangling id, so the previous not-found `IllegalStateException`
is gone.

## D4 — Redundant deployment copies removed on the replay path

**Design:** not addressed; §O3's latency math depends on it.

**Shipped:** `StackAssembler` no longer deep-copies at the update-boundary rebase
or when loading a base snapshot.

**Reason:** `JournalReplayer.generateDeployment` already returns an independent
tree (fresh `ObjectNode`, every child deep-copied), and the `JournalReplayer`
constructor copies whatever base it is handed. Each boundary crossing therefore
built the deployment and then copied it twice. Two of the three are removed; the
constructor's copy stays as the single ownership boundary, which is what lets
callers pass a managed `Snapshot.checkpoint` without a defensive copy of their
own.

Removing the constructor copy as well would need `JournalReplayer` to take
ownership of its base explicitly. Not done here — it is a contract change on the
protocol-critical path and does not belong in the same change as a bug fix.

## D5 — Single disable sentinel; config validated at startup

**Design:** T3 asks for "a documented sentinel (e.g. `0`) disables pruning
entirely".

**Shipped:** `newest-snapshots: 0` disables pruning. Each ladder bound treats `0`
as "this bound is inactive" — both at `0` leaves the anchor, recency tier,
lineage bases and live base only. Negative values fail at startup
(`@Observes StartupEvent`, matching `LockConfigValidator`) rather than from
inside the compaction transaction, where a typo surfaced only as repeated job
failures with the message buried in `compaction_jobs.error_message`.

## D6 — Deferred

- **Bulk delete parameter count.** `id in ?2` passes every doomed id in one
  statement; past ~65k ids for a single stack this exceeds Postgres's bind-parameter
  limit. Needs ~65k updates on one stack, and batching would not shorten the
  real cost (the first prune holds the `stack_state` row lock while deleting the
  backlog). Fix if it appears: drive the delete from the keep set with
  `NOT IN (SELECT ...)`.
- **T3 verification command** names `-pl persistence`; the tests live in
  `modules/app`.
