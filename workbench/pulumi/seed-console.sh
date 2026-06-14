#!/usr/bin/env sh
# Seed the console OIDC admin into the workbench org so GET /api/user shows organizations.
#
# The OIDC user row is normally created on first login, so we pre-create the (iss, sub) identity for
# the configured admin subject and add it as an owner of the org. On first sign-in UserUpserter finds
# this row (by iss/sub) and the membership is already there. Idempotent. Runs inside the dev container
# (docker CLI talks to the host daemon, same as the other workbench db targets).
#
# Values come from fixed workbench config (no quotes), so they are interpolated straight into the SQL.
set -eu

: "${DB_CONTAINER:?}" "${DB_USER:?}" "${DB_NAME:?}" "${ORG:?}" "${OIDC_ISSUER:?}" "${ADMIN_SUB:?}"

psql() {
  docker exec -i "$DB_CONTAINER" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" "$@"
}

# Wait for the org to exist — it is created by the app's bootstrap at startup.
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
        ON CONFLICT (org_id, user_id) DO NOTHING"

echo "[seed-console] \"$ADMIN_SUB\" is now an owner of org \"$ORG\""
