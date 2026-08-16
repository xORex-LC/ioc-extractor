#!/usr/bin/env bash
# shellcheck disable=SC2016 # Markdown backticks in report format strings are literal
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${SCRIPT_DIR}/common.sh"

SIZE="1000"
TTL="15s"
HISTORY_RETENTION="30m"
PURGE_RETENTION="1s"
BACKSTOP="1s"
EXPORT_QUIET_PERIOD="5s"
BATCH_SIZE="1000"
TIMEOUT="300"
MAX_RSS_KIB="1048576"
MIN_EXPIRY_ROWS_PER_SECOND="0"
MIN_CANONICAL_ROWS="1"
MAX_DEADLINE_SPREAD_MS="0"
MAX_RETENTION_SECONDS="300"
PORT="18083"
WORKSPACE="${DEV_ROOT}/lifecycle-smoke"
JAR=""
SEED="62001"
RUNTIME_STARTED="false"

ARTIFACTS=(masks ip_list address_blacklist hashes)
ID_ARTIFACTS=(masks ip_list hashes)
declare -A PROJECTIONS=(
  [masks]="masks_list_generated.csv"
  [ip_list]="ip_list_generated.csv"
  [address_blacklist]="address_blacklist_generated.csv"
  [hashes]="hashes_list_generated.csv"
)
declare -A ACTIVE_COUNTS=()
declare -A FORMER_MAX_IDS=()

usage() {
  cat <<'EOF'
Usage: tools/dev/lifecycle-smoke.sh [OPTIONS]

Options:
  --size N                   IOC-bearing input rows (default: 1000)
  --ttl DURATION             Fixed validity for the expiry phase (default: 15s)
  --history-retention DUR    Retention during expiry observation (default: 30m)
  --purge-retention DUR      Retention after restart (default: 1s)
  --backstop DURATION        Reconcile correctness backstop (default: 1s)
  --export-quiet-period DUR  New-data export quiet period (default: 5s)
  --batch-size N             Rows per SQLite lifecycle transaction (default: 1000)
  --timeout SECONDS          Timeout for each eventual-state wait (default: 300)
  --max-rss-kib N            Maximum JVM VmHWM (default: 1048576, systemd 1 GiB)
  --min-expiry-rows-per-second N
                             Optional measured drain throughput floor (default: 0)
  --min-canonical-rows N     Minimum rows after canonical routing (default: 1)
  --max-deadline-spread-ms N Maximum same-wave spread; 0 disables (default: 0)
  --max-retention-seconds N  Maximum restart-to-history-drain time (default: 300)
  --port PORT                Loopback actuator port (default: 18083)
  --workspace PATH           Workspace below repo-local .dev
  --jar PATH                 Explicit bootable application jar
  --seed N                   Deterministic fixture seed (default: 62001)

The scenario ingests through the daemon, waits for active rows to expire into
typed history, restarts with short history retention, verifies cascade cleanup
and then proves that a later observation does not reuse public IDs. Evidence is
written below the workspace; business rows are never inserted directly by this
tool.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --size) SIZE="${2:?}"; shift 2 ;;
    --ttl) TTL="${2:?}"; shift 2 ;;
    --history-retention) HISTORY_RETENTION="${2:?}"; shift 2 ;;
    --purge-retention) PURGE_RETENTION="${2:?}"; shift 2 ;;
    --backstop) BACKSTOP="${2:?}"; shift 2 ;;
    --export-quiet-period) EXPORT_QUIET_PERIOD="${2:?}"; shift 2 ;;
    --batch-size) BATCH_SIZE="${2:?}"; shift 2 ;;
    --timeout) TIMEOUT="${2:?}"; shift 2 ;;
    --max-rss-kib) MAX_RSS_KIB="${2:?}"; shift 2 ;;
    --min-expiry-rows-per-second) MIN_EXPIRY_ROWS_PER_SECOND="${2:?}"; shift 2 ;;
    --min-canonical-rows) MIN_CANONICAL_ROWS="${2:?}"; shift 2 ;;
    --max-deadline-spread-ms) MAX_DEADLINE_SPREAD_MS="${2:?}"; shift 2 ;;
    --max-retention-seconds) MAX_RETENTION_SECONDS="${2:?}"; shift 2 ;;
    --port) PORT="${2:?}"; shift 2 ;;
    --workspace) WORKSPACE="${2:?}"; shift 2 ;;
    --jar) JAR="${2:?}"; shift 2 ;;
    --seed) SEED="${2:?}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; dev_die "unknown lifecycle-smoke option: $1" ;;
  esac
done

for value in "${SIZE}" "${BATCH_SIZE}" "${TIMEOUT}" "${MAX_RSS_KIB}" \
    "${MIN_CANONICAL_ROWS}" "${MAX_RETENTION_SECONDS}"; do
  [[ "${value}" =~ ^[1-9][0-9]*$ ]] \
    || dev_die "size, batch-size, timeout and max-rss-kib must be positive integers"
done
[[ "${MIN_EXPIRY_ROWS_PER_SECOND}" =~ ^[0-9]+([.][0-9]+)?$ ]] \
  || dev_die "minimum expiry throughput must be a non-negative number"
[[ "${MAX_DEADLINE_SPREAD_MS}" =~ ^[0-9]+$ ]] \
  || dev_die "maximum deadline spread must be a non-negative integer"
[[ "${SEED}" =~ ^-?[0-9]+$ ]] || dev_die "seed must be an integer"
dev_validate_port "${PORT}" || dev_die "port must be an integer in 1..65535"
for value in "${TTL}" "${HISTORY_RETENTION}" "${PURGE_RETENTION}" "${BACKSTOP}"; do
  [[ "${value}" =~ ^[1-9][0-9]*(ms|s|m|h|d)$ ]] \
    || dev_die "lifecycle durations must be positive and use ms, s, m, h or d: ${value}"
done

dev_require_java21
dev_require_command curl
dev_require_command jq
dev_require_command sqlite3
dev_require_command sha256sum
dev_resolve_app_jar "${JAR}"
JAR="${DEV_APP_JAR}"
[[ "${WORKSPACE}" == /* ]] || WORKSPACE="${DEV_REPO_ROOT}/${WORKSPACE}"
dev_validate_workspace "${WORKSPACE}"
WORKSPACE="${DEV_VALIDATED_WORKSPACE}"
DB="${WORKSPACE}/var/db/ioc-dataframe.db"
REPORT="${WORKSPACE}/lifecycle-report.md"
QUERY_PLANS="${WORKSPACE}/query-plans.txt"

cleanup() {
  if [[ "${RUNTIME_STARTED}" == "true" ]]; then
    "${SCRIPT_DIR}/runtime.sh" --workspace "${WORKSPACE}" down >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

now_ms() {
  date +%s%3N
}

sql() {
  sqlite3 -readonly -batch -noheader -cmd '.timeout 5000' "${DB}" "$1"
}

active_total() {
  sql 'SELECT (SELECT COUNT(*) FROM masks)
      + (SELECT COUNT(*) FROM ip_list)
      + (SELECT COUNT(*) FROM address_blacklist)
      + (SELECT COUNT(*) FROM hashes);' | tail -1
}

history_total() {
  sql 'SELECT (SELECT COUNT(*) FROM masks_history)
      + (SELECT COUNT(*) FROM ip_list_history)
      + (SELECT COUNT(*) FROM address_blacklist_history)
      + (SELECT COUNT(*) FROM hashes_history);' | tail -1
}

history_sources_total() {
  sql 'SELECT (SELECT COUNT(*) FROM masks_history_sources)
      + (SELECT COUNT(*) FROM ip_list_history_sources)
      + (SELECT COUNT(*) FROM address_blacklist_history_sources)
      + (SELECT COUNT(*) FROM hashes_history_sources);' | tail -1
}

pending_projections() {
  sql 'SELECT COUNT(*) FROM artifact_projection_state
       WHERE projected_generation < required_generation;' | tail -1
}

lifecycle_health_status() {
  curl --noproxy '*' --silent --fail --max-time 2 \
    "http://127.0.0.1:${PORT}/actuator/health/lifecycle" 2>/dev/null \
    | jq -r '.status // empty'
}

wait_for_health_up() {
  local deadline=$((SECONDS + TIMEOUT)) status=""
  while (( SECONDS < deadline )); do
    status="$(lifecycle_health_status || true)"
    [[ "${status}" == "UP" ]] && return 0
    sleep 1
  done
  dev_die "lifecycle health did not become UP within ${TIMEOUT}s (last=${status:-unavailable})"
}

wait_for_done() { # source-name
  local name="$1" deadline=$((SECONDS + TIMEOUT)) found=""
  while (( SECONDS < deadline )); do
    found="$(find "${WORKSPACE}/var/done" -maxdepth 1 -type f \
      -name "*-${name}" -print -quit 2>/dev/null || true)"
    [[ -n "${found}" ]] && return 0
    sleep 1
  done
  dev_die "daemon did not archive ${name} within ${TIMEOUT}s"
}

completed_slice_count() {
  local profile_dir="${WORKSPACE}/var/export/reputation-lists"
  [[ -d "${profile_dir}" ]] || { printf '0\n'; return; }
  find "${profile_dir}" -mindepth 2 -maxdepth 2 -type f -name _SUCCESS -print \
    | wc -l | tr -d ' '
}

wait_for_slice_count() { # expected
  local expected="$1" deadline=$((SECONDS + TIMEOUT)) actual=""
  while (( SECONDS < deadline )); do
    actual="$(completed_slice_count)"
    [[ "${actual}" -eq "${expected}" ]] && return 0
    sleep 1
  done
  dev_die "reputation-lists has ${actual:-0} completed slices, expected ${expected}"
}

latest_slice_dir() {
  local profile_dir="${WORKSPACE}/var/export/reputation-lists"
  find "${profile_dir}" -mindepth 2 -maxdepth 2 -type f -name _SUCCESS \
    -printf '%T@ %h\n' | sort -n | tail -1 | cut -d' ' -f2-
}

wait_for_latest_export_rows() { # expected
  local expected="$1" deadline=$((SECONDS + TIMEOUT)) slice="" actual=""
  while (( SECONDS < deadline )); do
    slice="$(latest_slice_dir 2>/dev/null || true)"
    if [[ -n "${slice}" && -f "${slice}/manifest.json" ]]; then
      actual="$(jq '[.artifacts[].rows] | add // 0' "${slice}/manifest.json")"
      [[ "${actual}" -eq "${expected}" ]] && return 0
    fi
    sleep 1
  done
  dev_die "latest reputation-lists slice has ${actual:-no} rows, expected ${expected}"
}

start_runtime() { # history-retention
  local retention="$1"
  "${SCRIPT_DIR}/runtime.sh" \
    --workspace "${WORKSPACE}" \
    --port "${PORT}" \
    --jar "${JAR}" \
    --health-attempts "${TIMEOUT}" \
    --jvm-arg -Xms128m \
    --jvm-arg -Xmx512m \
    --jvm-arg -XX:+ExitOnOutOfMemoryError \
    --set ioc.lifecycle.validity.mode=fixed \
    --set "ioc.lifecycle.validity.fixed-ttl=${TTL}" \
    --set ioc.lifecycle.validity.existing-records=expire \
    --set "ioc.lifecycle.history-retention=${retention}" \
    --set ioc.lifecycle.receipt-retention=30m \
    --set "ioc.lifecycle.reconcile.backstop-interval=${BACKSTOP}" \
    --set "ioc.lifecycle.reconcile.batch-size=${BATCH_SIZE}" \
    --set ioc.export.trigger.type=quiet-period \
    --set "ioc.export.trigger.interval=${EXPORT_QUIET_PERIOD}" \
    --set "ioc.export.trigger.quiet-period=${EXPORT_QUIET_PERIOD}" \
    --set ioc.export.trigger.max-cap=1h \
    --set ioc.ingestion.detect.use-watch-service=false \
    --set ioc.ingestion.detect.reconcile-interval=1s \
    --set ioc.ingestion.stability.quiet-period=1s \
    up
  RUNTIME_STARTED="true"
  wait_for_health_up
}

stop_runtime() {
  "${SCRIPT_DIR}/runtime.sh" --workspace "${WORKSPACE}" down >/dev/null
  RUNTIME_STARTED="false"
}

high_water_rss_kib() {
  local pid
  pid="$(<"${WORKSPACE}/run/daemon.pid")"
  awk '/^VmHWM:/ {print $2}' "/proc/${pid}/status" 2>/dev/null || printf '0\n'
}

dev_log "resetting lifecycle workspace ${WORKSPACE}"
"${SCRIPT_DIR}/runtime.sh" --workspace "${WORKSPACE}" reset >/dev/null
start_runtime "${HISTORY_RETENTION}"

FIXTURE="${WORKSPACE}/initial.html"
java "${SCRIPT_DIR}/GenerateIocFixture.java" \
  --size "${SIZE}" --seed "${SEED}" --duplicate-rate 0 --defang-rate 0.35 \
  --output "${FIXTURE}" > "${WORKSPACE}/fixture.log"

INGEST_STARTED_MS="$(now_ms)"
"${SCRIPT_DIR}/submit.sh" --workspace "${WORKSPACE}" "${FIXTURE}" >/dev/null
wait_for_done "initial.html"
INGEST_COMPLETED_MS="$(now_ms)"
INGEST_ELAPSED_MS=$((INGEST_COMPLETED_MS - INGEST_STARTED_MS))
wait_for_health_up
INITIAL_EXPORTED_ROWS="$(sql 'SELECT (SELECT COUNT(*) FROM masks)
  + (SELECT COUNT(*) FROM ip_list) + (SELECT COUNT(*) FROM hashes);' | tail -1)"
wait_for_latest_export_rows "${INITIAL_EXPORTED_ROWS}"
SLICES_BEFORE_EXPIRY="$(completed_slice_count)"

TOTAL_ACTIVE=0
for artifact in "${ARTIFACTS[@]}"; do
  ACTIVE_COUNTS["${artifact}"]="$(sql "SELECT COUNT(*) FROM ${artifact};" | tail -1)"
  (( TOTAL_ACTIVE += ACTIVE_COUNTS["${artifact}"] ))
done
(( TOTAL_ACTIVE >= MIN_CANONICAL_ROWS )) \
  || dev_die "ingestion produced ${TOTAL_ACTIVE} canonical rows, expected at least ${MIN_CANONICAL_ROWS}"

MIN_DEADLINE_MS="$(sql 'SELECT MIN(deadline) FROM (
  SELECT _valid_until_epoch_ms AS deadline FROM masks
  UNION ALL SELECT _valid_until_epoch_ms FROM ip_list
  UNION ALL SELECT _valid_until_epoch_ms FROM address_blacklist
  UNION ALL SELECT _valid_until_epoch_ms FROM hashes);' | tail -1)"
MAX_DEADLINE_MS="$(sql 'SELECT MAX(deadline) FROM (
  SELECT _valid_until_epoch_ms AS deadline FROM masks
  UNION ALL SELECT _valid_until_epoch_ms FROM ip_list
  UNION ALL SELECT _valid_until_epoch_ms FROM address_blacklist
  UNION ALL SELECT _valid_until_epoch_ms FROM hashes);' | tail -1)"
[[ "${MIN_DEADLINE_MS}" =~ ^[0-9]+$ && "${MAX_DEADLINE_MS}" =~ ^[0-9]+$ ]] \
  || dev_die "active rows do not have valid lifecycle deadlines"
DEADLINE_SPREAD_MS=$((MAX_DEADLINE_MS - MIN_DEADLINE_MS))
if (( MAX_DEADLINE_SPREAD_MS > 0 && DEADLINE_SPREAD_MS > MAX_DEADLINE_SPREAD_MS )); then
  dev_die "deadline spread ${DEADLINE_SPREAD_MS}ms exceeded ${MAX_DEADLINE_SPREAD_MS}ms"
fi

sql 'SELECT artifact || "=" || revision FROM artifact_revision ORDER BY artifact;' \
  > "${WORKSPACE}/revision-before-expiry.txt"
BASE_CYCLE_ID="$(sql 'SELECT COALESCE(MAX(cycle_id), 0) FROM lifecycle_reconcile_cycle;' | tail -1)"
RSS_AFTER_INGEST_KIB="$(high_water_rss_kib)"

: > "${QUERY_PLANS}"
for artifact in "${ARTIFACTS[@]}"; do
  {
    printf '[expiry:%s]\n' "${artifact}"
    sql "EXPLAIN QUERY PLAN SELECT id FROM ${artifact}
         WHERE _valid_until_epoch_ms <= ${MAX_DEADLINE_MS}
         ORDER BY _valid_until_epoch_ms, _lifecycle_id LIMIT ${BATCH_SIZE};"
    printf '[retention:%s]\n' "${artifact}"
    sql "EXPLAIN QUERY PLAN SELECT history_id FROM ${artifact}_history
         WHERE closed_at_epoch_ms <= ${MAX_DEADLINE_MS}
         ORDER BY closed_at_epoch_ms, history_id LIMIT ${BATCH_SIZE};"
  } >> "${QUERY_PLANS}"
done
for artifact in "${ARTIFACTS[@]}"; do
  grep -Fq "USING COVERING INDEX ix_${artifact}_lifecycle_due" "${QUERY_PLANS}" \
    || dev_die "expiry query plan does not use the lifecycle index for ${artifact}"
  grep -Fq "USING COVERING INDEX ix_${artifact}_history_retention" "${QUERY_PLANS}" \
    || dev_die "retention query plan does not use the retention index for ${artifact}"
done

dev_log "waiting for ${TOTAL_ACTIVE} active rows to expire (deadline spread ${DEADLINE_SPREAD_MS}ms)"
EXPIRY_WAIT_DEADLINE=$((SECONDS + TIMEOUT))
FIRST_HISTORY_MS=""
while (( SECONDS < EXPIRY_WAIT_DEADLINE )); do
  CURRENT_HISTORY="$(history_total)"
  if [[ -z "${FIRST_HISTORY_MS}" && "${CURRENT_HISTORY}" -gt 0 ]]; then
    FIRST_HISTORY_MS="$(now_ms)"
  fi
  if [[ "$(active_total)" -eq 0 \
      && "${CURRENT_HISTORY}" -eq "${TOTAL_ACTIVE}" \
      && "$(pending_projections)" -eq 0 ]]; then
    break
  fi
  sleep 1
done
[[ "$(active_total)" -eq 0 ]] || dev_die "active rows did not drain within ${TIMEOUT}s"
[[ "$(history_total)" -eq "${TOTAL_ACTIVE}" ]] \
  || dev_die "history count does not match the pre-expiry active set"
[[ "$(pending_projections)" -eq 0 ]] || dev_die "mutable projections did not converge"
FIRST_HISTORY_MS="${FIRST_HISTORY_MS:-$(now_ms)}"
EXPIRY_DRAINED_MS="$(now_ms)"
EXPIRY_START_LATENCY_MS=$((FIRST_HISTORY_MS - MIN_DEADLINE_MS))
(( EXPIRY_START_LATENCY_MS < 0 )) && EXPIRY_START_LATENCY_MS=0
EXPIRY_DRAIN_LATENCY_MS=$((EXPIRY_DRAINED_MS - MAX_DEADLINE_MS))
(( EXPIRY_DRAIN_LATENCY_MS < 0 )) && EXPIRY_DRAIN_LATENCY_MS=0
EXPIRY_WORK_MS=$((EXPIRY_DRAINED_MS - FIRST_HISTORY_MS))
(( EXPIRY_WORK_MS > 0 )) || EXPIRY_WORK_MS=1
EXPIRY_ROWS_PER_SECOND="$(awk -v rows="${TOTAL_ACTIVE}" -v ms="${EXPIRY_WORK_MS}" \
  'BEGIN {printf "%.2f", (rows * 1000) / ms}')"
(( EXPIRY_START_LATENCY_MS <= 5000 )) \
  || dev_die "expiry started ${EXPIRY_START_LATENCY_MS}ms after its deadline (limit: 5000ms)"
awk -v actual="${EXPIRY_ROWS_PER_SECOND}" -v minimum="${MIN_EXPIRY_ROWS_PER_SECOND}" \
  'BEGIN {exit !(actual >= minimum)}' \
  || dev_die "expiry throughput ${EXPIRY_ROWS_PER_SECOND} rows/s is below ${MIN_EXPIRY_ROWS_PER_SECOND}"

sql 'SELECT artifact || "=" || revision FROM artifact_revision ORDER BY artifact;' \
  > "${WORKSPACE}/revision-after-expiry.txt"
cmp -s "${WORKSPACE}/revision-before-expiry.txt" "${WORKSPACE}/revision-after-expiry.txt" \
  || dev_die "expiry changed insert-driven artifact revision"
[[ "$(completed_slice_count)" -eq "${SLICES_BEFORE_EXPIRY}" ]] \
  || dev_die "expiry created an immutable export slice"

for artifact in "${ARTIFACTS[@]}"; do
  [[ "$(sql "SELECT COUNT(*) FROM ${artifact}_history
      WHERE close_reason <> 'EXPIRED' OR closed_at_epoch_ms IS NULL;" | tail -1)" -eq 0 ]] \
    || dev_die "${artifact} history contains an invalid expiry snapshot"
  [[ "$(sql "SELECT COUNT(*) FROM ${artifact}_history h
      WHERE NOT EXISTS (SELECT 1 FROM ${artifact}_history_sources s
                        WHERE s.history_id = h.history_id);" | tail -1)" -eq 0 ]] \
    || dev_die "${artifact} history lost its compact source summary"
  [[ "$(wc -l < "${WORKSPACE}/dataframe/${PROJECTIONS[${artifact}]}" | tr -d ' ')" -eq 1 ]] \
    || dev_die "${artifact} projection is not header-only after full expiry"
done
wait_for_health_up
RSS_AFTER_EXPIRY_KIB="$(high_water_rss_kib)"

for artifact in "${ID_ARTIFACTS[@]}"; do
  FORMER_MAX_IDS["${artifact}"]="$(sql "SELECT COALESCE(MAX(id), 0) FROM ${artifact}_history;" | tail -1)"
done
sql 'SELECT next_value FROM lifecycle_id_allocator ORDER BY singleton_id;
     SELECT artifact || "=" || next_value FROM artifact_id_allocator ORDER BY artifact;' \
  > "${WORKSPACE}/allocators-before-retention.txt"

stop_runtime
RETENTION_STARTED_MS="$(now_ms)"
start_runtime "${PURGE_RETENTION}"

RETENTION_WAIT_DEADLINE=$((SECONDS + TIMEOUT))
while (( SECONDS < RETENTION_WAIT_DEADLINE )); do
  if [[ "$(history_total)" -eq 0 && "$(history_sources_total)" -eq 0 ]]; then
    break
  fi
  sleep 1
done
[[ "$(history_total)" -eq 0 ]] || dev_die "history retention did not drain within ${TIMEOUT}s"
[[ "$(history_sources_total)" -eq 0 ]] \
  || dev_die "history source summaries did not cascade-delete"
[[ "$(active_total)" -eq 0 ]] || dev_die "history retention changed the empty active set"
RETENTION_DRAINED_MS="$(now_ms)"
RETENTION_ELAPSED_MS=$((RETENTION_DRAINED_MS - RETENTION_STARTED_MS))
(( RETENTION_ELAPSED_MS <= MAX_RETENTION_SECONDS * 1000 )) \
  || dev_die "history retention took ${RETENTION_ELAPSED_MS}ms (limit: ${MAX_RETENTION_SECONDS}s)"

sql 'SELECT next_value FROM lifecycle_id_allocator ORDER BY singleton_id;
     SELECT artifact || "=" || next_value FROM artifact_id_allocator ORDER BY artifact;' \
  > "${WORKSPACE}/allocators-after-retention.txt"
cmp -s "${WORKSPACE}/allocators-before-retention.txt" "${WORKSPACE}/allocators-after-retention.txt" \
  || dev_die "history retention changed durable ID allocator state"
wait_for_health_up

REAPPEAR_FIXTURE="${WORKSPACE}/reappearance.html"
java "${SCRIPT_DIR}/GenerateIocFixture.java" \
  --size 60 --seed "$((SEED + 1))" --duplicate-rate 0 --defang-rate 0.35 \
  --output "${REAPPEAR_FIXTURE}" > "${WORKSPACE}/reappearance-fixture.log"
"${SCRIPT_DIR}/submit.sh" --workspace "${WORKSPACE}" "${REAPPEAR_FIXTURE}" >/dev/null
wait_for_done "reappearance.html"
for artifact in "${ID_ARTIFACTS[@]}"; do
  NEW_MIN_ID="$(sql "SELECT COALESCE(MIN(id), 0) FROM ${artifact};" | tail -1)"
  (( NEW_MIN_ID > FORMER_MAX_IDS["${artifact}"] )) \
    || dev_die "${artifact} reused a public ID after history deletion"
done
EXPECTED_EXPORTED_ROWS="$(sql 'SELECT (SELECT COUNT(*) FROM masks)
  + (SELECT COUNT(*) FROM ip_list) + (SELECT COUNT(*) FROM hashes);' | tail -1)"
wait_for_latest_export_rows "${EXPECTED_EXPORTED_ROWS}"
wait_for_slice_count "$((SLICES_BEFORE_EXPIRY + 1))"
LATEST_SLICE="$(latest_slice_dir)"
ACTUAL_EXPORTED_ROWS="$(jq '[.artifacts[].rows] | add // 0' "${LATEST_SLICE}/manifest.json")"
[[ "${ACTUAL_EXPORTED_ROWS}" -eq "${EXPECTED_EXPORTED_ROWS}" ]] \
  || dev_die "new-row export contains ${ACTUAL_EXPORTED_ROWS} rows, expected ${EXPECTED_EXPORTED_ROWS} active rows"
wait_for_health_up
RSS_FINAL_KIB="$(high_water_rss_kib)"

EXPIRED_CYCLES="$(sql "SELECT COUNT(*) FROM lifecycle_reconcile_cycle
  WHERE cycle_id > ${BASE_CYCLE_ID} AND expired_count > 0;" | tail -1)"
EXPIRED_ACCOUNTED="$(sql "SELECT COALESCE(SUM(expired_count), 0) FROM lifecycle_reconcile_cycle
  WHERE cycle_id > ${BASE_CYCLE_ID};" | tail -1)"
[[ "${EXPIRED_ACCOUNTED}" -eq "${TOTAL_ACTIVE}" ]] \
  || dev_die "reconcile journal expired ${EXPIRED_ACCOUNTED}, expected ${TOTAL_ACTIVE}"
EXPECTED_EXPIRY_BATCHES=0
for artifact in "${ARTIFACTS[@]}"; do
  count="${ACTIVE_COUNTS[${artifact}]}"
  (( EXPECTED_EXPIRY_BATCHES += (count + BATCH_SIZE - 1) / BATCH_SIZE ))
done
OBSERVED_MAX_RSS_KIB="${RSS_AFTER_INGEST_KIB}"
(( RSS_AFTER_EXPIRY_KIB > OBSERVED_MAX_RSS_KIB )) && OBSERVED_MAX_RSS_KIB="${RSS_AFTER_EXPIRY_KIB}"
(( RSS_FINAL_KIB > OBSERVED_MAX_RSS_KIB )) && OBSERVED_MAX_RSS_KIB="${RSS_FINAL_KIB}"
(( OBSERVED_MAX_RSS_KIB <= MAX_RSS_KIB )) \
  || dev_die "JVM VmHWM ${OBSERVED_MAX_RSS_KIB} KiB exceeded ${MAX_RSS_KIB} KiB"

{
  printf '# Canonical lifecycle smoke report\n\n'
  printf -- '- commit: `%s`\n' "$(git -C "${DEV_REPO_ROOT}" rev-parse HEAD)"
  printf -- '- worktree dirty: `%s`\n' "$(if git -C "${DEV_REPO_ROOT}" diff --quiet && git -C "${DEV_REPO_ROOT}" diff --cached --quiet; then printf false; else printf true; fi)"
  printf -- '- jar SHA-256: `%s`\n' "$(sha256sum "${JAR}" | awk '{print $1}')"
  printf -- '- host: `%s`\n' "$(uname -srmo)"
  printf -- '- Java: `%s`\n' "$(java -version 2>&1 | head -1)"
  printf -- '- SQLite: `%s`\n' "$(sqlite3 --version)"
  printf -- '- CPUs / memory KiB: `%s / %s`\n\n' "$(nproc)" "$(awk '/MemTotal/ {print $2}' /proc/meminfo)"
  printf '## Scenario\n\n'
  printf -- '- fixture input rows: `%s`\n' "${SIZE}"
  printf -- '- canonical active rows: `%s`\n' "${TOTAL_ACTIVE}"
  for artifact in "${ARTIFACTS[@]}"; do
    printf -- '  - `%s`: `%s`\n' "${artifact}" "${ACTIVE_COUNTS[${artifact}]}"
  done
  printf -- '- fixed TTL / deadline spread: `%s / %sms`\n' "${TTL}" "${DEADLINE_SPREAD_MS}"
  printf -- '- history retention phases: `%s`, then `%s`\n' \
    "${HISTORY_RETENTION}" "${PURGE_RETENTION}"
  printf -- '- backstop / batch size: `%s / %s`\n' "${BACKSTOP}" "${BATCH_SIZE}"
  printf -- '- new-data export quiet period: `%s`\n\n' "${EXPORT_QUIET_PERIOD}"
  printf '## Measurements\n\n'
  printf -- '- ingest-to-done: `%sms`\n' "${INGEST_ELAPSED_MS}"
  printf -- '- expiry start latency from earliest deadline: `%sms`\n' "${EXPIRY_START_LATENCY_MS}"
  printf -- '- drain latency after latest deadline: `%sms`\n' "${EXPIRY_DRAIN_LATENCY_MS}"
  printf -- '- measured archive/drain throughput: `%s rows/s`\n' "${EXPIRY_ROWS_PER_SECOND}"
  printf -- '- reconcile cycles with expired rows: `%s`\n' "${EXPIRED_CYCLES}"
  printf -- '- minimum bounded expiry transactions: `%s`\n' "${EXPECTED_EXPIRY_BATCHES}"
  printf -- '- history-retention restart-to-drain: `%sms`\n' "${RETENTION_ELAPSED_MS}"
  printf -- '- maximum observed JVM VmHWM: `%s KiB` (limit `%s KiB`)\n' \
    "${OBSERVED_MAX_RSS_KIB}" "${MAX_RSS_KIB}"
  printf -- '- configured throughput floor: `%s rows/s`\n\n' "${MIN_EXPIRY_ROWS_PER_SECOND}"
  printf -- '- configured canonical/deadline/retention thresholds: `%s rows / %sms / %ss`\n\n' \
    "${MIN_CANONICAL_ROWS}" "${MAX_DEADLINE_SPREAD_MS}" "${MAX_RETENTION_SECONDS}"
  printf '## Assertions\n\n'
  printf '%s\n' \
    '- lifecycle health returned `UP` after admission, expiry, retention and reappearance;' \
    '- every active row moved to typed `EXPIRED` history with a source summary;' \
    '- mutable projections converged to header-only output;' \
    '- expiry left `artifact_revision` unchanged;' \
    '- expiry created no immutable slice; later new rows exported exact active membership;' \
    '- short retention removed history and source summaries without changing active rows or allocators;' \
    '- a later accepted source allocated public IDs above every former ID.'
  printf '\n## Query plans\n\n```text\n'
  cat "${QUERY_PLANS}"
  printf '```\n'
} > "${REPORT}"

stop_runtime
dev_log "canonical lifecycle smoke passed"
dev_log "report: ${REPORT}"
printf '%s\n' "${REPORT}"
