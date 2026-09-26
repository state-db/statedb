# Letting Aurora Serverless pause when state-db is idle

Status: proposal, not implemented.

## Objective

Deploys are sporadic, especially outside business hours. On Aurora Serverless v2 with a
minimum capacity of 0 ACU, the database can pause and stop billing compute while nothing uses it.
state-db should allow that pause during idle periods without changing behavior for deployments
on a provisioned RDS instance, which cannot pause.

Aurora pauses only after it has had **no connections** for `SecondsUntilAutoPause` (minimum 300s).
Fewer queries are not enough; the application must stop opening connections entirely.

## What keeps the database awake today

| Source | Interval |
| --- | --- |
| `ReportDeliveryWorker` poll | `statedb.reports.poll-interval`, 5s |
| `CompactionWorker` poll | `statedb.compaction.poll-interval`, 10s |
| `EventArchiveFolder` poll | `statedb.events.archive.poll-interval`, 5m |
| `ExpiredApiTokenPurger` | 24h |
| Agroal pool | idle connections survive up to about two `idle-removal-interval` periods |

`/health/live` does not use the database, so load balancer health checks do not keep it awake.

## Constraints

- Background work is deferrable. Snapshots are a rebuildable cache, reports are optional, and
  retention and purges can run late. Correctness must not depend on a poll running.
- Every background job starts from a request: a run completes, gets cancelled, or ends in a
  compaction. The pod that served that request is the one that should drain the work.
- No new shared state, leader election, or `LISTEN/NOTIFY`. A listening connection would itself
  prevent the pause.
- Existing deployments must behave exactly as they do today unless an operator opts in.

## Candidate approach: per-pod idle switch

1. **Activity clock.** A bean holds the time of the last `/pulumi/*` or `/api/*` request, updated
   by a Vert.x route handler. Health routes do not count. It starts at boot time, so a new pod
   first drains any pending work.
2. **Skip scheduled ticks while idle.** A shared `Scheduled.SkipPredicate` skips a tick when the
   last request is older than `statedb.idle.after`. Each `@Scheduled` job adds
   `skipExecutionIf = SkipWhenIdle.class`; the job bodies do not change. `0` disables the switch
   and is the default.
3. **Let the pool drain.** Keep the pool minimum at 0 and set a short `idle-removal-interval`
   (about 2m) so connections close soon after the jobs go quiet.
4. **Tolerate resume latency.** Resuming takes roughly 15s. Raise
   `quarkus.datasource.jdbc.acquisition-timeout` above the 5s default (about 30s) so the first CLI
   request after a pause waits instead of failing.

Work left behind, such as a report retry backed off past the window or a daily purge, runs on the
next request. Pods that received no requests can sleep safely because they created no work.

For a 15m idle window, about 5m of pool drain, and a 10m `SecondsUntilAutoPause`, the database
pauses about 30 minutes after the last request.

## Alternative considered: nudge on write, slow fallback poll

Code that creates work wakes the local worker after commit, and the scheduled polls drop to about
one hour as a safety net. It is similarly small, but every work-creating path must remember to
nudge, and the hourly poll still resumes a paused database 24 times a day.

Only lengthening the poll intervals was rejected: it trades pause time for slower reports and
compaction and does not reach zero connections.

## Open questions

- What idle window is long enough to cover report retries and compaction after a busy deploy?
- Should the daily token purge be exempt from skipping, or is running on the next request enough?
- Should the shipped CloudFormation offer an Aurora Serverless option, or is this only for
  self-managed database setups?
