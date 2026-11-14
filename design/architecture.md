# Architecture

state-db runs a Pulumi HTTP backend and a web console in one Quarkus application.
PostgreSQL stores stack state, identity, configuration, and background work. The
production container holds a native executable and the compiled browser assets.

An unmodified Pulumi CLI connects with `pulumi login https://host/pulumi`. The
`/pulumi` prefix is required because the CLI appends its own `/api/...` paths
and the console already owns `/api/`. Pulumi runs the infrastructure program and
calls providers on the client; state-db stores the resulting state and events
and never runs deployments itself.

## Request flow

```text
Pulumi CLI → /pulumi/api/* → API token or update lease ─┐
                                                       ├→ domain services → PostgreSQL
Browser    → /api/*        → console session + Cedar ───┘
           → /console/*    → static React application
```

Requests authenticated by an API token or a console session pass through Cedar
authorization. Requests on lease-scoped update routes rely on the authority the
caller established when it acquired the lease. The
[authentication reference](auth.md) describes both paths.

| Surface | Purpose |
| --- | --- |
| `/pulumi/api/*` | Pulumi protocol: stacks, updates, leases, events, and service secrets |
| `/api/*` | Session-authenticated console API, plus a few public authentication routes |
| `/console/*` | Browser routes served by the SPA fallback |
| `/cli-login` | Browser entry point for `pulumi login` |
| `/health/live` | Public liveness response that never touches the database |
| Port 9000 | Management listener for health and Prometheus metrics |

The SPA fallback serves `index.html` for `GET` requests outside `/api`,
`/pulumi`, and `/q/` whose last path segment has no file extension. An unknown
API route or missing asset therefore returns 404 instead of the app shell, which
would confuse the Pulumi CLI. The console calls only `/api/*` and never the
Pulumi protocol endpoints.

## Modules

| Module | Responsibility |
| --- | --- |
| `persistence` | Entities, migrations, Cedar policies, state reconstruction, background workers, and transactional services |
| `pulumi-api` | Pulumi routes, token and lease filters, wire types, and protocol errors |
| `console-api` | Browser authentication, console queries, and administration endpoints |
| `vcs` | GitHub App authentication and report publication |
| `app` | Quarkus assembly, runtime configuration, health, database credentials, and static delivery |
| `web` | React application with TanStack Router and TanStack Query |
| `test-support` | Shared fixtures and test helpers |
| `pulumi-cli-smoke` | Real-CLI compatibility and fault tests |

The Java modules belong to the [Maven reactor](../modules/pom.xml). `web` has
its own Bun build, and `app` packages its output as static resources.

HTTP resources adapt requests to domain operations. Org membership, stack
identity, Cedar resource construction, and tenant isolation all depend on the
schema, so shared reads and writes live beside the entities in `persistence`.
For example, `StackUpdateCompleter` owns the transaction that closes an update,
releases its lock, and advances stack metadata.

## Database

PostgreSQL is the only supported database. Flyway owns DDL, and Hibernate schema
generation stays disabled. Ordinary persistence uses Panache entities;
specialized queries use HQL or SQL for projections, row locks, and queue claims.

State has two representations: an ordered journal and snapshots that shorten
replay. Engine events feed console history and reports but are never used to
reconstruct checkpoints. [Pulumi state lifecycle](pulumi-state-lifecycle.md)
describes the write path, replay, and compaction.

All replicas share the database. A `stack_state` row lock serializes state
mutations for one stack, database queues coordinate compaction and report
delivery, and run-once startup work takes a named advisory lock. Flyway supplies
its own migration lock.

The application keeps no local state, so a database backup is a complete backup.
That backup also holds sensitive material: the service encryption keyset lives
in a singleton table, OIDC client secrets live in provider rows, and GitHub App
credentials are encrypted with the service keyset. [Service secrets](secrets.md)
explains what that keyset protects. Treat backups and database access with the
same care as the running service.

## Background work

Background workers run inside the application process:

- Compaction creates snapshots and prunes redundant ones.
- Event archival folds the events of finished updates into compressed blobs
  without dropping any event.
- Report delivery publishes completed runs to connected GitHub installations.
- Token cleanup deletes personal CLI tokens more than 30 days past expiry.

There is no separate worker deployment or message broker, and queue state
survives application restarts. The workers poll the database every few seconds,
so a serverless database does not auto-pause while the service runs.

## Deployment and startup

The native container supports amd64 and arm64, each built on a matching runner.
The runtime uses glibc, Cedar JNI, and zstd JNI.
[Native image](native-image.md) describes their packaging and reachability
requirements.

Startup validates and migrates the database before the service accepts traffic,
so replacing the image is the complete upgrade procedure. Concurrent replicas
wait on Flyway's lock. A failed migration stops the new task, and during a
rolling replacement the previous task keeps serving, so every migration must
work with the previous image as well. [Migrations](migrations.md) describes the
expand, backfill, switch, and contract sequence.

After migration, startup provisioning seeds the service keyset, the configured
bootstrap organization and administrator, and an optional bootstrap OIDC
provider. [Database connections](database-connections.md) covers how the
application authenticates to PostgreSQL.

A load balancer can route requests to any replica, since no stack is owned by a
particular process. Every replica must share `STATEDB_SESSION_ENCRYPTION_SECRET`
so browser sessions stay valid across them. TLS terminates at the deployment
boundary.

## Compatibility and limits

The backend requires Pulumi journaling, and its version guard rejects recognized
CLI versions older than 3.225.0. The [release build workflow](release.md) tests
a pinned set of CLI versions rather than every release in between.

The compatibility contract is the Pulumi CLI. Captured exchanges live in
[api-snapshots/pulumi](../api-snapshots/pulumi/) and the
[recorder fixtures](../tools/pulumi/recorder/fixtures/), and the
[smoke suite](../modules/pulumi-cli-smoke/README.md) exercises real commands and
recovery scenarios. When prose, fixtures, and CLI behavior disagree, the Pulumi
source and real-CLI tests decide; fixtures are recorded evidence and need
re-recording when the CLI changes.

The application does not implement OpenTofu state storage, deployment runners,
Pulumi ESC, or the full Pulumi Cloud API. Unsupported protocol surfaces have
explicit handlers in `pulumi-api`.
