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

## Execution: dev container only (non-negotiable)

This repo MUST be built, tested, and run inside the dev container defined by
`.devcontainer/devcontainer.json`. The host is treated as incomplete (wrong
JDK, no Maven, no Postgres). Do not fall back to the host if an in-container
command fails — diagnose and fix instead.

All project commands route through `./bin/in-container`:

```
./bin/in-container mvn -f modules/pom.xml test
./bin/in-container mvn -f modules/pom.xml -pl app -am quarkus:dev
```

When running a single module or focused tests in a module, include Maven's
reactor dependency build flag (`-am`) so sibling modules are built instead of
failing with missing `0.1.0-SNAPSHOT` artifacts. If the focused test pattern
only exists in the selected leaf module, also disable no-match failures for the
upstream modules:

```
./bin/in-container mvn -f modules/pom.xml -pl app -am -Dtest=SomeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Rules for agents:

- Never invoke `mvn`, `gradle`, `java`, `npm`, `python`, `pytest`, `psql`,
  etc. directly on the host. Always go through `./bin/in-container`. Show
  that form in any commands you suggest to the user.
- Read-only commands (`git`, `ls`, `rg`, file edits) are fine on the host.
- If the wrapper reports no running container, **stop** and tell the user to
  start it (Zed: "Rebuild Container"; or `devcontainer up --workspace-folder
  .`). Do not retry on the host.

Deliberate exception: `tools/pulumi/api-extract` (pure-Go host tool) and
`tools/pulumi/recorder` (builds and runs its own docker image) are not routed
through the gateway — they manage their own execution environments.

## Project: state-db

A self-hosted state management backend for Pulumi (primary target) and OpenTofu (stretch goal). The goal is to fill the gap between Pulumi's existing self-hosted backends (S3, filesystem, Postgres) — which are functional but headless — and the fully-managed Pulumi Cloud console. state-db provides both the HTTP state storage backend and a web console for browsing stacks and resources, i.e. a "poor man's Pulumi console."

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
A read-oriented UI for humans to inspect what Pulumi is managing:
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

- `io.statedb.persistence.BaseEntity` — UUIDv7 `id` (Hibernate `@UuidGenerator(style = TIME)`, application-generated) + DB-defaulted `created_at`. Use for append-only / immutable rows (e.g. `ApiToken`).
- `io.statedb.persistence.TimestampedEntity extends BaseEntity` — adds DB-defaulted `updated_at`. Use for mutable rows (e.g. `Organization`, `User`, `OidcProvider`).

Hibernate populates both columns via `@CreationTimestamp` / `@UpdateTimestamp` (JVM clock). Migrations should still declare `created_at TIMESTAMPTZ NOT NULL DEFAULT now()` and (for `TimestampedEntity` tables) `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()` — the column defaults are a safety net for raw-SQL inserts only; nothing in the DB advances `updated_at` on UPDATE, so writers that bypass Hibernate (admin scripts, batch SQL) must set it explicitly.

Composite-PK entities (e.g. `OrgMember` with `@IdClass`) don't fit either superclass — extend `PanacheEntityBase` directly.

## Multi-pod startup

The app is designed to run with more than one replica behind a load balancer. Two pieces of startup work are at risk of racing across pods:

- **Flyway migrations** — already safe. `quarkus-flyway` with `migrate-at-start: true` takes a Postgres advisory lock on `flyway_schema_history` before applying changes; concurrent pods serialize through it and followers find the schema already at the head revision.
- **Run-once application init** (e.g. `BootstrapProvisioner`) — wrap the work in `AdvisoryLock#runExclusive(key, task)`. The helper opens a new JTA transaction and holds a Postgres transaction-scoped advisory lock for its duration, so concurrent pods serialize through `task` and a crashed leader releases the lock immediately when its DB session is reaped. The task body must still be idempotent (followers will take the lock and re-run after the leader commits — they should find the work already done and no-op).

Lock keys live as `public static final long` constants on `AdvisoryLock`. Add a new constant rather than passing a literal — it keeps the registry in one place and makes collisions easy to spot.
