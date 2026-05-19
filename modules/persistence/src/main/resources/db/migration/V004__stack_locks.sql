-- stack_locks: per-stack distributed lock with expiry + heartbeat.
-- See design/db-schema.md §stack_locks.
-- UNIQUE(stack_id) is what lets `POST .../update/{updateID}` use an
-- INSERT ... ON CONFLICT (stack_id) DO UPDATE ... WHERE expires_at < now()
-- upsert to atomically steal an expired lock or 409 against a live one.

CREATE TABLE stack_locks (
    id           UUID        PRIMARY KEY,
    stack_id     UUID        NOT NULL UNIQUE REFERENCES stacks(id) ON DELETE CASCADE,
    update_id    UUID,
    locked_by    TEXT        NOT NULL,
    lock_token   UUID        NOT NULL,
    locked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (update_id, stack_id) REFERENCES stack_updates(id, stack_id)
);

CREATE INDEX idx_stack_locks_token ON stack_locks (lock_token);
