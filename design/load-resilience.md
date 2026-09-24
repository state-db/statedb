# Load Resilience: Concurrent Deploys on Small Tasks

This plan lists the changes needed so that a burst of concurrent Pulumi runs
makes state-db slower instead of killing its tasks. It comes from one
production incident. Each item says whether it is verified in code or still a
hypothesis.

## Incident

- A CI job ran `pulumi up` on 12 stacks at once against a deployment with 2
  ECS tasks sized like `deploy/aws/cloudformation.yaml`'s defaults, backed by
  Aurora Serverless (not the CloudFormation installer's `db.t4g.small` RDS
  instance — this plan's ECS/application changes are scoped to the task, not
  the database).
- Every run failed with a 502 during the refresh phase.
- Task A was `OOMKilled`.
- Task B failed its health check (timeout) and ECS replaced it.
- With 4 tasks, the same job succeeded.
- Slow-query log: `INSERT INTO journal_entries` took 102 ms,
  `INSERT INTO update_events` took 600 ms, and many `SELECT`s took about
  150 ms.

## Likely failure chain (OOM step reproduced; see section 0)

1. Each run calls `GET /export`. The assembler builds the full deployment as a
   Jackson tree in memory (`StackAssembler.java:58`). Each run also sends one
   sequential stream of journal batches and up to 3 concurrent event batches
   (Pulumi `backend/httpstate/state.go`, `maxConcurrentRequests = 3`).
2. The task has 0.25 vCPU and 512 MB (`cloudformation.yaml:459-460`). The
   native image has no runtime heap limit (`Dockerfile.native:148`). Heap and
   off-heap memory (Cedar JNI, zstd, thread stacks) together pass 512 MB, and
   ECS kills task A.
3. Task A's runs retry onto task B. Its pool connections are held by
   transactions that wait on row locks and on a database (Aurora Serverless,
   not the CloudFormation installer's RDS instance) whose slow-query cause is
   unmeasured. The health check `/api/auth/providers`
   (`cloudformation.yaml:810`) is a `@Blocking` route that runs a DB query, so
   it waits in the same queue. It times out, and ECS replaces task B.
4. The CLI retries each request 4 times with a 1 s, 2 s, 4 s backoff
   (`client/api.go:433`). The retries run out, the journal save fails, and the
   run fails.

Replacing a busy task makes the overload worse, because it drops more
in-flight requests onto the tasks that remain.

## 0. Reproduction (done)

`SOAK_MODE=burst` in `workbench/soak` reproduces the incident. See the soak README for the
command.

Measured, one app container at 512 MB / 0.25 vCPU, 12 stacks updated at once:

- 300 resources × 2 KB per stack: no failures. Peak RSS was about 250 MB.
- 800 resources × 16 KB per stack: OOM kill, then 4 OOM restarts in about 90 s. The CLIs failed
  with `EOF` on `/update` and `/export`; behind the ALB this is the 502. After the crash, 7 of 12
  stacks returned `409 ... currently locked`, and their updates stayed `in-progress`.
- The same load at 2 GB / 1 vCPU: no failures. Peak RSS was about 790 MB.
- Full-GC pauses grew from 194 ms to 612 ms before the kill. The live set peaked at about 386 MB.
- The pool reached 12 active connections with none waiting. Memory ran out first, not the pool.
- `LeaseRevalidator`'s `stack_locks ... FOR UPDATE` took up to 4.0 s at 512 MB, against 0.8 s at
  2 GB.

Not yet measured:

- Which allocation dominates, export trees or batch writes. A heap-focused comparison is still
  needed before changing `/export`.
- The health-check timeout on the second task. With one container, the health check failed
  because the container was down, not because it was queued. Testing this needs two app
  containers behind a load balancer.

Results after `FOR SHARE`, takeover-fails-update, renew duration, JDBC batching, and shutdown
timeout (same 800 × 16 KB burst, 512 MB / 0.25 vCPU, measured):

- Still OOM: 3 restarts within about 20 s in round 1. Round 1 lost 10 of 12 runs to `EOF`. These
  changes are not memory fixes.
- Stranded locks were unchanged: 8 of 12 stacks returned `409 ... currently locked` for all 5
  later rounds. All 8 lock rows still had the 1 h acquire lease. None had renewed, because the
  runs died 5–28 s in, before the CLI's first renew. Honoring the renew duration does not help a
  run that crashes this early. The initial acquire still uses `lease-ttl`, since the CLI sends no
  duration on acquire.
- Time until a stranded stack is usable again was not measured. Inferred: unchanged at up to
  1 h, because the takeover-fails-update path only runs once the lease expires.
- Orphaned updates: 10 stayed `in-progress`. Eight held the stranded locks. Two (soak-11 and
  soak-12) died after `POST /update` but before the lock was acquired. They are not
  `active_update_id`, so a takeover never fails them.
- `LeaseRevalidator` `FOR SHARE` peaked at 614 ms, against 4.0 s before. This is confounded: only
  4 stacks ran after round 1.
- No journal or event `INSERT` passed the 100 ms slow-query log. The soak compose URL does not
  set `reWriteBatchedInserts`, so this run had Hibernate batching only.

Results after shortening `statedb.lock.lease-ttl` to `5m` (same 800 × 16 KB burst, 512 MB /
0.25 vCPU, `SOAK_BURST_ROUNDS=8`, measured from `burst.tsv` and `stack_locks`/`stack_updates`
after the run):

- Still OOM, repeatedly: unlike the single round-1 crash in the previous run, this run crashed
  across most rounds (`EOF` / `read: connection reset by peer` / `unexpected EOF` on `/update` and
  `/export` in rounds 1–8). Confirms again that lease shortening is not a memory fix; section 1
  (deployment sizing) and section 3 (write-path efficiency) are still open.
- **Time until a stranded stack is usable again: measured at about 5–7 minutes, not up to 1 h.**
  `soak-02`, `-03`, `-04`, `-05`, `-06` acquired their lease in round 1 (fired at elapsed ≈287 s)
  and were still rejected with `409 ... currently locked` in round 2 (elapsed ≈344 s) and round 3
  (elapsed ≈471–477 s). All five had recovered and completed successfully by round 4 (fired at
  elapsed 602 s, succeeded by elapsed 715–723 s) — i.e. within one `lease-ttl` window of their
  original acquire, exactly as the 5 m default predicts, against the old default's up-to-1 h
  strand. After the burst finished (driver log `burst load finished`), the 7 locks still live
  (from round-8 acquires that then crashed) had `expires_at` 5 minutes after their `locked_at`,
  each independently expiring on its own schedule rather than sharing one stale 1 h window.
- Orphaned updates (`stack_updates` after the run, 8 rounds × up to 12 stacks): 41 `succeeded`,
  28 `failed`, 36 `in-progress`. Of the 36 `in-progress`: 7 are the stack's current
  `active_update_id` (a lease was live when the burst was torn down — expected, and it expires on
  its own within 5 m). The other 29 are not their stack's `active_update_id`. The run could not
  show whether any of them ever held a lease: the database was destroyed before
  `start_journal_seq` or the lock history was checked. Inferred from the code: acquire sets
  `active_update_id` in the same transaction, and a takeover fails the update it displaces. So the
  likely cause for all 29 is a run that died after `POST /update` but before it acquired the lock.
  The sweep in section 4 covers them either way.
- The report grader (`make soak cmd=report`) refused to grade this run ("only 5 samples; too short
  to grade") — expected, since `make soak cmd=report`'s heap/RSS/pool checks are meant for runs
  past the 2 h mark (see the soak README), and a burst run is intentionally short. The per-round
  numbers above come directly from `burst.tsv` and the database, not from the grader.
- Not measured: which specific fix (lease-ttl vs. the still-open memory work) determines the
  *first*-crash-to-*first*-success gap in a quieter run; this run's repeated crashes make that
  harder to isolate than the single-crash previous run.

## 1. Deployment template (`deploy/aws/cloudformation.yaml`)

| Change | Why | Status |
|---|---|---|
| Change the health check to a route that needs no DB connection and no worker thread | Today the health check waits in the same queue as Pulumi writes, so a busy task looks dead | Done. `AWS::ECS::ExpressGatewayService`'s `HealthCheckPath` has no separate health-check-port property (AWS CloudFormation resource reference), so it can only reach the primary container port. Added a public `GET /health/live` route on 8080 (`LivenessRoute`, registered on the Vert.x router, never touches the database or a worker thread) and pointed `HealthCheckPath` at it. |
| Increase the default task size, for example to 1 vCPU / 2 GB, and make it a parameter | 512 MB leaves too little headroom above the heap. 0.25 vCPU makes Serial GC pauses long. | Done: `AppTaskSize` parameter, default `1vCPU-2GB`, mapped to Fargate `Cpu`/`Memory` via the `AppTaskSizes` mapping. |
| Set an explicit heap limit in the image `ENTRYPOINT` | Keeps the GC working before the container reaches its memory limit | Done: `-XX:MaximumHeapSizePercent=60`. GraalVM native-image memory management docs: Serial GC defaults the max heap to 80% of physical memory when unset, and `-XX:MaximumHeapSizePercent`/`-Xmx` are both accepted at runtime by a native executable. |
| Make `MinTaskCount` a parameter with a documented minimum for CI bursts | The incident went away at 4 tasks | Done: `MinTaskCount` (default 2) and `MaxTaskCount` (default 4) are both parameters now, with a `Rules` entry rejecting `MaxTaskCount < MinTaskCount`. |
| Add `StopTimeout` to the app container | Let the 30s `quarkus.shutdown.timeout` drain finish before Fargate's SIGKILL | Done: `StopTimeout: 45`. |

Also update `site/src/content/docs/docs/deployment.md` sizing guidance.

**Results** (measured, same burst as section 0: `SOAK_MODE=burst SOAK_BURST_STACKS=12
SOAK_BURST_ROUNDS=4 SOAK_STACKS=12 SOAK_RESOURCES=800 SOAK_PAYLOAD_BYTES=16384`, one container,
single run at each size, `docker events`/`docker inspect` and `/health/live` +
`/api/auth/providers` polled about once a second from the host throughout):

| | `SOAK_MEMORY=512m SOAK_CPUS=0.25` | `SOAK_MEMORY=2g SOAK_CPUS=1.0` (new default) |
|---|---|---|
| Burst failures | 33/48 | 0/48 |
| Container OOM kills (`docker events`, `RestartCount`) | 7 | 0 |
| In-process `OutOfMemoryError: Garbage-collected heap size exceeded` (app log) | 3 | 0 |
| `/health/live` responses | 285×200, 6 timeouts | 482×200, 0 timeouts |
| `/api/auth/providers` responses | 284×200, 7 timeouts | 482×200, 0 timeouts |
| `/health/live` latency mean / max | 66.7 ms / 1088 ms | 13.8 ms / 251 ms |
| `/api/auth/providers` latency mean / max | 71.5 ms / 698 ms | 17.1 ms / 235 ms |
| Peak RSS (sampler `series.tsv`) | not captured (container cycling) | ~715 MB |

At 512 MB, `-XX:MaximumHeapSizePercent=60` changed the failure mode partway, as expected: 3 of the
crashes surfaced as a catchable in-process `OutOfMemoryError` on a request or compaction thread
(app log, `04:06:05`/`04:10:56`/`04:11:13`) rather than only a silent container kill, but the
container's cgroup memory (heap + off-heap JNI/thread-stack pressure) still exceeded 512 MB 7
times and ECS-equivalent `docker events` still shows `oom`/`die`. The heap limit alone does not
fix 512 MB / 0.25 vCPU; 1 vCPU / 2 GB is still the smallest size that ran this burst clean, which
is why it is now the template default.

All timeouts on both health routes landed in the same few-second windows as a restart (both routes
were unreachable together, because the whole process was down) — this single-container harness
does not reproduce the ECS scenario where a *second*, still-healthy task's health check queues
behind another task's retry storm; that needs two app containers behind a load balancer and is
still unmeasured (see section 0). What this run does show: `/health/live` never has to touch the
database or a worker thread, so nothing about its own design can make a busy-but-alive task look
dead the way `/api/auth/providers`'s `@Blocking` DB query could.

## 2. Backpressure (application)

Goal: overload returns `429` with `Retry-After`. The Pulumi CLI retries `429`
like `5xx` and honors `Retry-After` up to a cap (`sdk/go/common/util/httputil/http.go:116`).

- Put a per-task concurrency limit on `GET /export` and on the other paths that
  assemble full state, such as historical export and stack delete. When the
  limit is full, return `429` with a short `Retry-After`.
- Put a limit on journal and event appends that fails fast when the pool is
  near its limit, instead of waiting on the acquisition timeout.
- Set the Agroal `max-size` and `acquisition-timeout` explicitly in
  `application.yml`, and size the pool against RDS `max_connections` × task
  count.
- The CLI tries each request only 4 times. Keep the `Retry-After` values short
  (1-2 s) so that a burst slows down but does not fail.
- `StackLockAcquirer.awaitCompactionSettled` calls `Thread.sleep` on a request
  worker thread (`StackLockAcquirer.java:77`). Code reading shows it holds a
  worker thread, not a DB connection: each poll uses its own short
  transaction. It can hold that thread for up to `compaction-wait` (30 s by
  default) per acquire. In a burst this can use up the worker pool. It is
  unmeasured.
- `CompactionWorker.compact` holds the `stack_state` row lock while it
  assembles the full deployment and prunes snapshots (`CompactionWorker.java:156-193`).
  A lock acquire for the next update waits behind it. Its duration on a large
  stack is unmeasured.
- Tests: a contract test for `429` and `Retry-After` on each route that is
  limited. Add `./dev protocol` coverage so a real CLI retries through a `429`.

## 3. Write-path efficiency (application)

Journal latency directly slows the CLI. Each resource step waits for its entry
to be saved (Pulumi `journal/snapshot.go`, `AddJournalEntry`), and a run sends
its journal batches one at a time.

- **JDBC batching.** `JournalAppender` (`:150`) and `EventBatchAppender` (`:63`)
  persist one row at a time. Set pgjdbc `reWriteBatchedInserts` and the
  Hibernate batch size. `BaseEntity` IDs are app-assigned (UUIDv7), which
  batching needs. Done: both are operator flags,
  `STATEDB_DB_REWRITE_BATCHED_INSERTS` (default `true`, via
  `quarkus.datasource.jdbc.additional-jdbc-properties.reWriteBatchedInserts`,
  a runtime Agroal property) and `STATEDB_DB_INSERT_BATCH_SIZE` (default `50`).
  `quarkus.hibernate-orm.jdbc.statement-batch-size` turned out to be
  build-time-fixed in Quarkus 3.38 (`phase: BUILD_TIME` in its
  `quarkus-config-model.json`, and the config class only exists in the
  `-deployment` jar) — an env var would silently do nothing in the native
  image — so batch size instead goes through Hibernate's own runtime
  `hibernate.jdbc.batch_size` via `quarkus.hibernate-orm.unsupported-properties`,
  confirmed genuinely runtime by decompiling `HibernateOrmRecorder`: it applies
  the whole map to `RuntimeSettings.Builder` at Hibernate bootstrap, which runs
  at application start, not image build. Proved against the driver in
  `RewriteBatchedInsertsEnabledTest`/`RewriteBatchedInsertsDisabledTest`
  (`modules/app/src/test/java/app/statedb/app/ops/`): a raw `executeBatch()` of
  8 identical prepared INSERTs reports `Statement.SUCCESS_NO_INFO` (-2) per row
  with rewrite on, and `1` per row with it off.

  **Results** (measured, `SOAK_MEMORY=2g SOAK_CPUS=1.0`, `SOAK_MODE=burst
  SOAK_BURST_STACKS=12 SOAK_BURST_ROUNDS=4 SOAK_STACKS=12 SOAK_RESOURCES=800
  SOAK_PAYLOAD_BYTES=16384`, three runs, each 48 burst operations, `/q/metrics`
  and the slow-query log read before `make soak cmd=destroy`):

  | Run | journalentries mean / max | events/batch mean / max | burst failures | slow single-row journal/event `INSERT`s |
  |---|---|---|---|---|
  | (a) rewrite on, batch 50 (defaults) | 41.5 ms / 512.6 ms | 155.3 ms / 1033.0 ms | 0/48 | 0 |
  | (b) rewrite off, batch 50 | 44.8 ms / 780.6 ms | 168.9 ms / 1404.0 ms | 0/48 | 0 |
  | (c) rewrite on, batch 0 (Hibernate batching off) | 47.8 ms / 405.1 ms | 215.4 ms / 902.1 ms | 0/48 | 210 |

  At this burst size (2 GB / 1 vCPU, the shape that survived the section-0
  reproduction), all three configurations completed all 48 operations with no
  failures — this burst does not by itself reproduce the incident's overload,
  so it isolates write-path cost rather than crash behavior. Mean latency
  differences between (a) and (b) are small (~3 ms journal, ~14 ms events) and
  close to run-to-run noise at this scale; the max columns are noisy in the
  same way and don't order cleanly by configuration. The clean signal is the
  slow-query count: 0 in (a) and (b), 210 in (c). With `insert-batch-size=0`,
  Hibernate never calls `addBatch()`, so pgjdbc's rewrite never gets a batch to
  rewrite either — `STATEDB_DB_REWRITE_BATCHED_INSERTS=true` alone does nothing
  without Hibernate-side batching to feed it. Turning off Hibernate batching
  reproduces the original incident's signature (`journal_entries` and
  `update_events` inserts individually crossing the 100 ms slow-query
  threshold), which run (c)'s log confirms directly. Not confirmed in this run:
  a Postgres-side statement count showing the multi-row rewrite in flight
  (`pg_stat_statements` was not preloaded in the soak Postgres image); the
  `-2` update-count behavior from the Step 1 test is the direct evidence that
  the rewrite itself works, and is why it's `reWriteBatchedInserts` reaching
  the driver.
- **Lock mode in `LeaseRevalidator`** (`:33`). Change `FOR UPDATE` to
  `FOR SHARE`. `FOR SHARE` still blocks the `DELETE` and `UPDATE` from cancel,
  complete, and renew, which is the guarantee we need. It also lets the event
  batches for one update run in parallel with each other and with the journal
  append. Journal appends are already serialized by `stack_state`. Update the
  Javadoc and the concurrency tests. Done. Renew starvation was measured with 4
  threads re-taking the share lock back to back for 3 s: renew max 37 ms under
  `FOR SHARE` and 96 ms under `FOR UPDATE`. So renew needs no priority
  mechanism. `LeaseRenewalStarvationTest` guards this.
- **Fewer round trips in `JournalAppender`.** Replace the `UPDATE` followed by
  a `SELECT start_journal_seq` (`:110-122`) with one `UPDATE ... RETURNING`, or
  a `COALESCE`. For retried entries, compare the whole batch in one query
  instead of one `payloadEquals` query per entry (`:216`).
- **Export memory.** Measure the peak memory of an `/export` on a large stack.
  If the reproduction points at the export trees, stream the response, or at
  least drop the base tree as soon as the replay has materialized it.

## 4. Crash and lease correctness (application)

A pod crash cannot leave a partial journal batch in the database: each PATCH is
one transaction, and a retry of a committed batch is an idempotent no-op. The
gaps below are about locks and update status after a crash.

- **Honor the CLI's requested lease duration.** The CLI asks for 5 minutes
  (Pulumi `state.go:152`). `StackLockRenewer` (`:28`) and `StackLockAcquirer`
  (`:116`) always apply `statedb.lock.lease-ttl` (1 h). After a crash, the
  stack stays locked for up to an hour unless someone runs `pulumi cancel`.
  Read the requested `duration` from the renew request. Clamp it to
  `lease-ttl` and to `max_expires_at`. Check the wire shape against
  `apitype.RenewUpdateLeaseRequest` and the fixtures. Done for renew.
- **Shorten the acquire lease (done).** The soak showed that renew alone does
  not help: runs died before their first renew and kept the 1 h acquire
  lease. The CLI ignores the server's expiry. It assumes 5 minutes from the
  start, checks every 37.5 s, and renews after 2.5 minutes (Pulumi
  `state.go:152`, `token_source.go:88,109`). So a 5-minute acquire lease
  matches what the CLI already assumes. `design/architecture.md` said the 1 h
  default existed because the "CLI doesn't always renew" — that claim did not
  hold up. Source audit (`pkg/backend/httpstate/backend.go`, both v3.259.0 and
  the minimum-supported v3.225.0): `Preview`, `Update`, `Import`, `Refresh`,
  `Destroy`, and `Watch` all route through `apply` → `runEngineAction` →
  `newUpdate` (`state.go:143-164`), which builds the same renewing
  `tokenSource` whenever a token is present, regardless of `--preview-only` or
  update kind. The only use of `updateTokenStaticSource`
  (`client.go:2295`/`2279` depending on version) is inside
  `RenewUpdateLease` itself, to carry the current token on the renew call —
  not a long-held static token on any lease-consuming path. `pulumi state`
  edit commands and `stack import` go through `ImportDeployment`
  (`backend.go:2342`/`2084`), which uploads the whole edited deployment in one
  blocking call and never holds a client-side lease token at all.
  `CancelCurrentUpdate` calls `CancelUpdate` with the user's own auth, not a
  lease token. Server side, `StackLockAcquirer` (the only lease acquirer) is
  called from a single route, `UpdateResource`'s `POST /update/{id}` — no
  console, importer, or renamer path acquires a lease directly. No lease path
  in the supported range held a static token, so the default was changed:
  `statedb.lock.lease-ttl` is now `5m` (`modules/app/src/main/resources/application.yml`).
  Proved against a real CLI in
  `modules/pulumi-cli-smoke/.../faults/LeaseRenewalFaultIT.java`
  (`longRunningUpRenewsTheLeaseBeforeCompleting`): a `pulumi up` held open past
  2.5 minutes by a 200 s slow resource shows `stack_locks.heartbeat_at`
  advancing and the lease token rotating without any operator action, then
  completes successfully. This adds about 3 minutes to `./dev protocol`.
- **Sweep orphaned updates (next).** A run can die after `POST /update` but
  before it acquires the lock. That update is never `active_update_id`, so a
  takeover never marks it failed. The second soak left 29 of them. Under
  `AdvisoryLock#runExclusive`, periodically mark failed any `in-progress`
  update that is not its stack's `active_update_id` and is older than
  `lease-ttl` plus a margin. This is safe for a run that is still alive: the
  CLI acquires within seconds of `POST /update`.
- **Trade-off of the 5-minute lease.** If every state-db task is down for more
  than about 2.5 minutes, a run that is still alive cannot renew and loses its
  lock. Such a run already fails, because its journal saves fail, so this
  costs nothing new.
- **Mark abandoned updates as failed.** When a new run takes over an expired
  lock (`StackLockAcquirer.java:142`, then `:173`), the previous update stays
  `in-progress` forever. Its events are never folded, and history shows it as
  running. In the same transaction, set the previous `active_update_id` to
  `failed` and set `ended_at`. Also add a periodic sweep under
  `AdvisoryLock#runExclusive` for expired leases on stacks that nobody touches
  again. Keep the terminal-status trigger rules.
- **Graceful shutdown.** `application.yml` has no `quarkus.shutdown.timeout`.
  Add a shutdown timeout, and make the ECS stop timeout longer than it, so a
  scale-in or rollout lets in-flight journal PATCHes finish.
- **Idempotent event retries (low priority).** A retry of a committed event
  batch returns `409` (`EventSequenceConflictException`). The CLI only logs it,
  so no state is at risk. Consider the same idempotent no-op that
  `JournalAppender` uses.
- Tests: contract tests for lease duration, takeover marking the previous
  update failed, and the sweep. Run `./dev protocol` for the lifecycle
  changes.

## 5. Operator guidance

Add a short recovery section to `pulumi-state-lifecycle.md` or the deployment
docs:

- After a failed run, `pulumi cancel` releases the lock at once.
- Pending creates in the next export mean that a provider call succeeded but
  its Success entry was not saved. Resolve them with
  `pulumi refresh --clear-pending-creates` or `--import-pending-creates`.
- A crash during refresh is harmless, because refresh changes no cloud
  resources.

## Order of work

1. Template: health check, task size, heap limit (section 1). This is the
   smallest change with the largest effect. Re-run the reproduction.
2. Lease duration and marking abandoned updates as failed (section 4). These
   are correctness fixes and do not depend on the reproduction.
3. Batching and `FOR SHARE` (section 3). Measure before and after.
4. Backpressure (section 2). Tune it against the reproduction.
5. Graceful shutdown, event idempotency, and operator docs.

## Gates

- `./dev quick` while iterating, and `./dev check` before handoff.
- `./dev protocol` for the lease, lifecycle, `429`, and write-path changes.
- `./dev native-contract` if a change adds reflection or changes
  runtime-selected serialization.
