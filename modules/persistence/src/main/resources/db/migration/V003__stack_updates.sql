-- stack_updates: one row per Pulumi operation (preview/update/destroy/refresh/import/rename).
-- See design/db-schema.md §stack_updates for rationale. The id IS the updateID
-- returned by POST /api/stacks/{org}/{project}/{stack}/{update|preview|destroy}.

CREATE TABLE stack_updates (
    id       UUID    PRIMARY KEY,
    stack_id UUID    NOT NULL REFERENCES stacks(id) ON DELETE CASCADE,

    version  INTEGER NOT NULL,
    kind     TEXT    NOT NULL CHECK (kind IN (
                 'update', 'preview', 'destroy', 'refresh', 'import', 'rename')),
    status   TEXT    NOT NULL DEFAULT 'in-progress' CHECK (status IN (
                 'in-progress', 'succeeded', 'failed', 'cancelled')),

    client_request_id TEXT,

    journal_version       INTEGER,
    base_snapshot_version INTEGER,
    start_journal_seq     BIGINT,
    end_journal_seq       BIGINT,

    config         JSONB,
    metadata       JSONB,
    options        JSONB,
    description    TEXT,
    runtime        TEXT,
    pulumi_version TEXT,
    tags           JSONB,

    message TEXT,

    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at   TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (stack_id, version),
    -- Target for the composite FK from journal_entries / pending_operations (later slices).
    UNIQUE (id, stack_id)
);

-- Idempotency: enforce (stack, request) uniqueness only when caller supplied a key.
CREATE UNIQUE INDEX idx_updates_idempotency
    ON stack_updates (stack_id, client_request_id)
    WHERE client_request_id IS NOT NULL;

CREATE INDEX idx_updates_stack_status ON stack_updates (stack_id, status);
CREATE INDEX idx_updates_in_progress  ON stack_updates (stack_id)
    WHERE status = 'in-progress';

-- Promote the deferred stacks.active_update_id FK now that stack_updates exists.
ALTER TABLE stacks
    ADD CONSTRAINT fk_stacks_active_update
    FOREIGN KEY (active_update_id) REFERENCES stack_updates(id)
    DEFERRABLE INITIALLY DEFERRED;

-- Terminal-status guard: once a row reaches succeeded/failed/cancelled it never
-- transitions again; in-progress can only be the initial value.
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
END;
$$;

CREATE TRIGGER trg_update_status_guard
    BEFORE UPDATE OF status ON stack_updates
    FOR EACH ROW EXECUTE FUNCTION trg_guard_update_status();
