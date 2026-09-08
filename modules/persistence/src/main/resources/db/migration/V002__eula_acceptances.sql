-- Records acceptance of the state-db End User License Agreement.
--
-- Append-only: each acceptance is legal evidence and is never updated or deleted. The
-- gate in StackCreator/StackImporter asks whether a row exists for the version the
-- running image ships. Retaining superseded versions preserves the acceptance history
-- across EULA revisions.
--
-- Two channels record acceptance. 'console' is a named site administrator clicking
-- accept and always carries the accepting user. 'configuration' is an operator setting
-- statedb.eula.accepted-version on the deployment itself, which has no console user;
-- the check below keeps the two shapes from being confused.

CREATE TABLE public.eula_acceptances (
    id uuid NOT NULL,
    version text NOT NULL,
    document_sha256 text NOT NULL,
    accepted_via text NOT NULL,
    accepted_by_user_id uuid,
    accepted_from text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT eula_acceptances_version_check CHECK ((version <> ''::text)),
    CONSTRAINT eula_acceptances_document_sha256_check CHECK ((document_sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT eula_acceptances_accepted_via_check CHECK ((accepted_via = ANY (ARRAY['console'::text, 'configuration'::text]))),
    CONSTRAINT eula_acceptances_console_user_check CHECK (((accepted_via <> 'console'::text) OR (accepted_by_user_id IS NOT NULL)))
);

ALTER TABLE ONLY public.eula_acceptances
    ADD CONSTRAINT eula_acceptances_pkey PRIMARY KEY (id);

-- One console acceptance per user per version: re-accepting is idempotent, while a new
-- version still requires a fresh acceptance. Configuration acceptances have no user, so
-- this partial index leaves them to the index below.
CREATE UNIQUE INDEX idx_eula_acceptances_version_user
    ON public.eula_acceptances USING btree (version, accepted_by_user_id)
    WHERE (accepted_by_user_id IS NOT NULL);

-- One configuration acceptance per version, so restarting a deployment that carries
-- statedb.eula.accepted-version does not append a row on every boot.
CREATE UNIQUE INDEX idx_eula_acceptances_version_configuration
    ON public.eula_acceptances USING btree (version)
    WHERE (accepted_via = 'configuration'::text);

CREATE INDEX idx_eula_acceptances_version_created
    ON public.eula_acceptances USING btree (version, created_at DESC);

-- The acceptance record must outlive the accepting user's account, so this FK does not
-- cascade or null: a deactivated user keeps their row, and users rows are never deleted
-- while an acceptance references them.
ALTER TABLE ONLY public.eula_acceptances
    ADD CONSTRAINT eula_acceptances_accepted_by_user_id_fkey FOREIGN KEY (accepted_by_user_id) REFERENCES public.users(id);
