# AGENTS.md

This file provides guidance to coding agents working in this repository.

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

### Interactive main-checkout workbench

When the user asks to start, restart, or inspect the live application in the
main checkout, use this foreground command in a managed terminal session:

```
make pulumi-workbench cmd=app-dev
```

This is the deliberate host-native exception to the verification rules above.
The target owns the whole lifecycle: Docker runs Postgres and the mock IdP,
while mise-pinned Bun and Java run the console and Quarkus on the host. Use
`make pulumi-workbench cmd=app-stop` to clear a stale run.

Do not launch `bun dev.ts`, Maven, Quarkus, or `dev-host.sh` directly. Do not
detach them with `nohup` or recreate the target's environment variables by
hand. If the target fails, diagnose and fix that target instead of bypassing
it; otherwise processes escape its cleanup and may use unpinned host tools.

Deliberate exception: `tools/pulumi/api-extract` (pure-Go host tool),
`tools/pulumi/recorder` (builds and runs its own docker image),
`workbench/soak` (drives its own Compose stack), and the `analyze-semgrep` /
`analyze-deps` Make targets (run the upstream Semgrep and osv-scanner images
against a read-only bind mount of the tree) manage their own execution
environments rather than running through the verification VM.

Native image and release tooling (`bin/build-native-app-image`, `bin/release-gate`,
`bin/generate-java-sbom`, and `bin/release`) is also host-Docker tooling. Mandrel
compilation and all runtime checks happen inside pinned containers; do not run a
host JDK or Maven for these commands.

Semgrep and osv-scanner use their maintained images instead of becoming local
toolchain prerequisites. The Java and web analysis passes use the mise-pinned
toolchain through the root Make targets.

`workbench/soak` runs Compose from the host so a multi-day run is owned by its
purpose-built stack rather than an interactive development process.

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
- `StackLockAcquirer` takes the same row lock in the transaction that sets `active_update_id`, after waiting out any queued compaction (`statedb.lock.compaction-wait`; `0` disables).

Anything else that assembles, persists, or deletes a snapshot must do the same — `SnapshotPruner` runs inside the compaction transaction for exactly this reason. The `stack_locks` lease does not help: it serializes updates against each other, and compaction never acquires it.
