-- stack_locks: per-stack distributed lock with expiry + heartbeat.
-- See design/db-schema.md §stack_locks.
-- UNIQUE(stack_id) is what lets `POST .../update/{updateID}` use an
-- INSERT ... ON CONFLICT (stack_id) DO UPDATE ... WHERE expires_at < now()
-- upsert to atomically steal an expired lock or 409 against a live one.
--
-- The lease bearer is a live secret while the lock exists; storing it as
-- SHA-256 means a leaked DB read cannot drive a `pulumi up`. The wire shape
-- stays a UUID (Pulumi treats it opaquely); lookups hash the inbound
-- plaintext and query the unique index.

CREATE TABLE stack_locks (
    id              UUID        PRIMARY KEY,
    stack_id        UUID        NOT NULL UNIQUE REFERENCES stacks(id) ON DELETE CASCADE,
    update_id       UUID,
    locked_by       TEXT        NOT NULL,
    lock_token_hash BYTEA       NOT NULL,
    locked_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    -- Hard ceiling on total lease lifetime. expires_at is clamped to this on
    -- every renew, so a bearer that has been continuously renewed past this
    -- point is rejected even when otherwise valid. Bounds the blast radius of
    -- a leaked token when the legit client has stopped rotating it.
    max_expires_at  TIMESTAMPTZ NOT NULL,
    heartbeat_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (update_id, stack_id) REFERENCES stack_updates(id, stack_id)
);

CREATE UNIQUE INDEX idx_stack_locks_token_hash ON stack_locks (lock_token_hash);
