# state-db: PostgreSQL Schema Design

## Context

This schema supports the Wave 1 HTTP state backend for Pulumi and the identity/membership tables that the Wave 2 console needs to authenticate users and mint API tokens. The Pulumi CLI speaks a REST-ish API when `PULUMI_BACKEND_URL` is set to a custom HTTP endpoint; this service implements that protocol. Wire-shape ground truth is the recorded fixture catalog at `tools/pulumi/recorder/fixtures/` (Pulumi CLI v3.242.0 against a stub backend); when fixtures and Pulumi documentation disagree, fixtures win.

The design must support the `delta-checkpoint-updates` journaling protocol (capability version 1), which is what `journalVersion: 1` in the start-update request activates. Without journaling, the checkpoint endpoints can carry full or delta snapshots; with journaling enabled, the Pulumi CLI sends gzip-compressed `{"entries":[...]}` batches to `/journalentries`.

Forward compatibility for OpenTofu: the `workspaces` parent table provides type-erased identity from day one. Pulumi stacks are 1:1 with a `kind='pulumi'` workspace today. When Tofu support lands in Wave 3, a separate `tofu_workspaces` extension table joins to the same `workspaces` parent, leaving Pulumi-specific state tables untouched.

---

## Key Design Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | **UUIDv7 PKs, generated app-side by Hibernate** | Orgs, projects, stacks can all be renamed, so FK relationships must not break — UUIDs survive renames. UUIDv7's millisecond-timestamp prefix preserves insert locality on append-heavy tables (`journal_entries`, `update_events`, `snapshots`), avoiding the page-split churn of random v4. Generation in the app (Hibernate `@UuidGenerator(style = TIME)`) keeps the schema portable across Postgres versions and makes IDs available pre-insert for correlation/logging. |
| 2 | **No denormalized `stack_ref`; resolve `(org, project, stack)` via JOIN at read time** | The 3-table JOIN is microseconds on small FK-indexed parent tables. Denormalizing would buy nothing at our scale and cost three rename triggers, a PL/pgSQL helper, and a `@Generated` round-trip on every stack write. Pulumi-API response strings of the form `org/project/stack` are assembled in the DTO layer from data the request already loaded. |
| 3 | **Checkpoint as JSONB** | Resource structure varies wildly per provider; Pulumi expects exact JSON back |
| 4 | **Separate `snapshots` + `journal_entries`** | Different access patterns: snapshots are infrequent large writes; journal entries are frequent small appends |
| 5 | **`stack_state` pointer table** | Single source of truth for current snapshot + journal position per stack |
| 6 | **`pending_operations` table** | Fast crash-recovery lookup without replaying full journal |
| 7 | **Lock expiry + heartbeat** | Stale lock recovery when client crashes; bearer hash must match to unlock |
| 8 | **Immutable journal entries** | Append-only matches Pulumi journaling semantics; compaction creates new snapshot |
| 9 | **Async compaction job queue** | Compaction is slow; shouldn't block API responses; can be retried |
| 10 | **`api_tokens.kind` discriminator (`oidc` vs `service_account`)** | OIDC-derived tokens are short-lived (24h default); each mint requires a fresh OIDC handshake (`prompt=none` re-auth), so IdP disablement propagates within the TTL through OIDC primitives alone. Service-account tokens are long-lived for CI/CD with mandatory `expires_at` enforced in app code. Both share the wire format (`Authorization: token <tok>`) — the distinction is policy, not protocol. |
| 11 | **`workspaces` parent table** | Type-erased identity for cross-tool listing (Pulumi today, Tofu in Wave 3); 1:1 with type-specific row; deletion cascades down the type-specific tree |
| 12 | **`update_events` first-class** | Event payloads from `events/batch` are decoded and queryable per update; BRIN index on `created_at` enables cheap time-bounded vacuum |
| 13 | **OIDC users + `org_members`** | Console authn via configurable OIDC providers; org membership decoupled from token ownership; admin set is env-driven, not stored in DB |
| 14 | **Surrogate UUIDv7 PK on every mutable entity** | All mutable entities carry their own `id UUID PRIMARY KEY` and extend `BaseEntity` / `TimestampedEntity`. 1:1 lifecycle binding to a parent is expressed via `UNIQUE FK + ON DELETE CASCADE`, never a shared PK. Keeps the entity layer uniform (no `@MapsId` edge cases) at the cost of one extra UUID column on `stack_state` / `stack_locks`. `org_members` remains the only exception — composite-PK association tables fit nothing else. |

---

## Tables

| Table | Purpose |
|-------|---------|
| `organizations` | Top-level tenant boundary |
| `projects` | Belongs to org, renameable |
| `stacks` | Belongs to project, renameable |
| `api_tokens` | Authenticates Pulumi CLI requests (hashed); `kind` separates short-lived OIDC tokens from long-lived service-account tokens |
| `snapshots` | Full DeploymentV3 checkpoint at compaction points |
| `stack_state` | Per-stack pointer: current snapshot + journal position + read cache |
| `stack_updates` | One row per operation (preview/update/destroy/refresh/import) |
| `journal_entries` | Append-only delta entries per update |
| `pending_operations` | In-flight resource ops; pruned on complete/failure |
| `stack_locks` | Distributed lock with expiry and heartbeat |
| `compaction_jobs` | Background job queue for snapshot compaction |
| `oidc_providers` | Configurable OIDC issuers; env-bootstrapped at startup, additional providers manageable via console |
| `users` | OIDC-authenticated console users; natural key `(iss, sub)` |
| `org_members` | User → org membership with role (owner/member/viewer) |
| `workspaces` | Type-erased parent for state-managing entities (Pulumi stacks today, Tofu workspaces in Wave 3) |
| `update_events` | Decoded events from `events/batch`; queryable per update; BRIN-indexed for time-based vacuum |
| `service_secrets_keyset` | Singleton serialized Tink `AES256_GCM` keyset for the `service` secrets provider; seeded by `BootstrapProvisioner` on first start (see [`secrets.md`](secrets.md)) |

---

## Full SQL Schema

```sql
-- ============================================================================
-- ID GENERATION
-- ============================================================================
-- All UUID PKs are UUIDv7 values generated by the application layer
-- (Hibernate ORM, `@UuidGenerator(style = TIME)`). The lease bearer is
-- separately a `UUID.randomUUID()` (v4) — it is a short-lived secret, not a
-- primary key, so insert-locality doesn't apply.
-- The DB does not assign defaults — every INSERT supplies an explicit id.
-- This keeps the schema portable across Postgres versions (no dependency on
-- Postgres 18's native uuidv7()) and yields time-ordered ids that preserve
-- B-tree insert locality on hot append tables (journal_entries, update_events).

-- ============================================================================
-- IDENTITY & AUTH
-- ============================================================================

-- Configurable OIDC providers. One row is bootstrapped from env at startup
-- (so first login works on a fresh DB); additional providers can be added
-- at runtime by an admin via the console.
CREATE TABLE oidc_providers (
    id            UUID        PRIMARY KEY,
    name          TEXT        NOT NULL UNIQUE,                  -- 'google', 'corp-okta'
    issuer_url    TEXT        NOT NULL,                         -- expected `iss` claim value
    client_id     TEXT        NOT NULL,
    client_secret TEXT        NOT NULL,                         -- DB must be encrypted at rest
    scopes        TEXT[]      NOT NULL DEFAULT ARRAY['openid','profile','email'],
    enabled       BOOLEAN     NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (issuer_url, client_id)
);

-- One user per (iss, sub). Email is a hint, not an identity key — IdPs change
-- emails. Admin status is NOT stored here; it is computed per-request against
-- the env-driven STATE_DB_ADMIN_SUBJECTS allow-list so admins can be rotated
-- without DB writes. Linking multiple identities (Google + Okta → same user)
-- is future work.
CREATE TABLE users (
    id            UUID        PRIMARY KEY,
    iss           TEXT        NOT NULL,
    sub           TEXT        NOT NULL,
    email         TEXT,
    display_name  TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ,
    UNIQUE (iss, sub)
);

-- API tokens are minted from the console; every token has an owner. They
-- additionally carry an org scope used for authorization on Pulumi CLI calls.
-- Pulumi CLI sends `Authorization: token <tok>`; we store sha256, never the raw.
--
-- Two kinds:
--   'oidc'            — minted by a user via OIDC re-auth (prompt=none); short
--                       TTL (24h default). When the user is disabled at the
--                       IdP, the next mint attempt fails (login_required) and
--                       the existing token expires within its TTL.
--   'service_account' — minted by an admin for non-interactive callers (CI/CD).
--                       Long-lived, manually revoked. App code enforces a
--                       mandatory expires_at on this kind (capped at 1y).
CREATE TABLE api_tokens (
    id           UUID        PRIMARY KEY,
    token_hash   TEXT        NOT NULL UNIQUE,
    description  TEXT,
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,  -- owner who minted it
    org_id       UUID        NOT NULL,                                          -- FK added after organizations; access scope
    kind         TEXT        NOT NULL CHECK (kind IN ('oidc', 'service_account')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ
);

CREATE INDEX idx_api_tokens_user ON api_tokens (user_id);

-- ============================================================================
-- CORE ENTITIES
-- ============================================================================

CREATE TABLE organizations (
    id           UUID        PRIMARY KEY,
    name         TEXT        NOT NULL UNIQUE,   -- "testorg" (also the githubLogin)
    display_name TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE api_tokens
    ADD CONSTRAINT fk_token_org FOREIGN KEY (org_id)
    REFERENCES organizations(id) ON DELETE CASCADE;

-- User membership in orgs. Drives console authz; a user must be a member of an
-- org to mint API tokens scoped to it. Token validation must verify the owner
-- still has membership — removing a user from an org effectively revokes their
-- tokens for that org without deleting them.
CREATE TABLE org_members (
    org_id     UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role       TEXT        NOT NULL CHECK (role IN ('owner', 'member', 'viewer')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, user_id)
);

CREATE INDEX idx_org_members_user ON org_members (user_id);

-- Workspaces are type-erased state-managing entities. Today only kind='pulumi'.
-- kind='tofu' will join via a separate tofu_workspaces extension table in Wave 3.
-- The console lists across all workspaces in an org by querying this table and
-- LEFT JOINing the type-specific extension for display fields.
--
-- Lifecycle is owned here: deleting a workspace cascades to its type-specific
-- row (stacks today) and from there to all state. App code must never delete
-- the type-specific row directly — the workspace would be left orphaned.
CREATE TABLE workspaces (
    id         UUID        PRIMARY KEY,
    org_id     UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    kind       TEXT        NOT NULL CHECK (kind IN ('pulumi', 'tofu')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Target for the composite FK from stacks(workspace_id, org_id).
    UNIQUE (id, org_id)
);

CREATE INDEX idx_workspaces_org_kind ON workspaces (org_id, kind);

CREATE TABLE projects (
    id         UUID        PRIMARY KEY,
    org_id     UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (org_id, name),
    -- Target for the composite FK from stacks(project_id, org_id).
    UNIQUE (id, org_id)
);

-- Stacks can be renamed; (org, project, stack) lookups JOIN through projects
-- and organizations at read time (no denormalized ref column).
-- active_update_id FK is DEFERRABLE to avoid the circular dep with
-- stack_updates, which is created later.
--
-- org_id is denormalized so that a single composite FK can enforce that
-- project_id and workspace_id resolve to rows in the same organization. Without
-- it, a stack could reference a project in org A and a workspace in org B,
-- silently breaking tenant isolation.
CREATE TABLE stacks (
    id               UUID        PRIMARY KEY,
    org_id           UUID        NOT NULL,
    project_id       UUID        NOT NULL,
    name             TEXT        NOT NULL,

    -- 1:1 with the type-erased workspaces row. Lifecycle is owned by workspaces:
    -- DELETE FROM workspaces WHERE id = ? cascades here and to all state below.
    workspace_id     UUID        NOT NULL UNIQUE,

    -- Pulumi API response fields
    version          INTEGER     NOT NULL DEFAULT 0,  -- increments on each completed non-preview op
    active_update_id UUID,                            -- points to in-progress stack_updates row; null when idle
    tags             JSONB,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (project_id, name),
    FOREIGN KEY (project_id, org_id) REFERENCES projects(id, org_id) ON DELETE CASCADE,
    FOREIGN KEY (workspace_id, org_id) REFERENCES workspaces(id, org_id) ON DELETE CASCADE
);

-- ============================================================================
-- STATE STORAGE
-- ============================================================================

-- Full DeploymentV3 checkpoints. Written by compaction. A journal entry with
-- kind=5 ('write') carries an inline newSnapshot and is replayed by the
-- assembler; it does not create a snapshots row until a later compaction.
CREATE TABLE snapshots (
    id                         UUID        PRIMARY KEY,
    stack_id                   UUID        NOT NULL REFERENCES stacks(id) ON DELETE CASCADE,
    version                    INTEGER     NOT NULL,

    checkpoint                 JSONB       NOT NULL,  -- full DeploymentV3
    checkpoint_schema_version  INTEGER     NOT NULL DEFAULT 3,
    secrets_provider_type      TEXT,                  -- 'passphrase', 'awskms', etc.
    resource_count             INTEGER     NOT NULL DEFAULT 0,

    -- Which journal entry this snapshot covers up to (null = no journal yet)
    compacted_from_journal_seq BIGINT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (stack_id, version)
);

CREATE INDEX idx_snapshots_stack_version ON snapshots (stack_id, version DESC);

-- One row per stack (UNIQUE(stack_id) enforces singleton; surrogate `id` per
-- Decision #14). Tracks the current base snapshot and journal position.
-- (An earlier cached_checkpoint/cache_valid read cache lived here; base
-- snapshots superseded it before any writer populated it, and V014 dropped it.)
CREATE TABLE stack_state (
    id                              UUID        PRIMARY KEY,
    stack_id                        UUID        NOT NULL UNIQUE REFERENCES stacks(id) ON DELETE CASCADE,

    -- RESTRICT prevents deleting a snapshot that is still the active base.
    -- Update this pointer first, then the old snapshot becomes deletable.
    base_snapshot_id                UUID        REFERENCES snapshots(id) ON DELETE RESTRICT,
    base_snapshot_version           INTEGER     NOT NULL DEFAULT 0,

    journal_seq                     BIGINT      NOT NULL DEFAULT 0,
    journal_entries_since_compaction INTEGER    NOT NULL DEFAULT 0,
    auto_compact_threshold          INTEGER     NOT NULL DEFAULT 100,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================================
-- UPDATE OPERATIONS
-- ============================================================================

-- One row per Pulumi operation. The UUID here IS the updateID returned by
-- POST /api/stacks/{org}/{project}/{stack}/update|preview|destroy|refresh.
--
-- client_request_id enables idempotent create: retried POST /update calls with
-- the same client_request_id return the existing row rather than inserting a
-- duplicate (app must check before insert, or use INSERT ... ON CONFLICT).
--
-- Fields sourced from UpdateProgramRequest body (POST .../update|preview|destroy):
--   config, description, metadata (environment map + message),
--   options (color, debug, dryRun, parallel, etc.), runtime
-- Fields sourced from the start-update body (POST .../update/{updateID}):
--   journal_version, tags
CREATE TABLE stack_updates (
    id       UUID    PRIMARY KEY,
    stack_id UUID    NOT NULL REFERENCES stacks(id) ON DELETE CASCADE,

    version  INTEGER NOT NULL,  -- monotonically increasing per stack
    kind     TEXT    NOT NULL CHECK (kind IN (
                 'update', 'preview', 'destroy', 'refresh', 'import', 'rename')),
    status   TEXT    NOT NULL DEFAULT 'in-progress' CHECK (status IN (
                 'in-progress', 'succeeded', 'failed', 'cancelled')),

    -- Idempotency key supplied by the caller; optional but strongly recommended.
    client_request_id TEXT,

    -- Journaling state
    journal_version       INTEGER,  -- 1 = delta-checkpoint-updates active
    base_snapshot_version INTEGER,
    -- Per-update offset captured atomically on the update's first journal PATCH:
    -- start_journal_seq := stack_state.journal_seq at the moment the first batch arrives.
    -- Every entry's server sequence_id is then start_journal_seq + wire_sequenceID, which
    -- keeps `ORDER BY sequence_id` correct even when wire IDs arrive at the server out of
    -- order across PATCHes (see invariant #9 and the journal_entries comment block).
    start_journal_seq     BIGINT,
    end_journal_seq       BIGINT,   -- set on completion

    -- Request payload (stored for history/audit; not needed for state reconstruction)
    config         JSONB,
    metadata       JSONB,     -- { environment: {...}, message: "" }
    options        JSONB,     -- { color, debug, dryRun, parallel, ... }
    description    TEXT,
    runtime        TEXT,
    pulumi_version TEXT,
    tags           JSONB,     -- from start-update request

    message    TEXT,

    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at   TIMESTAMPTZ,

    UNIQUE (stack_id, version),

    -- Composite unique key needed by the journal_entries / pending_operations cross-entity FKs below.
    UNIQUE (id, stack_id)
);

-- Partial unique index: enforces idempotency per (stack, request) when caller provides a key.
CREATE UNIQUE INDEX idx_updates_idempotency
    ON stack_updates (stack_id, client_request_id)
    WHERE client_request_id IS NOT NULL;

CREATE INDEX idx_updates_stack_status ON stack_updates (stack_id, status);
CREATE INDEX idx_updates_in_progress  ON stack_updates (stack_id)
    WHERE status = 'in-progress';

-- Add deferred FK from stacks.active_update_id now that stack_updates exists.
ALTER TABLE stacks
    ADD CONSTRAINT fk_stacks_active_update
    FOREIGN KEY (active_update_id) REFERENCES stack_updates(id)
    DEFERRABLE INITIALLY DEFERRED;

-- ============================================================================
-- JOURNALING
-- ============================================================================

-- Append-only. Never modified after insert.
-- The composite FK (update_id, stack_id) → stack_updates(id, stack_id) enforces
-- that a journal entry's stack_id matches the stack_id on its parent update row,
-- preventing cross-entity mismatches at the DB level.
CREATE TABLE journal_entries (
    id           UUID    PRIMARY KEY,
    stack_id     UUID    NOT NULL REFERENCES stacks(id) ON DELETE CASCADE,
    update_id    UUID    NOT NULL,
    sequence_id       BIGINT NOT NULL, -- server-assigned replay order, monotonic within a stack; = stack_updates.start_journal_seq + wire_sequence_id
    wire_sequence_id  BIGINT NOT NULL, -- Pulumi sequenceID, unique within one update; assigned by the CLI from a monotonic counter but may arrive at the server out of order under resource parallelism

    kind         SMALLINT NOT NULL,  -- pinned to Pulumi CLI v3.242.0 kinds 0..7; validated in the handler so a new upstream kind surfaces as a 400, not a constraint violation
    operation_id BIGINT,            -- wire operationID; 0 is used for sentinel entries
    version      INTEGER NOT NULL DEFAULT 1,

    -- Index-like wire fields used heavily by reconstruction. They are nullable
    -- because each journal kind touches only the fields it needs.
    remove_old              BIGINT,
    remove_new              BIGINT,
    delete_old              BIGINT,
    delete_new              BIGINT,
    pending_replacement_old BIGINT,
    pending_replacement_new BIGINT,
    is_refresh              BOOLEAN NOT NULL DEFAULT false,

    -- Heavy nested fields stay together:
    --   operation        (AppOperationV2 with a full ResourceV3)
    --   state            (ResourceV3)
    --   secretsProvider  (AppSecretsProvidersV1)
    --   newSnapshot      (DeploymentV3)
    payload      JSONB   NOT NULL,
    resource_urn TEXT,              -- denormalized from payload.operation.resource.urn (Begin) or payload.state.urn (Success/RefreshSuccess/Outputs); null for SecretsManager, Write, Failure, and RebuiltBaseState

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (stack_id, sequence_id),
    UNIQUE (update_id, wire_sequence_id),

    -- Enforces stack_id consistency: update_id must belong to this same stack.
    FOREIGN KEY (update_id, stack_id) REFERENCES stack_updates(id, stack_id)
);

CREATE INDEX idx_journal_stack_seq ON journal_entries (stack_id, sequence_id);
CREATE INDEX idx_journal_update     ON journal_entries (update_id);
CREATE INDEX idx_journal_operation  ON journal_entries (stack_id, operation_id)
    WHERE operation_id IS NOT NULL;

-- ============================================================================
-- EVENT STREAM
-- ============================================================================

-- Decoded events from POST .../update/{updateID}/events/batch. Pulumi CLI
-- streams stdout, diagnostics, and resource lifecycle events here. Each row is
-- one decoded event; the batch payload's gzip envelope is unwrapped on receipt
-- and individual events are inserted with their original sequence numbers.
--
-- Retention: a vacuum job prunes rows older than a configured threshold using
-- the BRIN index on created_at. The journal is the system of record for state;
-- events are observability data and safe to drop independently.
CREATE TABLE update_events (
    id         UUID        PRIMARY KEY,
    stack_id   UUID        NOT NULL,
    update_id  UUID        NOT NULL,
    sequence   INTEGER     NOT NULL,    -- from the event envelope
    kind       TEXT        NOT NULL,    -- 'stdout', 'diagnostic', 'resourcePre', 'resourceOutputs', 'summary', 'cancel', ...
    severity   TEXT,                    -- 'info' | 'warning' | 'error' for diagnostics; null otherwise
    payload    JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (update_id, sequence),

    -- Composite FK enforces stack_id consistency, same pattern as journal_entries.
    FOREIGN KEY (update_id, stack_id) REFERENCES stack_updates(id, stack_id) ON DELETE CASCADE
);

CREATE INDEX idx_update_events_update    ON update_events (update_id, sequence);
CREATE INDEX idx_update_events_severity  ON update_events (update_id, severity)
    WHERE severity IN ('warning', 'error');
CREATE INDEX idx_update_events_created   ON update_events USING BRIN (created_at);

-- ============================================================================
-- CRASH RECOVERY
-- ============================================================================

-- Tracks resource operations started but not yet completed.
-- Populated on kind=0 (Begin) entries that include operation.resource; pruned
-- on kind=1/2/3 terminal entries for the same update-local operationID.
-- Same composite FK pattern as journal_entries to prevent cross-stack mismatches.
CREATE TABLE pending_operations (
    id             UUID   PRIMARY KEY,
    stack_id       UUID   NOT NULL REFERENCES stacks(id) ON DELETE CASCADE,
    update_id      UUID   NOT NULL,
    operation_id   BIGINT NOT NULL,
    resource_urn   TEXT   NOT NULL,
    resource_type  TEXT   NOT NULL,
    operation_type TEXT   NOT NULL CHECK (operation_type IN (
                       'creating', 'updating', 'deleting', 'reading', 'importing')),
    begin_journal_seq BIGINT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (update_id, operation_id),

    -- Enforces stack_id consistency.
    FOREIGN KEY (update_id, stack_id) REFERENCES stack_updates(id, stack_id)
);

CREATE INDEX idx_pending_ops_stack ON pending_operations (stack_id);

-- ============================================================================
-- LOCKING
-- ============================================================================

-- Exclusive per-stack lock (UNIQUE(stack_id) enforces one lock per stack;
-- surrogate `id` per Decision #14). Acquired by POST .../update/{updateID}
-- (start-update).
--
-- The bearer is a UUID generated by the server at acquire/renew and returned
-- in the lease response as `token`. The Pulumi CLI sends it as
-- `Authorization: update-token <bearer>` on lease-scoped operations:
-- PATCH /journalentries, POST /events/batch, POST /complete,
-- POST /renew_lease, DELETE /update/{id}. Storage is the SHA-256 of the
-- bearer's canonical UUID string in `lock_token_hash`; plaintext is never
-- persisted. Validation hashes the inbound plaintext and requires
-- `expires_at > now()` on the matching row. Renewal rotates: a new bearer is
-- generated and its hash replaces the old one, bounding the replay window of
-- any leaked token to a single lease window.
-- Lease-token validation is a separate code path from api_tokens auth.
--
-- Stale lock recovery: any acquire can steal a lock where expires_at < now().
--
-- Composite FK enforces stack_id consistency with the parent update (same
-- pattern as journal_entries / pending_operations / update_events). Default
-- NO ACTION means the app must DELETE the lock before deleting its parent
-- update — preventing silently orphaned locks.
CREATE TABLE stack_locks (
    id              UUID        PRIMARY KEY,
    stack_id        UUID        NOT NULL UNIQUE REFERENCES stacks(id) ON DELETE CASCADE,
    update_id       UUID,
    locked_by       TEXT        NOT NULL,
    lock_token_hash BYTEA       NOT NULL,
    locked_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    -- Hard ceiling on total lease lifetime. expires_at is clamped to this on
    -- every renew (and on initial acquire when ttl > maxLifetime), so a
    -- bearer that has been continuously renewed past this point is rejected
    -- even when otherwise valid. Bounds the blast radius of a leaked token
    -- when the legit client has stopped rotating it.
    max_expires_at  TIMESTAMPTZ NOT NULL,
    heartbeat_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (update_id, stack_id) REFERENCES stack_updates(id, stack_id)
);

-- ============================================================================
-- COMPACTION
-- ============================================================================

-- new_snapshot_id is SET NULL rather than CASCADE so that a deleted snapshot
-- doesn't silently remove the job's completion record.
CREATE TABLE compaction_jobs (
    id                    UUID        PRIMARY KEY,
    stack_id              UUID        NOT NULL REFERENCES stacks(id) ON DELETE CASCADE,
    status                TEXT        NOT NULL DEFAULT 'pending' CHECK (status IN (
                              'pending', 'running', 'completed', 'failed')),
    from_snapshot_version INTEGER     NOT NULL,
    from_journal_seq      BIGINT      NOT NULL,
    to_journal_seq        BIGINT      NOT NULL,
    new_snapshot_id       UUID        REFERENCES snapshots(id) ON DELETE SET NULL,
    error_message         TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at            TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ
);

CREATE INDEX idx_compaction_pending ON compaction_jobs (status, created_at)
    WHERE status = 'pending';

-- ============================================================================
-- TRIGGERS
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Status transition guard: terminal status is irreversible
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_guard_update_status()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('succeeded', 'failed', 'cancelled') THEN
        RAISE EXCEPTION
            'stack_updates % is already terminal (status=%). No further transitions allowed.',
            OLD.id, OLD.status;
    END IF;
    IF NEW.status = 'in-progress' AND OLD.status <> 'in-progress' THEN
        RAISE EXCEPTION
            'Cannot revert stack_updates % from % back to in-progress.',
            OLD.id, OLD.status;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_update_status_guard
    BEFORE UPDATE OF status ON stack_updates
    FOR EACH ROW EXECUTE FUNCTION trg_guard_update_status();

-- Singleton row holding a serialized Tink `AES256_GCM` keyset for the
-- `service` secrets provider. Seeded once by `BootstrapProvisioner` (under
-- advisory lock) on first start; contents are opaque to the schema — Tink
-- owns the framing. The CHECK enforces the singleton at the DB level. Full
-- design and threat model: `secrets.md`.
CREATE TABLE service_secrets_keyset (
    id            SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    key_material  BYTEA        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

---

## API → Schema Mapping

| Pulumi API Call | Tables Written | Tables Read |
|----------------|----------------|-------------|
| `GET /api/user` | — | `api_tokens`, `organizations` |
| `GET /api/user/organizations/default` | — | `organizations` |
| `GET /api/user/stacks?project=X` | — | `stacks`, `projects`, `organizations` |
| `POST /api/stacks/{org}/{project}` | `workspaces`, `stacks`, `stack_state` | `projects`, `organizations` |
| `GET /api/stacks/{org}/{project}/{stack}` | — | `stacks`, `projects`, `organizations` |
| `DELETE /api/stacks/{org}/{project}/{stack}` | DELETE `workspaces` (cascades to `stacks` and all state) | `stack_state` (check resources == 0) |
| `GET /api/stacks/{org}/{project}/{stack}/export` | — | `stack_state` → `snapshots` + `journal_entries` |
| `POST .../update\|preview\|refresh\|destroy` | INSERT `stack_updates` | `stacks` |
| `POST .../update/{updateID}` | INSERT `stack_locks`, UPDATE `stacks.active_update_id` | `stack_updates` |
| `PATCH .../update/{updateID}/journalentries` | INSERT `journal_entries`, UPDATE `stack_state.journal_seq` | `stack_locks` (validate token) |
| `PATCH .../update/{updateID}/checkpoint`, `/checkpointdelta`, `/checkpointverbatim` | 501 in Wave 1 when journaling is negotiated | `stack_locks` (validate token) |
| `POST .../update/{updateID}/events/batch` | INSERT `update_events` (one row per decoded event) | `stack_locks` (validate token) |
| `POST .../update/{updateID}/complete` | UPDATE `stack_updates.status`, DELETE `stack_locks`, UPDATE `stacks.version` + `active_update_id = null` | `stack_updates` |
| `POST .../{stack}/encrypt`, `/decrypt`, `/batch-decrypt` | — | `service_secrets_keyset` (singleton master key, loaded at startup); per-request HKDF derivation against the resolved `stacks.id`. Full design: [`secrets.md`](secrets.md). |

---

## Journal Entry Payload Structures

Each entry uses the same row shape. The wire discriminator is numeric
`kind`, not a text enum:

```
0 Begin            → payload.operation = { resource: ResourceV3, type: string }
1 Success          → optional payload.state = ResourceV3; remove_* fields may be set
2 Failure          → clears a pending operation; payload is usually empty
3 RefreshSuccess   → payload.state = ResourceV3; is_refresh and remove_old are commonly set
4 Outputs          → payload.state = synthetic pulumi:pulumi:Stack resource
5 Write            → payload.newSnapshot = DeploymentV3
6 SecretsManager   → payload.secretsProvider = AppSecretsProvidersV1; operation_id is 0
7 RebuiltBaseState → dependency-rebuild marker; payload is usually empty
```

The index/operation fields are six distinct nullable int64s:

| Field | Replay meaning |
|-------|----------------|
| `removeOld` | Index into the base snapshot's `resources` list; skip that base slot during materialisation. |
| `removeNew` | Operation ID for a resource created earlier in the current replayer; skip the matching new resource. |
| `deleteOld` | Index into the base snapshot's `resources` list; mark that base resource with `delete: true`. |
| `deleteNew` | Operation ID for a resource created earlier in the current replayer; mark that new resource with `delete: true`. |
| `pendingReplacementOld` | Index into the base snapshot's `resources` list; mark that base resource with `pendingReplacement: true`. |
| `pendingReplacementNew` | Operation ID for a resource created earlier in the current replayer; mark that new resource with `pendingReplacement: true`. |

In short: `*Old` fields are base-resource indexes; `*New` fields are operation
IDs. Boolean fields such as `isRefresh` are omit-default-false on the wire.

Pulumi's wire `sequenceID` resets for each update. It is stored as
`wire_sequence_id`; the server-assigned `sequence_id` is the stack-global
replay order used by export and compaction.

The CLI assigns `wire_sequenceID` from a single atomic counter at entry-creation
time, but concurrent resource workers (under `--parallel > 1`, the default) may
push entries to the journal channel out of counter order. Across PATCHes a
later batch can therefore carry a wire ID lower than the previous batch's max.
To keep `ORDER BY sequence_id` consistent with the CLI's intended replay order,
the server records a per-update offset on `stack_updates.start_journal_seq` —
captured atomically on the update's first PATCH as the current value of
`stack_state.journal_seq` — and assigns each entry's server `sequence_id` as
`start_journal_seq + wire_sequenceID`. Replay then sorts by `sequence_id` and
gets back the wire order regardless of arrival order. Duplicate wire IDs are
caught by `UNIQUE (update_id, wire_sequence_id)` and surface as a 409.

---

## State Reconstruction (for `GET /export`)

```
1. Load stack_state WHERE stack_id = $id
2. Load snapshot WHERE id = stack_state.base_snapshot_id, or start from an
   empty DeploymentV3 if there is no base snapshot yet
3. SELECT FROM journal_entries
      WHERE stack_id = $id
        AND sequence_id > COALESCE(snapshot.compacted_from_journal_seq, 0)
      ORDER BY sequence_id ASC
4. Replay with a port of Pulumi's journal-apply logic, not a hand-rolled
   simplification:
     Begin              → record a pending operation when operation.resource exists
     Success            → clear pending op; apply removeOld/removeNew/delete*/pendingReplacement*;
                          append state when present
     Failure            → clear pending op without appending state
     RefreshSuccess     → replace/remove the base-indexed or operation-ID-indexed resource
                          without appending to the new resource list
     Outputs            → replace the indexed synthetic pulumi:pulumi:Stack resource in place
     Write              → replace the whole working deployment with newSnapshot
     SecretsManager     → update deployment secretsProvider
     RebuiltBaseState   → promote the materialised deployment to the working base
5. Rebuild stale dependency references after refresh, normalize URN aliases, and
   return the UntypedDeployment shape:
     { version: 3|4, deployment: <reconstructed DeploymentV3>, features?: [...] }
```

---

## Correctness Invariants

The tables below split what the schema enforces from what application code must guarantee.

### Handled by schema

| # | Concern | Mechanism |
|---|---------|-----------|
| 1 | Orphaned records | `ON DELETE CASCADE` on all child tables; `ON DELETE RESTRICT` on `stack_state.base_snapshot_id` (prevents deleting active base snapshot); `ON DELETE SET NULL` on `compaction_jobs.new_snapshot_id`; default `NO ACTION` on `stack_locks` composite FK (forces app to DELETE the lock before deleting its parent update) |
| 2 | Cross-entity mismatch | Composite FK `(update_id, stack_id) → stack_updates(id, stack_id)` on `journal_entries`, `pending_operations`, `update_events`, and `stack_locks`; DB rejects rows whose `update_id` belongs to a different stack |
| 6 | Duplicate singletons | `stack_state` and `stack_locks` both carry `UNIQUE (stack_id)` (the surrogate PK is the row's own `id`) |
| 7 | Invalid status transitions | `trg_update_status_guard` rejects any UPDATE that tries to leave a terminal status or revert to `in-progress` |
| 11 | Stale current-state reads | Reads assemble from `base_snapshot_id` + journal replay past `compacted_from_journal_seq`; no separate read cache to go stale (the old `cache_valid` cache was dropped in V014) |
| 13 | Idempotent update creation | `client_request_id TEXT` + partial unique index `(stack_id, client_request_id) WHERE client_request_id IS NOT NULL`; app uses `INSERT ... ON CONFLICT DO NOTHING` |
| 15 | Orphan/cleanup | Cascades cover the full tree; `stack_state.base_snapshot_id ON DELETE RESTRICT` prevents silent data loss from premature snapshot deletion |

### Application invariants (schema provides mechanism; app must use it correctly)

| # | Concern | Required app behavior |
|---|---------|----------------------|
| 5 | Multi-table partial failure | Every write that touches multiple tables must run inside a single transaction (`BEGIN` / `COMMIT`). Notably: create update + set `stacks.active_update_id`; complete update + delete lock + increment `stacks.version`. |
| 8 | Lost update / version races | `stacks.version` increment and `stack_updates.version` assignment must use `SELECT ... FOR UPDATE` on the `stacks` row. Since the lock prevents concurrent writers on the same stack, races only arise between the lock-acquire step and version increment — serialize these in the same transaction. |
| 9 | Journal sequence ordering | Treat `journal_entries.sequence_id` as the server-assigned stack-global replay order, computed as `stack_updates.start_journal_seq + wire_sequenceID`. The Pulumi wire `sequenceID` resets per update. On the update's first PATCH, atomically capture the offset (`UPDATE stack_updates SET start_journal_seq = (SELECT journal_seq FROM stack_state ...) WHERE id = ? AND start_journal_seq IS NULL`); every subsequent PATCH for that update reads the captured offset. The CLI assigns wire IDs from a monotonic counter but parallel resource workers may push them to the channel out of order, so do not enforce contiguity or across-PATCH monotonicity on `wire_sequence_id` — uniqueness is the only invariant (`UNIQUE (update_id, wire_sequence_id)` catches duplicate retries as 409). Insert every entry from one `{"entries":[...]}` batch and advance `stack_state.journal_seq` to `GREATEST(journal_seq, offset + max(wire_sequenceID) + 1)` in a single transaction holding `SELECT ... FOR UPDATE` on `stack_state` — the lock serializes appenders across pods so the offset capture is consistent. |
| 10 | Lease token validation | Lease-scoped operations (`PATCH .../journalentries`, `PATCH .../checkpoint*`, `POST .../events/batch`, `POST .../complete`, `POST .../renew_lease`, `DELETE .../update/{id}`) carry `Authorization: update-token <leaseTok>` instead of `token <apiTok>`. Verify by hashing `<leaseTok>` (SHA-256 of its canonical UUID string) and matching against `stack_locks.lock_token_hash` AND `stack_locks.expires_at > now()`. Reject with 409 if mismatched or expired. Inside lease-scoped mutating transactions, re-check the lease with `FOR UPDATE` + `clock_timestamp()` after taking the primary row lock — the filter's check cannot prevent expiry or cancel during the dispatch gap. Renewal rotates the bearer. The lease-token and api-token paths are independent and never overlap on the same request. |
| 12 | Compaction races | The compactor must hold a `SELECT ... FOR UPDATE` on `stack_state` for the duration of its read → compute → commit cycle. This blocks concurrent journal appends (which also lock `stack_state`) and ensures `to_journal_seq` remains consistent at commit time. |
| 14 | Tenant isolation | Every query that retrieves stacks or state must JOIN to `projects` / `organizations` (or filter by `stacks.project_id → projects.org_id`) to verify the org matches the authenticated token's `org_id`. Never accept a bare `stack_id` from external input without confirming ownership. |
| 17 | Workspace lifecycle | Stack creation must `INSERT workspaces` then `INSERT stacks` (with `workspace_id` set) inside one transaction. Stack deletion must `DELETE FROM workspaces` (which cascades). Never `DELETE FROM stacks` directly — the workspace row would be orphaned. |
| 18 | Token authorization scope | Every Pulumi CLI request must verify (a) the token's hash exists and is unexpired, (b) `api_tokens.org_id` matches the target stack's org, and (c) `org_members` row still exists for `(token.org_id, token.user_id)`. Removing a user from an org must invalidate their tokens for that org without deleting the row. |

### Non-issues

| # | Concern | Why not applicable |
|---|---------|-------------------|
| 16 | Portability | Deliberately Postgres-only: `JSONB`, `TIMESTAMPTZ`, `DEFERRABLE FK`, partial indexes, BRIN, `FOR UPDATE`. No abstraction layer is planned. |

---

## Gaps / Out of Scope (Wave 1)

| Feature | Note |
|---------|------|
| Stack rename (`POST .../rename`) | Single-row `UPDATE stacks SET name = ?`; lookups always resolve through the JOIN, so no derived data to maintain |
| History endpoint (`GET .../updates`) | Reads `stack_updates`; no new table needed |
| Stack config (`PUT .../config`) | Separate per-stack config storage not required for Wave 1 |
| Non-journal checkpoint writes | `/checkpoint`, `/checkpointdelta`, and `/checkpointverbatim` are real OpenAPI paths, but Wave 1 negotiates `journalVersion: 1` and returns 501 for those fallback paths |
| Custom KMS-backed secrets providers | Wave 1 must implement the Pulumi service secrets endpoints for `type: service`; provider-specific delegation (AWS KMS, Azure Key Vault, etc.) can layer on later. Service-managed encryption necessarily receives plaintext on `/encrypt` and returns ciphertext stored opaquely inside checkpoint JSONB. |
| OIDC identity linking | One user = one `(iss, sub)`. Linking multiple IdP identities to a single user (e.g., "I have Google and Okta accounts") is future work. |
| `statedb-cli` companion | A small CLI helper to automate the daily OIDC re-auth and write the resulting token into `~/.pulumi/credentials.json` (AWS-SSO-style). Wave 1 ships console-paste only; helper is later UX polish. |
| Tofu workspace extension | `workspaces` parent ships in Wave 1 so identity is stable from day one. Type-specific `tofu_workspaces` extension and Tofu state tables ship in Wave 3. |
| Event vacuum scheduler | `update_events` retention is enforced by an app-level job that deletes by `created_at`. The job itself (cadence, threshold) is config, not schema. |

---

## Verification (when implementing)

1. `pulumi login http://localhost:<port>` → validates `/api/user`, `/api/user/organizations/default`
2. `pulumi stack init dev` → `stacks` row inserted; `stack_state` row created; `(org, project, stack)` lookup returns the new stack
3. `pulumi up` → full update lifecycle: preview + actual `stack_updates`, `stack_locks`, `journal_entries`, `/encrypt`; `stacks.version` increments after the actual update; compaction installs a fresh base snapshot (with `resource_count`) after completion
4. `pulumi stack export` → reconstructs checkpoint from `stack_state` + `journal_entries`; diff against expected output
5. `pulumi destroy` → new `stack_updates` rows; `pending_operations` cleared; `stacks.version` increments
6. `pulumi stack rm` → all rows cascade-deleted; verify nothing remains
7. Concurrent `pulumi up` from two terminals → second returns lock conflict (409)
8. Duplicate `POST /update` with same `client_request_id` → returns existing row, no duplicate
9. `UPDATE stack_updates SET status = 'in-progress' WHERE status = 'succeeded'` → trigger raises exception
10. Rename org → subsequent `(new_org_name, project, stack)` lookups resolve correctly; no derived data needs rewriting
