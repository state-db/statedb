-- Stack sharing & visibility (design/stack-sharing.md).
--
-- 'guest' is the additive-only human membership tier: membership (tenancy,
-- revocation, pulumi login) with no default permissions — access is exactly the
-- sum of the user's policy_grants rows. The Cedar role ladder places it below
-- viewer (guest ⊂ viewer ⊂ member ⊂ owner); it matches no static policy.

ALTER TABLE org_members DROP CONSTRAINT org_members_role_check;
ALTER TABLE org_members
    ADD CONSTRAINT org_members_role_check
    CHECK (role IN ('owner', 'member', 'viewer', 'service', 'guest'));

-- Read visibility: 'internal' = org-wide member read (the default, GitHub's
-- sense of the word); 'private' = readable only by owners, service accounts,
-- and explicit policy_grants grantees. viewer.cedar gates its org-wide read on
-- visibility = 'internal'; the list finders and StackEntityLoader apply the
-- mirrored SQL predicate.
ALTER TABLE stacks
    ADD COLUMN visibility TEXT NOT NULL DEFAULT 'internal'
    CHECK (visibility IN ('internal', 'private'));
