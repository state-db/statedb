-- Identity & auth tables. See design/db-schema.md for rationale and the
-- full schema this is the first slice of.
--
-- All UUID PKs are application-generated UUIDv7 (Hibernate
-- @UuidGenerator(style = TIME)). The DB does not assign defaults.

CREATE TABLE organizations (
    id           UUID        PRIMARY KEY,
    name         TEXT        NOT NULL UNIQUE,
    display_name TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE oidc_providers (
    id            UUID        PRIMARY KEY,
    name          TEXT        NOT NULL UNIQUE,
    issuer_url    TEXT        NOT NULL,
    client_id     TEXT        NOT NULL,
    client_secret TEXT        NOT NULL,
    scopes        TEXT[]      NOT NULL DEFAULT ARRAY['openid','profile','email'],
    enabled       BOOLEAN     NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (issuer_url, client_id)
);

CREATE TABLE users (
    id            UUID        PRIMARY KEY,
    iss           TEXT        NOT NULL,
    sub           TEXT        NOT NULL,
    email         TEXT,
    display_name  TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ,
    UNIQUE (iss, sub)
);

CREATE TABLE api_tokens (
    id           UUID        PRIMARY KEY,
    token_hash   TEXT        NOT NULL UNIQUE,
    description  TEXT,
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    org_id       UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    kind         TEXT        NOT NULL CHECK (kind IN ('oidc', 'service_account')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ
);

-- Postgres does not auto-index FK columns; without these the cascade on
-- DELETE organizations / users seq-scans api_tokens.
CREATE INDEX idx_api_tokens_user ON api_tokens (user_id);
CREATE INDEX idx_api_tokens_org  ON api_tokens (org_id);

CREATE TABLE org_members (
    org_id     UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role       TEXT        NOT NULL CHECK (role IN ('owner', 'member', 'viewer')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, user_id)
);

CREATE INDEX idx_org_members_user ON org_members (user_id);

-- The application maintains updated_at via Hibernate @UpdateTimestamp; the
-- DEFAULT now() above is a safety net for non-Hibernate inserts only, and
-- nothing in the DB advances the column on UPDATE.
