# state-db agent guide

state-db is a self-hosted Pulumi state backend and web console. Pulumi CLI
compatibility is the primary contract: when observed CLI behavior, fixtures,
and prose disagree, verify against the Pulumi source and real CLI, then update
the stale artifact. OpenTofu support is not implemented.

## Repository map

Read only the documents relevant to the task:

- `design/README.md` — index of the design documents.
- `design/architecture.md` — module boundaries and request flow.
- `design/db-schema.md` — schema rationale; Flyway migrations are authoritative.
- `design/pulumi-state-lifecycle.md` — updates, journals, events, and compaction.
- `api-snapshots/pulumi/` and `tools/pulumi/recorder/fixtures/` — captured wire
  evidence. `modules/pulumi-cli-smoke/README.md` owns real-CLI verification.
- `design/auth.md`, `design/stack-sharing.md`, `design/secrets.md`, and
  `design/native-image.md` — read when changing those contracts.
- `design/console-ux.md`, `design/pr-enrichment.md`, `design/load-resilience.md`,
  `design/database-connections.md`, `design/migrations.md`, and `design/release.md`
  — read for the console, GitHub reports, runtime load, database connectivity,
  migrations, and releases.
- `workbench/pulumi/README.md` — interactive application and fixtures.

Follow any closer `AGENTS.md` for a subsystem. Do not copy detailed design
rationale into this file; keep the owning document or code comment current.

## Supported commands and credential boundary

`./dev` is the repository-owned verification interface:

```text
./dev doctor           check prerequisites
./dev quick            fast iteration checks
./dev check            authoritative final code/config gate
./dev protocol         real Pulumi CLI smoke and fault tests
./dev native-contract  native-image reachability contract
./dev schema           regenerate the readable PostgreSQL schema
./dev notices          regenerate dependency notices
./dev publish-candidate build, publish, and gate this machine's architecture candidate
./dev signoff          clean-VM check and publish status for an exact commit
```

- Run `./dev quick` while changing code and `./dev check` before handoff. Add
  `./dev protocol` for Pulumi wire or lifecycle changes, and
  `./dev native-contract` for reflection, runtime-selected serialization,
  resources, proxies, JNI, or other native-reachability changes.
- Run `./dev signoff` only when explicitly requested; it publishes external
  status. Run `./dev publish-candidate` only when explicitly requested; it
  publishes an image to the candidate registry. Run `./dev notices` only when
  notices need regeneration.
- Run `./dev schema` after a Flyway migration changes the current schema.
- Builds, tests, and dependency resolution used for verification must go
  through public `./dev` intents. Do not invoke Maven, Bun, Go, or their task
  functions directly under host credentials. `./dev -- ...` is a human escape
  hatch, not agent verification.
- Host inspection (`git`, `ls`, `rg`) and file editing are allowed.
- Never set or forward `STATEDB_ISOLATED`, call `__guest-bootstrap` or
  `__isolated-task`, mount host credentials or the Docker socket into
  verification, or bypass a failed `./dev doctor` with a host build.

Some checked-in entrypoints intentionally own host Docker or pinned host tools
(native images/releases, scanners, the recorder, and soak workbench). Invoke
only their documented `Makefile`/`bin` entrypoint; do not reconstruct their
environment.

For the live main-checkout application, run dev mode as a managed foreground
session, or a release image detached; check either headlessly:

```text
make pulumi-workbench cmd=app-dev
make pulumi-workbench cmd=app args="STATEDB_IMAGE=<ref>"
make pulumi-workbench cmd=status
make pulumi-workbench cmd=oidc-smoke
make pulumi-workbench cmd=app-stop
```

Do not launch or detach Quarkus, Maven, Bun, or `dev-host.sh` directly. Diagnose
the owning target if it fails.

## Pulumi compatibility

- Do not guess wire shapes. Read `github.com/pulumi/pulumi/pkg/backend/httpstate/`,
  `sdk/go/common/apitype/`, captured fixtures, and real-CLI failures.
- Preserve the existing CLI workflow: users point an unmodified Pulumi CLI at
  state-db.
- Update or add focused contract tests when changing routes, auth schemes,
  checkpoint/journal handling, locking, or update lifecycle behavior.

## ORM idioms

- Postgres DDL belongs in Flyway migrations. Hibernate schema generation stays
  disabled. Ordinary entities extend `BaseEntity` (append-only) or
  `TimestampedEntity` (mutable); composite-key entities extend
  `PanacheEntityBase` directly.
- Default to Panache entity finders, mutations, and `persist` operations in
  application code and tests. Do not add generic DAO classes.
- Put shared cross-table reads in narrowly named `*Finder`, `*Resolver`, or
  `*Lookup` services, and multi-row writes in command-style services named for
  the business action.
- Use `EntityManager`, explicit HQL, or native SQL only when Panache cannot
  express the operation cleanly or entity hydration is a named hot-path cost.
  Keep the reason in nearby Javadoc or a comment. Raw SQL in tests is reserved
  for database behavior or setup that entities cannot represent accurately.

## Java test transactions

- Use `@TestTransaction` when a direct persistence test fits in one rollback
  transaction. Keep explicit boundaries when the test checks a commit or
  rollback.
- For HTTP tests, use feature fixtures that commit setup before the request.
  Delete test-owned rows in `@AfterEach`. Keep the request and its assertions
  in the test.
- Annotate committed `@BeforeEach` and `@AfterEach` methods with
  `@Transactional` instead of wrapping their bodies. Fixture steps they combine
  join that transaction; a fixture that is a whole committed step owns it.
- If a test needs a fresh transaction, statically import `read` or `tx` from
  `app.statedb.testsupport.TestTransactions`. Use `read(() -> ...)` if the
  callback returns a value. Use `tx(() -> ...)` for a void callback. Both
  helpers use `QuarkusTransaction.requiringNew()`.
- Use `app.statedb.app.Races.holdThenContend` in app tests that must verify
  a contender waits on the holder's PostgreSQL lock. Keep dedicated
  coordination for tests that start peers together or verify they can proceed
  while a lock is held.

## Concurrency invariants

- Run-once startup work uses `AdvisoryLock#runExclusive` with a named constant
  on `AdvisoryLock`; the task must remain idempotent. Flyway already provides
  its own cross-pod migration lock.
- Compaction and updates are mutually exclusive. Any code that assembles,
  installs, prunes, or deletes snapshots must participate in the shared
  `stack_state` row-lock protocol and respect `stacks.active_update_id`.
  `stack_locks` alone does not protect compaction.

## Completion

Keep changes scoped to the request and preserve unrelated worktree changes.
Add focused tests at the layer that owns the behavior. Before handoff, run the
smallest relevant checks during iteration and the final gates listed above; if
a required gate cannot run, report exactly what ran and why it stopped.
