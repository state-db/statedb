-- Journal append for Pulumi update progress.
--
-- See design/db-schema.md §JOURNALING for the table design.
--
-- Replay key: server-assigned `sequence_id` is computed from a per-update offset
-- captured in stack_updates.start_journal_seq + the wire `sequenceID`. The CLI
-- assigns wire IDs via an atomic counter at entry-creation time, but concurrent
-- resource workers can push to the journal channel out of counter order, so wire
-- IDs may arrive at the server non-monotonically across PATCHes. The offset
-- scheme makes `ORDER BY sequence_id` recover correct replay order regardless
-- of arrival order.

CREATE TABLE journal_entries (
    id                UUID    PRIMARY KEY,
    stack_id          UUID    NOT NULL REFERENCES stacks(id) ON DELETE CASCADE,
    update_id         UUID    NOT NULL,
    sequence_id       BIGINT  NOT NULL,
    wire_sequence_id  BIGINT  NOT NULL,

    kind         SMALLINT NOT NULL,
    operation_id BIGINT,
    version      INTEGER  NOT NULL DEFAULT 1,

    remove_old              BIGINT,
    remove_new              BIGINT,
    delete_old              BIGINT,
    delete_new              BIGINT,
    pending_replacement_old BIGINT,
    pending_replacement_new BIGINT,
    is_refresh              BOOLEAN NOT NULL DEFAULT false,

    payload      JSONB NOT NULL,
    resource_urn TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (stack_id, sequence_id),
    UNIQUE (update_id, wire_sequence_id),

    FOREIGN KEY (update_id, stack_id) REFERENCES stack_updates(id, stack_id)
);

CREATE INDEX idx_journal_stack_seq ON journal_entries (stack_id, sequence_id);
CREATE INDEX idx_journal_update    ON journal_entries (update_id);
CREATE INDEX idx_journal_operation ON journal_entries (stack_id, operation_id)
    WHERE operation_id IS NOT NULL;

-- Crash-recovery tracker. Populated on kind=0 (Begin) entries that include
-- operation.resource; pruned on kind=1/2/3 terminal entries for the same
-- (update_id, operation_id). Stale rows from terminated updates are swept
-- by compaction (later slice).
CREATE TABLE pending_operations (
    id                UUID    PRIMARY KEY,
    stack_id          UUID    NOT NULL REFERENCES stacks(id) ON DELETE CASCADE,
    update_id         UUID    NOT NULL,
    operation_id      BIGINT  NOT NULL,
    resource_urn      TEXT    NOT NULL,
    resource_type     TEXT    NOT NULL,
    operation_type    TEXT    NOT NULL CHECK (operation_type IN (
                          'creating', 'updating', 'deleting', 'reading', 'importing')),
    begin_journal_seq BIGINT  NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (update_id, operation_id),
    FOREIGN KEY (update_id, stack_id) REFERENCES stack_updates(id, stack_id)
);

CREATE INDEX idx_pending_ops_stack ON pending_operations (stack_id);

-- Cache invalidation: any journal append flips stack_state.cache_valid = false
-- so the next /export rebuilds rather than serving a stale cached checkpoint.
CREATE OR REPLACE FUNCTION trg_invalidate_stack_cache()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    UPDATE stack_state
       SET cache_valid = false,
           updated_at  = now()
     WHERE stack_id    = NEW.stack_id
       AND cache_valid = true;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_journal_invalidates_cache
    AFTER INSERT ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION trg_invalidate_stack_cache();
