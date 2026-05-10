# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
