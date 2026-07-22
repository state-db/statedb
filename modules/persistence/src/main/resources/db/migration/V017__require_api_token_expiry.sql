-- Every credential kind has a bounded lifetime. Current mint paths already set
-- expiry; making it a database invariant removes the historical "never expires"
-- token state from authentication and console contracts.
ALTER TABLE api_tokens
    ALTER COLUMN expires_at SET NOT NULL;
