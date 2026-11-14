# Load and recovery

Concurrent Pulumi runs consume memory, database connections, and worker threads.
Runs on different stacks proceed in parallel, while writes to one stack serialize
on its database locks.

The safeguards below reduce avoidable contention and keep recovery paths open.
They do not establish a fixed number of supported concurrent deployments:
resource size, event volume, replay length, and database latency all affect
capacity.

## Memory

The native image entry point sets `-XX:MaximumHeapSizePercent=60`. Without it the
Serial GC heap may grow to 80% of container memory, leaving too little room for
the JNI libraries, thread stacks, and other native allocations. The percentage
scales with whatever memory limit the deployment sets, so it needs an explicit
container memory limit to be useful.

Each run calls `GET /export`. `StackAssembler` streams journal projections
instead of loading ORM entities, but it builds the complete deployment as a JSON
tree, so large concurrent exports can still exhaust a small task. Compaction and
snapshot spacing limit how much journal each export replays.

The [soak workbench](../workbench/soak/README.md) has a burst mode that
reproduces memory pressure with large resource payloads. Its results depend on
the configured workload and task size, not only on the number of stacks.

## Database writes

Each resource step in the CLI waits for its journal entry to be saved, so journal
latency directly slows a run. Hibernate batches inserts, and the PostgreSQL
driver rewrites each batch into multi-row statements. Both are runtime settings:

| Configuration | Default |
| --- | --- |
| `statedb.db.insert-batch-size` | `50` |
| `statedb.db.rewrite-batched-inserts` | `true` |

The driver rewrite only applies to batches Hibernate creates, so setting the
batch size to 0 disables both. The batch size goes through Hibernate's runtime
`hibernate.jdbc.batch_size` property because Quarkus fixes its own batch-size key
at build time.

Journal writes for one stack serialize on `stack_state`. Lease revalidation takes
a shared lock on the lease row, so a run's journal append and event batches can
proceed together, while cancellation, completion, and renewal wait for them to
commit.

Event archiving and compaction have per-tick limits. These bound background work
but are not admission control for HTTP requests; the database pool and
transaction wait time remain the main capacity indicators.

## Health and shutdown

`GET /health/live` on the public port runs on the event loop and never touches
the database or a worker thread, so a task busy with Pulumi writes does not fail
its load balancer check. It shows that the HTTP process is alive, not that it can
accept state writes.

The management listener on port 9000 serves `/q/health/ready`, which includes
database health and the Cedar authorization self-test. Management endpoints stay
off the public listener.

Quarkus allows 30 seconds for graceful shutdown so in-flight journal and event
requests can finish during scale-in or rollout. The orchestrator's stop timeout
must be longer than that before it kills the process.

## Leases and interrupted updates

The CLI assumes a five-minute lease from the moment it starts and renews after
two and a half minutes, whatever expiry the server returns, so the default
`statedb.lock.lease-ttl` is five minutes. A shorter lease can expire before the
first renewal. A longer one delays takeover after a crash, because a run that
dies before its first renewal keeps the stack locked for the whole initial lease.
If every state-db task is down for more than about two and a half minutes, a
still-running client cannot renew and loses its lease; its journal saves are
already failing at that point.

A crash cannot leave part of a journal batch in the database, because each batch
commits as one transaction. Expired-lease takeover marks the displaced active
update as failed. It does not roll back infrastructure changes or erase committed
journal entries.

After an interrupted run:

- `pulumi cancel` releases the lease at once instead of waiting for it to expire.
- Pending creates in the next export mean a provider call may have succeeded
  without its Success entry being saved. Resolve them with
  `pulumi refresh --clear-pending-creates` or `--import-pending-creates`.

Nothing periodically cleans up abandoned update records. A client that creates
an update but never acquires a lease leaves an `in-progress` history row that no
takeover will fail, because it was never the stack's active update.

## Observability and verification

The runtime exposes Agroal pool metrics, logs SQL slower than 100 ms, and logs
requests slower than `statedb.ops.slow-request-threshold` (250 ms) together with
pool state. These separate database saturation from expensive state
reconstruction. Configuration lives in
[application.yml](../modules/app/src/main/resources/application.yml).

The [fault suite](../modules/pulumi-cli-smoke/README.md) covers interrupted runs,
concurrent writers, cancellation, and lease renewal, and the soak workbench
measures resource pressure. Neither replaces capacity tests against a
deployment's own stack sizes and database.
