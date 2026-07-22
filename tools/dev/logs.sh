#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${SCRIPT_DIR}/common.sh"

WORKSPACE="${DEV_ROOT}/runtime"
LOG_FILE=""
FOLLOW="false"

usage() {
  cat <<'EOF'
Usage: tools/dev/logs.sh [--workspace PATH] [--file PATH] [--follow] COMMAND [VALUE]

Defaults:
  --workspace .dev/runtime
  --file <workspace>/var/logs/ioc-extractor.ecs.json

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
    --workspace) WORKSPACE="${2:?}"; shift 2 ;;
    --file) LOG_FILE="${2:?}"; shift 2 ;;
    --follow) FOLLOW="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) break ;;
  esac
done

COMMAND="${1:-}"
[[ -n "${COMMAND}" ]] || { usage >&2; exit 2; }
shift
[[ "${WORKSPACE}" == /* ]] || WORKSPACE="${DEV_REPO_ROOT}/${WORKSPACE}"
dev_validate_workspace "${WORKSPACE}"
WORKSPACE="${DEV_VALIDATED_WORKSPACE}"
[[ -n "${LOG_FILE}" ]] || LOG_FILE="${WORKSPACE}/var/logs/ioc-extractor.ecs.json"
[[ -f "${LOG_FILE}" && ! -L "${LOG_FILE}" ]] \
  || dev_die "ECS log file not found or unsafe: ${LOG_FILE}"

read_log() {
  if [[ "${FOLLOW}" == "true" ]]; then
    tail -n +1 -F -- "${LOG_FILE}"
  else
    cat -- "${LOG_FILE}"
  fi
}

select_field() { # logical dotted field, expected value
  local field="$1" value="$2"
  read_log | jq -c --arg field "${field}" --arg value "${value}" \
    'select(getpath($field | split(".")) == $value)'
}

case "${COMMAND}" in
  raw) read_log ;;
  pretty)
    dev_require_command jq
    read_log | jq .
    ;;
  errors)
    dev_require_command jq
    read_log | jq -c \
      'select(getpath(["log", "level"]) == "ERROR" or getpath(["log", "level"]) == "FATAL")'
    ;;
  event)
    dev_require_command jq
    VALUE="${1:?event action is required}"
    select_field "event.action" "${VALUE}"
    ;;
  run)
    dev_require_command jq
    VALUE="${1:?run id is required}"
    select_field "ioc.run.id" "${VALUE}"
    ;;
  diagnostic)
    dev_require_command jq
    VALUE="${1:?diagnostic code is required}"
    select_field "ioc.diagnostic.code" "${VALUE}"
    ;;
  *) dev_die "unknown logs command: ${COMMAND}" ;;
esac
