#!/usr/bin/env bash
# Pulumi compatibility sweep — one command that answers "has Pulumi changed
# anything that affects state-db since the version we're pinned to?"
#
# Chains the three existing tools:
#   1. Pin audit        — recorder Dockerfile pin vs verify go.mod vs nightly.yml
#   2. api-extract      — source-level endpoint diff, baseline → target
#   3. recorder         — trial capture with the target CLI into a scratch dir,
#                         then a noise-masked diff against the committed fixtures
#                         and catalog
#
# By default everything a sweep produces — endpoint snapshots, comparison,
# trial capture, diffs, and the report — stays in the gitignored scratch dir
# (.sweep/<target>/), so a routine "just checking" run leaves `git status`
# clean. Pass --publish when adopting the target version to copy the snapshot,
# comparison, and report into api-snapshots/pulumi/ as the committed record of
# the review.
#
# Usage:
#   ./sweep.sh                      # target = latest Pulumi release
#   ./sweep.sh --to v3.253.0        # explicit target
#   ./sweep.sh --to v3.242.0        # target == baseline: reproducibility check
#   ./sweep.sh --skip-record        # pins + endpoint diff only (no Docker)
#   ./sweep.sh --no-build           # reuse cached recorder image for the target
#   ./sweep.sh --to vX --publish    # also copy snapshot/comparison/report into
#                                   # api-snapshots/pulumi/ (adoption record)
#
# Like the recorder and verifier, this is a host tool (Go + Docker + python3);
# it is deliberately not routed through ./bin/in-container.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
RECORDER_DIR="$ROOT/tools/pulumi/recorder"
VERIFY_DIR="$ROOT/tools/pulumi/verify"
SNAPSHOTS_DIR="$ROOT/api-snapshots/pulumi"
NIGHTLY_YML="$ROOT/.github/workflows/nightly.yml"

TO=""
SKIP_RECORD=0
NO_BUILD=0
PUBLISH=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --to) TO="$2"; shift 2 ;;
    --skip-record) SKIP_RECORD=1; shift ;;
    --no-build) NO_BUILD=1; shift ;;
    --publish) PUBLISH=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

# --- resolve baseline + target -----------------------------------------------

BASELINE_BARE="$(sed -n 's/^ARG PULUMI_VERSION=//p' "$RECORDER_DIR/Dockerfile")"
BASELINE="v${BASELINE_BARE}"

if [[ -z "$TO" ]]; then
  echo "==> resolving latest Pulumi release"
  TO="$(curl -fsSL https://api.github.com/repos/pulumi/pulumi/releases/latest \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"])')"
fi
TO_BARE="${TO#v}"

WORKDIR="$HERE/.sweep/$TO"
EXTRACT_DIR="$WORKDIR/api"
REPORT="$WORKDIR/sweep-$BASELINE-to-$TO.md"
mkdir -p "$WORKDIR" "$EXTRACT_DIR"

echo "==> sweep: baseline $BASELINE → target $TO"

# --- 1. pin audit ------------------------------------------------------------

VERIFY_SDK="$(sed -n 's|^require github.com/pulumi/pulumi/sdk/v3 v||p' "$VERIFY_DIR/go.mod" | tr -d ' ')"
NIGHTLY_VERSIONS="$(grep -o 'pulumi/pulumi:[0-9][0-9.]*' "$NIGHTLY_YML" | cut -d: -f2 | sort -u | paste -sd ', ' -)"

pin_status() { [[ "$1" == "$2" ]] && echo "OK" || echo "**MISMATCH**"; }
VERIFY_STATUS="$(pin_status "$VERIFY_SDK" "$BASELINE_BARE")"
if grep -q "pulumi/pulumi:$BASELINE_BARE" "$NIGHTLY_YML"; then
  NIGHTLY_STATUS="OK (baseline in matrix)"
else
  NIGHTLY_STATUS="**MISMATCH** (baseline $BASELINE_BARE not in matrix)"
fi
if [[ "$TO" != "$BASELINE" ]] && ! grep -q "pulumi/pulumi:$TO_BARE" "$NIGHTLY_YML"; then
  NIGHTLY_STATUS="$NIGHTLY_STATUS; target $TO_BARE not yet in matrix"
fi

# Committed snapshot dirs for versions that are neither the baseline nor the
# target are regenerable (pinned refs) and safe to delete on adoption.
PRUNABLE="$(cd "$SNAPSHOTS_DIR" && ls -d v3.* 2>/dev/null \
  | grep -v -e "^$BASELINE\$" -e "^$TO\$" | paste -sd ', ' - || true)"

# --- 2. endpoint diff (api-extract) ------------------------------------------

# Snapshots and the comparison are produced in the scratch extract dir; the
# committed api-snapshots tree is only written by --publish.
COMPARE_FILE="$EXTRACT_DIR/comparisons/compare-$BASELINE-$TO.md"
if [[ "$TO" == "$BASELINE" ]]; then
  echo "==> target equals baseline — skipping endpoint diff (reproducibility check mode)"
  COMPARE_FILE=""
else
  if [[ ! -d "$EXTRACT_DIR/$BASELINE" ]]; then
    if [[ -d "$SNAPSHOTS_DIR/$BASELINE" ]]; then
      cp -R "$SNAPSHOTS_DIR/$BASELINE" "$EXTRACT_DIR/$BASELINE"
    else
      echo "==> extracting baseline snapshot $BASELINE (not in api-snapshots)"
      make -C "$ROOT" pulumi-extract "PULUMI_REF=$BASELINE" "PULUMI_SNAPSHOTS_DIR=$EXTRACT_DIR"
    fi
  fi
  if [[ ! -d "$EXTRACT_DIR/$TO" ]]; then
    echo "==> extracting $TO"
    make -C "$ROOT" pulumi-extract "PULUMI_REF=$TO" "PULUMI_SNAPSHOTS_DIR=$EXTRACT_DIR"
  fi
  echo "==> comparing $BASELINE → $TO"
  make -C "$ROOT" pulumi-compare "FROM=$BASELINE" "TO=$TO" "PULUMI_SNAPSHOTS_DIR=$EXTRACT_DIR"
fi

# --- 3. trial capture + fixture/catalog diff ---------------------------------

CAPTURE_STATUS="skipped (--skip-record)"
FIXTURE_SUMMARY=""
CATALOG_DIFF_FILE=""
FIXTURE_DIFF_FILE=""
if [[ "$SKIP_RECORD" -eq 0 ]]; then
  echo "==> trial capture with pulumi $TO_BARE (fixtures → $WORKDIR)"
  RUN_ARGS=()
  [[ "$NO_BUILD" -eq 1 ]] && RUN_ARGS+=(--no-build)
  CAPTURE_LOG="$WORKDIR/capture.log"
  if PULUMI_VERSION="$TO_BARE" \
     FIXTURES_DIR="$WORKDIR/fixtures" \
     SNAPSHOTS_DIR="$WORKDIR/snapshots" \
     "$RECORDER_DIR/run.sh" ${RUN_ARGS[@]+"${RUN_ARGS[@]}"} 2>&1 | tee "$CAPTURE_LOG"; then
    CAPTURE_STATUS="completed"
  else
    LAST_CMD="$(grep -o '^--- .*---$' "$CAPTURE_LOG" | tail -1 || true)"
    CAPTURE_STATUS="**FAILED** during \`${LAST_CMD:-unknown command}\` — stub gap or protocol change; see capture.log. Fixture diff below covers the partial capture."
  fi

  FIXTURE_DIFF_FILE="$WORKDIR/fixtures-diff.md"
  FIXTURE_SUMMARY="$(python3 "$HERE/fixture_diff.py" \
    "$RECORDER_DIR/fixtures" "$WORKDIR/fixtures" --out "$FIXTURE_DIFF_FILE" || true)"
  # Keep absolute scratch paths out of the (potentially committed) report.
  FIXTURE_SUMMARY="${FIXTURE_SUMMARY%% → *}"

  if [[ -f "$WORKDIR/snapshots/catalog.md" ]]; then
    CATALOG_DIFF_FILE="$WORKDIR/catalog.diff"
    diff -u "$SNAPSHOTS_DIR/catalog.md" "$WORKDIR/snapshots/catalog.md" \
      > "$CATALOG_DIFF_FILE" || true
  fi
fi

# --- 4. report ---------------------------------------------------------------

{
  echo "# Pulumi compat sweep: $BASELINE → $TO"
  echo
  echo "Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  echo "## Pin audit"
  echo
  echo "| Pin | Value | Status |"
  echo "|---|---|---|"
  echo "| recorder CLI (\`tools/pulumi/recorder/Dockerfile\`) | $BASELINE_BARE | baseline |"
  echo "| verify SDK (\`tools/pulumi/verify/go.mod\`) | $VERIFY_SDK | $VERIFY_STATUS |"
  echo "| nightly matrix (\`.github/workflows/nightly.yml\`) | $NIGHTLY_VERSIONS | $NIGHTLY_STATUS |"
  echo
  if [[ -n "$PRUNABLE" ]]; then
    echo "Prunable committed snapshots (neither baseline nor target; regenerable from pinned refs): $PRUNABLE"
    echo
  fi
  echo "## Endpoint diff (source-level, scoped)"
  echo
  if [[ -n "$COMPARE_FILE" && -f "$COMPARE_FILE" ]]; then
    # Embed only the summary + added/removed sections; the full endpoint
    # inventory stays in the comparison file itself.
    awk '/^## Endpoints by category/{exit} {print}' "$COMPARE_FILE"
    echo
    echo "Full inventory: \`$(basename "$COMPARE_FILE")\` (published next to this report; otherwise in the sweep scratch dir)."
  else
    echo "_Skipped (target equals baseline)._"
  fi
  echo
  echo "## Trial capture (recorder, pulumi $TO_BARE)"
  echo
  echo "Status: $CAPTURE_STATUS"
  echo
  if [[ -n "$FIXTURE_SUMMARY" ]]; then
    echo "$FIXTURE_SUMMARY"
    echo
    echo "Full masked diff: \`tools/pulumi/compat/.sweep/$TO/fixtures-diff.md\`"
    echo
  fi
  if [[ -n "$CATALOG_DIFF_FILE" ]]; then
    if [[ -s "$CATALOG_DIFF_FILE" ]]; then
      echo "### Catalog diff"
      echo
      echo '```diff'
      head -200 "$CATALOG_DIFF_FILE"
      echo '```'
    else
      echo "Catalog: unchanged."
    fi
    echo
  fi
  echo "## Follow-ups when adopting $TO"
  echo
  echo "1. Review the diffs above; adjust stubs/state-db if the protocol moved."
  echo "2. Bump \`ARG PULUMI_VERSION\` in \`tools/pulumi/recorder/Dockerfile\`, re-run \`make pulumi-recorder-discover\`, review + commit fixtures/catalog."
  echo "3. Bump \`github.com/pulumi/pulumi/sdk/v3\` in \`tools/pulumi/verify/go.mod\` to $TO (lockstep rule)."
  echo "4. Update the nightly matrix in \`.github/workflows/nightly.yml\` (keep oldest-supported + new pin)."
  echo "5. Run the replay/smoke/fault tiers before committing pin bumps."
  echo "6. Delete superseded snapshot dirs under \`api-snapshots/pulumi/\` (see pin audit)."
} > "$REPORT"

# --- 5. publish (adoption record) --------------------------------------------

if [[ "$PUBLISH" -eq 1 ]]; then
  echo "==> publishing adoption record into $SNAPSHOTS_DIR"
  mkdir -p "$SNAPSHOTS_DIR/sweeps"
  cp "$REPORT" "$SNAPSHOTS_DIR/sweeps/"
  if [[ "$TO" != "$BASELINE" ]]; then
    [[ -d "$SNAPSHOTS_DIR/$BASELINE" ]] || cp -R "$EXTRACT_DIR/$BASELINE" "$SNAPSHOTS_DIR/$BASELINE"
    rm -rf "$SNAPSHOTS_DIR/$TO"
    cp -R "$EXTRACT_DIR/$TO" "$SNAPSHOTS_DIR/$TO"
    mkdir -p "$SNAPSHOTS_DIR/comparisons"
    cp "$COMPARE_FILE" "$SNAPSHOTS_DIR/comparisons/"
  fi
fi

echo
echo "==> report: $REPORT"
if [[ "$PUBLISH" -eq 1 ]]; then
  echo "==> published: $SNAPSHOTS_DIR/sweeps/$(basename "$REPORT")"
fi
if [[ -n "$FIXTURE_DIFF_FILE" ]]; then
  echo "==> fixture diff: $FIXTURE_DIFF_FILE"
fi
