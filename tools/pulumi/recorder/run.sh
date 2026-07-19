#!/usr/bin/env bash
# Build the recorder image and run a capture session.
# Fixtures land in ./fixtures/ on the host.
#
# Usage:
#   ./run.sh              # full run: build + capture
#   ./run.sh --no-build   # skip docker build (use cached image)
#
# Environment overrides (all optional; defaults preserve the committed-baseline
# behavior):
#   PULUMI_VERSION   CLI version to capture with (default: the ARG pin in the
#                    Dockerfile). Non-default versions build/tag their own image
#                    (pulumi-recorder:<version>) so cached layers coexist.
#   FIXTURES_DIR     where fixtures are written (default: ./fixtures)
#   SNAPSHOTS_DIR    where catalog.md is written (default: api-snapshots/pulumi)
#
# Trial captures against a candidate Pulumi version should redirect
# FIXTURES_DIR and SNAPSHOTS_DIR so the committed fixture set and catalog stay
# untouched (see tools/pulumi/compat-sweep.sh).
set -euo pipefail

cd "$(dirname "$0")"

DEFAULT_PULUMI_VERSION="$(sed -n 's/^ARG PULUMI_VERSION=//p' Dockerfile)"
PULUMI_VERSION="${PULUMI_VERSION:-$DEFAULT_PULUMI_VERSION}"
IMAGE="pulumi-recorder:${PULUMI_VERSION}"
FIXTURES_DIR="${FIXTURES_DIR:-$(pwd)/fixtures}"
BUILD=1

for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=0 ;;
    *) echo "unknown arg: $arg"; exit 1 ;;
  esac
done

DEFAULT_SNAPSHOTS_DIR="$(cd "$(dirname "$0")/../../../api-snapshots/pulumi" && pwd)"
SNAPSHOTS_DIR="${SNAPSHOTS_DIR:-$DEFAULT_SNAPSHOTS_DIR}"

mkdir -p "$FIXTURES_DIR" "$SNAPSHOTS_DIR"

if [[ "$BUILD" -eq 1 ]]; then
  echo "==> building $IMAGE"
  docker build -t "$IMAGE" --build-arg "PULUMI_VERSION=${PULUMI_VERSION}" .
fi

echo "==> cleaning previous capture"
rm -rf "$FIXTURES_DIR/by-command" "$FIXTURES_DIR/meta.json"
mkdir -p "$FIXTURES_DIR"

echo "==> running capture  (pulumi ${PULUMI_VERSION})"
echo "==>                  (fixtures → $FIXTURES_DIR)"
echo "==>                  (catalog  → $SNAPSHOTS_DIR/catalog.md)"
docker run --rm \
  -e FIXTURE_DIR=/fixtures \
  -v "${FIXTURES_DIR}:/fixtures" \
  -v "${SNAPSHOTS_DIR}:/snapshots" \
  "$IMAGE"
