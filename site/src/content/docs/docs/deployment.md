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

Kubernetes Secret-mounted file example:

```yaml
env:
  - name: STATEDB_LICENSE_PATH
    value: /run/secrets/state-db/license.json
volumeMounts:
  - name: state-db-license
    mountPath: /run/secrets/state-db
    readOnly: true
volumes:
  - name: state-db-license
    secret:
      secretName: state-db-license
```

Create that Secret with a `license.json` key. Rotate it by updating the Secret
and allowing the mounted file to refresh; no database write is involved.

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

## Running more than one replica

statedb is designed to run with multiple replicas behind a load balancer:

- Flyway migrations serialize across pods via a Postgres advisory lock;
  followers find the schema already migrated.
- Run-once startup work (such as bootstrap provisioning) takes the same kind of
  advisory lock, so concurrent pods do not race.
- Update leases live in the database, so locking is correct regardless of which
  replica serves a request.

Set `STATEDB_SESSION_ENCRYPTION_SECRET` explicitly when running more than one
replica — without it each pod would mint its own key and sessions would bounce.

## Upgrades

Deploy the new version; migrations run at startup before the service accepts
traffic. With multiple replicas, roll pods as usual — the advisory lock keeps
concurrent migration attempts safe.
