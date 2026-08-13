#!/usr/bin/env bash
# Grade a soak run. Runs outside compose because restart count and OOM kill come from
# `docker inspect`. Exits non-zero if any check fails.
set -uo pipefail

ARTIFACTS_DIR="${ARTIFACTS_DIR:-artifacts}"
APP_CONTAINER="${APP_CONTAINER:-state-db-soak-app}"
SERIES="$ARTIFACTS_DIR/series.tsv"
OPS="$ARTIFACTS_DIR/ops.tsv"

# Trends, not absolutes: a flat high footprint is healthy, a climbing one is not.
HEAP_SLOPE_MAX_MB_H="${HEAP_SLOPE_MAX_MB_H:-5}"
RSS_SLOPE_MAX_MB_H="${RSS_SLOPE_MAX_MB_H:-10}"
POOL_AWAITING_MAX="${POOL_AWAITING_MAX:-0}"
HTTP_5XX_MAX="${HTTP_5XX_MAX:-0}"
RESTARTS_MAX="${RESTARTS_MAX:-0}"
ORPHAN_LOCKS_MAX="${ORPHAN_LOCKS_MAX:-0}"
SCRAPE_FAILURES_MAX="${SCRAPE_FAILURES_MAX:-0}"
# Scrape latency puts a second or two between the two clocks; a real suspend loses minutes.
SUSPEND_GAP_MIN_S="${SUSPEND_GAP_MIN_S:-30}"
SUSPEND_LOST_MAX_S="${SUSPEND_LOST_MAX_S:-120}"

FAILURES=0
pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
info() { printf '  ----  %s\n' "$1"; }

if [[ ! -s "$SERIES" ]]; then
  echo "no series at $SERIES — run 'make soak cmd=artifacts' first" >&2
  exit 2
fi

samples="$(($(wc -l <"$SERIES") - 1))"
if (( samples < 10 )); then
  echo "only $samples samples; too short to grade" >&2
  exit 2
fi

echo ""
echo "state-db soak report"
echo "===================="
duration_h="$(awk -F'\t' 'NR>1 { last = $2 } END { printf "%.1f", last / 3600 }' "$SERIES")"
info "$samples samples over ${duration_h}h"

# Short windows measure JVM warmup, not drift, so trends below the threshold print ungraded.
GRADE_TRENDS=1
TREND_SKIP_REASON=""
if awk "BEGIN { exit !($duration_h < ${MIN_TREND_HOURS:-2}) }"; then
  GRADE_TRENDS=0
  TREND_SKIP_REASON="run too short"
  info "run shorter than ${MIN_TREND_HOURS:-2}h — memory trends reported but not graded"
fi
echo ""

# --- run validity ----------------------------------------------------------------------------
#
# Failed scrapes record zeros and an empty ops log has nothing to fail, so without this a run that
# never happened grades as a clean pass.
echo "Run validity"
read -r scrape_fail uptime_max <<<"$(awk -F'\t' '
  NR > 1 { if ($31 == "0") f++; if ($3 + 0 > u) u = $3 + 0 }
  END { printf "%d %.0f", f + 0, u + 0 }' "$SERIES")"

if [[ "$(awk -F'\t' 'NR==1{print NF}' "$SERIES")" -lt 31 ]]; then
  info "series predates scrape_ok; cannot confirm the app was reachable"
elif (( scrape_fail <= SCRAPE_FAILURES_MAX )); then
  pass "every metrics scrape succeeded"
else
  fail "${scrape_fail} metrics scrape(s) failed — the app was unreachable during the run"
fi

if (( uptime_max > 0 )); then
  pass "app was up (peak uptime ${uptime_max}s)"
else
  fail "app never reported uptime — it did not come up, so nothing below is meaningful"
fi

# A suspended host freezes the container while wall clock keeps running, leaving holes in the
# series that make every trend meaningless. The JVM's uptime is monotonic-clock based and does not
# advance across a suspend, so wall time the uptime cannot account for is the signal.
read -r gaps lost worst <<<"$(awk -F'\t' -v min="$SUSPEND_GAP_MIN_S" '
  NR > 1 && $31 == "1" && $3 + 0 > 0 {
    ts = $1 + 0; up = $3 + 0;
    # A restart rewinds uptime; that is graded separately and is not a suspend.
    if (prev != "" && up - prev >= 0) {
      d = (ts - prev_ts) - (up - prev);
      if (d > min) { g++; lost += d; if (d > worst) worst = d }
    }
    prev_ts = ts; prev = up;
  }
  END { printf "%d %.0f %.0f", g + 0, lost + 0, worst + 0 }' "$SERIES")"

if (( lost <= SUSPEND_LOST_MAX_S )); then
  pass "no host suspend detected (wall clock and JVM uptime agree)"
else
  GRADE_TRENDS=0
  TREND_SKIP_REASON="host suspended"
  fail "${gaps} gap(s) totalling ${lost}s (worst ${worst}s) where wall clock advanced but the JVM did not — the host suspended; trends below are not graded"
fi
echo ""

# --- memory trends -------------------------------------------------------------------------
#
# The leak signal is jvm_gc_live_data_size_bytes. Regressing raw heap usage would mostly measure
# where in the GC cycle each sample landed. Slope in MB/hour.

# The native image exports jvm_gc_overhead but not jvm_gc_live_data_size_bytes, so on that build
# the live set comes from the -XX:+PrintGC lines instead:
#
#   [1234.5s] GC(42) Pause Full GC (Collect on allocation) 226.00M->48.00M 17.236ms
#
# The value after a *full* collection is the live set. Regress the minimum per window rather than
# every sample: an individual full GC can be triggered mid-allocation and retain garbage, so the
# floor tracks the leak and the raw points mostly track allocation rate.
NATIVE_GC_LOG="${NATIVE_GC_LOG:-$ARTIFACTS_DIR/gc-native.log}"
LIVE_SET_WINDOW_S="${LIVE_SET_WINDOW_S:-1800}"
LIVE_SET_WARMUP_S="${LIVE_SET_WARMUP_S:-300}"

grade_native_live_set() {
  if [[ ! -f "$NATIVE_GC_LOG" ]]; then
    fail "no live-set data: neither jvm_gc_live_data_size_bytes nor ${NATIVE_GC_LOG##*/} is present"
    return
  fi

  # A restart resets the GC clock to zero. Fitting across that discontinuity invents a slope, so
  # keep only the final segment and say so.
  local parsed
  parsed="$(awk -v warmup="$LIVE_SET_WARMUP_S" -v window="$LIVE_SET_WINDOW_S" '
    match($0, /^\[[0-9.]+s\] GC\([0-9]+\) Pause Full GC .* [0-9.]+M->[0-9.]+M/) {
      t = substr($1, 2, length($1) - 3) + 0
      after = $0; sub(/.*->/, "", after); sub(/M .*/, "", after)
      if (t < last_t) { segments++; delete floor; n = 0 }
      last_t = t
      total++
      if (t < warmup) next
      n++
      w = int(t / window)
      if (!(w in floor) || after + 0 < floor[w]) { floor[w] = after + 0; floor_t[w] = t }
      if (first_t == "") first_t = t
    }
    END {
      if (total == 0) { print "NOMATCH 0 0 0 0"; exit }
      c = 0
      for (w in floor) { c++; st += floor_t[w]; sv += floor[w]; stv += floor_t[w] * floor[w]; stt += floor_t[w] * floor_t[w] }
      if (c < 3) { print "TOOFEW " total " 0 0 " segments; exit }
      denom = (c * stt - st * st)
      slope = denom == 0 ? 0 : (c * stv - st * sv) / denom
      lo = ""; hi = ""
      for (w in floor) {
        if (lo == "" || floor_t[w] < lo_t) { lo_t = floor_t[w]; lo = floor[w] }
        if (hi == "" || floor_t[w] > hi_t) { hi_t = floor_t[w]; hi = floor[w] }
      }
      printf "%.3f %d %.2f %.2f %d", slope * 3600, total, lo, hi, segments
    }' "$NATIVE_GC_LOG")"

  local slope count floor_first floor_last segments
  read -r slope count floor_first floor_last segments <<<"$parsed"

  case "$slope" in
  NOMATCH)
    fail "${NATIVE_GC_LOG##*/} has no 'Pause Full GC' lines — is -XX:+PrintGC still set on the app?"
    return
    ;;
  TOOFEW)
    info "only ${count} full GC(s) after warmup — too few ${LIVE_SET_WINDOW_S}s windows to fit a trend"
    return
    ;;
  esac

  (( segments > 0 )) && info "app restarted ${segments} time(s); live set fitted over the last run only"
  info "post-GC live-set floor: ${floor_first}MB -> ${floor_last}MB (${count} full GCs)"
  if (( GRADE_TRENDS == 0 )); then
    info "live-set trend ${slope}MB/h (not graded: ${TREND_SKIP_REASON})"
  elif awk "BEGIN { exit !($slope <= $HEAP_SLOPE_MAX_MB_H) }"; then
    pass "live-set trend ${slope}MB/h (limit ${HEAP_SLOPE_MAX_MB_H}, from GC log)"
  else
    fail "live-set trend ${slope}MB/h exceeds ${HEAP_SLOPE_MAX_MB_H}MB/h — probable leak"
  fi
}

echo "Memory"
read -r live_slope live_first live_last <<<"$(awk -F'\t' '
  NR > 1 && $29 != "" && $29 + 0 > 0 { n++; t[n] = $2; v[n] = $29 }
  END {
    if (n < 8) { print "NA 0 0"; exit }
    start = int(n / 2); c = 0;
    for (i = start; i <= n; i++) { c++; st += t[i]; sv += v[i]; stv += t[i] * v[i]; stt += t[i] * t[i] }
    denom = (c * stt - st * st);
    slope = denom == 0 ? 0 : (c * stv - st * sv) / denom;
    printf "%.3f %.1f %.1f", slope * 3600 / 1048576, v[start] / 1048576, v[n] / 1048576;
  }' "$SERIES")"

heap_range="$(awk -F'\t' '
  NR > 1 { if (lo == "" || $4 < lo) lo = $4; if ($4 > hi) hi = $4 }
  END { printf "%.1f-%.1fMB", lo / 1048576, hi / 1048576 }' "$SERIES")"
gc_overhead_max="$(awk -F'\t' 'NR > 1 && $30 + 0 > m { m = $30 } END { printf "%.4f", m + 0 }' "$SERIES")"

info "heap in use ranged $heap_range; peak GC overhead ${gc_overhead_max}"
# The GC log wins when present: on a native run the series column holds old-gen occupancy, which
# includes uncollected garbage and would grade allocation rate rather than the live set.
if [[ -f "$NATIVE_GC_LOG" ]]; then
  grade_native_live_set
elif [[ "$live_slope" == "NA" ]]; then
  fail "no live-set data: neither jvm_gc_live_data_size_bytes nor ${NATIVE_GC_LOG##*/} is present"
else
  info "post-GC live set: ${live_first}MB -> ${live_last}MB"
  if (( GRADE_TRENDS == 0 )); then
    info "live-set trend ${live_slope}MB/h (not graded: ${TREND_SKIP_REASON})"
  elif awk "BEGIN { exit !($live_slope <= $HEAP_SLOPE_MAX_MB_H) }"; then
    pass "live-set trend ${live_slope}MB/h (limit ${HEAP_SLOPE_MAX_MB_H})"
  else
    fail "live-set trend ${live_slope}MB/h exceeds ${HEAP_SLOPE_MAX_MB_H}MB/h — probable leak"
  fi
fi

read -r rss_slope rss_first rss_last <<<"$(awk -F'\t' '
  NR > 1 { n++; t[n] = $2; v[n] = $8 }
  END {
    if (n < 8) { print "0 0 0"; exit }
    start = int(n / 2); c = 0;
    for (i = start; i <= n; i++) { c++; st += t[i]; sv += v[i]; stv += t[i] * v[i]; stt += t[i] * t[i] }
    denom = (c * stt - st * st);
    slope = denom == 0 ? 0 : (c * stv - st * sv) / denom;
    printf "%.3f %.1f %.1f", slope * 3600 / 1048576, v[start] / 1048576, v[n] / 1048576;
  }' "$SERIES")"
info "resident set: ${rss_first}MB -> ${rss_last}MB"
if (( GRADE_TRENDS == 0 )); then
  info "RSS trend ${rss_slope}MB/h (not graded: ${TREND_SKIP_REASON})"
elif awk "BEGIN { exit !($rss_slope <= $RSS_SLOPE_MAX_MB_H) }"; then
  pass "RSS trend ${rss_slope}MB/h (limit ${RSS_SLOPE_MAX_MB_H})"
else
  fail "RSS trend ${rss_slope}MB/h exceeds ${RSS_SLOPE_MAX_MB_H}MB/h — off-heap growth; check NMT"
fi
echo ""

# --- connection pool -----------------------------------------------------------------------
echo "JDBC pool"
read -r await_max block_max <<<"$(awk -F'\t' '
  NR > 1 { if ($14 > a) a = $14; if ($15 > b) b = $15 }
  END { printf "%.0f %.3f", a + 0, b + 0 }' "$SERIES")"
info "peak blocking wait ${block_max}s"
if (( await_max <= POOL_AWAITING_MAX )); then
  pass "no threads queued for a connection (peak awaiting ${await_max})"
else
  fail "peak ${await_max} threads awaiting a connection — pool saturation"
fi
echo ""

# --- errors and stability ------------------------------------------------------------------
echo "Stability"
http_5xx="$(awk -F'\t' 'NR>1 { last = $18 } END { printf "%.0f", last + 0 }' "$SERIES")"
if (( http_5xx <= HTTP_5XX_MAX )); then
  pass "no server errors (${http_5xx} 5xx responses)"
else
  fail "${http_5xx} 5xx responses — injected faults are provider-side and should not surface as 5xx"
fi

if restarts="$(docker inspect "$APP_CONTAINER" --format '{{.RestartCount}}' 2>/dev/null)"; then
  oomkilled="$(docker inspect "$APP_CONTAINER" --format '{{.State.OOMKilled}}' 2>/dev/null)"
  if (( restarts <= RESTARTS_MAX )) && [[ "$oomkilled" != "true" ]]; then
    pass "app did not restart (restarts=${restarts}, oom_killed=${oomkilled})"
  else
    fail "app restarted ${restarts} time(s), oom_killed=${oomkilled}"
  fi
else
  info "container $APP_CONTAINER not inspectable; skipping restart check"
fi
echo ""

# --- locks after quiesce -------------------------------------------------------------------
#
# Only meaningful once load has stopped; drive.sh idles after its duration expires.
echo "Locks"
read -r locks_final updates_final <<<"$(awk -F'\t' 'NR>1 { l = $19; u = $20 } END { printf "%.0f %.0f", l + 0, u + 0 }' "$SERIES")"
if [[ ! -f "$ARTIFACTS_DIR/load-finished" ]]; then
  # Mid-run a held lock is just the update in flight.
  info "load still running — ${locks_final} lock(s) held, quiesce check skipped"
elif (( locks_final <= ORPHAN_LOCKS_MAX )) && (( updates_final == 0 )); then
  pass "no locks or in-progress updates remain (${locks_final} locks, ${updates_final} updates)"
else
  fail "${locks_final} lock(s) and ${updates_final} in-progress update(s) survived quiesce"
fi
echo ""

# --- history growth ------------------------------------------------------------------------
#
# Nothing prunes these tables yet, so growth is expected. Super-linear growth would not be:
# rows per update climbing means per-update cost scaling with history depth.
echo "History growth (measured, not graded — no retention exists yet)"
awk -F'\t' '
  NR > 1 { n++; t[n] = $2; snap[n] = $21; jrn[n] = $22; ev[n] = $23; upd[n] = $24; sz[n] = $28 }
  END {
    if (n < 4) exit;
    hours = t[n] / 3600; if (hours <= 0) hours = 1;
    printf "  ----  snapshots %d -> %d (%.1f/h)\n", snap[1], snap[n], (snap[n] - snap[1]) / hours;
    printf "  ----  journal_entries %d -> %d (%.1f/h)\n", jrn[1], jrn[n], (jrn[n] - jrn[1]) / hours;
    printf "  ----  update_events %d -> %d (%.1f/h)\n", ev[1], ev[n], (ev[n] - ev[1]) / hours;
    printf "  ----  stack_updates %d -> %d (%.1f/h)\n", upd[1], upd[n], (upd[n] - upd[1]) / hours;
    printf "  ----  on-disk %.1fMB -> %.1fMB (%.1fMB/h)\n", sz[1] / 1048576, sz[n] / 1048576, (sz[n] - sz[1]) / 1048576 / hours;
    q = int(n / 4); if (q < 2) exit;
    early = (upd[q] - upd[1]) > 0 ? (ev[q] - ev[1]) / (upd[q] - upd[1]) : 0;
    late = (upd[n] - upd[n - q]) > 0 ? (ev[n] - ev[n - q]) / (upd[n] - upd[n - q]) : 0;
    printf "  ----  events per update: %.1f early, %.1f late\n", early, late;
    if (early > 0 && late > early * 1.5)
      printf "  \033[33mWARN\033[0m  events per update grew %.1fx — cost may scale with history depth\n", late / early;
  }' "$SERIES"
echo ""

# --- operations ----------------------------------------------------------------------------
if [[ -s "$OPS" ]]; then
  echo "Operations"
  read -r total unexpected fault_total fault_succeeded cont_groups cont_bad <<<"$(awk -F'\t' '
    NR > 1 {
      total++;
      if ($6 != 0 && $8 != 1) unexpected++;
      if ($5 == "fault-up") { ft++; if ($6 == 0) fs++ }
      if ($5 ~ /^contention-/) { ct[$3]++; if ($6 == 0) co[$3]++ }
    }
    END {
      # Exactly one must win: two winners means the lease excludes nothing, two losers means the
      # stack was already unusable. Partial groups are not judged.
      for (i in ct) if (ct[i] == 2) { g++; if (co[i] != 1) cb++ }
      printf "%d %d %d %d %d %d", total + 0, unexpected + 0, ft + 0, fs + 0, g + 0, cb + 0;
    }' "$OPS")"

  awk -F'\t' 'NR > 1 { c[$5]++ } END { printf "  ----  %d operations", NR - 1; for (k in c) printf ", %s=%d", k, c[k]; printf "\n" }' "$OPS"

  if [[ -f "$ARTIFACTS_DIR/load-finished" ]] && (( total == 0 )); then
    fail "the run completed without recording a single operation — the driver never drove"
  elif (( total == 0 )); then
    info "no operations recorded yet"
  fi

  if (( unexpected == 0 )); then
    pass "no unexpected operation failures"
  else
    fail "${unexpected} unexpected operation failure(s)"
  fi

  # expected_failure only excuses a non-zero exit; it never checks the failure happened.
  if (( fault_total == 0 )); then
    info "no fault injections in this run"
  elif (( fault_succeeded == 0 )); then
    pass "all ${fault_total} injected fault(s) failed as designed"
  else
    fail "${fault_succeeded}/${fault_total} injected fault(s) succeeded — fault injection is not working"
  fi

  if (( cont_groups == 0 )); then
    info "no complete contention pairs in this run"
  elif (( cont_bad == 0 )); then
    pass "all ${cont_groups} contention pair(s) had exactly one winner"
  else
    fail "${cont_bad}/${cont_groups} contention pair(s) did not have exactly one winner — lease exclusion is suspect"
  fi
  echo ""
fi

if (( FAILURES > 0 )); then
  printf '\033[31m%d check(s) failed\033[0m\n\n' "$FAILURES"
  exit 1
fi
printf '\033[32mall checks passed\033[0m\n\n'
