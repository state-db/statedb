# Soak / endurance harness

Runs the real state-db container image under continuous Pulumi load for days at a time, to catch
the failures short tests cannot: heap and GC drift, off-heap/RSS creep, JDBC pool exhaustion,
unbounded history growth, stranded state locks, and crashes or OOM kills.

Compose runs from the **host**, so a multi-day run survives a dev container rebuild. Only the
image build needs that container, so the sequence is: build, stop the dev container, start the
soak. Left running it is a ~4GB co-tenant on the same VM as the 2GB app under test.

```bash
make soak cmd=build     # needs the dev container (Maven + bun)
# stop the dev container here
make soak cmd=up        # build images and start the run (48h by default)
make soak cmd=status    # heap, pool and container state at a glance
make soak cmd=logs      # follow the driver, sampler and app
make soak cmd=report    # grade the run; non-zero exit if a check fails
make soak cmd=down      # stop, keeping volumes so the run can still be graded
make soak cmd=destroy   # stop and delete all volumes
```

## What it runs

| Service | Role |
| --- | --- |
| `app` | The real `state-db:dev` image, capped at 2 CPU / 2GB with swap disabled |
| `postgres` | Its own database and volume, separate from the workbench estate |
| `load` | Pulumi CLI churn via the local-only `statedbsim` provider |
| `sampler` | One TSV row per minute: JVM, pool, RSS and database growth |
| `prometheus` | Scrapes `/q/metrics` so a live run can be graphed interactively |

The load driver cycles create → update → destroy across several stacks, interleaves previews,
periodically injects a deterministic provider fault, races two updates against one stack to
exercise lock contention, and runs console read traffic alongside the writes.

## Tuning

Anything in `workbench/soak/Makefile` can be overridden:

```bash
make soak cmd=up args="SOAK_DURATION_HOURS=12 SOAK_RESOURCES=1000 SOAK_STACKS=8"
make soak cmd=up args="SOAK_MEMORY=1g SOAK_CPUS=1.0"     # model a smaller deployment
make soak cmd=up args="SOAK_CONSOLE_READS=0"             # Pulumi write path only
```

`SOAK_DURATION_HOURS=0` runs until stopped. When the duration expires the driver idles rather than
exiting, so the sampler keeps recording through a quiet window — the lock checks are only
meaningful once load has stopped.

## Reading the results

`make soak cmd=report` copies the artifacts volume to `artifacts/` and grades it. Graded checks:
no threads ever queued for a JDBC connection, no 5xx, no restarts or OOM kills, no locks or
in-progress updates surviving quiesce, and — on runs longer than two hours — bounded heap and RSS
trends. Below that the trends are printed but not graded, because a warming JVM produces an
alarming slope that means nothing.

The heap check regresses the *post-GC floor* rather than raw usage: raw heap mostly measures where
in the GC cycle each sample landed, whereas a rising floor is the leak signal.

History growth is **measured, not graded**. Nothing prunes `snapshots`, `journal_entries`,
`update_events` or `stack_updates` today, so growth is expected; the report exists to quantify the
rate. What it does warn about is *super-linear* growth — events per update climbing over the run,
which would mean per-update cost scaling with history depth.

`artifacts/` also holds `gc.log` (rotating), `pulumi.log`, the raw `series.tsv` and `ops.tsv`, and
any heap dump written on OOM. Native memory tracking is on, so off-heap growth can be broken down
with:

```bash
docker exec state-db-soak-app jcmd 1 VM.native_memory summary
```

## Design notes

**No bind mounts.** The dev container drives the host Docker daemon, so a relative bind mount would
be resolved by the daemon against the host filesystem, where the container's `/workspaces` path
does not exist. Config reaches containers through build contexts, which the CLI streams; results
come back out of named volumes via `docker cp`.

**The sampler shares the app's PID namespace.** That is what lets it read the JVM's true resident
set from `/proc/1/status` without handing a container the Docker socket.

**`artifacts-init` chowns the artifacts volume.** Named volumes are created root-owned and the app
image runs as UID 1000; without this the JVM cannot open its GC log and refuses to start. Doing it
in compose keeps soak-specific paths out of the production image.

**The driver backs off on consecutive failures.** A stack whose state has become unusable rejects
every operation instantly. Without backoff the loop hot-spins, generating thousands of junk updates
per hour and drowning the series in noise.

## Known finding: stacks wedge under rapid churn

On a fresh database, with no manual intervention, sustained churn currently strands an update
`in-progress` on each stack within roughly 10–20 iterations. The lock is held for its full
one-hour lease, so every subsequent operation returns `409 ... currently locked by another update`.

The Pulumi CLI reports a snapshot integrity error on read — "introduced by a previous operation
when it wrote the snapshot" — and the server logs no exception at all. A single `up` in isolation
does not reproduce it. This is why the lock check is graded and why the backoff exists; until it is
resolved, long runs spend most of their time wedged and the memory trends are correspondingly less
meaningful.
