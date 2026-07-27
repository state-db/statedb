-- Positional snapshot lookup for per-version export: find the newest snapshot whose
-- compaction boundary is at or below a completed update's end_journal_seq. The existing
-- idx_snapshots_stack_created orders by created_at and cannot serve this predicate.
CREATE INDEX idx_snapshots_stack_boundary
    ON snapshots (stack_id, compacted_from_journal_seq);
