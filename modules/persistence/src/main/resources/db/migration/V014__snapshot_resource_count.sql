-- Materialize the resource count on snapshots so list screens read a scalar
-- instead of reassembling checkpoints. Snapshot writers (compaction, import,
-- rename) set it from the deployment's resources array at snapshot time.

ALTER TABLE snapshots
    ADD COLUMN resource_count INTEGER NOT NULL DEFAULT 0;

UPDATE snapshots
   SET resource_count = jsonb_array_length(checkpoint->'resources')
 WHERE jsonb_typeof(checkpoint->'resources') = 'array';

-- Drop the pre-journaling stack_state read cache. Base snapshot compaction
-- superseded it; no writer ever populated it, and it only ever accumulated
-- invalidation code.

DROP TRIGGER trg_journal_invalidates_cache ON journal_entries;
DROP FUNCTION trg_invalidate_stack_cache();

ALTER TABLE stack_state
    DROP COLUMN cached_checkpoint,
    DROP COLUMN cached_at,
    DROP COLUMN cache_valid;
