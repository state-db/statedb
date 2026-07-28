ALTER TABLE update_events
    ALTER COLUMN payload SET COMPRESSION lz4;

ALTER TABLE journal_entries
    ALTER COLUMN payload SET COMPRESSION lz4;

ALTER TABLE snapshots
    ALTER COLUMN checkpoint SET COMPRESSION lz4;
