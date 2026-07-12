-- Enforce the org/project/stack name grammar (NameGrammar.java) at the schema level.
-- Names are joined with '/' to form Cedar resource ids and parsed out of request
-- paths by splitting on '/', so a name containing a slash (or other characters
-- outside the grammar) can make two distinct resources collide on one authorization
-- id. The application validates on every write path; these CHECKs are the backstop
-- for writers that bypass it (admin scripts, raw SQL).

ALTER TABLE organizations
    ADD CONSTRAINT organizations_name_grammar CHECK (name ~ '^[A-Za-z0-9._-]{1,100}$');

ALTER TABLE projects
    ADD CONSTRAINT projects_name_grammar CHECK (name ~ '^[A-Za-z0-9._-]{1,100}$');

ALTER TABLE stacks
    ADD CONSTRAINT stacks_name_grammar CHECK (name ~ '^[A-Za-z0-9._-]{1,100}$');
