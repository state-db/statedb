#!/usr/bin/env bash
# Host-native workbench app. One command: brings up infra,
# seeds workbench users, runs the app (foreground) and the bun console
# (background) together. Ctrl-C stops both; infra (Postgres + mock IdP) stays
# up — tear it down separately if needed:
#   docker compose -f workbench/pulumi/compose.yml down
#
# Internal supervisor for `make pulumi-workbench cmd=app` / `cmd=app-dev`.
# The Make targets own stale-process cleanup and select built-image or Quarkus
# dev mode; invoke them instead of running this script directly.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

ORG=${ORG:-acme}
CONSOLE_PORT=${CONSOLE_PORT:-5173}
IDP_UPSTREAM_PORT=${IDP_UPSTREAM_PORT:-58081}
DB_PORT=${DB_PORT:-55432}
DB_IMAGE=${DB_IMAGE:-postgres:16}
DB_VOLUME=${DB_VOLUME:-state-db-workbench-postgres-data}
DB_NAME=${DB_NAME:-statedb}
DB_USER=${DB_USER:-statedb}
DB_PASSWORD=${DB_PASSWORD:-statedb}
ISSUER="http://localhost:$CONSOLE_PORT/default"

for p in 8080 9000; do
  if lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then
    cat >&2 <<EOF
error: port $p is already in use.
Usually a leftover run this script's own cleanup didn't reap — try:
  make pulumi-workbench cmd=app-stop
or check what's holding it:
  lsof -nP -iTCP:$p -sTCP:LISTEN
EOF
    exit 1
  fi
done

echo "==> infra: Postgres + mock IdP"
make -C workbench/pulumi infra-up \
  DB_IMAGE="$DB_IMAGE" DB_VOLUME="$DB_VOLUME" DB_PORT="$DB_PORT" \
  DB_NAME="$DB_NAME" DB_USER="$DB_USER" DB_PASSWORD="$DB_PASSWORD" \
  IDP_UPSTREAM_PORT="$IDP_UPSTREAM_PORT"

echo "==> seeding workbench users (background)"
DB_CONTAINER=state-db-workbench-postgres DB_USER="$DB_USER" DB_NAME="$DB_NAME" ORG="$ORG" \
  OIDC_ISSUER="$ISSUER" ADMIN_SUB=admin \
  TOKEN_HASH=5ffbb9af69ff36cfa7e28cea7b3da597ed0d0489113674b3eb3a6c2b7e386ef5 \
  sh workbench/pulumi/seed-console.sh &
SEED_PID=$!

echo "==> bun console on :$CONSOLE_PORT (background)"
(cd modules/web && mise exec -- bun install --frozen-lockfile >/dev/null &&
  exec env PORT="$CONSOLE_PORT" OIDC_PROXY_TARGET="http://localhost:$IDP_UPSTREAM_PORT" \
    mise exec -- bun dev.ts) &
CONSOLE_PID=$!

cleanup() {
  trap - EXIT INT TERM
  kill "$CONSOLE_PID" "$SEED_PID" 2>/dev/null || true
  wait "$CONSOLE_PID" "$SEED_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "==> console will be at http://localhost:$CONSOLE_PORT — sign in as admin/developer/viewer"

if [[ -n "${STATEDB_IMAGE:-}" ]]; then
  echo "==> $STATEDB_IMAGE on :8080 (foreground — Ctrl-C stops this and the console; infra stays up)"
  docker run --rm --name state-db-workbench-app -p 8080:8080 -p 9000:9000 \
    -e STATEDB_JDBC_URL="jdbc:postgresql://host.docker.internal:$DB_PORT/$DB_NAME" \
    -e STATEDB_DB_USER="$DB_USER" -e STATEDB_DB_PASSWORD="$DB_PASSWORD" \
    -e STATEDB_BOOTSTRAP_ORG_NAME="$ORG" -e STATEDB_BOOTSTRAP_ORG_DISPLAY_NAME="Acme Workbench" \
    -e STATEDB_OIDC_BOOTSTRAP_NAME=mock -e STATEDB_OIDC_BOOTSTRAP_ISSUER="$ISSUER" \
    -e STATEDB_OIDC_BOOTSTRAP_CLIENT_ID=console -e STATEDB_OIDC_BOOTSTRAP_CLIENT_SECRET=console-secret \
    -e STATEDB_OIDC_DEFAULT_PROVIDER=mock \
    -e STATEDB_SESSION_ENCRYPTION_SECRET=state-db-workbench-session-secret-32-bytes \
    -e STATEDB_ADMIN_SUBJECTS="$ISSUER:admin" \
    "$STATEDB_IMAGE"
  exit 0
fi

echo "==> Quarkus dev mode on :8080 (foreground — Ctrl-C stops this and the console; infra stays up)"
QUARKUS_DATASOURCE_DEVSERVICES_ENABLED=false \
QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://localhost:$DB_PORT/$DB_NAME" \
QUARKUS_DATASOURCE_USERNAME="$DB_USER" QUARKUS_DATASOURCE_PASSWORD="$DB_PASSWORD" \
STATEDB_BOOTSTRAP_ORG_NAME="$ORG" STATEDB_BOOTSTRAP_ORG_DISPLAY_NAME="Acme Workbench" \
STATEDB_OIDC_BOOTSTRAP_NAME=mock STATEDB_OIDC_BOOTSTRAP_ISSUER="$ISSUER" \
STATEDB_OIDC_BOOTSTRAP_CLIENT_ID=console STATEDB_OIDC_BOOTSTRAP_CLIENT_SECRET=console-secret \
STATEDB_OIDC_DEFAULT_PROVIDER=mock \
STATEDB_SESSION_ENCRYPTION_SECRET=state-db-workbench-session-secret-32-bytes \
STATEDB_ADMIN_SUBJECTS="$ISSUER:admin" \
mise exec -- modules/mvnw -f modules/pom.xml -pl app -am -Dquarkus.test.continuous-testing=disabled quarkus:dev
