---
title: Deployment
description: Running statedb in production — database, configuration, replicas, backups.
---

statedb is a single service plus a Postgres database. There is no separate
console process, queue, or object store to operate.

## Configuration

Production configuration comes from the environment:

| Variable | Purpose |
| --- | --- |
| `STATEDB_JDBC_URL` | JDBC URL of your Postgres database, e.g. `jdbc:postgresql://db:5432/statedb` |
| `STATEDB_DB_USER` | Database user |
| `STATEDB_DB_PASSWORD` | Database password |
| `STATEDB_SESSION_ENCRYPTION_SECRET` | Encrypts console session cookies. Set it to a stable random value (32+ bytes) so sessions survive restarts and work across replicas. |
| `STATEDB_LICENSE_PATH` | Preferred mounted offline license path, conventionally `/run/secrets/state-db/license.json`. |
| `STATEDB_LICENSE` | Inline compact envelope JSON or base64-encoded envelope; ignored when a file path is set. |
| `STATEDB_DEPLOYMENT_ID` | Optional stable identifier checked against a license `deploymentId` claim. |

The service listens on port `8080` and serves both the CLI-facing API and the
web console.

Docker bind-mount example:

```sh
docker run --mount type=bind,src=/secure/license.json,dst=/run/secrets/state-db/license.json,readonly \
  -e STATEDB_LICENSE_PATH=/run/secrets/state-db/license.json state-db
```

Mount the license with the secret-file feature of your container runtime. Rotate
the file in place. A license rotation does not change the database.

## Database

- Postgres is the only supported database. **PostgreSQL 14 or newer, built with
  lz4 support (`--with-lz4`)**, is required — statedb stores its large payload
  columns with lz4 TOAST compression. The official Postgres images and the major
  managed offerings qualify; some minimal or distribution builds do not.
  Releases are tested against Postgres 16 and 18.
- The requirement is checked at startup before any migration is applied, so an
  unsupported server fails immediately with a clear error rather than leaving a
  partly migrated database.
- Schema is owned by Flyway migrations bundled with the service; they apply
  automatically at startup. Upgrading statedb upgrades the schema.
- **Backups are Postgres backups.** Your existing `pg_dump` / WAL archiving /
  managed-Postgres snapshot strategy covers all statedb data — state
  checkpoints, history, users, and tokens live in the database.

Use a dedicated database and a dedicated login role. The role does not need to
own the database or have superuser privileges.

Run these commands as the database owner. Replace the example password before
you run them:

```sql
CREATE ROLE statedb LOGIN PASSWORD 'replace-this-password';
GRANT CONNECT ON DATABASE statedb TO statedb;
GRANT USAGE, CREATE ON SCHEMA public TO statedb;
```

Set `STATEDB_DB_USER=statedb`. Flyway creates the application objects, so the
role owns each object that it must change in a later migration.

## Running more than one replica

statedb is designed to run with multiple tasks behind a load balancer:

- Flyway migrations serialize across tasks with a Postgres advisory lock;
  followers find the schema already migrated.
- Run-once startup work (such as bootstrap provisioning) takes the same kind of
  advisory lock, so concurrent tasks do not race.
- Update leases live in the database. Thus, each task observes the same lock state.

Set `STATEDB_SESSION_ENCRYPTION_SECRET` when you run more than one task. All
tasks must use the same value.

## Upgrades

Replace the image to upgrade statedb. The image contains Flyway and all required
migrations. Do not add an init container or a separate migration job.

Do not set `QUARKUS_INIT_AND_EXIT`. statedb must remain active after startup and
serve application traffic from the same task that ran the migration.

The candidate must become ready in less than three minutes. Other new tasks wait
up to five minutes for the Flyway lock.

Configure the runtime as follows:

- Send readiness checks to `/q/health/ready` on management port `9000`.
- Keep management port `9000` private to the runtime network.
- Set the health-check grace period to at least 600 seconds.
- Keep the old tasks healthy until the new task becomes ready.
- Enable automatic rollback when a deployment cannot reach a steady state.
- Give each task at least 10 seconds between `SIGTERM` and `SIGKILL`.

Amazon ECS Express Mode uses a 300-second health-check grace period by default.
Increase it to 600 seconds for statedb. Express Mode uses canary deployments and
alarm-based rollback by default.

For a standard ECS rolling service, enable the deployment circuit breaker and
rollback. Set `minimumHealthyPercent` and `maximumPercent` so ECS starts a new
task before it stops the last healthy task.

Use expand-only migrations while old and new tasks run together. A migration
must not remove a column, constraint, or representation that the old image uses.

See the [Amazon ECS Express Mode defaults](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/express-service-work.html)
and the [Amazon ECS rolling deployment guide](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/deployment-type-ecs.html).
