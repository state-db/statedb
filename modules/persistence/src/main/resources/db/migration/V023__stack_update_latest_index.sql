-- Console list/detail/activity views need the newest update per stack and newest-first history.
-- Without this shape, soak histories force repeated scans or an explicit sort over stack_updates.
CREATE INDEX idx_updates_stack_started_created
    ON stack_updates (stack_id, started_at DESC, created_at DESC);
