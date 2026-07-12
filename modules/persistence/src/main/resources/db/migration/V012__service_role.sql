-- Machine identities (CI service accounts) hold the 'service' role in an org.
-- The role sits outside the viewer/member/owner ladder and carries its own Cedar
-- grant set (policies/service.cedar), so CI permissions evolve independently of
-- human permissions.

ALTER TABLE org_members DROP CONSTRAINT org_members_role_check;
ALTER TABLE org_members
    ADD CONSTRAINT org_members_role_check
    CHECK (role IN ('owner', 'member', 'viewer', 'service'));
