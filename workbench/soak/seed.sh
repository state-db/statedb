#!/usr/bin/env bash
# Seed the soak CLI token. Tokens are normally minted through a console session; an unattended run
# needs a non-interactive credential, so it is inserted directly, as seed-console.sh does.
#
# Idempotent.
set -euo pipefail

: "${PGHOST:?}" "${PGUSER:?}" "${PGDATABASE:?}" "${PGPASSWORD:?}" "${ORG:?}" "${TOKEN_HASH:?}"

# The org appears only once the app's bootstrap has committed, which races container start.
echo "[seed] waiting for org \"$ORG\" to be bootstrapped ..."
until psql -tAqc "SELECT 1 FROM organizations WHERE name = '$ORG'" 2>/dev/null | grep -q 1; do
  sleep 2
done

# expires_at is NOT NULL, and a year is well outside any plausible run length.
psql -v ON_ERROR_STOP=1 -qc "
  INSERT INTO api_tokens (id, token_hash, description, user_id, org_id, kind, expires_at)
  SELECT gen_random_uuid(), '$TOKEN_HASH', 'soak', u.id, o.id, 'service_account', now() + interval '1 year'
    FROM organizations o, users u
   WHERE o.name = '$ORG' AND u.iss = 'local:bootstrap' AND u.sub = 'admin'
  ON CONFLICT (token_hash) DO NOTHING"

echo "[seed] soak CLI token is seeded in org \"$ORG\""
