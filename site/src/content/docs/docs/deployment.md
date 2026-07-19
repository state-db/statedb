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
| `STATE_DB_JDBC_URL` | JDBC URL of your Postgres database, e.g. `jdbc:postgresql://db:5432/statedb` |
| `STATE_DB_DB_USER` | Database user |
| `STATE_DB_DB_PASSWORD` | Database password |
| `STATE_DB_SESSION_ENCRYPTION_SECRET` | Encrypts console session cookies. Set it to a stable random value (32+ bytes) so sessions survive restarts and work across replicas. |

The service listens on port `8080` and serves both the CLI-facing API and the
web console.

## Database

- Postgres is the only supported database.
- Schema is owned by Flyway migrations bundled with the service; they apply
  automatically at startup. Upgrading statedb upgrades the schema.
- **Backups are Postgres backups.** Your existing `pg_dump` / WAL archiving /
  managed-Postgres snapshot strategy covers all statedb data — state
  checkpoints, history, users, and tokens live in the database.

## Running more than one replica

statedb is designed to run with multiple replicas behind a load balancer:

- Flyway migrations serialize across pods via a Postgres advisory lock;
  followers find the schema already migrated.
- Run-once startup work (such as bootstrap provisioning) takes the same kind of
  advisory lock, so concurrent pods do not race.
- Update leases live in the database, so locking is correct regardless of which
  replica serves a request.

Set `STATE_DB_SESSION_ENCRYPTION_SECRET` explicitly when running more than one
replica — without it each pod would mint its own key and sessions would bounce.

## Upgrades

Deploy the new version; migrations run at startup before the service accepts
traffic. With multiple replicas, roll pods as usual — the advisory lock keeps
concurrent migration attempts safe.
