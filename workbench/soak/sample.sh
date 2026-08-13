#!/usr/bin/env bash
# One TSV row per interval.
#
# Runs in the app's PID namespace so it can read true RSS from /proc/1/status. Everything else is
# the Prometheus scrape plus direct SQL, since the DB-side facts have no metrics endpoint.
set -uo pipefail

: "${METRICS_URL:?}" "${PGHOST:?}" "${PGUSER:?}" "${PGDATABASE:?}" "${PGPASSWORD:?}"

ARTIFACTS="${ARTIFACTS:-/soak/artifacts}"
INTERVAL="${SOAK_SAMPLE_INTERVAL:-60}"
SERIES="$ARTIFACTS/series.tsv"
SCRAPE="/tmp/scrape.txt"

mkdir -p "$ARTIFACTS"

COLUMNS=(
  ts elapsed_s uptime_s
  heap_used heap_committed heap_max nonheap_used rss_bytes
  gc_count gc_pause_sum_s threads_live
  pool_active pool_available pool_awaiting pool_blocking_max_s pool_acquire_total
  http_total http_5xx
  locks_live updates_in_progress
  rows_snapshots rows_journal rows_events rows_updates rows_pending rows_compaction
  compaction_failed growth_bytes
  gc_live_bytes gc_overhead scrape_ok
)

if [[ ! -s "$SERIES" ]]; then
  (IFS=$'\t'; echo "${COLUMNS[*]}") >"$SERIES"
fi

START_EPOCH="$(date +%s)"

# $1 metric name, $2 optional label substring to filter on. Sums across label sets, because
# jvm_memory_used_bytes and http_server_requests_seconds_count are per-pool and per-route.
#
# The value is read as $NF, not $2: Prometheus label values may contain spaces (the JVM's pool
# names are literally id="G1 Eden Space"), which splits the line into a variable number of awk
# fields. Only the trailing value has a stable position.
metric_sum() {
  awk -v n="$1" -v f="${2:-}" '
    index($0, n "{") == 1 && (f == "" || index($0, f) > 0) { s += $NF }
    END { printf "%.0f", s + 0 }' "$SCRAPE"
}

metric_float() {
  awk -v n="$1" -v f="${2:-}" '
    index($0, n "{") == 1 && (f == "" || index($0, f) > 0) { s += $NF }
    END { printf "%.4f", s + 0 }' "$SCRAPE"
}

# Unlabelled metrics: the whole name is field 1.
metric_bare() {
  awk -v n="$1" '$1 == n { printf "%.4f", $NF; found = 1; exit } END { if (!found) printf "0" }' "$SCRAPE"
}

rss_bytes() {
  # VmRSS is reported in kB. pid 1 is the JVM thanks to the shared PID namespace.
  awk '/^VmRSS:/ { printf "%.0f", $2 * 1024; found = 1 } END { if (!found) printf "0" }' /proc/1/status 2>/dev/null
}

db_row() {
  psql -tAqF$'\t' -c "
    SELECT (SELECT count(*) FROM stack_locks),
           (SELECT count(*) FROM stack_updates WHERE status = 'in-progress'),
           (SELECT count(*) FROM snapshots),
           (SELECT count(*) FROM journal_entries),
           (SELECT count(*) FROM update_events),
           (SELECT count(*) FROM stack_updates),
           (SELECT count(*) FROM pending_operations),
           (SELECT count(*) FROM compaction_jobs),
           (SELECT count(*) FROM compaction_jobs WHERE status = 'failed'),
           (SELECT pg_total_relation_size('snapshots')
                 + pg_total_relation_size('journal_entries')
                 + pg_total_relation_size('update_events')
                 + pg_total_relation_size('stack_updates'))" 2>/dev/null
}

echo "[sample] writing $SERIES every ${INTERVAL}s"

while true; do
  now="$(date +%s)"

  scrape_ok=1
  if curl -fsS --max-time 10 "$METRICS_URL" -o "$SCRAPE" 2>/dev/null; then
    uptime_s="$(metric_bare process_uptime_seconds)"
    heap_used="$(metric_sum jvm_memory_used_bytes 'area="heap"')"
    heap_committed="$(metric_sum jvm_memory_committed_bytes 'area="heap"')"
    heap_max="$(metric_sum jvm_memory_max_bytes 'area="heap"')"
    nonheap_used="$(metric_sum jvm_memory_used_bytes 'area="nonheap"')"
    gc_count="$(metric_sum jvm_gc_pause_seconds_count)"
    gc_sum="$(metric_float jvm_gc_pause_seconds_sum)"
    threads="$(metric_bare jvm_threads_live_threads)"
    pool_active="$(metric_bare agroal_active_count)"
    pool_available="$(metric_bare agroal_available_count)"
    pool_awaiting="$(metric_bare agroal_awaiting_count)"
    pool_block_max="$(metric_bare agroal_blocking_time_max_seconds)"
    pool_acquire="$(metric_bare agroal_acquire_count_total)"
    http_total="$(metric_sum http_server_requests_seconds_count)"
    http_5xx="$(metric_sum http_server_requests_seconds_count 'outcome="SERVER_ERROR"')"
    # The live set after the last major GC is the leak signal; approximating it from sampled
    # minima overestimates badly. gc_overhead corroborates.
    gc_live="$(metric_bare jvm_gc_live_data_size_bytes)"
    # The native image does not export that gauge. Old-gen occupancy is the closest sampled
    # stand-in and keeps this column and `make soak cmd=status` meaningful, but it includes
    # uncollected garbage, so report.sh grades the GC log instead of this value.
    if [[ -z "$gc_live" || "$gc_live" == "0" ]]; then
      gc_live="$(metric_sum jvm_memory_used_bytes 'area="heap",id="old"')"
    fi
    gc_overhead="$(metric_bare jvm_gc_overhead)"
  else
    # Record zeros rather than skipping the row, so a gap means the app was unreachable rather
    # than the sampler asleep. scrape_ok keeps those zeros distinguishable from real readings.
    scrape_ok=0
    uptime_s=0 heap_used=0 heap_committed=0 heap_max=0 nonheap_used=0
    gc_count=0 gc_sum=0 threads=0
    pool_active=0 pool_available=0 pool_awaiting=0 pool_block_max=0 pool_acquire=0
    http_total=0 http_5xx=0 gc_live=0 gc_overhead=0
  fi

  db="$(db_row)"
  [[ -z "$db" ]] && db=$'0\t0\t0\t0\t0\t0\t0\t0\t0\t0'

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$now" "$((now - START_EPOCH))" "$uptime_s" \
    "$heap_used" "$heap_committed" "$heap_max" "$nonheap_used" "$(rss_bytes)" \
    "$gc_count" "$gc_sum" "$threads" \
    "$pool_active" "$pool_available" "$pool_awaiting" "$pool_block_max" "$pool_acquire" \
    "$http_total" "$http_5xx" "$db" "$gc_live" "$gc_overhead" "$scrape_ok" >>"$SERIES"

  sleep "$INTERVAL"
done
