#!/usr/bin/env bash
# shellcheck disable=SC2016 # Markdown backticks in report text are literal.
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${SCRIPT_DIR}/common.sh"

SIZE="100000"
DUPLICATE_RATE="0.95"
SEED="73001"
TIMEOUT="900"
MAX_RSS_KIB="1048576"
MAX_SLOWDOWN="2.0"
PORT="18086"
WORKSPACE="${DEV_ROOT}/ioc-aggregate-load"
CANDIDATE_JAR=""
BASELINE_JAR=""
RUNNING_WORKSPACE=""

usage() {
  cat <<'EOF'
Usage: tools/dev/ioc-aggregate-load.sh [OPTIONS]

Options:
  --candidate-jar PATH    Candidate bootable jar (default: reactor jar)
  --baseline-jar PATH     Optional pre-feature bootable jar for comparison
  --size N                IOC-bearing observations (default: 100000)
  --duplicate-rate 0..1   Duplicate probability (default: 0.95)
  --seed N                Deterministic fixture seed (default: 73001)
  --timeout SECONDS       Per-profile completion timeout (default: 900)
  --max-rss-kib N         Candidate VmHWM ceiling (default: 1 GiB)
  --max-slowdown RATIO    Candidate/baseline elapsed ceiling (default: 2.0)
  --port PORT             Candidate actuator port; baseline uses PORT+1
  --workspace PATH        Evidence directory below repo-local .dev

The harness sends the same duplicate-heavy HTML document through the public
daemon ingestion path. It records end-to-end and WRITE_ARTIFACTS latency, peak
RSS, database size and indexed query plans. When a baseline jar is supplied,
the candidate must remain within the configured slowdown envelope.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --candidate-jar) CANDIDATE_JAR="${2:?}"; shift 2 ;;
    --baseline-jar) BASELINE_JAR="${2:?}"; shift 2 ;;
    --size) SIZE="${2:?}"; shift 2 ;;
    --duplicate-rate) DUPLICATE_RATE="${2:?}"; shift 2 ;;
    --seed) SEED="${2:?}"; shift 2 ;;
    --timeout) TIMEOUT="${2:?}"; shift 2 ;;
    --max-rss-kib) MAX_RSS_KIB="${2:?}"; shift 2 ;;
    --max-slowdown) MAX_SLOWDOWN="${2:?}"; shift 2 ;;
    --port) PORT="${2:?}"; shift 2 ;;
    --workspace) WORKSPACE="${2:?}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; dev_die "unknown IOC aggregate load option: $1" ;;
  esac
done

for value in "${SIZE}" "${TIMEOUT}" "${MAX_RSS_KIB}"; do
  [[ "${value}" =~ ^[1-9][0-9]*$ ]] \
    || dev_die "size, timeout and max-rss-kib must be positive integers"
done
[[ "${SEED}" =~ ^-?[0-9]+$ ]] || dev_die "seed must be an integer"
[[ "${DUPLICATE_RATE}" =~ ^(0([.][0-9]+)?|1([.]0+)?)$ ]] \
  || dev_die "duplicate-rate must be in 0..1"
[[ "${MAX_SLOWDOWN}" =~ ^[1-9][0-9]*([.][0-9]+)?$ ]] \
  || dev_die "max-slowdown must be at least 1.0"
dev_validate_port "${PORT}" || dev_die "port must be an integer in 1..65535"
BASELINE_PORT=$((PORT + 1))
dev_validate_port "${BASELINE_PORT}" || dev_die "baseline port exceeds 65535"

dev_require_java21
for command in curl jq sqlite3 sha256sum awk unzip; do
  dev_require_command "${command}"
done
dev_resolve_app_jar "${CANDIDATE_JAR}"
CANDIDATE_JAR="${DEV_APP_JAR}"
if [[ -n "${BASELINE_JAR}" ]]; then
  BASELINE_JAR="$(realpath -e -- "${BASELINE_JAR}")" \
    || dev_die "baseline jar does not exist"
  [[ -f "${BASELINE_JAR}" && ! -L "${BASELINE_JAR}" ]] \
    || dev_die "baseline jar must be a regular non-symlink file"
fi
[[ "${WORKSPACE}" == /* ]] || WORKSPACE="${DEV_REPO_ROOT}/${WORKSPACE}"
dev_validate_workspace "${WORKSPACE}"
WORKSPACE="${DEV_VALIDATED_WORKSPACE}"

cleanup() {
  if [[ -n "${RUNNING_WORKSPACE}" ]]; then
    "${SCRIPT_DIR}/runtime.sh" --workspace "${RUNNING_WORKSPACE}" down \
      >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

now_ms() {
  date +%s%3N
}

sql() { # workspace statement
  sqlite3 -readonly -batch -noheader -cmd '.timeout 5000' \
    "$1/var/db/ioc-dataframe.db" "$2"
}

jar_identity() {
  unzip -p "$1" META-INF/build-info.properties 2>/dev/null \
    | awk -F= '/^build.commit=|^build.version=|^build.time=/ {printf "%s%s", separator, $0; separator=","}'
}

run_profile() { # name jar port aggregate-required
  local name="$1" jar="$2" port="$3" aggregate_required="$4"
  local runtime="${WORKSPACE}/${name}" pid start_ms end_ms deadline done_file=""
  local rss elapsed write_nanos errors db_bytes projection_rows="0"

  "${SCRIPT_DIR}/runtime.sh" --workspace "${runtime}" reset >/dev/null
  "${SCRIPT_DIR}/runtime.sh" \
    --workspace "${runtime}" --port "${port}" --jar "${jar}" \
    --health-attempts 60 \
    --jvm-arg -Xms128m --jvm-arg -Xmx512m --jvm-arg -XX:+ExitOnOutOfMemoryError \
    --set ioc.lifecycle.validity.mode=fixed \
    --set ioc.lifecycle.validity.fixed-ttl=12h \
    --set ioc.lifecycle.validity.existing-records=reject \
    --set ioc.ingestion.detect.use-watch-service=false \
    --set ioc.ingestion.detect.reconcile-interval=1s \
    --set ioc.ingestion.stability.quiet-period=1s \
    --set ioc.pipeline.max-diagnostics-per-run=100 \
    --set logging.level.com.iocextractor.observability.logging.LoggingPipelineObserver=DEBUG \
    up
  RUNNING_WORKSPACE="${runtime}"
  pid="$(<"${runtime}/run/daemon.pid")"

  start_ms="$(now_ms)"
  "${SCRIPT_DIR}/submit.sh" --workspace "${runtime}" "${WORKSPACE}/fixture.html" \
    > "${runtime}/submit.log"
  deadline=$((SECONDS + TIMEOUT))
  while (( SECONDS < deadline )); do
    done_file="$(find "${runtime}/var/done" -maxdepth 1 -type f \
      -name '*fixture.html*' -print -quit 2>/dev/null || true)"
    [[ -n "${done_file}" ]] && break
    kill -0 "${pid}" 2>/dev/null \
      || dev_die "${name} daemon exited before completing the load profile"
    sleep 1
  done
  [[ -n "${done_file}" ]] \
    || dev_die "${name} did not complete the load profile within ${TIMEOUT}s"
  end_ms="$(now_ms)"
  elapsed=$((end_ms - start_ms))
  rss="$(awk '/^VmHWM:/ {print $2}' "/proc/${pid}/status")"
  dev_health_ready "${port}" || dev_die "${name} became unhealthy after ingestion"

  errors="$(jq -s '[.[] | select(.log.level == "ERROR" or .log.level == "FATAL")] | length' \
    "${runtime}/var/logs/ioc-extractor.ecs.json")"
  [[ "${errors}" -eq 0 ]] || dev_die "${name} emitted ${errors} ERROR/FATAL events"
  write_nanos="$(jq -r 'select(.event.action == "stage_complete"
      and .event.outcome == "success" and .ioc.stage == "WRITE_ARTIFACTS")
      | .event.duration' "${runtime}/var/logs/ioc-extractor.ecs.json" | tail -1)"
  [[ "${write_nanos}" =~ ^[0-9]+$ ]] \
    || dev_die "${name} did not emit WRITE_ARTIFACTS duration evidence"
  if [[ "${aggregate_required}" == "true" ]]; then
    [[ -s "${runtime}/dataframe/IOC_aggregate_generated.csv" ]] \
      || dev_die "candidate aggregate projection is missing"
    local rows invalid_carriers empty_names origins registrations terminal
    rows="$(sql "${runtime}" 'SELECT COUNT(*) FROM ioc_aggregate;')"
    invalid_carriers="$(sql "${runtime}" 'SELECT COUNT(*) FROM ioc_aggregate
      WHERE (ip_address IS NOT NULL) + (url_match IS NOT NULL)
          + (host_match IS NOT NULL) + (hash IS NOT NULL) <> 1;')"
    empty_names="$(sql "${runtime}" "SELECT COUNT(*) FROM ioc_aggregate
      WHERE name IS NULL OR trim(name) = '';" )"
    origins="$(sql "${runtime}" "SELECT COUNT(*) FROM canonical_lifecycle_field_origin
      WHERE artifact = 'ioc_aggregate' AND field_name = 'name';")"
    registrations="$(sql "${runtime}" 'SELECT COUNT(*) FROM registered_observation;')"
    terminal="$(sql "${runtime}" 'SELECT COUNT(*) FROM registered_observation
      WHERE terminal_at_ms IS NOT NULL;')"
    projection_rows=$(( $(wc -l < "${runtime}/dataframe/IOC_aggregate_generated.csv") - 1 ))
    (( rows > 0 )) || dev_die "candidate aggregate table is empty"
    [[ "${invalid_carriers}" -eq 0 ]] || dev_die "candidate emitted ambiguous aggregate carriers"
    [[ "${empty_names}" -eq 0 ]] || dev_die "candidate emitted empty aggregate names"
    [[ "${origins}" -eq "${rows}" ]] || dev_die "candidate field-origin count differs from rows"
    [[ "${registrations}" -eq 1 && "${terminal}" -eq 1 ]] \
      || dev_die "candidate document registration did not reach terminal state"
    [[ "${projection_rows}" -eq "${rows}" ]] \
      || dev_die "candidate projection row count differs from canonical storage"

    {
      printf '[registration-status]\n'
      sql "${runtime}" 'EXPLAIN QUERY PLAN SELECT COUNT(*) FROM registered_observation
        WHERE terminal_at_ms IS NULL;'
      printf '[field-origin]\n'
      sql "${runtime}" "EXPLAIN QUERY PLAN SELECT admission_order, occurrence_position
        FROM canonical_lifecycle_field_origin
        WHERE artifact = 'ioc_aggregate' AND lifecycle_id = 1 AND field_name = 'name';"
      printf '[registration-retention]\n'
      sql "${runtime}" "EXPLAIN QUERY PLAN SELECT occurrence_id FROM registered_observation
        WHERE terminal_at_ms IS NOT NULL AND terminal_at_ms < ${end_ms}
        ORDER BY terminal_at_ms, admission_order LIMIT 1000;"
    } > "${runtime}/query-plans.txt"
    grep -Eq 'USING (COVERING )?INDEX ix_registered_observation_terminal' \
      "${runtime}/query-plans.txt" \
      || dev_die "candidate registration query plans do not use the terminal index"
    grep -Eq 'USING INDEX sqlite_autoindex_canonical_lifecycle_field_origin_1' \
      "${runtime}/query-plans.txt" \
      || dev_die "candidate field-origin query does not use its primary index"
  fi

  "${SCRIPT_DIR}/runtime.sh" --workspace "${runtime}" down >/dev/null
  RUNNING_WORKSPACE=""
  db_bytes="$(stat -c %s "${runtime}/var/db/ioc-dataframe.db")"

  {
    printf 'profile=%s\n' "${name}"
    printf 'jar=%s\n' "${jar}"
    printf 'jar_sha256=%s\n' "$(sha256sum "${jar}" | awk '{print $1}')"
    printf 'jar_identity=%s\n' "$(jar_identity "${jar}")"
    printf 'elapsed_ms=%s\n' "${elapsed}"
    printf 'write_artifacts_nanos=%s\n' "${write_nanos}"
    printf 'vm_hwm_kib=%s\n' "${rss}"
    printf 'dataframe_db_bytes=%s\n' "${db_bytes}"
    printf 'aggregate_projection_rows=%s\n' "${projection_rows}"
    printf 'error_events=%s\n' "${errors}"
  } > "${runtime}/metrics.env"
}

read_metric() { # metrics file key
  awk -F= -v key="$2" '$1 == key {print substr($0, index($0, "=") + 1); found=1} END {exit !found}' "$1"
}

dev_log "resetting IOC aggregate load workspace ${WORKSPACE}"
dev_reset_workspace "${WORKSPACE}"
mkdir -p -- "${WORKSPACE}"
java "${SCRIPT_DIR}/GenerateIocFixture.java" \
  --size "${SIZE}" --seed "${SEED}" --duplicate-rate "${DUPLICATE_RATE}" \
  --defang-rate 0.35 --output "${WORKSPACE}/fixture.html" \
  --manifest "${WORKSPACE}/fixture.manifest.json" > "${WORKSPACE}/fixture.log"

if [[ -n "${BASELINE_JAR}" ]]; then
  dev_log "running deployed baseline profile"
  run_profile baseline "${BASELINE_JAR}" "${BASELINE_PORT}" false
fi
dev_log "running IOC aggregate candidate profile"
run_profile candidate "${CANDIDATE_JAR}" "${PORT}" true

CANDIDATE_ELAPSED="$(read_metric "${WORKSPACE}/candidate/metrics.env" elapsed_ms)"
CANDIDATE_WRITE="$(read_metric "${WORKSPACE}/candidate/metrics.env" write_artifacts_nanos)"
CANDIDATE_RSS="$(read_metric "${WORKSPACE}/candidate/metrics.env" vm_hwm_kib)"
(( CANDIDATE_RSS <= MAX_RSS_KIB )) \
  || dev_die "candidate VmHWM ${CANDIDATE_RSS} KiB exceeds ${MAX_RSS_KIB} KiB"

BASELINE_ELAPSED=""
BASELINE_WRITE=""
ELAPSED_RATIO=""
WRITE_RATIO=""
if [[ -n "${BASELINE_JAR}" ]]; then
  BASELINE_ELAPSED="$(read_metric "${WORKSPACE}/baseline/metrics.env" elapsed_ms)"
  BASELINE_WRITE="$(read_metric "${WORKSPACE}/baseline/metrics.env" write_artifacts_nanos)"
  ELAPSED_RATIO="$(awk -v candidate="${CANDIDATE_ELAPSED}" -v baseline="${BASELINE_ELAPSED}" \
    'BEGIN {printf "%.3f", candidate / baseline}')"
  WRITE_RATIO="$(awk -v candidate="${CANDIDATE_WRITE}" -v baseline="${BASELINE_WRITE}" \
    'BEGIN {printf "%.3f", candidate / baseline}')"
  awk -v ratio="${ELAPSED_RATIO}" -v maximum="${MAX_SLOWDOWN}" \
    'BEGIN {exit !(ratio <= maximum)}' \
    || dev_die "candidate elapsed slowdown ${ELAPSED_RATIO} exceeds ${MAX_SLOWDOWN}"
fi

REPORT="${WORKSPACE}/report.md"
{
  printf '# IOC aggregate duplicate-heavy load evidence\n\n'
  printf -- '- generated_at: `%s`\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf -- '- git_commit: `%s`\n' "$(git -C "${DEV_REPO_ROOT}" rev-parse HEAD)"
  printf -- '- worktree_fingerprint: `%s`\n' "$(dev_git_worktree_fingerprint)"
  printf -- '- host: `%s`\n' "$(uname -srvmo)"
  printf -- '- cpu: `%s`\n' "$(LC_ALL=C lscpu | awk -F: '/^Model name:/ {sub(/^[[:space:]]+/, "", $2); print $2; exit}')"
  printf -- '- java: `%s`\n' "$(java -version 2>&1 | head -1)"
  printf -- '- filesystem: `%s`\n' "$(findmnt -no FSTYPE -T "${WORKSPACE}")"
  printf -- '- input_rows: `%s`\n' "${SIZE}"
  printf -- '- duplicate_rate: `%s`\n' "${DUPLICATE_RATE}"
  printf -- '- seed: `%s`\n\n' "${SEED}"
  printf '| profile | elapsed ms | WRITE_ARTIFACTS ns | VmHWM KiB | dataframe DB bytes | aggregate rows |\n'
  printf '|---|---:|---:|---:|---:|---:|\n'
  if [[ -n "${BASELINE_JAR}" ]]; then
    awk -F= 'BEGIN {OFS="|"} /^(elapsed_ms|write_artifacts_nanos|vm_hwm_kib|dataframe_db_bytes|aggregate_projection_rows)=/ {value[$1]=$2}
      END {printf "| baseline | %s | %s | %s | %s | %s |\n", value["elapsed_ms"], value["write_artifacts_nanos"], value["vm_hwm_kib"], value["dataframe_db_bytes"], value["aggregate_projection_rows"]}' \
      "${WORKSPACE}/baseline/metrics.env"
  fi
  awk -F= '/^(elapsed_ms|write_artifacts_nanos|vm_hwm_kib|dataframe_db_bytes|aggregate_projection_rows)=/ {value[$1]=$2}
    END {printf "| candidate | %s | %s | %s | %s | %s |\n", value["elapsed_ms"], value["write_artifacts_nanos"], value["vm_hwm_kib"], value["dataframe_db_bytes"], value["aggregate_projection_rows"]}' \
    "${WORKSPACE}/candidate/metrics.env"
  if [[ -n "${BASELINE_JAR}" ]]; then
    printf '\n- candidate/baseline elapsed ratio: `%s` (limit `%s`)\n' "${ELAPSED_RATIO}" "${MAX_SLOWDOWN}"
    printf -- '- candidate/baseline WRITE_ARTIFACTS ratio: `%s`\n' "${WRITE_RATIO}"
  fi
  printf -- '- candidate VmHWM: `%s KiB` (limit `%s KiB`)\n' "${CANDIDATE_RSS}" "${MAX_RSS_KIB}"
  printf '\nQuery plans: `candidate/query-plans.txt`. Complete ECS and console logs are retained per profile.\n'
} > "${REPORT}"

dev_log "IOC aggregate duplicate-heavy qualification passed"
dev_log "report: ${REPORT}"
