-- Compressed archive of one terminal update's engine-event stream.
--
-- update_events is append-only per update and immutable once the update is
-- terminal, and the console reads it only per-update in sequence order. Folding
-- the stream into a single compressed blob trades per-row addressability -- which
-- nothing uses -- for an order-of-magnitude smaller footprint. This is a
-- representation change, not retention: the blob carries every event verbatim.
--
-- One row per update: update_id is the primary key, which is also what makes
-- folding idempotent across pods. The composite FK mirrors update_events and
-- rejects an archive whose update belongs to a different stack.
--
-- `summary` rows are deliberately left in update_events (they are also inside
-- the blob) because run detail and the console history list read them on every
-- view and must never pay a decompression for it.

CREATE TABLE update_event_archives (
    update_id    UUID        PRIMARY KEY,
    stack_id     UUID        NOT NULL,
    codec        TEXT        NOT NULL,
    event_count  INTEGER     NOT NULL,
    max_sequence INTEGER     NOT NULL,
    events       BYTEA       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    FOREIGN KEY (update_id, stack_id) REFERENCES stack_updates(id, stack_id) ON DELETE CASCADE
);

-- The blob is already compressed; EXTERNAL keeps TOAST from spending CPU on a
-- second pass that cannot win.
ALTER TABLE update_event_archives ALTER COLUMN events SET STORAGE EXTERNAL;

-- Which updates the folder has already settled.
--
-- Eligibility cannot be derived from the archive table alone. "No archive yet"
-- is true forever for updates that have no foldable events -- imports, renames,
-- runs aborted before their first event batch -- so a sweep keyed off it rescans
-- the whole of history on every tick to re-prove the same negatives, at a cost
-- that grows with total updates rather than with outstanding work.
--
-- The marker is set when an update is folded and equally when the folder looks
-- and finds nothing to fold, so the pending set drains to the recent window and
-- the partial index below holds only real work. Unfolding clears it.
--
-- The alternative -- a partial index on update_events -- would put an extra index
-- on the hottest insert path in the system: an update writes one row here and
-- hundreds there.

ALTER TABLE stack_updates
    ADD COLUMN events_folded_at TIMESTAMPTZ;

-- Terminal updates that will never have anything to fold start out settled, so
-- an existing deployment does not walk its whole history to discover that.
UPDATE stack_updates u
   SET events_folded_at = now()
 WHERE u.status <> 'in-progress'
   AND NOT EXISTS (SELECT 1 FROM update_events e
                    WHERE e.update_id = u.id AND e.kind <> 'summary');

CREATE INDEX idx_updates_fold_pending ON stack_updates (ended_at)
    WHERE events_folded_at IS NULL;
