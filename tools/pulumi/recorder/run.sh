#!/usr/bin/env bash
# Build the recorder image and run a capture session.
# Fixtures land in ./fixtures/ on the host.
#
# Usage:
#   ./run.sh              # full run: build + capture
#   ./run.sh --no-build   # skip docker build (use cached image)
set -euo pipefail

cd "$(dirname "$0")"

IMAGE=pulumi-recorder
FIXTURES_DIR="$(pwd)/fixtures"
BUILD=1

for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=0 ;;
    *) echo "unknown arg: $arg"; exit 1 ;;
  esac
done

SNAPSHOTS_DIR="$(cd "$(dirname "$0")/../../../api-snapshots/pulumi" && pwd)"

mkdir -p "$FIXTURES_DIR" "$SNAPSHOTS_DIR"

if [[ "$BUILD" -eq 1 ]]; then
  echo "==> building $IMAGE"
  docker build -t "$IMAGE" .
fi

echo "==> running capture  (fixtures → $FIXTURES_DIR)"
echo "==>                  (catalog  → $SNAPSHOTS_DIR/catalog.md)"
docker run --rm \
  -e FIXTURE_DIR=/fixtures \
  -v "${FIXTURES_DIR}:/fixtures" \
  -v "${SNAPSHOTS_DIR}:/snapshots" \
  "$IMAGE"
