#!/usr/bin/env bash
# Runs inside the Docker container.
# Starts the recorder server, drives pulumi through the full command sequence,
# then generates the catalog.
set -euo pipefail

PORT=8080
FIXTURE_DIR=${FIXTURE_DIR:-/fixtures}

# Recording identity — the single source for every place these values appear
# (stack refs below, meta.json, and the catalog generator flags).
ORG=testorg
PROJECT=recorder-test
STACK=dev
STACK_REF="${ORG}/${PROJECT}/${STACK}"

export PULUMI_HOME=/tmp/pulumi-home
export PULUMI_ACCESS_TOKEN=test-token
export PULUMI_BACKEND_URL="http://localhost:${PORT}"
export PULUMI_CONFIG_PASSPHRASE=test
export PULUMI_SKIP_UPDATE_CHECK=true
export PULUMI_SKIP_CONFIRMATIONS=true

mkdir -p "$FIXTURE_DIR" "$PULUMI_HOME"
rm -rf "$FIXTURE_DIR/by-command" "$FIXTURE_DIR/meta.json"

echo "==> starting recorder on :${PORT}"
/recorder/recorder -port "$PORT" -fixtures "$FIXTURE_DIR" &
RECORDER_PID=$!
trap 'kill "$RECORDER_PID" 2>/dev/null || true' EXIT

# Wait for the recorder to accept requests instead of a fixed sleep.
ready=0
for _ in $(seq 1 100); do
  if curl -sf "http://localhost:${PORT}/meta/health" > /dev/null; then
    ready=1
    break
  fi
  sleep 0.1
done
if [[ "$ready" -ne 1 ]]; then
  echo "recorder did not become ready on :${PORT}" >&2
  exit 1
fi

CLI_VERSION="$(pulumi version)"

# Signal the recorder that a new command is starting.
# Fixtures for this command land in fixtures/by-command/{label}/.
signal() {
  curl -sf -X POST "http://localhost:${PORT}/meta/command" \
    -H "Content-Type: application/json" \
    -d "{\"command\": \"$1\"}" > /dev/null
}

# Put a stack into the "update in flight" state without issuing a pulumi command.
# Nothing is recorded — /meta/ paths bypass fixture writing.
seed_active_update() {
  curl -sf -X POST "http://localhost:${PORT}/meta/active-update" \
    -H "Content-Type: application/json" \
    -d "{\"stack\": \"$1\"}" > /dev/null
}

run() {
  local cmd="$*"
  echo ""
  echo "--- $cmd ---"
  eval "$cmd" || {
    echo "[FAILED: $cmd]"
    echo "Fixtures captured so far are in $FIXTURE_DIR"
    exit 1
  }
}

# Like run(), but tolerates a non-zero exit. Used for experimental or
# not-yet-supported subcommands where the point of recording is to capture
# whatever HTTP call the CLI attempts (if any) before it gives up — the failure
# itself is the signal, not an error.
try() {
  local cmd="$*"
  echo ""
  echo "--- (allow-fail) $cmd ---"
  eval "$cmd" || echo "[non-zero exit (expected): $cmd]"
}

cd /testproject

signal "01-login"
run pulumi login "http://localhost:${PORT}"

signal "02-stack-init"
run pulumi stack init dev

signal "03-up"
run pulumi up --yes --parallel 1

signal "04-preview"
run pulumi preview --parallel 1

signal "05-refresh"
run pulumi refresh --yes --parallel 1

signal "06-stack-ls"
run pulumi stack ls

signal "07-stack-export"
run pulumi stack export

signal "08-destroy"
run pulumi destroy --yes --parallel 1

signal "09-stack-export-after-destroy"
run pulumi stack export

# Rebuild and tear down again, so the earlier versions end up behind two more update
# boundaries. `pulumi stack export --version N` has to reach back past both, and a stack
# that has been destroyed once is not the same starting point as a freshly created one —
# it already carries a secrets provider — so these have to be recorded here rather than
# borrowed from the session below.

signal "09a-up-after-destroy"
run pulumi up --yes --parallel 1

signal "09b-destroy-after-rebuild"
run pulumi destroy --yes --parallel 1

signal "10-stack-rm"
run pulumi stack rm dev --yes

# --- Additional `pulumi stack` subcommands (appended). The baseline removed the stack, so
# re-establish a populated stack before the read-oriented inspections, then tear it down
# again through rename/destroy/rm. ---

signal "11-stack-init"
run pulumi stack init dev

signal "12-up"
run pulumi up --yes --parallel 1

signal "13-stack-output"
run pulumi stack output --json --show-secrets

signal "14-stack-get"
try pulumi stack get

signal "15-stack-history"
run pulumi stack history --json

signal "16-stack-tag-set"
try pulumi stack tag set env recorder

signal "17-stack-tag-ls"
try pulumi stack tag ls --json

signal "18-stack-tag-get"
try pulumi stack tag get env

signal "19-stack-tag-rm"
try pulumi stack tag rm env

signal "20-stack-select"
run pulumi stack select dev

# `pulumi stack unselect` stopped issuing backend calls in newer CLIs (it now
# runs entirely against local workspace state), so there is nothing left to
# record for it.

signal "22-stack-graph"
run pulumi stack graph /tmp/graph.dot

# Experimental Pulumi Cloud features we do not support (expect errors).
signal "23-stack-drift"
try pulumi stack drift detect

signal "24-stack-schedule"
try pulumi stack schedule list

signal "25-stack-webhook"
try pulumi stack webhook list

signal "26-destroy"
run pulumi destroy --yes --parallel 1

# change-secrets-provider re-encrypts config, so it must run after the
# secret-bearing resources are gone.
signal "27-change-secrets-provider"
try pulumi stack change-secrets-provider passphrase

signal "28-stack-rename"
run pulumi stack rename staging

signal "29-stack-ls"
run pulumi stack ls

signal "30-stack-rm"
run pulumi stack rm staging --yes

signal "31-stack-init-for-cancel"
run pulumi stack init dev

# `pulumi cancel` needs an update already in flight, and no pulumi command leaves one
# behind — every update the CLI starts runs to completion. Seed it through the recorder's
# admin endpoint so the fabricated state is never recorded as CLI traffic.
seed_active_update "$STACK_REF"

signal "32-cancel"
run pulumi cancel --yes

signal "33-stack-rm-after-cancel"
run pulumi stack rm dev --yes

echo ""
echo "==> all commands completed"
echo "==> $(find "$FIXTURE_DIR/by-command" -name '*.json' 2>/dev/null | wc -l | tr -d ' ') fixtures written"

# Write meta so the catalog generator and fixture diffs know what concrete
# values — including which CLI version — produced this capture.
cat > "$FIXTURE_DIR/meta.json" <<EOF
{
  "org": "${ORG}",
  "project": "${PROJECT}",
  "stack": "${STACK}",
  "cli_version": "${CLI_VERSION}",
  "random_provider_version": "${STATEDB_RECORDER_RANDOM_PROVIDER_VERSION:-unknown}"
}
EOF

echo "==> generating catalog"
/recorder/catalog \
  -fixtures "$FIXTURE_DIR" \
  -out /snapshots/catalog.md \
  -org "$ORG" \
  -project "$PROJECT" \
  -stack "$STACK"
