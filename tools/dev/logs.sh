#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${SCRIPT_DIR}/common.sh"

LOG_FILE="${DEV_ROOT}/runtime/var/logs/ioc-extractor.ecs.json"
FOLLOW="false"

usage() {
  cat <<'EOF'
Usage: tools/dev/logs.sh [--file PATH] [--follow] COMMAND [VALUE]

Commands:
  raw                 Print ECS JSON lines unchanged
  pretty              Pretty-print every JSON event
  errors              Select ERROR/FATAL log levels
  event ACTION        Select event.action
  run RUN_ID          Select ioc.run.id
  diagnostic CODE     Select ioc.diagnostic.code
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file) LOG_FILE="${2:?}"; shift 2 ;;
    --follow) FOLLOW="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) break ;;
  esac
done

COMMAND="${1:-}"
[[ -n "${COMMAND}" ]] || { usage >&2; exit 2; }
shift
[[ -f "${LOG_FILE}" && ! -L "${LOG_FILE}" ]] \
  || dev_die "ECS log file not found or unsafe: ${LOG_FILE}"

read_log() {
  if [[ "${FOLLOW}" == "true" ]]; then
    tail -n +1 -F -- "${LOG_FILE}"
  else
    cat -- "${LOG_FILE}"
  fi
}

case "${COMMAND}" in
  raw) read_log ;;
  pretty)
    dev_require_command jq
    read_log | jq .
    ;;
  errors)
    dev_require_command jq
    read_log | jq -c 'select(.log.level == "ERROR" or .log.level == "FATAL")'
    ;;
  event)
    dev_require_command jq
    VALUE="${1:?event action is required}"
    read_log | jq -c --arg value "${VALUE}" 'select(.event.action == $value)'
    ;;
  run)
    dev_require_command jq
    VALUE="${1:?run id is required}"
    read_log | jq -c --arg value "${VALUE}" 'select(.ioc.run.id == $value)'
    ;;
  diagnostic)
    dev_require_command jq
    VALUE="${1:?diagnostic code is required}"
    read_log | jq -c --arg value "${VALUE}" 'select(.ioc.diagnostic.code == $value)'
    ;;
  *) dev_die "unknown logs command: ${COMMAND}" ;;
esac
