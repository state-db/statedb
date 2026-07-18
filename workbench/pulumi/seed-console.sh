#!/usr/bin/env sh
# Seed workbench-only fixtures the product does not provision from env:
#
# 1. Workbench OIDC users as members of the workbench org, so GET /api/user shows organizations.
#    The OIDC user row is normally created on first login, so we pre-create each (iss, sub)
#    identity; on first sign-in UserUpserter finds this row (by iss/sub) and the membership is
#    already there.
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
  -c "INSERT INTO users (id, iss, sub, email, display_name) VALUES \
          (gen_random_uuid(), '$OIDC_ISSUER', '$ADMIN_SUB', 'admin@example.com', 'Admin Owner'), \
          (gen_random_uuid(), '$OIDC_ISSUER', 'developer', 'developer@example.com', 'Developer Member'), \
          (gen_random_uuid(), '$OIDC_ISSUER', 'viewer', 'viewer@example.com', 'View Only') \
        ON CONFLICT (iss, sub) DO UPDATE \
          SET email = EXCLUDED.email, display_name = EXCLUDED.display_name, updated_at = now()" \
  -c "INSERT INTO org_members (org_id, user_id, role) \
        SELECT o.id, u.id, v.role FROM organizations o \
        JOIN (VALUES ('$ADMIN_SUB', 'owner'), ('developer', 'member'), ('viewer', 'viewer')) AS v(sub, role) ON true \
        JOIN users u ON u.iss = '$OIDC_ISSUER' AND u.sub = v.sub \
        WHERE o.name = '$ORG' \
        ON CONFLICT (org_id, user_id) DO UPDATE SET role = EXCLUDED.role" \
  -c "INSERT INTO api_tokens (id, token_hash, description, user_id, org_id, kind, expires_at) \
        SELECT gen_random_uuid(), '$TOKEN_HASH', 'workbench', u.id, o.id, 'service_account', now() + interval '1 year' \
        FROM organizations o, users u \
        WHERE o.name = '$ORG' AND u.iss = 'local:bootstrap' AND u.sub = 'admin' \
        ON CONFLICT (token_hash) DO NOTHING"

echo "[seed-console] \"$ADMIN_SUB\", \"developer\", and \"viewer\" are seeded in org \"$ORG\"; workbench CLI token is seeded"
