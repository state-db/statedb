-- Identities and service credentials are deactivated/revoked rather than deleted so
-- historical updates retain useful actor attribution.
ALTER TABLE users
    ADD COLUMN deactivated_at TIMESTAMPTZ,
    ADD COLUMN deactivated_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX idx_users_deactivated_by ON users (deactivated_by_user_id);

ALTER TABLE api_tokens
    ADD COLUMN created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN revoked_at TIMESTAMPTZ,
    ADD COLUMN revoked_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX idx_api_tokens_created_by ON api_tokens (created_by_user_id);
CREATE INDEX idx_api_tokens_revoked_by ON api_tokens (revoked_by_user_id);
CREATE INDEX idx_api_tokens_service_status
    ON api_tokens (org_id, expires_at)
    WHERE kind = 'service_account' AND revoked_at IS NULL;
