-- Deployment snapshots plus an asynchronous compaction queue.
--
-- Compaction is coordinated by the database: each stack can have at most one
-- pending/running job, workers claim rows with SKIP LOCKED, and the stack_state
-- row remains the serialization point shared with journal append and complete.

CREATE TABLE snapshots (
    id                           UUID        PRIMARY KEY,
    stack_id                     UUID        NOT NULL REFERENCES stacks(id) ON DELETE CASCADE,
    version                      INTEGER     NOT NULL,
    checkpoint                   JSONB       NOT NULL,
    compacted_from_journal_seq   BIGINT      NOT NULL,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_snapshots_stack_created ON snapshots (stack_id, created_at DESC);

CREATE TABLE compaction_jobs (
    id            UUID        PRIMARY KEY,
    stack_id      UUID        NOT NULL REFERENCES stacks(id) ON DELETE CASCADE,
    status        TEXT        NOT NULL CHECK (status IN ('pending', 'running', 'completed', 'failed')),
    attempts      INTEGER     NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ
);

CREATE UNIQUE INDEX ux_compaction_jobs_active_stack
    ON compaction_jobs (stack_id)
    WHERE status IN ('pending', 'running');

CREATE INDEX idx_compaction_jobs_status_created
    ON compaction_jobs (status, created_at);

ALTER TABLE stack_state
    ADD CONSTRAINT fk_stack_state_base_snapshot
    FOREIGN KEY (base_snapshot_id) REFERENCES snapshots(id);
