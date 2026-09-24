#!/usr/bin/env bash
# Continuous Pulumi churn against statedb.
#
# Not `set -e`: injected faults and lock contention are supposed to fail. Exit codes are recorded
# and report.sh separates expected failures from real ones.
set -uo pipefail

: "${BACKEND_URL:?}" "${PULUMI_ACCESS_TOKEN:?}" "${ORG:?}"

APP_URL="${APP_URL:-http://app:8080}"
ARTIFACTS="${ARTIFACTS:-/soak/artifacts}"
WORK="${WORK:-/soak/work}"
OPS_LOG="$ARTIFACTS/ops.tsv"

STACKS="${SOAK_STACKS:-4}"
RESOURCES="${SOAK_RESOURCES:-300}"
PARALLEL="${SOAK_PARALLEL:-8}"
PAYLOAD_BYTES="${SOAK_PAYLOAD_BYTES:-2048}"
CREATE_DELAY_MS="${SOAK_CREATE_DELAY_MS:-40}"
CREATE_JITTER_MS="${SOAK_CREATE_JITTER_MS:-80}"
DURATION_HOURS="${SOAK_DURATION_HOURS:-48}"

# `burst` models N CI jobs landing at once instead of cycle's steady one-at-a-time churn.
MODE="${SOAK_MODE:-cycle}"
BURST_STACKS="${SOAK_BURST_STACKS:-$STACKS}"
BURST_ROUNDS="${SOAK_BURST_ROUNDS:-0}"
BURST_LOG="$ARTIFACTS/burst.tsv"

# Cadences in iterations, kept coprime-ish so the special operations do not always coincide.
DESTROY_EVERY="${SOAK_DESTROY_EVERY:-5}"
FAULT_EVERY="${SOAK_FAULT_EVERY:-7}"
CONTENTION_EVERY="${SOAK_CONTENTION_EVERY:-3}"
PREVIEW_EVERY="${SOAK_PREVIEW_EVERY:-2}"
CONSOLE_READS="${SOAK_CONSOLE_READS:-1}"

export PULUMI_ACCESS_TOKEN
export PULUMI_CONFIG_PASSPHRASE="${PULUMI_CONFIG_PASSPHRASE:-soak}"
export PULUMI_HOME="${PULUMI_HOME:-/soak/.pulumi-home}"
export PULUMI_SKIP_UPDATE_CHECK=true
export PULUMI_SKIP_CONFIRMATIONS=true

mkdir -p "$ARTIFACTS" "$WORK"
START_EPOCH="$(date +%s)"
ITERATION=0

if [[ ! -s "$OPS_LOG" ]]; then
  printf 'ts\telapsed_s\titeration\tstack\top\texit_code\tduration_s\texpected_failure\n' >"$OPS_LOG"
fi
if [[ ! -s "$BURST_LOG" ]]; then
  printf 'ts\telapsed_s\tround\tstack\texit_code\tduration_s\thttp_status\terror\n' >"$BURST_LOG"
fi

log() { echo "[drive $(date -u +%H:%M:%S)] $*"; }

# Consecutive-failure backoff. A stack whose state has become unusable rejects every operation
# instantly, which without this turns the loop into a hot spin: thousands of junk updates per hour,
# a distorted series, and CPU spent on nothing. Backoff keeps a wedged stack from drowning the run
# while still retrying, since the lock lease does eventually expire.
declare -A CONSECUTIVE_FAILURES=()

backoff_for() {
  local stack="$1"
  local failures="${CONSECUTIVE_FAILURES[$stack]:-0}"
  (( failures < 3 )) && return
  local delay=$(( failures * 10 ))
  (( delay > 300 )) && delay=300
  log "$stack has failed ${failures}x consecutively; backing off ${delay}s"
  sleep "$delay"
}

stack_name() { printf 'soak-%02d' "$1"; }

# `expected_failure=1` marks operations whose non-zero exit is the point of the exercise.
run_op() {
  local stack="$1" op="$2" expected="$3" dir="$4"
  shift 4
  local began ended rc
  began="$(date +%s)"
  ( cd "$dir" && "$@" ) >>"$ARTIFACTS/pulumi.log" 2>&1
  rc=$?
  ended="$(date +%s)"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$ended" "$((ended - START_EPOCH))" "$ITERATION" "$stack" "$op" "$rc" "$((ended - began))" "$expected" \
    >>"$OPS_LOG"
  # Contention losers are supposed to fail and say nothing about the stack's health, so they do
  # not feed the backoff counter.
  if [[ "$expected" != "1" ]]; then
    if (( rc == 0 )); then
      CONSECUTIVE_FAILURES["$stack"]=0
    else
      CONSECUTIVE_FAILURES["$stack"]=$(( ${CONSECUTIVE_FAILURES[$stack]:-0} + 1 ))
    fi
  fi
  return "$rc"
}

# Writes the generated program for one stack.
generate() {
  local dir="$1" revision="$2" fail_index="${3:--1}" fail_mode="${4:-none}" fail_operation="${5:-update}"
  mkdir -p "$dir"
  SCALE_OUT="$dir" \
  SCALE_RESOURCES="$RESOURCES" \
  SCALE_PAYLOAD_BYTES="$PAYLOAD_BYTES" \
  SCALE_REVISION="$revision" \
  SCALE_CREATE_DELAY_MS="$CREATE_DELAY_MS" \
  SCALE_CREATE_JITTER_MS="$CREATE_JITTER_MS" \
  SCALE_FAIL_INDEX="$fail_index" \
  SCALE_FAIL_MODE="$fail_mode" \
  SCALE_FAIL_OPERATION="$fail_operation" \
    /soak/generate.sh >/dev/null
}

select_stack() {
  local dir="$1" ref="$2"
  ( cd "$dir" && pulumi stack select "$ref" >/dev/null 2>&1 ) ||
    ( cd "$dir" && pulumi stack init "$ref" >/dev/null 2>&1 )
}

burst_seed() {
  log "burst: seeding $BURST_STACKS stacks x $RESOURCES resources"
  for (( i = 1; i <= BURST_STACKS; i++ )); do
    stack="$(stack_name "$i")"
    dir="$WORK/$stack"
    generate "$dir" 0
    select_stack "$dir" "$ORG/$stack"
    run_op "$stack" seed 0 "$dir" \
      pulumi up --yes --skip-preview --parallel "$PARALLEL" --message "soak burst seed"
  done
}

# One log per run so concurrent CLIs never interleave.
burst_run_one() {
  local stack="$1" dir="$2" round="$3"
  local out="$ARTIFACTS/burst-${stack}-round${round}.log"
  local began ended rc errline http_status
  began="$(date +%s)"
  ( cd "$dir" && pulumi up --refresh --yes --skip-preview --parallel "$PARALLEL" \
      --message "soak burst round=$round" ) >"$out" 2>&1
  rc=$?
  ended="$(date +%s)"
  errline="$(grep -m1 -Ei 'error:|code = [A-Za-z]+ desc =|Bad Gateway|Gateway Timeout|Service Unavailable|Too Many Requests' "$out" \
    | head -c 300 | tr '\t\n' '  ')"
  # Anchored to a status phrase so line numbers like `Pulumi.yaml line 429` don't match.
  http_status="$(grep -m1 -Eio 'HTTP(/[0-9.]+)? (error )?(429|499|502|503|504)\b|status ?code[: ]+(429|499|502|503|504)\b|\b(429|499|502|503|504)\b[[:space:]]+(Too Many Requests|Client Closed Request|Bad Gateway|Service Unavailable|Gateway Timeout)' "$out" \
    | grep -Eo '429|499|502|503|504' | head -1)"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$ended" "$((ended - START_EPOCH))" "$round" "$stack" "$rc" "$((ended - began))" \
    "${http_status:-}" "$errline" \
    >>"$BURST_LOG"
  return "$rc"
}

burst_loop() {
  burst_seed
  local round=0 elapsed_h
  while true; do
    round=$((round + 1))
    elapsed_h=$(( ($(date +%s) - START_EPOCH) / 3600 ))
    if (( DURATION_HOURS > 0 )) && (( elapsed_h >= DURATION_HOURS )); then
      log "burst: duration reached (${elapsed_h}h); stopping"
      break
    fi
    if (( BURST_ROUNDS > 0 )) && (( round > BURST_ROUNDS )); then
      log "burst: completed $BURST_ROUNDS round(s); stopping"
      break
    fi

    log "burst round $round: firing $BURST_STACKS concurrent 'pulumi up --refresh' (elapsed ${elapsed_h}h)"
    local pids=()
    for (( i = 1; i <= BURST_STACKS; i++ )); do
      stack="$(stack_name "$i")"
      dir="$WORK/$stack"
      # A new revision gives every resource a diff, so the round exercises the write path.
      generate "$dir" "$round"
      burst_run_one "$stack" "$dir" "$round" &
      pids+=($!)
    done
    local failed=0
    for pid in "${pids[@]}"; do
      wait "$pid" || failed=$((failed + 1))
    done
    log "burst round $round: $failed/$BURST_STACKS run(s) failed"
  done
}

# Console reads hit different tables and query shapes than the write path, and concurrent reads
# are what turn a slow connection leak into visible pool starvation.
console_reads() {
  local jar="$ARTIFACTS/console-cookies.txt"
  while true; do
    curl -fsS -c "$jar" -o /dev/null \
      -d "username=soak" -d "password=${GLASS_PASSWORD:-password}" \
      "$APP_URL/api/auth/local" 2>/dev/null
    for _ in $(seq 1 60); do
      for path in /api/user /api/workspaces /api/admin/locks /api/admin/compaction-jobs; do
        curl -fsS -b "$jar" -o /dev/null --max-time 10 "$APP_URL$path" 2>/dev/null
      done
      local s
      s="$(stack_name $(( (RANDOM % STACKS) + 1 )))"
      curl -fsS -b "$jar" -o /dev/null --max-time 10 \
        "$APP_URL/api/pulumi/stacks/$ORG/scale-simulator/$s" 2>/dev/null
      curl -fsS -b "$jar" -o /dev/null --max-time 10 \
        "$APP_URL/api/pulumi/stacks/$ORG/scale-simulator/$s/updates" 2>/dev/null
      sleep 5
    done
    # Re-login periodically: the form-auth cookie has a finite timeout and a soak outlives it.
  done
}

log "waiting for $BACKEND_URL"
until curl -fsS --max-time 5 -H "Authorization: token $PULUMI_ACCESS_TOKEN" \
    "$BACKEND_URL/api/capabilities" >/dev/null 2>&1; do
  sleep 3
done

pulumi login "$BACKEND_URL" >/dev/null 2>&1 || { log "pulumi login failed"; exit 1; }

if [[ "$CONSOLE_READS" == "1" ]]; then
  console_reads &
  log "console read traffic started (pid $!)"
fi

if [[ "$MODE" == "burst" ]]; then
  log "logged in; burst mode, $BURST_STACKS stacks x $RESOURCES resources"
  burst_loop
  log "burst load finished"
  date +%s >"$ARTIFACTS/load-finished"
  sleep infinity
fi

log "logged in; churning $STACKS stacks x $RESOURCES resources"

while true; do
  ITERATION=$((ITERATION + 1))
  elapsed_h=$(( ($(date +%s) - START_EPOCH) / 3600 ))
  if (( DURATION_HOURS > 0 )) && (( elapsed_h >= DURATION_HOURS )); then
    log "duration reached (${elapsed_h}h); stopping"
    break
  fi

  log "iteration $ITERATION (elapsed ${elapsed_h}h)"

  for (( i = 1; i <= STACKS; i++ )); do
    stack="$(stack_name "$i")"
    ref="$ORG/$stack"
    dir="$WORK/$stack"

    backoff_for "$stack"
    generate "$dir" "$ITERATION"
    select_stack "$dir" "$ref"

    if (( ITERATION % PREVIEW_EVERY == 0 )); then
      run_op "$stack" preview 0 "$dir" \
        pulumi preview --message "soak preview i=$ITERATION"
    fi

    run_op "$stack" up 0 "$dir" \
      pulumi up --yes --skip-preview --parallel "$PARALLEL" \
        --message "soak up i=$ITERATION"

    # Exercises the delete path and the resource-count collapse after it.
    if (( ITERATION % DESTROY_EVERY == 0 )) && (( i == 1 )); then
      run_op "$stack" destroy 0 "$dir" \
        pulumi destroy --yes --skip-preview --message "soak destroy i=$ITERATION"
    fi
  done

  # A deterministic mid-graph failure forces the server through failed-update completion. The
  # fault must name the operation the node will actually see. Normally everything already exists
  # and only `update` fires, but when this iteration's destroy also landed on this stack — every
  # DESTROY_EVERY * FAULT_EVERY iterations — the stack is empty and only `create` fires. Naming
  # the wrong one lets the up succeed, silently skipping the fault.
  if (( ITERATION % FAULT_EVERY == 0 )); then
    stack="$(stack_name 1)"
    dir="$WORK/$stack"
    fault_operation=update
    if (( ITERATION % DESTROY_EVERY == 0 )); then
      fault_operation=create
    fi
    generate "$dir" "$ITERATION" $(( RESOURCES / 2 )) always "$fault_operation"
    select_stack "$dir" "$ORG/$stack"
    run_op "$stack" fault-up 1 "$dir" \
      pulumi up --yes --skip-preview --parallel "$PARALLEL" \
        --message "soak fault i=$ITERATION"
    generate "$dir" "$ITERATION"
  fi

  # Two updates racing one stack's lock: exactly one should win, and the loser must not strand it.
  if (( ITERATION % CONTENTION_EVERY == 0 )); then
    stack="$(stack_name 2)"
    dir="$WORK/$stack"
    contender="$WORK/$stack-contender"
    generate "$contender" "$ITERATION"
    select_stack "$contender" "$ORG/$stack"
    run_op "$stack" contention-a 1 "$dir" \
      pulumi up --yes --skip-preview --parallel "$PARALLEL" --message "soak contend-a i=$ITERATION" &
    pid_a=$!
    run_op "$stack" contention-b 1 "$contender" \
      pulumi up --yes --skip-preview --parallel "$PARALLEL" --message "soak contend-b i=$ITERATION" &
    pid_b=$!
    wait "$pid_a" "$pid_b" 2>/dev/null
  fi
done

log "load finished after $ITERATION iterations"
# Marks the quiesce window; report.sh keys off this rather than guessing from the series.
date +%s >"$ARTIFACTS/load-finished"
# Stay alive so the sampler records the quiesce window; the lock checks need load stopped.
sleep infinity
