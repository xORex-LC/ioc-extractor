#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${SCRIPT_DIR}/common.sh"

WORKSPACE="${DEV_ROOT}/runtime"
DB="dataframe"

usage() {
  cat <<'EOF'
Usage: tools/dev/database.sh [--workspace PATH] [--db service|dataframe] COMMAND

Commands:
  shell    Open sqlite3 in read-only mode
  schema   Print PRAGMA user_version and complete schema
  tables   List tables and views
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --workspace) WORKSPACE="${2:?}"; shift 2 ;;
    --db) DB="${2:?}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) break ;;
  esac
done
COMMAND="${1:-}"
[[ -n "${COMMAND}" && $# -eq 1 ]] || { usage >&2; exit 2; }
case "${DB}" in
  service) DB_NAME="ioc-service.db" ;;
  dataframe) DB_NAME="ioc-dataframe.db" ;;
  *) dev_die "database must be service or dataframe" ;;
esac

[[ "${WORKSPACE}" == /* ]] || WORKSPACE="${DEV_REPO_ROOT}/${WORKSPACE}"
dev_validate_workspace "${WORKSPACE}"
WORKSPACE="${DEV_VALIDATED_WORKSPACE}"
DB_PATH="${WORKSPACE}/var/db/${DB_NAME}"
[[ -f "${DB_PATH}" && ! -L "${DB_PATH}" ]] \
  || dev_die "database not found or unsafe: ${DB_PATH}"
dev_require_command sqlite3

case "${COMMAND}" in
  shell) exec sqlite3 -readonly "${DB_PATH}" ;;
  schema)
    sqlite3 -readonly -header -column "${DB_PATH}" 'PRAGMA user_version;'
    sqlite3 -readonly "${DB_PATH}" '.schema'
    ;;
  tables) sqlite3 -readonly "${DB_PATH}" '.tables' ;;
  *) dev_die "unknown database command: ${COMMAND}" ;;
esac
