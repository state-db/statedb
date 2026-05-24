-- Decoded events from POST .../update/{updateID}/events/batch.
--
-- Pulumi CLI streams stdout, diagnostics, and resource lifecycle events here.
-- Each row is one decoded event; the batch payload's gzip envelope is unwrapped
-- on receipt (Vert.x auto-decompression) and individual events are inserted
-- with their original sequence numbers preserved.
--
-- The journal is the system of record for state; events are observability data
-- and safe to drop independently. The BRIN index on created_at exists so a
-- time-bounded retention sweep can prune old rows cheaply.

CREATE TABLE update_events (
    id         UUID        PRIMARY KEY,
    stack_id   UUID        NOT NULL,
    update_id  UUID        NOT NULL,
    sequence   INTEGER     NOT NULL,
    kind       TEXT        NOT NULL,
    severity   TEXT,
    payload    JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (update_id, sequence),

    -- Composite FK enforces stack_id consistency, same pattern as journal_entries:
    -- rejects events whose update_id belongs to a different stack.
    FOREIGN KEY (update_id, stack_id) REFERENCES stack_updates(id, stack_id) ON DELETE CASCADE
);

CREATE INDEX idx_update_events_update    ON update_events (update_id, sequence);
CREATE INDEX idx_update_events_severity  ON update_events (update_id, severity)
    WHERE severity IN ('warning', 'error');
CREATE INDEX idx_update_events_created   ON update_events USING BRIN (created_at);
