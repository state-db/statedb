-- Dry-run passes must not consume the applied-version sequence.
--
-- `pulumi up` (and refresh/destroy) runs two operations against the backend: a dry-run pass
-- (options.dryRun = true, same kind as the apply) and the apply. Versions address *applied*
-- updates — `pulumi stack export --version N` starts at 1 and increases by one per applied
-- update — so dry-run rows taking versions breaks version addressing, double-bumps
-- stacks.version on completion, and leaks phantom rows into `pulumi stack history`.
--
-- Dry-run rows get a first-class flag and version 0; the (stack_id, version) uniqueness
-- guarantee now applies to applied rows only.

ALTER TABLE stack_updates
    ADD COLUMN dry_run BOOLEAN NOT NULL DEFAULT false;

UPDATE stack_updates
SET dry_run = true
WHERE kind = 'preview'
   OR (options ->> 'dryRun')::boolean IS TRUE;

ALTER TABLE stack_updates DROP CONSTRAINT stack_updates_stack_id_version_key;

UPDATE stack_updates SET version = 0 WHERE dry_run;

-- Renumber applied rows contiguously from 1 per stack (dry-run rows previously burned versions).
WITH renumbered AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY stack_id ORDER BY version) AS applied_version
    FROM stack_updates
    WHERE NOT dry_run
)
UPDATE stack_updates su
SET version = r.applied_version
FROM renumbered r
WHERE su.id = r.id;

CREATE UNIQUE INDEX stack_updates_stack_id_version_key
    ON stack_updates (stack_id, version)
    WHERE NOT dry_run;

-- stacks.version was bumped once per terminal non-preview update, including dry-run apply
-- passes; re-derive it from the corrected rows.
UPDATE stacks s
SET version = (
    SELECT COUNT(*)
    FROM stack_updates u
    WHERE u.stack_id = s.id
      AND NOT u.dry_run
      AND u.status IN ('succeeded', 'failed', 'cancelled')
);
