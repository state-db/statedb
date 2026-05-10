# state-db: PostgreSQL Schema Design

## Context

This schema supports the Wave 1 HTTP state backend for Pulumi and the identity/membership tables that the Wave 2 console needs to authenticate users and mint API tokens. The Pulumi CLI speaks a REST-ish API when `PULUMI_BACKEND_URL` is set to a custom HTTP endpoint; this service implements that protocol. Wire-shape ground truth is the recorded fixture catalog at `tools/pulumi/recorder/fixtures/` (Pulumi CLI v3.234.0 against a stub backend); when fixtures and Pulumi documentation disagree, fixtures win.

The design must support the `delta-checkpoint-updates` journaling protocol (capability version 1), which is what `journalVersion: 1` in the start-update request activates. Without journaling, the checkpoint PATCH contains a full snapshot; with journaling it contains incremental entries.

Forward compatibility for OpenTofu: the `workspaces` parent table provides type-erased identity from day one. Pulumi stacks are 1:1 with a `kind='pulumi'` workspace today. When Tofu support lands in Wave 3, a separate `tofu_workspaces` extension table joins to the same `workspaces` parent, leaving Pulumi-specific state tables untouched.

---

## Key Design Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | **UUIDv7 PKs, generated app-side by Hibernate** | Orgs, projects, stacks can all be renamed, so FK relationships must not break — UUIDs survive renames. UUIDv7's millisecond-timestamp prefix preserves insert locality on append-heavy tables (`journal_entries`, `update_events`, `snapshots`), avoiding the page-split churn of random v4. Generation in the app (Hibernate `@UuidGenerator(style = TIME)`) keeps the schema portable across Postgres versions and makes IDs available pre-insert for correlation/logging. |
| 2 | **Denormalized `stack_ref`** | Avoids 3-table join on every API request; maintained by triggers on both INSERT and UPDATE |
| 3 | **Checkpoint as JSONB** | Resource structure varies wildly per provider; Pulumi expects exact JSON back |
| 4 | **Separate `snapshots` + `journal_entries`** | Different access patterns: snapshots are infrequent large writes; journal entries are frequent small appends |
| 5 | **`stack_state` pointer table** | Single source of truth for current snapshot + journal position per stack |
| 6 | **`pending_operations` table** | Fast crash-recovery lookup without replaying full journal |
| 7 | **Lock expiry + heartbeat** | Stale lock recovery when client crashes; `lock_token` must match to unlock |
| 8 | **Immutable journal entries** | Append-only matches Pulumi journaling semantics; compaction creates new snapshot |
| 9 | **Async compaction job queue** | Compaction is slow; shouldn't block API responses; can be retried |
| 10 | **`api_tokens.kind` discriminator (`oidc` vs `service_account`)** | OIDC-derived tokens are short-lived (24h default); each mint requires a fresh OIDC handshake (`prompt=none` re-auth), so IdP disablement propagates within the TTL through OIDC primitives alone. Service-account tokens are long-lived for CI/CD with mandatory `expires_at` enforced in app code. Both share the wire format (`Authorization: token <tok>`) — the distinction is policy, not protocol. |
| 11 | **`workspaces` parent table** | Type-erased identity for cross-tool listing (Pulumi today, Tofu in Wave 3); 1:1 with type-specific row; deletion cascades down the type-specific tree |
| 12 | **`update_events` first-class** | Event payloads from `events/batch` are decoded and queryable per update; BRIN index on `created_at` enables cheap time-bounded vacuum |
| 13 | **OIDC users + `org_members`** | Console authn via configurable OIDC providers; org membership decoupled from token ownership; admin set is env-driven, not stored in DB |

---

## Tables

| Table | Purpose |
|-------|---------|
| `organizations` | Top-level tenant boundary |
| `projects` | Belongs to org, renameable |
| `stacks` | Belongs to project, renameable; holds denormalized `stack_ref` |
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

---

## Full SQL Schema

```sql
-- ============================================================================
-- ID GENERATION
-- ============================================================================
-- All UUID PKs (and the lock_token) are UUIDv7 values generated by the
-- application layer (Hibernate ORM, `@UuidGenerator(style = TIME)`).
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
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_workspaces_org_kind ON workspaces (org_id, kind);

CREATE TABLE projects (
    id         UUID        PRIMARY KEY,
    org_id     UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (org_id, name)
);

-- Stacks can be renamed; stack_ref is kept in sync by the trg_stack_upsert trigger
-- on both INSERT and UPDATE.
-- active_update_id is denormalized (FK is DEFERRABLE to avoid circular dep with
-- stack_updates, which is created later).
CREATE TABLE stacks (
    id               UUID        PRIMARY KEY,
    project_id       UUID        NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name             TEXT        NOT NULL,

    -- 1:1 with the type-erased workspaces row. Lifecycle is owned by workspaces:
    -- DELETE FROM workspaces WHERE id = ? cascades here and to all state below.
    workspace_id     UUID        NOT NULL UNIQUE REFERENCES workspaces(id) ON DELETE CASCADE,

    -- Denormalized: "org/project/stack" — set on INSERT and updated on any rename
    stack_ref        TEXT        NOT NULL UNIQUE,

    -- Pulumi API response fields
    version          INTEGER     NOT NULL DEFAULT 0,  -- increments on each completed non-preview op
    active_update_id UUID,                            -- points to in-progress stack_updates row; null when idle
    tags             JSONB,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (project_id, name)
);

CREATE INDEX idx_stacks_ref ON stacks (stack_ref);

-- ============================================================================
-- STATE STORAGE
-- ============================================================================

-- Full DeploymentV3 checkpoints. Written on compaction or on a 'write' journal
-- entry (provider migrations). The checkpoint field stores the decompressed JSON
-- (incoming PATCH bodies are gzip-encoded and must be decompressed before insert).
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

-- One row per stack (PRIMARY KEY enforces singleton). Tracks the current base
-- snapshot and journal position. cached_checkpoint is a read optimization;
-- the trg_journal_invalidates_cache trigger ensures it is never stale.
CREATE TABLE stack_state (
    stack_id                        UUID        PRIMARY KEY REFERENCES stacks(id) ON DELETE CASCADE,

    -- RESTRICT prevents deleting a snapshot that is still the active base.
    -- Update this pointer first, then the old snapshot becomes deletable.
    base_snapshot_id                UUID        REFERENCES snapshots(id) ON DELETE RESTRICT,
    base_snapshot_version           INTEGER     NOT NULL DEFAULT 0,

    journal_seq                     BIGINT      NOT NULL DEFAULT 0,
    journal_entries_since_compaction INTEGER    NOT NULL DEFAULT 0,
    auto_compact_threshold          INTEGER     NOT NULL DEFAULT 100,

    -- Invalidated automatically by trg_journal_invalidates_cache on each journal INSERT.
    cached_checkpoint               JSONB,
    cached_at                       TIMESTAMPTZ,
    cache_valid                     BOOLEAN     NOT NULL DEFAULT false,

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
    sequence_id  BIGINT  NOT NULL,
    operation_id UUID,             -- correlates begin/success/failure entries

    entry_type     TEXT    NOT NULL CHECK (entry_type IN (
                       'begin', 'success', 'failure', 'refresh_success',
                       'outputs', 'write', 'rebuilt_base_state')),
    format_version INTEGER NOT NULL DEFAULT 1,
    payload        JSONB   NOT NULL,
    resource_urn   TEXT,           -- denormalized; null for non-resource entries

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (stack_id, sequence_id),

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
-- Populated on 'begin' journal entries; pruned on 'success'/'failure'.
-- Same composite FK pattern as journal_entries to prevent cross-stack mismatches.
CREATE TABLE pending_operations (
    id             UUID   PRIMARY KEY,
    stack_id       UUID   NOT NULL REFERENCES stacks(id) ON DELETE CASCADE,
    update_id      UUID   NOT NULL,
    operation_id   UUID   NOT NULL,
    resource_urn   TEXT   NOT NULL,
    resource_type  TEXT   NOT NULL,
    operation_type TEXT   NOT NULL CHECK (operation_type IN (
                       'creating', 'updating', 'deleting', 'reading', 'importing')),
    begin_journal_seq BIGINT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (stack_id, operation_id),

    -- Enforces stack_id consistency.
    FOREIGN KEY (update_id, stack_id) REFERENCES stack_updates(id, stack_id)
);

CREATE INDEX idx_pending_ops_stack ON pending_operations (stack_id);

-- ============================================================================
-- LOCKING
-- ============================================================================

-- Exclusive per-stack lock (PRIMARY KEY enforces one lock per stack).
-- Acquired by POST .../update/{updateID} (start-update).
--
-- lock_token is returned in the lease response as `token`. The Pulumi CLI sends
-- it as `Authorization: update-token <lock_token>` on lease-scoped operations:
-- PATCH /checkpoint, POST /events/batch, POST /complete, POST /renew_lease,
-- DELETE /update/{id}. Validation: token must match AND expires_at > now().
-- Lease-token validation is a separate code path from api_tokens auth.
--
-- Stale lock recovery: any acquire can steal a lock where expires_at < now().
--
-- Composite FK enforces stack_id consistency with the parent update (same
-- pattern as journal_entries / pending_operations / update_events). Default
-- NO ACTION means the app must DELETE the lock before deleting its parent
-- update — preventing silently orphaned locks.
CREATE TABLE stack_locks (
    stack_id     UUID        PRIMARY KEY REFERENCES stacks(id) ON DELETE CASCADE,
    update_id    UUID,
    locked_by    TEXT        NOT NULL,
    lock_token   UUID        NOT NULL,
    locked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now(),
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
-- 1. stack_ref: computed on INSERT and kept in sync on rename
-- ---------------------------------------------------------------------------
-- build_stack_ref is used for UPDATE (row already exists in DB).
-- INSERT uses inline computation because the new row isn't yet visible.
CREATE OR REPLACE FUNCTION build_stack_ref(p_stack_id UUID)
RETURNS TEXT LANGUAGE SQL STABLE AS $$
    SELECT o.name || '/' || p.name || '/' || s.name
    FROM stacks s
    JOIN projects p ON s.project_id = p.id
    JOIN organizations o ON p.org_id = o.id
    WHERE s.id = p_stack_id
$$;

CREATE OR REPLACE FUNCTION trg_update_stack_refs()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'organizations' THEN
        UPDATE stacks s
           SET stack_ref  = build_stack_ref(s.id),
               updated_at = now()
          FROM projects p
         WHERE s.project_id = p.id AND p.org_id = NEW.id;
    ELSIF TG_TABLE_NAME = 'projects' THEN
        UPDATE stacks
           SET stack_ref  = build_stack_ref(id),
               updated_at = now()
         WHERE project_id = NEW.id;
    ELSIF TG_TABLE_NAME = 'stacks' THEN
        IF TG_OP = 'INSERT' THEN
            -- Row doesn't exist in the DB yet; can't use build_stack_ref.
            SELECT o.name || '/' || p.name || '/' || NEW.name
              INTO NEW.stack_ref
              FROM projects p
              JOIN organizations o ON p.org_id = o.id
             WHERE p.id = NEW.project_id;
        ELSE
            NEW.stack_ref := build_stack_ref(NEW.id);
        END IF;
        NEW.updated_at := now();
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_org_rename
    AFTER UPDATE OF name ON organizations
    FOR EACH ROW EXECUTE FUNCTION trg_update_stack_refs();

CREATE TRIGGER trg_project_rename
    AFTER UPDATE OF name ON projects
    FOR EACH ROW EXECUTE FUNCTION trg_update_stack_refs();

-- Fires on INSERT (sets stack_ref) and on name UPDATE (keeps it in sync).
CREATE TRIGGER trg_stack_upsert
    BEFORE INSERT OR UPDATE OF name ON stacks
    FOR EACH ROW EXECUTE FUNCTION trg_update_stack_refs();

-- ---------------------------------------------------------------------------
-- 2. Status transition guard: terminal status is irreversible
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

-- ---------------------------------------------------------------------------
-- 3. Cache invalidation: journal INSERT automatically clears cached_checkpoint
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_invalidate_stack_cache()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    UPDATE stack_state
       SET cache_valid = false,
           updated_at  = now()
     WHERE stack_id   = NEW.stack_id
       AND cache_valid = true;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_journal_invalidates_cache
    AFTER INSERT ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION trg_invalidate_stack_cache();
```

---

## API → Schema Mapping

| Pulumi API Call | Tables Written | Tables Read |
|----------------|----------------|-------------|
| `GET /api/user` | — | `api_tokens`, `organizations` |
| `GET /api/user/organizations/default` | — | `organizations` |
| `GET /api/user/stacks?project=X` | — | `stacks`, `projects`, `organizations` |
| `POST /api/stacks/{org}/{project}` | `workspaces`, `stacks`, `stack_state` | `projects`, `organizations` |
| `GET /api/stacks/{org}/{project}/{stack}` | — | `stacks` (via `stack_ref`) |
| `DELETE /api/stacks/{org}/{project}/{stack}` | DELETE `workspaces` (cascades to `stacks` and all state) | `stack_state` (check resources == 0) |
| `GET /api/stacks/{org}/{project}/{stack}/export` | — | `stack_state` → `snapshots` + `journal_entries` |
| `POST .../update\|preview\|destroy` | INSERT `stack_updates` | `stacks` |
| `POST .../update/{updateID}` | INSERT `stack_locks`, UPDATE `stacks.active_update_id` | `stack_updates` |
| `PATCH .../update/{updateID}/checkpoint` | INSERT `journal_entries` OR INSERT `snapshots` | `stack_locks` (validate token) |
| `POST .../update/{updateID}/events/batch` | INSERT `update_events` (one row per decoded event) | `stack_locks` (validate token) |
| `POST .../update/{updateID}/complete` | UPDATE `stack_updates.status`, DELETE `stack_locks`, UPDATE `stacks.version` + `active_update_id = null` | `stack_updates` |

---

## Journal Entry Payload Structures

Each entry type uses the same row shape but different `payload` contents:

```
begin            → { operation?: { resource: urn, type: string }, deleteOld?: int }
success          → { removeOld?: int, removeNew?: uuid, pendingReplacement?: int,
                     delete?: int, state?: ResourceState, elideWrite: bool, isRefresh: bool }
failure          → {}
refresh_success  → { removeOld?: int, removeNew?: uuid, state?: ResourceState }
outputs          → { removeOld?: int, removeNew?: uuid, state?: ResourceState }
write            → { newSnapshot: DeploymentV3 }   -- first entry only; used for provider migrations
rebuilt_base_state → {}
```

---

## State Reconstruction (for `GET /export`)

```
1. Load stack_state WHERE stack_id = $id
2. Load snapshot WHERE id = stack_state.base_snapshot_id
3. SELECT FROM journal_entries
      WHERE stack_id = $id
        AND sequence_id > COALESCE(snapshot.compacted_from_journal_seq, 0)
      ORDER BY sequence_id ASC
4. Apply each entry to the snapshot's resource list:
     begin             → record pending_operation at deleteOld index
     success           → remove at removeOld/removeNew, append state; rebuild deps if isRefresh
     failure           → remove pending_operation
     refresh_success   → replace resource at same index
     outputs           → replace resource outputs at same index
     write             → replace entire snapshot (migrations only)
     rebuilt_base_state → prune stale dependency references
5. Return { version: 3, deployment: <reconstructed DeploymentV3> }
```

---

## Correctness Invariants

The tables below split what the schema enforces from what application code must guarantee.

### Handled by schema

| # | Concern | Mechanism |
|---|---------|-----------|
| 1 | Orphaned records | `ON DELETE CASCADE` on all child tables; `ON DELETE RESTRICT` on `stack_state.base_snapshot_id` (prevents deleting active base snapshot); `ON DELETE SET NULL` on `compaction_jobs.new_snapshot_id`; default `NO ACTION` on `stack_locks` composite FK (forces app to DELETE the lock before deleting its parent update) |
| 2 | Cross-entity mismatch | Composite FK `(update_id, stack_id) → stack_updates(id, stack_id)` on `journal_entries`, `pending_operations`, `update_events`, and `stack_locks`; DB rejects rows whose `update_id` belongs to a different stack |
| 3/4 | Rename drift / insert-time denormalization | `trg_stack_upsert` fires on `BEFORE INSERT OR UPDATE OF name`; INSERT branch computes `stack_ref` directly from `NEW.project_id` without querying the not-yet-committed row |
| 6 | Duplicate singletons | `stack_state` and `stack_locks` both use `stack_id PRIMARY KEY` |
| 7 | Invalid status transitions | `trg_update_status_guard` rejects any UPDATE that tries to leave a terminal status or revert to `in-progress` |
| 11 | Cache staleness | `trg_journal_invalidates_cache` sets `cache_valid = false` on every `journal_entries` INSERT for that stack |
| 13 | Idempotent update creation | `client_request_id TEXT` + partial unique index `(stack_id, client_request_id) WHERE client_request_id IS NOT NULL`; app uses `INSERT ... ON CONFLICT DO NOTHING` |
| 15 | Orphan/cleanup | Cascades cover the full tree; `stack_state.base_snapshot_id ON DELETE RESTRICT` prevents silent data loss from premature snapshot deletion |

### Application invariants (schema provides mechanism; app must use it correctly)

| # | Concern | Required app behavior |
|---|---------|----------------------|
| 5 | Multi-table partial failure | Every write that touches multiple tables must run inside a single transaction (`BEGIN` / `COMMIT`). Notably: create update + set `stacks.active_update_id`; complete update + delete lock + increment `stacks.version`. |
| 8 | Lost update / version races | `stacks.version` increment and `stack_updates.version` assignment must use `SELECT ... FOR UPDATE` on the `stacks` row. Since the lock prevents concurrent writers on the same stack, races only arise between the lock-acquire step and version increment — serialize these in the same transaction. |
| 9 | Journal sequence ordering | Assign `sequence_id` by doing `SELECT stack_state.journal_seq + 1 FOR UPDATE`, then incrementing `stack_state.journal_seq` and inserting the journal entry — all in one transaction. The row lock serializes concurrent sequence allocation. |
| 10 | Lease token validation | Lease-scoped operations (`PATCH .../checkpoint`, `POST .../events/batch`, `POST .../complete`, `POST .../renew_lease`, `DELETE .../update/{id}`) carry `Authorization: update-token <leaseTok>` instead of `token <apiTok>`. Verify the `<leaseTok>` matches `stack_locks.lock_token` AND `stack_locks.expires_at > now()`. Reject with 409 if mismatched or expired. The lease-token and api-token paths are independent and never overlap on the same request. |
| 12 | Compaction races | The compactor must hold a `SELECT ... FOR UPDATE` on `stack_state` for the duration of its read → compute → commit cycle. This blocks concurrent journal appends (which also lock `stack_state`) and ensures `to_journal_seq` remains consistent at commit time. |
| 14 | Tenant isolation | Every query that retrieves stacks or state must join through the `stack_ref` to verify the org matches the authenticated token's `org_id`. Never accept a bare `stack_id` from external input without confirming ownership. |
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
| Stack rename (`POST .../rename`) | No new table needed; triggers handle `stack_ref` update |
| Lease renewal (`POST .../renew_lease`) | Updates `stack_locks.expires_at`; needed for long deploys |
| History endpoint (`GET .../updates`) | Reads `stack_updates`; no new table needed |
| Stack config (`PUT .../config`) | Separate per-stack config storage not required for Wave 1 |
| Secrets encryption | Delegated to user-configured secrets providers (AWS KMS, Azure Key Vault, etc.); stored opaquely inside checkpoint JSONB. Service never sees plaintext. |
| OIDC identity linking | One user = one `(iss, sub)`. Linking multiple IdP identities to a single user (e.g., "I have Google and Okta accounts") is future work. |
| `statedb-cli` companion | A small CLI helper to automate the daily OIDC re-auth and write the resulting token into `~/.pulumi/credentials.json` (AWS-SSO-style). Wave 1 ships console-paste only; helper is later UX polish. |
| Tofu workspace extension | `workspaces` parent ships in Wave 1 so identity is stable from day one. Type-specific `tofu_workspaces` extension and Tofu state tables ship in Wave 3. |
| Event vacuum scheduler | `update_events` retention is enforced by an app-level job that deletes by `created_at`. The job itself (cadence, threshold) is config, not schema. |

---

## Verification (when implementing)

1. `pulumi login http://localhost:<port>` → validates `/api/user`, `/api/user/organizations/default`
2. `pulumi stack init dev` → `stacks` row inserted with correct `stack_ref`; `stack_state` row created
3. `pulumi up` → full update lifecycle: 2× `stack_updates`, `stack_locks`, `journal_entries`; `stacks.version` increments; `cache_valid` reset to false after each journal insert
4. `pulumi stack export` → reconstructs checkpoint from `stack_state` + `journal_entries`; diff against expected output
5. `pulumi destroy` → new `stack_updates` rows; `pending_operations` cleared; `stacks.version` increments
6. `pulumi stack rm` → all rows cascade-deleted; verify nothing remains
7. Concurrent `pulumi up` from two terminals → second returns lock conflict (409)
8. Duplicate `POST /update` with same `client_request_id` → returns existing row, no duplicate
9. `UPDATE stack_updates SET status = 'in-progress' WHERE status = 'succeeded'` → trigger raises exception
10. Rename org → all `stacks.stack_ref` values updated atomically by trigger
