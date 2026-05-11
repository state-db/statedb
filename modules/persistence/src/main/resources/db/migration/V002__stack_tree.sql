-- Stack-tree tables. See design/db-schema.md for rationale.
-- (org, project, stack) lookups are 3-table JOINs at read time; no denormalized
-- ref column or rename triggers are maintained.

CREATE TABLE workspaces (
    id         UUID        PRIMARY KEY,
    org_id     UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    kind       TEXT        NOT NULL CHECK (kind IN ('pulumi', 'tofu')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Target for the composite FK from stacks(workspace_id, org_id); pins a stack's
    -- workspace to the same org as the stack itself.
    UNIQUE (id, org_id)
);

CREATE TABLE projects (
    id         UUID        PRIMARY KEY,
    org_id     UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (org_id, name),
    -- Target for the composite FK from stacks(project_id, org_id).
    UNIQUE (id, org_id)
);

-- org_id is denormalized so a single composite FK can enforce that project_id
-- and workspace_id resolve to rows in the same organization. Without it,
-- stacks could straddle two orgs and break tenant isolation.
CREATE TABLE stacks (
    id               UUID        PRIMARY KEY,
    org_id           UUID        NOT NULL,
    project_id       UUID        NOT NULL,
    name             TEXT        NOT NULL,
    workspace_id     UUID        NOT NULL UNIQUE,
    version          INTEGER     NOT NULL DEFAULT 0,
    active_update_id UUID,
    tags             JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, name),
    FOREIGN KEY (project_id, org_id) REFERENCES projects(id, org_id) ON DELETE CASCADE,
    FOREIGN KEY (workspace_id, org_id) REFERENCES workspaces(id, org_id) ON DELETE CASCADE
);

CREATE TABLE stack_state (
    id                               UUID        PRIMARY KEY,
    stack_id                         UUID        NOT NULL UNIQUE REFERENCES stacks(id) ON DELETE CASCADE,
    base_snapshot_id                 UUID,
    base_snapshot_version            INTEGER     NOT NULL DEFAULT 0,
    journal_seq                      BIGINT      NOT NULL DEFAULT 0,
    journal_entries_since_compaction INTEGER     NOT NULL DEFAULT 0,
    auto_compact_threshold           INTEGER     NOT NULL DEFAULT 100,
    cached_checkpoint                JSONB,
    cached_at                        TIMESTAMPTZ,
    cache_valid                      BOOLEAN     NOT NULL DEFAULT false,
    created_at                       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Postgres does not auto-index FK columns. workspaces.org_id is the only FK
-- here not covered by a UNIQUE constraint — stacks.workspace_id, stack_state.stack_id,
-- and the FK halves of UNIQUE(org_id,name) / UNIQUE(project_id,name) all reuse
-- their respective UNIQUE indexes via leftmost-prefix matching. The composite
-- FKs stacks(project_id, org_id) and stacks(workspace_id, org_id) likewise reuse
-- UNIQUE(project_id, name) and UNIQUE(workspace_id) for cascade lookups (org_id
-- is functionally determined by the leftmost column).
CREATE INDEX idx_workspaces_org_kind ON workspaces (org_id, kind);
