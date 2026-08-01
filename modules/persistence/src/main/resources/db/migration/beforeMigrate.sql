-- Flyway callback: runs before any migration is applied, on every startup.
--
-- state-db requires lz4 TOAST compression (V020) on the payload columns, so the
-- supported floor is PostgreSQL 14 built --with-lz4. Checking here rather than
-- letting V020 fail keeps a fresh install from stopping half-migrated, and
-- turns a bare "compression method lz4 not supported" into an actionable error.
--
-- Postgres registers the 'lz4' value of the default_toast_compression enum
-- under #ifdef USE_LZ4, so its enumvals report build support exactly. On
-- PostgreSQL 13 and older the setting does not exist and the check is false.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_settings
         WHERE name = 'default_toast_compression'
           AND 'lz4' = ANY (enumvals)
    ) THEN
        RAISE EXCEPTION
            'state-db requires PostgreSQL 14 or newer built with lz4 support; this server is % and does not offer lz4 TOAST compression',
            current_setting('server_version')
            USING HINT =
                'Use a PostgreSQL 14+ build compiled --with-lz4 (the official postgres images are).';
    END IF;
END
$$;
