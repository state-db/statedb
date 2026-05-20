-- service_secrets_keyset: singleton row holding the serialized Tink keyset
-- used by the service secrets provider (POST /encrypt, /decrypt, /batch-decrypt).
-- Seeded once by BootstrapProvisioner under an advisory lock — never by Flyway,
-- so the key bytes never live in migration history.
-- See design/secrets.md.

CREATE TABLE service_secrets_keyset (
    id           SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    key_material BYTEA        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
