-- policy_grants: DB-backed attachments of Cedar policy templates to a principal
-- and (optionally) a stack. Each row links one classpath template by id and
-- supplies its ?principal / ?resource slots, producing one linked policy in the
-- live set (PolicySetCache assembles them at startup and on reload).
--
--   * StackWriteAccess  → ?principal = the user, ?resource = the org
--     (org-scoped via the Stack -> Project -> Org hierarchy). resource_stack_id null.
--   * StackReadResourcePolicy / StackWriteResourcePolicy → ?principal = the user,
--     ?resource = the stack. resource_stack_id set.
--
-- References are DB-native UUIDs (not Cedar strings) so grants are org-scoped and
-- cascade cleanly. The constraints below keep every row in a shape PolicySetCache
-- can turn into a valid TemplateLink: a known template, the right ?resource slot
-- populated, and (for shares) a stack that actually belongs to the grant's org.
-- See design/auth.md.

-- Composite-FK target so a share can pin its stack to the grant's org (mirrors the
-- stacks(project_id, org_id) -> projects(id, org_id) pattern in V002). id is already
-- unique on its own; this adds the (id, org_id) unique index the FK below references.
ALTER TABLE stacks ADD CONSTRAINT stacks_id_org_key UNIQUE (id, org_id);

CREATE TABLE policy_grants (
    id                UUID         PRIMARY KEY,
    template_id       TEXT         NOT NULL,
    org_id            UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    principal_user_id UUID         NOT NULL REFERENCES users(id)         ON DELETE CASCADE,
    resource_stack_id UUID,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Each template binds a fixed ?resource shape: the managed (per-org) template
    -- leaves resource_stack_id null (?resource = the org); the per-stack share
    -- templates require it (?resource = that stack). This also closes the set of
    -- accepted template ids — adding a template is a deliberate migration.
    CONSTRAINT policy_grants_template_shape CHECK (
        (template_id = 'StackWriteAccess'                                          AND resource_stack_id IS NULL)
     OR (template_id IN ('StackReadResourcePolicy', 'StackWriteResourcePolicy')    AND resource_stack_id IS NOT NULL)
    ),

    -- A share's stack must belong to the org the grant is tenanted under, so a row
    -- can never bind ?resource to another org's stack. MATCH SIMPLE skips this when
    -- resource_stack_id is null (managed grants); the standalone org_id FK still
    -- constrains the org. Cascades when the stack is deleted.
    CONSTRAINT policy_grants_stack_in_org
        FOREIGN KEY (resource_stack_id, org_id) REFERENCES stacks (id, org_id) ON DELETE CASCADE
);

CREATE INDEX policy_grants_org_idx ON policy_grants (org_id);
CREATE INDEX policy_grants_principal_idx ON policy_grants (principal_user_id);
CREATE INDEX policy_grants_stack_idx ON policy_grants (resource_stack_id);
