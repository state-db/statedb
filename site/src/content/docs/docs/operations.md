---
title: Operate statedb
description: Upgrades, backups, replicas, sizing, monitoring, and recovering an interrupted update.
---

Running statedb means running one stateless service and one PostgreSQL
database. This page covers the work after installation. For the settings that
get it started, see the [self-managed installation](/docs/deployment/).

## Upgrades

To upgrade, replace the application image. Every schema migration ships inside
the image and runs at startup, before the new task accepts traffic, so there is
no migration job to run.

- Keep the old tasks serving until a new one reports ready, and enable
  automatic rollback for a service that never becomes steady.
- If a migration fails, the new task exits and the old tasks keep serving.
- When several new tasks start together, one applies the migration while the
  others wait on a database lock and then verify the result.
- Give a stopping task more than 30 seconds before it is killed. statedb uses
  that time to finish in-flight journal and event writes.

Old and new tasks overlap during a rollout, so each release's migrations are
written to work with the previous release's image, and every release is tested
by upgrading from the one before it. Upgrading one release at a time is
therefore the tested path. Check the release notes for irreversible migrations
before you roll back.

## Backups

Every statedb record is in PostgreSQL: checkpoints, history, users,
organizations, grants, token hashes, and the service secrets keyset. Your
existing database backup covers all of it, whether that is managed snapshots,
WAL archiving, or `pg_dump`.

:::caution
The keyset for the service-managed secrets provider is in the same database.
A backup holds everything needed to decrypt those secrets, so protect backups
as carefully as the database.
:::

Test the restore. Restore into a separate database, start the same statedb
version against it, and export a stack with the Pulumi CLI. That exercises the
whole path from stored rows back to a checkpoint the CLI accepts.

## Replicas

Update leases and background work are coordinated through PostgreSQL, so any
healthy replica can serve any request. Every replica needs the same database
and the same `STATEDB_SESSION_ENCRYPTION_SECRET`; without a shared secret, a
browser session breaks when a request reaches a different replica.

## Sizing

Rebuilding a checkpoint holds the whole deployment in memory, so memory use
follows the size of your largest stacks and how many of them deploy at once.
Start at 1 vCPU and 2 GiB per task. A task with 0.25 vCPU and 512 MiB is not
enough for concurrent deploys of stacks with hundreds of resources.

Set an explicit container memory limit. The heap is capped at 60% of it, and
the rest is left for native libraries and thread stacks.

Two settings control how journal and event rows are written:

| Variable | Default | Meaning |
| --- | --- | --- |
| `STATEDB_DB_INSERT_BATCH_SIZE` | `50` | Rows per insert batch |
| `STATEDB_DB_REWRITE_BATCHED_INSERTS` | `true` | Send each batch as one multi-row statement |

A batch size of `0` turns both off and writes one statement per row.

## Health and monitoring

| Purpose | Port | Path |
| --- | --- | --- |
| Readiness | `9000` | `/q/health/ready` |
| Liveness | `9000` | `/q/health/live` |
| Liveness, for a load balancer that cannot reach `9000` | `8080` | `/health/live` |
| Prometheus metrics | `9000` | `/q/metrics` |

Readiness covers the database and an authorization self-test. The public
`/health/live` never touches the database, so a task that is busy with Pulumi
writes still answers it. It shows that the process is alive, not that it can
accept writes.

Watch the database connection pool first. The metrics include pool gauges, and
statedb logs any request slower than 250 ms together with the pool's state, and
any SQL statement slower than 100 ms. A pool that stays full means the database
or the task count is the limit.

Background workers poll the database every few seconds, so a serverless
database that pauses when idle will not pause while statedb is running.

## Interrupted updates

An update holds a lease on its stack and renews it while it runs. If the CLI
dies, the lease expires five minutes after its last renewal and the next update
takes over. statedb marks the interrupted update as failed. It does not undo any
infrastructure change the CLI had already made.

- To release a stack sooner, run `pulumi cancel`, or use **Force-unlock** in the
  console under **Manage → Operations**.
- If the next update reports pending creates, a provider call may have finished
  without its result being saved. Resolve them with
  `pulumi refresh --clear-pending-creates` or `--import-pending-creates`.

If every statedb task is down for more than about two and a half minutes, a
running CLI cannot renew its lease and its update fails. State already written
is safe in PostgreSQL.

## Losing the identity provider

If your OpenID Connect provider is unavailable, existing CLI tokens keep working
until they expire, and CI is unaffected because service-account tokens do not
depend on it. To reach the console, use
[recovery access](/docs/deployment/#recovery-access).

## Moving off statedb

State leaves the same way it arrives. [Migrate stacks](/docs/migration/) covers
exporting a stack to another backend.
