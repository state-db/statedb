ALTER TABLE public.api_tokens
    ADD COLUMN access_profile text DEFAULT 'deploy'::text NOT NULL,
    ADD CONSTRAINT api_tokens_access_profile_check
        CHECK (
            (kind = 'oidc'::text AND access_profile = 'deploy'::text)
            OR (
                kind = 'service_account'::text
                AND access_profile = ANY (ARRAY['deploy'::text, 'stack_lifecycle'::text])
            )
        );
