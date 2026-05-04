#!/usr/bin/env bash
# Runs inside the Docker container.
# Starts the recorder server, drives pulumi through the full command sequence,
# then generates the catalog.
set -euo pipefail

PORT=8080
FIXTURE_DIR=${FIXTURE_DIR:-/fixtures}

export PULUMI_HOME=/tmp/pulumi-home
export PULUMI_ACCESS_TOKEN=test-token
export PULUMI_BACKEND_URL="http://localhost:${PORT}"
export PULUMI_CONFIG_PASSPHRASE=test
export PULUMI_SKIP_UPDATE_CHECK=true
export PULUMI_SKIP_CONFIRMATIONS=true

mkdir -p "$FIXTURE_DIR" "$PULUMI_HOME"

echo "==> starting recorder on :${PORT}"
/recorder/recorder -port "$PORT" -fixtures "$FIXTURE_DIR" &
RECORDER_PID=$!
trap 'kill "$RECORDER_PID" 2>/dev/null || true' EXIT

sleep 0.5

# Signal the recorder that a new command is starting.
# Fixtures for this command land in fixtures/by-command/{label}/.
signal() {
  curl -sf -X POST "http://localhost:${PORT}/meta/command" \
    -H "Content-Type: application/json" \
    -d "{\"command\": \"$1\"}" > /dev/null
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

cd /testproject

signal "01-login"
run pulumi login "http://localhost:${PORT}"

signal "02-stack-init"
run pulumi stack init dev

signal "03-preview"
run pulumi preview

signal "04-up"
run pulumi up --yes

signal "05-stack-ls"
run pulumi stack ls

signal "06-stack-export"
run pulumi stack export

signal "07-destroy"
run pulumi destroy --yes

signal "08-stack-rm"
run pulumi stack rm dev --yes

echo ""
echo "==> all commands completed"
echo "==> $(find "$FIXTURE_DIR/by-command" -name '*.json' 2>/dev/null | wc -l | tr -d ' ') fixtures written"

# Write meta so the catalog generator knows what concrete values were used.
cat > "$FIXTURE_DIR/meta.json" <<EOF
{
  "org": "testorg",
  "project": "recorder-test",
  "stack": "dev",
  "recorded_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF

echo "==> generating catalog"
/recorder/catalog \
  -fixtures "$FIXTURE_DIR" \
  -out /snapshots/catalog.md \
  -org testorg \
  -project recorder-test \
  -stack dev
