# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Agent behavior

These behavioral guidelines are adapted from
`multica-ai/andrej-karpathy-skills` and should be applied alongside the
project-specific rules below. They bias toward caution and small diffs; use
judgment for trivial tasks.

### Think before coding

- State assumptions explicitly before implementing when the task has ambiguity.
- If there are multiple reasonable interpretations, surface them instead of
  silently choosing one.
- Ask a clarifying question when uncertainty would materially change the design,
  API, data model, migration, or test strategy.
- Push back when a request appears unsafe, over-scoped, or inconsistent with the
  project goals or protocol compatibility requirements.
- If a simpler approach would solve the problem, call it out and prefer it
  unless there is a clear reason not to.

### Simplicity first

- Write the minimum code that satisfies the request and existing tests.
- Do not add speculative features, configuration points, extension hooks, or
  abstractions for one-off code.
- Do not add error handling for states that cannot occur under the current
  contracts; prefer clear invariants and focused validation.
- If an implementation feels much larger than the problem, stop and simplify
  before continuing.

### Surgical changes

- Touch only the files and lines needed for the user's request.
- Do not opportunistically refactor, reformat, rename, or clean up adjacent code.
- Match the surrounding style even when a different style would be preferable in
  a greenfield file.
- If unrelated dead code or design debt is noticed, mention it rather than
  changing it.
- Remove only the imports, variables, functions, or files made obsolete by the
  current change.
- Every changed line should trace back to the user's request or to verification
  required by that request.

### Goal-driven execution

- Convert work into verifiable success criteria before making broad changes.
- For bug fixes, prefer a test or reproduction that fails before the fix and
  passes afterward.
- For new behavior, add or update focused tests at the level where the behavior
  is owned.
- For refactors, verify behavior before and after when practical.
- For multi-step tasks, keep a short plan with a verification step for each
  meaningful phase, then loop until the criteria are met.

## Execution architecture

The repository-owned interface is `./dev`; callers name intent and must not
couple themselves to a particular environment adapter:

```
./dev quick       # focused checks in the credential-free verification VM
./dev check       # full check in the reusable Lima verification VM
./dev protocol    # real Pulumi CLI smoke/fault tier in that VM
./dev notices     # regenerate dependency notices inside that VM
./dev signoff     # clean-VM check, then host-side signoff for the exact commit
```

Mise pins the native Java, Bun, and Go toolchains; Maven Wrapper pins Maven.
Those are reproducibility mechanisms, not the credential boundary. Authoritative
verification copies the tree into a credential-free Lima VM with a private
rootless Docker daemon. The VM receives no host home mount, SSH agent, GitHub
token, cloud credentials, or general-purpose host Docker socket. Its system
firewall rejects new connections to private, carrier-grade NAT, link-local, and
unique-local networks—including the host's gateway, LAN, and tailnet addresses—while
retaining public internet egress. Repository code runs without sudo so it cannot
remove that rule. `./dev check` reuses guest caches;
`./dev signoff` recreates the VM before checking the exact commit.

Rules for agents:

- Use `./dev quick` during a change and `./dev check` for the final gate.
- Never invoke dependency or build tools directly under the host's ambient
  credentials. `./dev -- <command>` is an explicit human development escape
  hatch, not an agent verification path.
- Never pass the host Docker socket, SSH agent, credential directories, or
  cloud configuration into build/test environments.
- Read-only commands (`git`, `ls`, `rg`, file edits) are fine on the host.
- If `./dev doctor` reports that mise or Lima is absent, stop and report the
  prerequisite. Do not fall back to unsandboxed host execution.

The dev container is an optional interactive editor adapter. It may invoke
`./dev` but is not the execution policy for agents or CI.

### Legacy interactive dev-container worktrees

The following lifecycle exists only for humans who deliberately choose the
legacy full-workbench dev-container experience. Agents do not need or start it.

Use the lifecycle scripts from a linked Codex worktree:

```
./bin/worktree-dev-up
./bin/worktree-dev-down
```

`worktree-dev-up` creates/discovers a dev container labelled for the current
worktree, starts the shared workbench Postgres and mock IdP, creates a database
named for the worktree, and starts Bun and Quarkus against that database. It
also gives the worktree's OIDC provider a unique name. This database isolation
is required: never point a worktree at the main `statedb` database or bootstrap
the main `mock` provider, because bootstrap reconciliation would overwrite the
main checkout's issuer and callback URL.

The worktree console uses `127.0.0.1` while main uses `localhost`. Keep this
hostname separation even though both resolve to loopback: browser cookies do
not include ports, so using `localhost` for both consoles lets OIDC state and
session cookies from one checkout interfere with the other.

`worktree-dev-down` stops the worktree processes, drops only its isolated
database, and removes only its labelled dev container. It deliberately leaves
the shared Postgres volume/container, mock IdP, and cached devcontainer image in
place. Run it when the worktree is finished so containers, processes, and test
data do not accumulate.

Each Codex worktree needs its own dev container. Never run worktree code in a
container whose `devcontainer.local_folder` label points at the main checkout
or another worktree. Stop/remove another worktree's container only when the
user has said that worktree is finished.

When the default ports are already owned by another checkout, publish the two
application ports with `1` prepended:

- container `8080` -> host `18080`
- container `5173` -> host `15173`
- do **not** prepend `1` to `55432` (`155432` is not a valid TCP port). Reuse
  the shared workbench Postgres on `55432`, or choose another valid free port.

The scripts automate the following setup. If diagnosing them, note that the
devcontainer CLI has no `--publish` option. To create those mappings while
keeping `./bin/in-container` able to discover the container:

1. Temporarily add `--publish=18080:8080` and `--publish=15173:5173` to
   `runArgs` in the standard `.devcontainer/devcontainer.json`.
2. Run `devcontainer up --workspace-folder <absolute-worktree-path>`.
3. Immediately revert only those temporary config lines. The running container
   retains its port mappings and its label still references the standard config
   path. Confirm the config has no diff before feature work.
4. Verify with `docker port <container-id>` and run all project commands through
   `./bin/in-container` as usual.

Do not start from an alternate config pathname: `devcontainer exec` includes
the config path in container discovery, so a container labelled with a temporary
config can be invisible to `./bin/in-container` even though it is running.

Git worktrees may contain a `.git` file whose `gitdir` is an absolute host path.
If that path is not mounted into the container, in-container `git rev-parse`
fails and workbench Make targets that use it resolve paths incorrectly. Prefer
starting the container with the Git common directory bind-mounted at the same
absolute path (or use `--mount-git-worktree-common-dir` when the worktree was
created with relative paths). Read-only Git commands may instead run on the
host, but that does not fix Make targets that call Git inside the container.

For the full workbench on the prefixed console port, the browser-visible OIDC
issuer is `http://127.0.0.1:15173/default`. Quarkus must be able to reach that
same issuer from inside the container; a container-local forward from `15173`
to Bun on `5173` (for example with `socat`) keeps discovery and browser redirects
on one issuer. Open the console at `http://127.0.0.1:15173`; the backend is at
`http://localhost:18080`.

Deliberate exception: `tools/pulumi/api-extract` (pure-Go host tool),
`tools/pulumi/recorder` (builds and runs its own docker image),
`workbench/soak` (drives its own Compose stack), and the `analyze-semgrep` /
`analyze-deps` Make targets (run the upstream Semgrep and osv-scanner images
against a read-only bind mount of the tree) are not routed through the
gateway — they manage their own execution environments.

Native image and release tooling (`bin/build-native-app-image`, `bin/release-gate`,
`bin/generate-java-sbom`, and `bin/release`) is also host-Docker tooling. Mandrel
compilation and all runtime checks happen inside pinned containers; do not run a
host JDK or Maven for these commands.

Semgrep and osv-scanner are deliberately not devcontainer features: adding them
would force a container rebuild on every contributor for two tools that are
only invoked from `make analyze`, and both publish maintained images. The other
two analysis passes (`analyze-java`, `analyze-web`) do route through
`./bin/in-container` as usual.

`workbench/soak` runs Compose from the host so the dev container can be stopped
for the duration of a run: it is a ~4GB co-tenant on the same Docker VM as the
2GB app under test. Build the image first, then stop it, then `make soak cmd=up`.

## Project: state-db

A self-hosted state management backend compatible with the Pulumi CLI (primary target) and OpenTofu (stretch goal). The goal is to fill the gap between existing self-hosted state backends (S3, filesystem, Postgres) — which are functional but headless — and a managed cloud console. state-db provides both the HTTP state storage backend and an independent web console for browsing stacks and resources.

End users configure their Pulumi or OpenTofu CLI to point at this service as its backend. They never interact with state-db directly — their existing CLI workflows remain unchanged.

## Objectives

**Wave 1 — State Backend**
Implement the HTTP API that Pulumi's CLI speaks when `PULUMI_BACKEND_URL` points at a custom HTTP endpoint. This covers:
- Stack CRUD (create, list, rename, delete stacks per organization/project)
- State file storage and retrieval (checkpoint JSON blobs)
- State locking and unlock (preventing concurrent `pulumi up` runs)
- History / update log per stack

The API contract is defined by Pulumi's own service API; reverse-engineering or referencing the open-source Pulumi service code is the source of truth.

**Wave 2 — Web Console**
A read-oriented UI for humans to inspect infrastructure managed through compatible CLI workflows:
- Browse organizations, projects, and stacks
- View current resources in a stack (parsed from the checkpoint blob)
- View update history and diffs
- Stack output values

## Architecture Principles

- **RDBMS-backed** — state blobs and metadata live in a relational database (schema TBD, likely Postgres). Unlike the file or S3 backends, this enables rich querying, history, and the web console.
- **Single deployable service** — the HTTP backend and web console are served from the same process to keep self-hosting simple.
- **No Pulumi CLI changes required** — compatibility with the existing `PULUMI_BACKEND_URL` mechanism is non-negotiable. If the CLI breaks, the backend is wrong.
- **OpenTofu compatibility** — OpenTofu uses an HTTP backend with a similar but distinct protocol (`address`, `lock_address`, `unlock_address`). Where the protocols overlap, support both; where they diverge, isolate the differences behind an adapter layer.

## Pulumi Backend Protocol

Pulumi's HTTP backend (`httpstate`) speaks a REST-ish API. Key reference points:
- Source: `github.com/pulumi/pulumi` — `pkg/backend/httpstate/` and `sdk/go/common/apitype/`
- The checkpoint format is defined in `apitype.CheckpointV3`
- Locking uses a `LeaseID` header mechanism
- Authentication is via token in the `Authorization: token <tok>` header

When in doubt about API shape, read the Pulumi source rather than guessing.

## Persistence

Entities live in `modules/persistence` (Hibernate ORM + Panache, Postgres). Schema is owned by Flyway (`src/main/resources/db/migration/`); Hibernate has `database.generation=none` and never touches DDL.

Two `@MappedSuperclass` base classes define the standard entity shape — extend one rather than redeclaring `id` / timestamps on every new entity:

- `app.statedb.persistence.BaseEntity` — UUIDv7 `id` (Hibernate `@UuidGenerator(style = TIME)`, application-generated) + DB-defaulted `created_at`. Use for append-only / immutable rows (e.g. `ApiToken`).
- `app.statedb.persistence.TimestampedEntity extends BaseEntity` — adds DB-defaulted `updated_at`. Use for mutable rows (e.g. `Organization`, `User`, `OidcProvider`).

Hibernate populates both columns via `@CreationTimestamp` / `@UpdateTimestamp` (JVM clock). Migrations should still declare `created_at TIMESTAMPTZ NOT NULL DEFAULT now()` and (for `TimestampedEntity` tables) `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()` — the column defaults are a safety net for raw-SQL inserts only; nothing in the DB advances `updated_at` on UPDATE, so writers that bypass Hibernate (admin scripts, batch SQL) must set it explicitly.

Composite-PK entities (e.g. `OrgMember` with `@IdClass`) don't fit either superclass — extend `PanacheEntityBase` directly.

### ORM idioms

This is a hard rule for both application code and tests: default to idiomatic Panache/entity operations. Do not start with `@Inject EntityManager`, HQL strings, or SQL because it feels faster to write. Use the escape hatches below only when the ORM cannot express the operation cleanly or when avoiding entity hydration is a named hot-path requirement.

- **Reads / queries** — call Panache's static finders on the entity: `Stack.find("project.name = ?1 and name = ?2", project, stack)`, `Stack.list(...)`, `.firstResultOptional()`, `.project(Dto.class)`. Pass full HQL strings to `find(...)` when you need `join fetch` or a constructor projection — the API takes them.
- **Writes** — `entity.persistAndFlush()` (or `persist()` if you don't need the immediate flush). Use `@Transactional` to bracket multi-write units of work (`StackCreator` is the canonical example).
- **Tests** — seed and assert state through entities, Panache finders/counts, and transactional helpers. Raw SQL in tests is still raw SQL: use it only for DDL/migration assertions, database-specific behavior, or setup that would be materially harder or less accurate through entities. Do not use SQL merely to make fixture setup shorter.
- **Placement / naming** — don't introduce generic DAO classes. Keep entity-owned one-table operations on the Panache entity. Put shared cross-table reads, DTO projections, and tenant-isolation joins in narrowly named query services (`*Finder`, `*Resolver`, `*Lookup`) in the package that owns the aggregate. Put multi-row transactional writes in command-style services named after the business action (`*Creator`, `*Renamer`, `*Deleter`, `*Writer`). If a service would only wrap one trivial `Entity.find(...)`, don't create it.
- **Relationship navigation** — entities expose `@ManyToOne` (and where appropriate `@OneToOne`) navigation *alongside* the raw UUID FK columns. The navigation field is `fetch = LAZY` and marked `insertable=false, updatable=false`; writers continue to set the UUID column directly so the DB schema (composite FKs, UNIQUE constraints, denormalized `org_id` rows) remains the source of truth. Add `join fetch` to your HQL when a finder needs to traverse the chain, to keep it one SQL round trip.
- **Escape hatch — bare `EntityManager` + HQL.** Justified for scalar / constructor projection over multi-table joins on hot paths where avoiding entity hydration is a measurable win. `StackRefResolver` is the canonical example: every authenticated Pulumi request hits it, it returns an 8-field record, and it deliberately bypasses entity loading. Document the reason in the class Javadoc when you reach for this.
- **Escape hatch — `EntityManager.createNativeQuery(...)`.** Reserved for things HQL can't express cleanly: `ON CONFLICT` upserts (`StackCreator#upsertProject`), advisory locks, BRIN-index hints, retention deletes that need to bypass the dialect's defaults. Not a general-purpose tool.

Before committing code that uses `EntityManager`, HQL, or native SQL, do this check: could this be `Entity.find(...)`, `Entity.count(...)`, an entity mutation, or `persist()` instead? If yes, rewrite it. If no, leave a short comment or Javadoc naming the reason.

## Multi-pod startup

The app is designed to run with more than one replica behind a load balancer. Two pieces of startup work are at risk of racing across pods:

- **Flyway migrations** — already safe. `quarkus-flyway` with `migrate-at-start: true` takes a Postgres advisory lock on `flyway_schema_history` before applying changes; concurrent pods serialize through it and followers find the schema already at the head revision.
- **Run-once application init** (e.g. `BootstrapProvisioner`) — wrap the work in `AdvisoryLock#runExclusive(key, task)`. The helper opens a new JTA transaction and holds a Postgres transaction-scoped advisory lock for its duration, so concurrent pods serialize through `task` and a crashed leader releases the lock immediately when its DB session is reaped. The task body must still be idempotent (followers will take the lock and re-run after the leader commits — they should find the work already done and no-op).

Lock keys live as `public static final long` constants on `AdvisoryLock`. Add a new constant rather than passing a literal — it keeps the registry in one place and makes collisions easy to spot.

## Compaction and updates are mutually exclusive

Compaction rewrites a stack's base snapshot from the journal. It must never observe a partially written update: the assembled checkpoint would place resources ahead of their provider, Pulumi would refuse to load it, and the persisted snapshot leaves the stack unusable.

Both halves are required:

- `CompactionWorker` takes the `stack_state` row lock and refuses to snapshot while `stacks.active_update_id` is set, returning the job to `pending`.
- `StackLockAcquirer` takes the same row lock in the transaction that sets `active_update_id`, after waiting out any queued compaction (`state-db.lock.compaction-wait`; `0` disables).

Anything else that assembles, persists, or deletes a snapshot must do the same — `SnapshotPruner` runs inside the compaction transaction for exactly this reason. The `stack_locks` lease does not help: it serializes updates against each other, and compaction never acquires it.
