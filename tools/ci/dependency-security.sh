#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." >/dev/null 2>&1 && pwd)"
DATA_DIR="${DEPENDENCY_CHECK_DATA:-${REPO_ROOT}/.dependency-check-data}"
COMMAND="${1:-}"

usage() {
  echo "Usage: tools/ci/dependency-security.sh update|scan|report"
}

case "${COMMAND}" in
  -h|--help) usage; exit 0 ;;
  update|scan|report) : ;;
  *) usage >&2; exit 2 ;;
esac

if [[ "${COMMAND}" == report ]]; then
  for report in \
      "${REPO_ROOT}/target/dependency-check-report.html" \
      "${REPO_ROOT}/target/dependency-check-report.json"; do
    [[ ! -f "${report}" ]] || printf '%s\n' "${report}"
  done
  exit 0
fi

[[ -n "${NVD_API_KEY:-}" ]] || {
  echo "NVD_API_KEY is required (the value is never printed)" >&2
  exit 1
}
mkdir -p -- "${DATA_DIR}"
cd "${REPO_ROOT}"

COMMON_ARGS=(
  -DdataDirectory="${DATA_DIR}"
  -DnvdApiKeyEnvironmentVariable=NVD_API_KEY
  -DassemblyAnalyzerEnabled=false
  -DossindexAnalyzerEnabled=false
)

if [[ "${COMMAND}" == update ]]; then
  exec ./mvnw -B -ntp dependency-check:update-only "${COMMON_ARGS[@]}"
fi

exec ./mvnw -B -ntp dependency-check:aggregate \
  "${COMMON_ARGS[@]}" \
  -Dformats=HTML,JSON \
  -DfailBuildOnCVSS=7.0
