-- Compaction is enqueued after every completed update (StackUpdateCompleter), so nothing ever
-- compared journal_entries_since_compaction against this column. Drop it rather than leave a
-- setting the console reports and no code honours.

ALTER TABLE stack_state
    DROP COLUMN auto_compact_threshold;
