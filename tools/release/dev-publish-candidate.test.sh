#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

readonly COMMIT=0123456789abcdef0123456789abcdef01234567
readonly SHORT_COMMIT=0123456
readonly DIGEST=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
mkdir -p "$fixture/bin" "$fixture/fakebin"
cp dev "$fixture/dev"

cat >"$fixture/bin/build-native-app-image" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'build' >>"$TEST_LOG"
printf ' <%s>' "$@" >>"$TEST_LOG"
printf '\n' >>"$TEST_LOG"
[[ ${TEST_FAIL:-} != build ]]
EOF
cat >"$fixture/bin/release-gate" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'gate' >>"$TEST_LOG"
printf ' <%s>' "$@" >>"$TEST_LOG"
printf '\n' >>"$TEST_LOG"
[[ ${TEST_FAIL:-} != gate ]]
EOF
cat >"$fixture/fakebin/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "$1 ${2:-} ${3:-}" in
'status --porcelain --untracked-files=no') [[ ${TEST_DIRTY:-false} != true ]] || echo ' M tracked' ;;
'rev-parse HEAD '|'rev-parse HEAD') echo "${TEST_COMMIT:-0123456789abcdef0123456789abcdef01234567}" ;;
'rev-parse --short=7 HEAD') echo "${TEST_SHORT_COMMIT:-0123456}" ;;
*) echo "unexpected git command: $*" >&2; exit 97 ;;
esac
EOF
cat >"$fixture/fakebin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'docker' >>"$TEST_LOG"
printf ' <%s>' "$@" >>"$TEST_LOG"
printf '\n' >>"$TEST_LOG"
case "$1 ${2:-} ${3:-}" in
'version --format {{.Server.Arch}}') echo "${TEST_ARCH-amd64}" ;;
'push '*) [[ ${TEST_FAIL:-} != push ]] ;;
'buildx imagetools inspect') printf '%s\n' "${TEST_DIGEST:-\"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}" ;;
*) : ;;
esac
EOF
chmod +x "$fixture/dev" "$fixture/bin/build-native-app-image" "$fixture/bin/release-gate" \
  "$fixture/fakebin/git" "$fixture/fakebin/docker"

run_candidate() {
  TEST_LOG=$fixture/commands PATH="$fixture/fakebin:/usr/bin:/bin" \
    "$fixture/dev" publish-candidate "$@"
}

assert_contains() {
  local file=$1 expected=$2
  grep -F -- "$expected" "$file" >/dev/null || {
    echo "dev-publish-candidate.test: expected '$expected' in $file" >&2
    sed -n '1,160p' "$file" >&2
    exit 1
  }
}

: >"$fixture/commands"
output=$(STATEDB_REGISTRY=registry.example/team/state-db run_candidate)
candidate="registry.example/team/state-db-candidates:$COMMIT-amd64"
immutable="registry.example/team/state-db-candidates@$DIGEST"
assert_contains "$fixture/commands" "build <state-db:native-ci>"
assert_contains "$fixture/commands" "docker <tag> <state-db:native-ci> <$candidate>"
assert_contains "$fixture/commands" "docker <push> <$candidate>"
assert_contains "$fixture/commands" "docker <pull> <$immutable>"
assert_contains "$fixture/commands" "gate <$immutable> <--expect-commit> <$SHORT_COMMIT>"
pull_line=$(grep -nF "docker <pull> <$immutable>" "$fixture/commands" | cut -d: -f1)
gate_line=$(grep -nF "gate <$immutable>" "$fixture/commands" | cut -d: -f1)
[[ $pull_line -lt $gate_line ]] || {
  echo "dev-publish-candidate.test: registry pull did not precede the release gate" >&2
  exit 1
}
grep -F "published $candidate" <<<"$output" >/dev/null
grep -F "digest    $immutable" <<<"$output" >/dev/null

: >"$fixture/commands"
override=registry.example/explicit/candidates
STATEDB_REGISTRY=ignored.example/state-db STATEDB_CANDIDATE_REGISTRY=$override run_candidate >/dev/null
assert_contains "$fixture/commands" "docker <push> <$override:$COMMIT-amd64>"

: >"$fixture/commands"
if TEST_DIRTY=true run_candidate >"$fixture/out" 2>"$fixture/err"; then
  echo "dev-publish-candidate.test: dirty tracked tree was accepted" >&2
  exit 1
fi
assert_contains "$fixture/err" "tracked tree has uncommitted changes"
[[ ! -s $fixture/commands ]] || { echo "dev-publish-candidate.test: dirty tree ran Docker" >&2; exit 1; }

for invalid_arch in x86_64 ''; do
  : >"$fixture/commands"
  if TEST_ARCH=$invalid_arch run_candidate >"$fixture/out" 2>"$fixture/err"; then
    echo "dev-publish-candidate.test: invalid architecture '$invalid_arch' was accepted" >&2
    exit 1
  fi
  assert_contains "$fixture/err" "unsupported Docker server architecture"
  ! grep -F 'build' "$fixture/commands" >/dev/null || exit 1
done

: >"$fixture/commands"
if TEST_DIGEST='"sha256:not-a-digest"' run_candidate >"$fixture/out" 2>"$fixture/err"; then
  echo "dev-publish-candidate.test: invalid registry digest was accepted" >&2
  exit 1
fi
assert_contains "$fixture/err" "invalid candidate digest"
! grep -F 'gate' "$fixture/commands" >/dev/null || exit 1

: >"$fixture/commands"
if TEST_FAIL=push run_candidate >"$fixture/out" 2>"$fixture/err"; then
  echo "dev-publish-candidate.test: Docker push failure was swallowed" >&2
  exit 1
fi
! grep -F 'imagetools' "$fixture/commands" >/dev/null || exit 1

: >"$fixture/commands"
if TEST_FAIL=gate run_candidate >"$fixture/out" 2>"$fixture/err"; then
  echo "dev-publish-candidate.test: release gate failure was swallowed" >&2
  exit 1
fi
assert_contains "$fixture/commands" \
  "docker <pull> <ghcr.io/state-db/statedb-candidates@$DIGEST>"

if run_candidate unexpected >"$fixture/out" 2>"$fixture/err"; then
  echo "dev-publish-candidate.test: unexpected public arguments were accepted" >&2
  exit 1
fi
assert_contains "$fixture/err" "publish-candidate takes no arguments"

echo "dev publish-candidate checks passed"
