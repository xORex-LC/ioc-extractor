#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${SCRIPT_DIR}/common.sh"

JAR=""
WORKSPACE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar) JAR="${2:?application jar is required}"; shift 2 ;;
    --workspace) WORKSPACE="${2:?workspace is required}"; shift 2 ;;
    *) break ;;
  esac
done
[[ $# -gt 0 ]] || dev_die "application arguments are required"

dev_require_java21
dev_resolve_app_jar "${JAR}"
if [[ -n "${WORKSPACE}" ]]; then
  [[ "${WORKSPACE}" == /* ]] || WORKSPACE="${DEV_REPO_ROOT}/${WORKSPACE}"
  dev_prepare_workspace "${WORKSPACE}"
  cd "${DEV_VALIDATED_WORKSPACE}"
else
  cd "${DEV_REPO_ROOT}"
fi
unset DEBUG TRACE
exec java -jar "${DEV_APP_JAR}" "$@"
