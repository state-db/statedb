-- OIDC CLI tokens become account-scoped. A token minted from a console SSO session
-- authenticates the user, not one org; authorization is resolved per request against
-- the stack's org (exactly like a console session) — matching Pulumi's own
-- account-scoped token model, where org is selected by the stack path, not the token.
--
-- service_account tokens stay org-scoped (org_id NOT NULL). The CHECK pins the two
-- kinds to their scope so the invariant the application relies on is enforced in the DB:
--   oidc            -> org_id IS NULL  (account-scoped; role resolved per request)
--   service_account -> org_id NOT NULL (org-scoped)
ALTER TABLE api_tokens ALTER COLUMN org_id DROP NOT NULL;

ALTER TABLE api_tokens ADD CONSTRAINT api_tokens_org_scope CHECK (
    (kind = 'service_account' AND org_id IS NOT NULL)
 OR (kind = 'oidc'            AND org_id IS NULL)
);
