#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${SCRIPT_DIR}/common.sh"

WORKSPACE="${DEV_ROOT}/runtime"
PORT="18081"
JAR=""
HEALTH_ATTEMPTS="30"
HEALTH_INTERVAL="1"
JAVA_PROPERTIES=()
JVM_OPTIONS=()

usage() {
  cat <<'EOF'
Usage: tools/dev/runtime.sh [OPTIONS] up|down|status|reset

Options:
  --workspace PATH          Workspace below repo-local .dev (default: .dev/runtime)
  --port PORT               Loopback actuator port (default: 18081)
  --jar PATH                Explicit bootable jar
  --health-attempts N       Startup attempts (default: 30)
  --health-interval N       Seconds between attempts (default: 1)
  --set KEY=VALUE           Additional JVM system property (repeatable)
  --jvm-arg ARG             Safe JVM memory/runtime option (repeatable)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --workspace) WORKSPACE="${2:?}"; shift 2 ;;
    --port) PORT="${2:?}"; shift 2 ;;
    --jar) JAR="${2:?}"; shift 2 ;;
    --health-attempts) HEALTH_ATTEMPTS="${2:?}"; shift 2 ;;
    --health-interval) HEALTH_INTERVAL="${2:?}"; shift 2 ;;
    --set) JAVA_PROPERTIES+=("${2:?}"); shift 2 ;;
    --jvm-arg) JVM_OPTIONS+=("${2:?}"); shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) break ;;
  esac
done

COMMAND="${1:-}"
[[ -n "${COMMAND}" && $# -eq 1 ]] || { usage >&2; exit 2; }
[[ "${WORKSPACE}" == /* ]] || WORKSPACE="${DEV_REPO_ROOT}/${WORKSPACE}"
dev_validate_workspace "${WORKSPACE}"
WORKSPACE="${DEV_VALIDATED_WORKSPACE}"
dev_validate_port "${PORT}" || dev_die "port must be an integer in 1..65535"
for value in "${HEALTH_ATTEMPTS}" "${HEALTH_INTERVAL}"; do
  [[ "${value}" =~ ^[1-9][0-9]*$ ]] || dev_die "health timing values must be positive integers"
done
for property in "${JAVA_PROPERTIES[@]}"; do
  [[ "${property}" =~ ^([A-Za-z0-9_.-]|\[[0-9]+\])+=[^[:cntrl:]]*$ ]] \
    || dev_die "invalid --set property: ${property}"
done
for option in "${JVM_OPTIONS[@]}"; do
  [[ "${option}" =~ ^-X(ms|mx)[1-9][0-9]*[kKmMgG]$ \
      || "${option}" =~ ^-XX:[+-][A-Za-z][A-Za-z0-9]*$ ]] \
    || dev_die "unsupported --jvm-arg; use --set for system properties: ${option}"
done

RUN_DIR="${WORKSPACE}/run"
PID_FILE="${RUN_DIR}/daemon.pid"
PORT_FILE="${RUN_DIR}/daemon.port"
JAR_FILE="${RUN_DIR}/daemon.jar"
CONSOLE_LOG="${WORKSPACE}/var/logs/console.log"

read_pid() {
  [[ -f "${PID_FILE}" && ! -L "${PID_FILE}" ]] || return 1
  local pid
  pid="$(<"${PID_FILE}")"
  [[ "${pid}" =~ ^[1-9][0-9]*$ ]] || return 1
  printf '%s\n' "${pid}"
}

read_recorded_jar() {
  [[ -f "${JAR_FILE}" && ! -L "${JAR_FILE}" ]] || return 1
  local recorded
  recorded="$(<"${JAR_FILE}")"
  [[ "${recorded}" == /* ]] || return 1
  printf '%s\n' "${recorded}"
}

stop_runtime() {
  local pid recorded_jar attempt
  if ! pid="$(read_pid)"; then
    rm -f -- "${PID_FILE}"
    dev_log "runtime is not running"
    return
  fi
  if ! recorded_jar="$(read_recorded_jar)" || ! dev_pid_matches_jar "${pid}" "${recorded_jar}"; then
    dev_die "refusing to signal PID ${pid}: it does not match the recorded application jar"
  fi

  dev_log "stopping daemon PID ${pid}"
  kill -TERM "${pid}"
  for ((attempt = 1; attempt <= 20; attempt++)); do
    if ! kill -0 "${pid}" 2>/dev/null; then
      rm -f -- "${PID_FILE}"
      return
    fi
    sleep 1
  done
  dev_warn "daemon did not stop after 20 seconds; sending SIGKILL to validated PID ${pid}"
  kill -KILL "${pid}"
  rm -f -- "${PID_FILE}"
}

case "${COMMAND}" in
  up)
    dev_require_java21
    dev_require_command curl
    dev_resolve_app_jar "${JAR}"
    dev_prepare_workspace "${WORKSPACE}"
    mkdir -p -- "${RUN_DIR}" "${WORKSPACE}/var/logs"
    if EXISTING_PID="$(read_pid 2>/dev/null)"; then
      if dev_pid_matches_jar "${EXISTING_PID}" "$(read_recorded_jar 2>/dev/null || true)"; then
        dev_die "runtime is already running with PID ${EXISTING_PID}"
      fi
      rm -f -- "${PID_FILE}"
    fi

    JVM_ARGS=(
      "-Dioc.runtime.mode=daemon"
      "-Dioc.observability.mode=daemon"
      "-Dspring.main.web-application-type=servlet"
      "-Dserver.address=127.0.0.1"
      "-Dserver.port=${PORT}"
    )
    for property in "${JAVA_PROPERTIES[@]}"; do
      JVM_ARGS+=("-D${property}")
    done

    printf '%s\n' "${PORT}" > "${PORT_FILE}"
    printf '%s\n' "${DEV_APP_JAR}" > "${JAR_FILE}"
    (
      cd "${WORKSPACE}"
      # Spring Boot treats ambient DEBUG/TRACE as framework switches. Developer
      # verbosity must be explicit through --set, not inherited accidentally.
      unset DEBUG TRACE
      nohup java "${JVM_OPTIONS[@]}" "${JVM_ARGS[@]}" -jar "${DEV_APP_JAR}" \
        > "${CONSOLE_LOG}" 2>&1 &
      printf '%s\n' "$!" > "${PID_FILE}"
    )
    PID="$(read_pid)"
    for ((attempt = 1; attempt <= HEALTH_ATTEMPTS; attempt++)); do
      if dev_health_ready "${PORT}"; then
        dev_log "daemon is healthy: pid=${PID} port=${PORT} workspace=${WORKSPACE}"
        exit 0
      fi
      if ! kill -0 "${PID}" 2>/dev/null; then
        tail -n 80 -- "${CONSOLE_LOG}" >&2 || true
        rm -f -- "${PID_FILE}"
        dev_die "daemon exited before becoming healthy"
      fi
      (( attempt == HEALTH_ATTEMPTS )) || sleep "${HEALTH_INTERVAL}"
    done
    tail -n 80 -- "${CONSOLE_LOG}" >&2 || true
    stop_runtime || true
    dev_die "daemon did not become healthy after ${HEALTH_ATTEMPTS} attempts"
    ;;
  down) stop_runtime ;;
  status)
    if ! PID="$(read_pid)" || ! RECORDED_JAR="$(read_recorded_jar)" \
        || ! dev_pid_matches_jar "${PID}" "${RECORDED_JAR}"; then
      echo "STOPPED workspace=${WORKSPACE}"
      exit 1
    fi
    RECORDED_PORT="$(<"${PORT_FILE}")"
    if dev_health_ready "${RECORDED_PORT}"; then
      echo "HEALTHY pid=${PID} port=${RECORDED_PORT} workspace=${WORKSPACE}"
    else
      echo "RUNNING_UNHEALTHY pid=${PID} port=${RECORDED_PORT} workspace=${WORKSPACE}"
      exit 1
    fi
    ;;
  reset)
    if [[ -f "${PID_FILE}" ]]; then
      stop_runtime
    fi
    dev_reset_workspace "${WORKSPACE}"
    dev_log "reset workspace ${WORKSPACE}"
    ;;
  *) dev_die "unknown runtime command: ${COMMAND}" ;;
esac
