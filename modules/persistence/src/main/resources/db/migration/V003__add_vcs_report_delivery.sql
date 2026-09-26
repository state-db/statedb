CREATE TABLE public.vcs_app_registrations (
    id uuid PRIMARY KEY,
    host text NOT NULL UNIQUE,
    app_id bigint NOT NULL,
    slug text NOT NULL,
    owner text NOT NULL,
    html_url text NOT NULL,
    private_key_ciphertext bytea NOT NULL,
    client_id text NOT NULL,
    client_secret_ciphertext bytea NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT vcs_app_registrations_host_shape
        CHECK (host = lower(host) AND host !~ '[/[:space:]]'),
    CONSTRAINT vcs_app_registrations_app_id_positive CHECK (app_id > 0),
    CONSTRAINT vcs_app_registrations_slug_nonempty CHECK (slug <> ''),
    CONSTRAINT vcs_app_registrations_owner_nonempty CHECK (owner <> ''),
    CONSTRAINT vcs_app_registrations_version_positive CHECK (version > 0)
);

CREATE TABLE public.vcs_auth_flows (
    state_hash bytea PRIMARY KEY,
    initiated_by_user_id uuid NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    purpose text NOT NULL CHECK (purpose IN ('manifest', 'install', 'oauth')),
    org_id uuid REFERENCES public.organizations(id) ON DELETE CASCADE,
    app_version integer,
    installation_id bigint,
    code_verifier text,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT vcs_auth_flows_context_check CHECK (
        (purpose = 'manifest' AND org_id IS NULL AND app_version IS NULL AND installation_id IS NULL AND code_verifier IS NULL)
        OR (purpose = 'install' AND org_id IS NOT NULL AND app_version IS NOT NULL AND app_version > 0 AND installation_id IS NULL AND code_verifier IS NULL)
        OR (purpose = 'oauth' AND org_id IS NOT NULL AND app_version IS NOT NULL AND app_version > 0 AND installation_id IS NOT NULL AND installation_id > 0 AND code_verifier IS NOT NULL)
    )
);

CREATE INDEX idx_vcs_auth_flows_org ON public.vcs_auth_flows (org_id);

CREATE INDEX idx_vcs_auth_flows_expiry
    ON public.vcs_auth_flows (expires_at);

CREATE TABLE public.vcs_connections (
    id uuid PRIMARY KEY,
    org_id uuid NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
    kind text NOT NULL,
    host text NOT NULL,
    installation_id bigint,
    enabled boolean DEFAULT true NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT vcs_connections_kind_shape CHECK (kind ~ '^[a-z][a-z0-9_-]{0,31}$'),
    CONSTRAINT vcs_connections_host_shape CHECK (host = lower(host) AND host !~ '[/[:space:]]'),
    CONSTRAINT vcs_connections_version_positive CHECK (version > 0),
    CONSTRAINT vcs_connections_installation_id_positive
        CHECK (installation_id IS NULL OR installation_id > 0),
    UNIQUE (org_id, host)
);

-- No foreign key to stack_updates: this optional queue must not lock or scan update history.
CREATE TABLE public.report_deliveries (
    id uuid PRIMARY KEY,
    update_id uuid NOT NULL UNIQUE,
    stack_id uuid NOT NULL,
    connection_id uuid NOT NULL REFERENCES public.vcs_connections(id) ON DELETE CASCADE,
    repository_owner text NOT NULL,
    repository_name text NOT NULL,
    head_sha text NOT NULL,
    pr_number bigint,
    generation bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    state text DEFAULT 'pending' NOT NULL,
    CONSTRAINT report_deliveries_state_check CHECK (state IN ('pending', 'delivered', 'failed')),
    target_repository_id text,
    target_pr_number bigint,
    body text,
    attempts integer DEFAULT 0 NOT NULL CONSTRAINT report_deliveries_attempts_check CHECK (attempts >= 0),
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    settled_at timestamp with time zone,
    external_id text,
    last_error text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT report_deliveries_head_sha_check CHECK (head_sha ~ '^[0-9a-f]{40,64}$'),
    CONSTRAINT report_deliveries_pr_number_check CHECK (pr_number IS NULL OR pr_number > 0),
    CONSTRAINT report_deliveries_target_check CHECK ((target_repository_id IS NULL AND target_pr_number IS NULL AND body IS NULL)
        OR (target_repository_id IS NOT NULL AND target_pr_number > 0 AND body IS NOT NULL))
);

CREATE INDEX idx_report_deliveries_pending
    ON public.report_deliveries (next_attempt_at, id) WHERE state = 'pending';
CREATE INDEX idx_report_deliveries_retention
    ON public.report_deliveries (settled_at, id) WHERE state <> 'pending';
