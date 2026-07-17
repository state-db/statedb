#!/usr/bin/env sh
# Seed workbench-only fixtures the product does not provision from env:
#
# 1. The console OIDC admin as an owner of the workbench org, so GET /api/user shows organizations.
#    The OIDC user row is normally created on first login, so we pre-create the (iss, sub) identity
#    for the configured admin subject; on first sign-in UserUpserter finds this row (by iss/sub) and
#    the membership is already there.
# 2. The static workbench CLI token (sha256 in TOKEN_HASH), attached to the bootstrap admin user.
#    The product mints CLI tokens through a console session (break-glass or OIDC) via the
#    `pulumi login` browser flow; the workbench make targets need a non-interactive credential, so
#    it is seeded directly as a service-account token.
#
# Idempotent. Runs inside the dev container (docker CLI talks to the host daemon, same as the other
# workbench db targets). Values come from fixed workbench config (no quotes), so they are
# interpolated straight into the SQL.
set -eu

: "${DB_CONTAINER:?}" "${DB_USER:?}" "${DB_NAME:?}" "${ORG:?}" "${OIDC_ISSUER:?}" "${ADMIN_SUB:?}" "${TOKEN_HASH:?}"

psql() {
  docker exec -i "$DB_CONTAINER" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" "$@"
}

# Wait for the org to exist — it is created (with the bootstrap admin user and membership, in one
# transaction) by the app's bootstrap at startup.
echo "[seed-console] waiting for org \"$ORG\" ..."
until psql -tA -c "SELECT 1 FROM organizations WHERE name = '$ORG'" 2>/dev/null | grep -q 1; do
  sleep 2
done

psql \
  -c "INSERT INTO users (id, iss, sub) VALUES (gen_random_uuid(), '$OIDC_ISSUER', '$ADMIN_SUB') \
        ON CONFLICT (iss, sub) DO NOTHING" \
  -c "INSERT INTO org_members (org_id, user_id, role) \
        SELECT o.id, u.id, 'owner' FROM organizations o, users u \
        WHERE o.name = '$ORG' AND u.iss = '$OIDC_ISSUER' AND u.sub = '$ADMIN_SUB' \
        ON CONFLICT (org_id, user_id) DO NOTHING" \
  -c "INSERT INTO api_tokens (id, token_hash, description, user_id, org_id, kind, expires_at) \
        SELECT gen_random_uuid(), '$TOKEN_HASH', 'workbench', u.id, o.id, 'service_account', now() + interval '1 year' \
        FROM organizations o, users u \
        WHERE o.name = '$ORG' AND u.iss = 'local:bootstrap' AND u.sub = 'admin' \
        ON CONFLICT (token_hash) DO NOTHING"

echo "[seed-console] \"$ADMIN_SUB\" is an owner of org \"$ORG\"; workbench CLI token is seeded"
