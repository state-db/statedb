# Database migrations

This document defines the database-upgrade contract for state-db images.
Flyway migrations in `modules/persistence/src/main/resources/db/migration` are authoritative.

## Image upgrade contract

Replacing the state-db image is the complete upgrade procedure. The image validates and migrates the database before it serves traffic.

The deployment does not require an init container, migration job, Flyway CLI, or external migration bundle.

Flyway uses the shared PostgreSQL database to serialize concurrent startup migrations. Other replacement tasks wait and validate the completed schema.

Readiness stays unavailable until migration and startup provisioning complete. A migration error stops the new task with a nonzero exit status.

The release gate gives a candidate three minutes to become ready. A task waits five minutes for another task to release the Flyway lock.

## Migration rules

Never edit a versioned migration after a release contains it. Add a new migration that moves the schema forward.

Use monotonically increasing integer versions. Do not use out-of-order migrations or reuse a version on another release branch.

Keep each startup migration short and transactional. Do not put a large data backfill in a Flyway migration.

The complete startup migration chain must finish in less than three minutes. Each SQL statement has a two-minute timeout.

Use repeatable migrations only for replaceable definitions, such as views or functions. The schema generator and release gate include `R__*.sql` files.

Keep `beforeMigrate.sql` fast and safe to run again. This callback checks the supported PostgreSQL feature floor on each startup.

## Rolling replacement

Managed container runtimes can run the old and new images at the same time. Flyway locking does not make their schemas compatible.

Use this sequence for an incompatible representation change:

1. Add the new schema without removing the old schema.
2. Deploy code that can use both representations.
3. Backfill existing rows with a resumable background worker.
4. Switch reads and writes after the backfill completes.
5. Remove the old schema after the previous image leaves the rollback window.

Add columns without a volatile default. Add a validated constraint in a later migration when a table scan can block writes.

Use `CREATE INDEX CONCURRENTLY` when a normal index build can block a populated table. Put only nontransactional statements in that migration.

Add a sibling Flyway script configuration file to a nontransactional migration. For example, `V025__index.sql.conf` contains `executeInTransaction=false`. Flyway and the schema generator use this same declaration.

Set a bounded PostgreSQL lock timeout for migration connections. A new task must fail instead of waiting indefinitely behind an application transaction.

## Data backfills

A background worker owns a backfill that can exceed the startup budget. The worker must be resumable and safe across concurrent tasks.

Store backfill progress in the database. Use a database lock or row-claim protocol to make each batch safe across tasks.

The old and new images must remain correct while the backfill is incomplete. Flyway must not mark data conversion complete before the worker completes it.

## Current schema view

[`schema.sql`](schema.sql) is a generated, non-executable view of the current schema. It helps reviewers understand the result of the migration chain.

Run `./dev schema` after a schema change. The command applies all migrations to a clean, pinned PostgreSQL database and runs `pg_dump --schema-only`.

`./dev check` generates the view again and fails when the committed file is stale. The generator removes ownership, grants, and unstable dump metadata.

Do not deploy `design/schema.sql`. It does not contain the upgrade path or data backfills.

## Migration history

Keep every migration after the first supported release. Flyway Community does not provide cumulative `B__` baseline migrations.

Do not squash the chain while a supported database contains its migration history. Operational evidence must justify a different policy.

The pre-release V001 through V024 chain was replayed on PostgreSQL 17. Its schema was byte-identical to the consolidated V001 schema.

## Release evidence

The candidate-image gate compares `flyway_schema_history` with the migration files from the release source. A missing native-image resource fails the gate.

The gate starts two candidate tasks against one database. On a fresh install, the database is empty. During an upgrade, the previous image initializes it first. Both candidate tasks must become ready and report the latest successful execution of every packaged migration.

The release gate upgrades from the previous supported minor release. The old task completes a Pulumi write lifecycle after the new migration.

Release notes must state the schema range, migration duration, backup requirement, and irreversible changes.
