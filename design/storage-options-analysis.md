# state-db storage architecture — options analysis

Scope: how state-db stores per-deploy data (journal, events, snapshots) so a busy
self-hosted install stays economical, without giving up **per-version state** or
**console history**. Produced against the 3-hour soak dataset (~3.5 GB, 4 stacks ×
300 resources, 1,988 updates) plus the §4 baseline handed to me. Every number is
labelled `measured` (I ran it or it was verified in §4), `estimated` (assumption
stated), or `unknown`.

No scratch tables were created; all new measurements used read-only queries and
`COPY ... TO STDOUT` piped through `gzip` inside the container.

---

## 0. New evidence gathered for this analysis

These extend the §4 baseline and drive the options below.

**Heap vs index split** (`measured`, `pg_relation_size`/`pg_indexes_size`):

| Table | Total | Heap | Indexes |
|---|---|---|---|
| `update_events` | 1717 MB | 1592 MB | 124 MB |
| `journal_entries` | 1419 MB | 1249 MB | 169 MB |
| `snapshots` | 407 MB | 304 kB (rest TOAST) | 216 kB |

**Journal payload by kind** (`measured`):

| kind | rows | payload stored | avg |
|---|---|---|---|
| 0 Begin | 400,420 | 284 MB | 744 B |
| 1 Success | 400,388 | 403 MB | 1,056 B |
| 2 Failure | 32 | 25 kB | — |
| 4 Outputs | 1,270 | 733 kB | — |
| 6 SecretsManager | 1,326 | 223 kB | — |

**Event payload by kind** (`measured`; preview = rows whose update is `kind='preview'`):

| kind | rows | payload stored | avg | preview rows |
|---|---|---|---|---|
| `resOutputs` | 572,492 | 793 MB | 1,452 B | 172,104 |
| `resourcePre` | 571,956 | 631 MB | 1,157 B | 171,536 |
| `summary` | 1,894 | 505 kB | 273 B | 568 |
| `prelude` | 1,894 | 194 kB | 105 B | 568 |
| `resOpFailed` + `diagnostic` | 96 | 72 kB | — | 0 |

**The same resource state is persisted ~6× per operation** (`measured`, by
inspecting payloads of one operation): `resourcePre.metadata.{old,new}`,
`resOutputs.metadata.{old,new}`, journal Success `state`, and journal Begin
`operation.resource`. The two event kinds carry *both* old and new full resource
state each; the journal carries new state once (Success) plus the resource again
in Begin.

**Per-update block compression** (`measured` on one typical succeeded `update`,
606 events): event rows stored (per-row pglz) 822 kB, raw 4,762 kB; the whole
stream `COPY`-serialized and `gzip -9`ed is **107,740 B → 7.6× smaller than what
Postgres stores today** (44× vs raw). Journal rows for the same update: 567 kB
stored → 58,906 B gzipped (**9.6×**). §5 caveat applies: synthetic filler and
pathological URNs inflate cross-row redundancy; on real data expect **3–5×
(estimated)** — cross-row redundancy (repeated URNs, keys, old/new duplication
within an update) survives high-entropy values even when intra-value compression
does not.

**Reader inventory** (`measured`, exhaustive grep):

- Journal rows **below** the compaction boundary have **zero readers** today.
  `StackAssembler` reads only `sequence_id > compacted_from_journal_seq`;
  `JournalAppender` reads only the current update's rows (retry idempotency).
- Snapshots other than `stack_state.base_snapshot_id` have **zero readers**
  (`StackAssembler` and `WorkspaceListFinder.resourceCounts` both go through
  `base_snapshot_id` only). 1,890 of 1,894 snapshots (386 of 390 MB stored,
  `measured`) are write-only dead weight.
- Console run detail (`run-detail.tsx`) consumes `resourcePre`/`resOutputs`/
  `resOpFailed` envelopes — including `metadata.old` and `metadata.new` — to
  render the timeline, resource list, output diffs, and per-property diffs.
  **Event payloads are load-bearing for console history; the journal cannot
  replace them** without replaying prior state to synthesize `old` (rejected
  below). `summary` is fetched by a separate row lookup on every run-detail view.
- `ConsoleRunsResource` already carries an `EVENT_RETENTION = 30 days` /
  `expired` concept in its API shape — the console contract already tolerates
  event streams becoming unavailable.

**Duplicate indexes confirmed** (`measured`): `idx_update_events_update` (45 MB)
≡ `update_events_update_id_sequence_key` (45 MB); `idx_journal_stack_seq` (51 MB)
≡ `journal_entries_stack_id_sequence_id_key` (51 MB). **96 MB** of pure
duplication.

Environment: Postgres 16.14, `default_toast_compression = pglz` (`measured`).

---

## 1. Options

### O1 — Drop the two duplicate indexes

**Changes:** one Flyway migration dropping `idx_update_events_update` and
`idx_journal_stack_seq`. The adjacent UNIQUE constraints' indexes are
byte-for-byte equivalent for every query plan.

**Saving:** 96 MB on this dataset, **2.7% of total; `measured`**. More
importantly it removes two index maintenances from every event/journal insert —
the hot write path — forever. Unaffected by §5 distortions (index size scales
with row count, not payload entropy).

**Costs:** none. **Forecloses:** nothing. **Reversibility:** `CREATE INDEX`
recreates them. **Interactions:** none; do it regardless of everything else.

### O2 — lz4 column compression for new payload writes

**Changes:** `ALTER TABLE ... ALTER COLUMN payload SET COMPRESSION lz4` on
`update_events`, `journal_entries`, and `snapshots.checkpoint` (Flyway
migration). Affects only newly written datums; existing rows keep pglz.

**Saving:** ~8% on stored payload bytes (`measured` in §4: 1,194 vs 1,300 B).
The larger win is CPU: lz4 decompresses several times faster than pglz, and
`StackAssembler` decompresses every journal row above the boundary on every
export and every compaction.

**Costs:** near zero. lz4 is compiled into the stock Postgres 16 image
(`measured`: the pglz-vs-lz4 comparison in §4 ran on this instance).
**Forecloses:** nothing. **Reversibility:** flip the setting back; mixed-codec
tables are fully supported. **Interactions:** superseded for *cold* data by
O5/O6 (which move cold payloads out of TOAST entirely); still worth it for the
hot window.

### O3 — Prune superseded snapshots (retention ladder for a derived cache)

**Changes:** compaction currently inserts a snapshot and abandons the previous
one; nothing ever deletes. Add deletion of superseded snapshots after a
successful compaction, per the ladder policy below.

This is **not** the rejected retention-first strategy: snapshots are a derived
cache of the journal, which remains complete. Deleting one loses zero
information — any historical snapshot is reproducible by replaying the journal
from an earlier base (or from zero). The owner's constraint protects *history*,
not redundant materializations of it.

**Why dropping any subset is safe (no rewiring, ever):** snapshots are not a
delta chain — each one is a full materialization of the journal prefix up to
its own boundary, and nothing in `journal_entries` references snapshots (its
FKs go to `stacks`/`stack_updates` only; the sole snapshot reference anywhere
is `stack_state.base_snapshot_id`). Replay of any suffix folds onto any older
retained snapshot, or onto the empty base — the path every fresh stack already
exercises before its first compaction. Journal entries reference base resources
by array index, but `StackAssembler` re-materializes the deployment at every
update boundary during replay, so those indices resolve against a
deterministically reconstructed state regardless of which rung replay started
from. That determinism is not a new assumption: current-state export and
compaction already depend on it (it is what the integrity-verifier tier pins).
The one constraint — replay must start on an update boundary — is satisfied by
construction: every snapshot's `compacted_from_journal_seq` equals a completed
update's `end_journal_seq` (`measured`: all 1,894 of 1,894 aligned; guaranteed
by the compaction/update mutual exclusion).

**Ladder policy (three tiers per stack):**

1. **Live base** — always kept; FK-enforced (`NO ACTION`).
2. **Recency tier** — the newest **K** snapshots (default ~10). Rollback and
   inspection overwhelmingly target recent versions; these stay O(1).
3. **History ladder** — walking backward, keep a snapshot only if it is at
   least **G** journal entries above the previously retained one (default
   G ≈ 1,000–10,000; see latency math); prune the rest. The greedy walk from
   the oldest snapshot is deterministic and stable as new compactions arrive,
   and naturally keeps the first snapshot, so the ladder covers the whole
   timeline.

**G is denominated in journal entries, not time or count**, because replay cost
is linear in entries: `measured` on the soak, 1,894 compactions averaged 42 ms
(p95 69 ms) to load a 1.5 MB base, replay an average 425-entry gap, and persist
a 216 kB snapshot — so worst-case per-version export ≈ one rung load +
G × tens of µs. G = 10,000 is sub-second (`estimated` from that measured rate)
and retains ~4% of snapshots at the soak's 425-entries-per-compaction cadence.
Time-based spacing would let a busy stack accumulate unbounded gaps; per-N
versions would hoard snapshots on quiet stacks. The 42 ms rate is soak-shaped
(300-resource stacks); re-measure on a large stack before freezing the default
(`unknown` there).

**Per-version lookup contract** (implemented — `StackAssembler.assembleAt`,
`StackExportResource` `GET .../export/{version}`, index in
`V018__snapshot_boundary_index.sql`): resolve
`stack_updates.end_journal_seq` for `(stack, version)` (dry-run rows carry
version 0 and never match; `end_journal_seq IS NULL` → 404), pick the newest
snapshot with `compacted_from_journal_seq <= end_seq` by position — *not* via
`stack_state.base_snapshot_id`, which may already be past the boundary — and
replay `sequence_id > rung_boundary AND sequence_id <= end_seq`. Whichever
rungs survived pruning, the nearest-below rung wins; adding a rung back is just
an insert that readers pick up on the next lookup.

**Saving:** 386 of 390 MB stored checkpoint bytes on this dataset — **~11% of
the database; `measured`** (only 4 snapshots are referenced). The soak's
snapshot count is inflated by its update rate, but the *shape* is structural:
snapshot bytes grow linearly with compaction count forever, and 100% of
non-base snapshots are unread by any current code path (`measured`).

**Size note (`measured`):** the ~211 kB/snapshot figure is *already pglz-
compressed* (raw 1.56 MB); the same checkpoint gzip -9s to **42.5 kB**. On the
soak that makes a compressed snapshot per version cheaper than a compressed
journal segment per update (59 kB) — but the soak is the most
snapshot-favorable workload possible (every resource changes every update).
Real journal segments scale with *changed* resources; snapshots scale with
*stack size* at any codec. And since the journal must be retained regardless
(it is the system of record — see rejected options), every kept snapshot is
additive accelerator cost, never an alternative to journal bytes. Corollary:
storing ladder rungs as compressed blobs (~42 kB apiece) makes a dense ladder
cheap, and is worth folding into the O5 archival pattern later.

**Costs:** a `DELETE` inside the compaction transaction (the `stack_state` row
lock is already held there — no new locking). Old-version exports pay replay
bounded by G. The FK `stack_state.base_snapshot_id → snapshots` (NO ACTION) is
a hard safety net: the live base physically cannot be deleted.

**Forecloses:** nothing — per-version state is served by the journal; the
ladder only tunes its latency. **Reversibility:** policy change; already-deleted
snapshots are rebuildable by replay. **Interactions:** K and G are the operator
knobs trading storage for old-version export latency; combines with everything;
compressed rungs reuse O5's pattern.

### O4 — Strip Begin payloads once matched and terminal

**Changes:** a journal Begin (kind 0) payload duplicates the resource that its
terminal entry (kind 1/2/3) supersedes. `JournalReplayer.add()` reads a Begin
payload only into `incompleteOps`, and every terminal kind removes that entry —
so for a **matched** Begin in a **terminal** update, the payload is provably
never read again by any code path (`measured`: reader inventory above; the §4
baseline confirmed 400,420 of 400,420 Begins matched, zero orphans). After an
update reaches terminal status, null/empty the payload of its matched Begin
rows, keeping the rows themselves (sequence continuity, `wire_sequence_id`
idempotency shape, `pending_operations.begin_journal_seq` references).

Unmatched Begins must keep their payloads: for a failed/cancelled update they
become the exported checkpoint's `pending_operations`, which Pulumi surfaces to
the user (crash-recovery invariant).

**Saving:** 284 MB payload on this dataset — **20% of journal heap, 8% of
total; `measured`**. Roughly proportional on real data (Begin payload is a full
resource copy regardless of entropy); `estimated` real share of journal:
~15–25%.

**Costs:** one bulk `UPDATE` per completed update (~600 rows in the soak
shape), best done post-commit/async so `/complete` latency is flat. Heap space
returns via normal vacuum reuse, not file shrink. One subtlety: `JournalAppender`'s
retry idempotency compares stored vs resent payloads — but a PATCH requires a
live lease and stripping happens only after the lease is gone, so no legal retry
can observe a stripped row.

**Forecloses:** nothing. Historical replay to any version boundary never reads
matched Begin payloads (all Begins within a terminal update are matched inside
that same update). **Reversibility:** the *decision* reverses (stop stripping);
already-stripped bytes are gone — acceptable because they are duplicates of the
terminal entries' data. **Interactions:** reduces O6's blob sizes slightly;
worth doing even with O6 because it also thins the hot window O6 never touches.

### O5 — Fold each terminal update's event stream into one compressed blob

**Changes:** `update_events` is 48% of the database and is append-only per
update; once the update is terminal the stream is immutable, and the console
reads it only per-update, in sequence order, 500 at a time. Add an
`update_event_archives` table (one row per update: compressed serialized event
stream + count/max-sequence metadata, composite FK to `stack_updates`). A
background folder — same SKIP-LOCKED/idempotent pattern as `compaction_jobs` —
folds an update's events into a blob after terminal + grace period, then deletes
the archived rows in the same transaction. `ConsoleRunsResource.events` serves
from rows when present, otherwise decompresses the blob and slices the requested
page. Keep `summary` (and optionally `prelude`) rows hot — 700 kB total
(`measured`) — because run-detail hits `summary` on every view.

This is a representation change, not retention: the blob preserves every event
bit-for-bit, so console history (timeline, diffs, outputs) is unchanged.

**Saving:** the dominant one. Per the sample update: 822 kB stored → 105 kB
blob (`measured`, gzip -9; app-side zstd would beat this). Applied across the
dataset: ~1,425 MB event payload → **~210 MB estimated**, plus ~167 MB of
per-row heap overhead (heap minus payload, `measured`) and ~124 MB of indexes
shrink toward zero for archived updates. Net effect on this dataset: 1,717 MB →
**roughly 250 MB (estimated)**. §5 sensitivity: the 7.6× is inflated by
synthetic filler; on real data expect **3–5× (estimated)** because the
old/new/pre/outputs duplication *within* one update — the thing block
compression exploits and per-row compression cannot — is real Pulumi behavior,
not a generator artifact. Even at 3× this option halves total database size.

**Costs:** a new table, a folder worker, and a two-branch read path in one
endpoint. Decompressing a ~100 kB–1 MB blob per console page view is
single-digit milliseconds. Live tailing is untouched (rows exist until terminal
+ grace). Bulk row deletes create vacuum work — autovacuum absorbs it at real
install rates; space is reused, not returned to the OS (normal for Postgres).
Codec choice (JDK gzip = no new dependency, vs zstd-jni = better ratio) is an
implementation decision.

**Forecloses:** ad-hoc SQL over old event payloads — which nothing does today
(`measured`: no SQL reads inside `payload` anywhere). Per-event rows can always
be re-materialized from a blob. **Reversibility:** good — an unfold routine
turns a blob back into rows; keep it as an admin/test tool and the whole option
rolls back cleanly. **Interactions:** makes event-side O2 mostly moot for cold
data; independent of journal options; the preview knob (O8) rides on it.

### O6 — Cold journal segments below the compaction boundary (later, gated)

**Changes:** the same fold pattern applied to the system of record: journal
rows for terminal updates entirely below every consumer's horizon (below the
stack's compaction boundary, update terminal) are moved into per-update
compressed segments; hot rows above the boundary stay as rows. The current
export path is untouched (`StackAssembler` reads only above the boundary —
`measured`); only the future per-version export reads segments.

**Saving:** 567 kB → 59 kB per update (`measured`, 9.6×, same §5 caveat →
**3–5× estimated real**). On this dataset journal would drop from 1,419 MB to
roughly **200–300 MB (estimated)**.

**Costs & why it is gated:** this touches the system of record — a bug here
corrupts state history rather than a run log. It must not run while the update
is within `JournalAppender`'s retry window, must serialize with compaction
(boundary only advances, which helps — a segment's eligibility is monotonic),
and the per-version export path (`StackAssembler.assembleAt`) must learn to
read segments before rows below the boundary are ever deleted. Do this only
after O5 has proven the fold/unfold pattern in production.

**Forecloses:** nothing if segments are lossless. **Reversibility:** same
unfold story as O5. **Interactions:** depends on O5 (pattern) and O7 (the only
reader); O4 shrinks its inputs.

### O7 — Per-version export (implemented)

`pulumi stack export --version N` is served by `GET
/pulumi/api/stacks/{org}/{project}/{stack}/export/{version}`
(`StackExportResource.exportVersion`) via `StackAssembler.assembleAt` — the
bounded-replay variant following the O3 lookup contract. The storage plan must
keep it working: O3's ladder bounds its latency (never its correctness), O4
never strips what bounded replay reads (matched Begins are matched within
their own update), O6 must teach it to read cold segments before any journal
row is folded. Note the wire path is Pulumi's own (`ExportStackDeployment`
appends `/{version}`), so protocol compatibility applies to this endpoint.

### Rejected options, and why

- **Content-addressed resource storage / delta snapshots.** The journal *is*
  already the delta representation, and it is retained in full — every version
  is reachable without any new structure. CAS adds GC of unreferenced blobs,
  join-heavy assembly, and a new interaction surface with the compaction
  exclusion invariant, to optimize `snapshots` — which O3 reduces to a handful
  of rows per stack anyway. Also `unknown` benefit: this dataset cannot measure
  cross-version dedup (§5: 0.3% identical resources is a generator artifact).
  Revisit only if a future measured workload shows the snapshot ladder itself
  is a cost center.
- **Deriving console events from the journal.** The console renders
  `metadata.old` for diffs; the journal stores only new state. Synthesizing
  `old` means replaying prior state per view — complexity and latency to save
  bytes O5 already saves without semantic risk.
- **Retention-first deletion.** Rejected by the owner; also unnecessary — O5
  keeps full fidelity at ~4–8× less space. The one nuance worth surfacing:
  **preview** event streams (27% of events, `measured`) are what-if output, not
  infrastructure history. An optional, off-by-default retention knob for
  *archived preview blobs* (O8) is defensible — capability lost: viewing old
  preview run detail past the horizon; the `expired` flag already models this.
- **Natural-key PKs / dropping UUID surrogates** (~90 MB `measured` in §4):
  fights the documented `BaseEntity` convention and Hibernate wiring for a
  saving O5/O6 mostly obsolete (archived rows disappear along with their PKs).
- **`update_events.kind TEXT → SMALLINT`** (~11 MB heap + index effects,
  `measured` avg 10 B/row): real but small, and the cold copies get compressed
  inside blobs anyway. Not worth a migration alone; fold into the archive
  format instead.
- **Partitioning.** Buys vacuum locality and drop-partition retention — but
  retention isn't the strategy and O5 already removes the bulk. Operational
  complexity for a self-hosted single-service posture; defer.
- **zstd dictionaries.** `measured` worse than plain zstd at these sizes (§4).

---

## 2. Recommendation

Sequenced cheapest-and-safest first. Expected end-state on the soak dataset:
**3.5 GB → ~1.1 GB after phases 1–4, ~0.5–0.7 GB if phase 5 ships**
(`estimated`, composed of the per-option figures above). In the §4 unit:
~8.7 KB per resource per update → **~3.5 KB (phases 1–4) → ~1.5–2 KB (with
phase 5)**, with real-world compression entropy likely costing a further
~1.5–2× on the blob-compression terms (`estimated`).

1. **O1 + O2** (one migration each, no code): drop duplicate indexes, lz4 for
   new writes. Zero-risk, immediate, improves the hot path.
2. **O3** snapshot pruning with a keep-ladder. Small code change inside the
   lock already held; 11% of the dataset; enables sane per-version latency
   later.
3. **O4** Begin-payload stripping on terminal. 8% of the dataset, provably
   unread bytes, crash-recovery semantics preserved by the matched-only rule.
4. **O5** event archival. The big one — targets the 48% table with a measured
   per-update 7.6× — and establishes the fold/unfold pattern, job queue, and
   read-path fallback that O6 later reuses. Ship with the unfold tool.
5. **O6** journal cold segments — only after O5 has soaked in production;
   per-version export (O7, implemented) is the reader it must serve. Do not
   start it before then.

**Do-nothing baseline:** at 8.7 KB/resource/update, an install doing 20
updates/day on a 100-resource stack accrues ~17 MB/day ≈ 6 GB/year
(`estimated`) — a defensible "do nothing" for small installs, and phases 1–2
alone are probably enough for them. A busy install (200 updates/day across
stacks averaging 300 resources) accrues ~0.5 GB/day ≈ 190 GB/year
(`estimated`) — that is who phases 3–5 are for. The plan degrades gracefully:
each phase stands alone, so an operator-visible win lands at every step.

**What I would not do:** anything in the rejected list above, and O6 out of
order. The journal is the only copy of history once snapshots are pruned;
touching its representation is last, behind a proven pattern and a real reader.

**Open questions the soak data cannot answer** (per §5): real-world blob
compression ratios (needs a capture of genuine provider state — the recorder
fixtures in `tools/pulumi/recorder/fixtures/` are a starting corpus); whether
real installs' preview share matters (soak's 27% is generator-shaped); real
stack-trace prevalence in event payloads (zero here, `measured` — so
`withoutStackTraces` parity for events is `unknown` value and left out of the
plan; re-measure on a real Node/Python workload before bothering).
