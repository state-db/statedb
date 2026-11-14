# Database migrations

Replacing the state-db image is the complete upgrade procedure. The application
validates and migrates PostgreSQL during startup, before it serves traffic. A
deployment needs no init container, migration job, Flyway CLI, or separate
migration bundle.

[Flyway migrations](../modules/persistence/src/main/resources/db/migration/) are
authoritative. [schema.sql](schema.sql) is a generated reference, not a
deployment input.

## Startup contract

Flyway serializes concurrent startup migrations through the shared database;
other replacement tasks wait, then validate the completed schema. Readiness stays
down until migrations and startup provisioning finish, and a migration error
stops the new task with a nonzero exit status.

`StateDbFlywayConfiguration` enforces this path. It rejects configuration that
disables Flyway or migrate-at-start, enables clean, repair, or baseline-on-migrate,
allows out-of-order migrations, or changes the schema, history table, or
location. After startup it fails if any migration is still pending.

Migration connections set a ten-second lock timeout and a two-minute statement
timeout, so a new task fails instead of waiting indefinitely behind an
application transaction. A task waits up to five minutes for another task to
release the Flyway lock, and connection attempts retry for about a minute to
cover a cold database. The release gate gives a candidate three minutes to become
ready, which bounds the whole startup migration chain.

The `beforeMigrate.sql` callback runs on every startup and checks the PostgreSQL
feature floor. Keep it fast and safe to repeat. Hibernate schema generation stays
disabled.

## Migration rules

Never edit a versioned migration after a release contains it. Add a migration
with a new, increasing integer version for each schema change, and never reuse a
version on another branch.

Keep each startup migration short and transactional. A data backfill that can
exceed the startup budget belongs in a resumable background worker that stores
its progress in the database and uses a lock or row-claim protocol across
replicas. Old and new images must stay correct while the backfill is incomplete,
and no migration may mark the conversion finished before the worker completes it.

Use repeatable `R__*.sql` migrations only for replaceable definitions such as
views or functions. The schema generator and the release gate both include them.

A nontransactional migration, such as one using `CREATE INDEX CONCURRENTLY`,
contains only nontransactional statements and has a sibling script configuration
file (for example `V010__add_index.sql.conf`) containing
`executeInTransaction=false`. Flyway and the schema generator read the same
declaration.

## Rolling replacement

Old and new images can run at the same time during a deployment. Flyway's lock
serializes migrations but does not make the two images' schema expectations
compatible.

For an incompatible representation change:

1. Add the new schema while keeping the old representation.
2. Deploy code that can use both representations.
3. Backfill existing rows with resumable background work.
4. Switch reads and writes after the backfill finishes.
5. Remove the old representation after the previous image leaves the rollback window.

On populated tables, add columns without a volatile default, add a validated
constraint in a later migration when validation would scan the table under a
blocking lock, and build indexes concurrently when a normal build would block
writes. A short migration on an empty test database does not prove that an
upgrade has a bounded effect on production writes.

## Migration history

Keep every migration after the first supported release. Flyway Community has no
cumulative `B__` baseline migrations, so the chain cannot be squashed while a
supported database carries its history.

## Schema reference

`./dev schema` applies the migrations to a clean, pinned PostgreSQL database and
writes the readable schema with `pg_dump --schema-only`, removing ownership,
grants, and unstable dump metadata. `./dev check` regenerates it and fails when
the committed file is stale.

## Release verification

`bin/release-gate` compares the candidate's `flyway_schema_history` with the
migration files in the release source. It starts two candidate replicas against
one database to verify concurrent startup and migration convergence; both must
become ready and report a successful execution of every packaged migration.

When a previous image is supplied, the gate starts it first, keeps it running
through the candidate's migration, and then requires it to complete an
authenticated Pulumi write lifecycle. Without a previous image, the gate reports
the upgrade check as skipped.

Release notes must state the schema changes, migration duration, backup
requirements, and irreversible steps. [Releases](release.md) describes the
publication gate.
