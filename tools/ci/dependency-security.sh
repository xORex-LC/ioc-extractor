#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." >/dev/null 2>&1 && pwd)"
DATA_DIR="${DEPENDENCY_CHECK_DATA:-}"
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

cd "${REPO_ROOT}"

COMMON_ARGS=(
  -DassemblyAnalyzerEnabled=false
  -DossindexAnalyzerEnabled=false
)
if [[ -n "${DATA_DIR}" ]]; then
  COMMON_ARGS+=( -DdataDirectory="${DATA_DIR}" )
fi

if [[ "${COMMAND}" == update ]]; then
  [[ -n "${NVD_API_KEY:-}" ]] || {
    echo "NVD_API_KEY is required for update (the value is never printed)" >&2
    exit 1
  }
  [[ -z "${DATA_DIR}" ]] || mkdir -p -- "${DATA_DIR}"
  exec ./mvnw -B -ntp dependency-check:update-only \
    "${COMMON_ARGS[@]}" \
    -DnvdApiKeyEnvironmentVariable=NVD_API_KEY
fi

# Deliberate contract: scan is offline and never contacts NVD. Updating the
# database is an explicit, separately observable `update` operation.
if [[ -n "${DATA_DIR}" && ! -f "${DATA_DIR}/odc.mv.db" ]]; then
  echo "Dependency-Check database is missing in ${DATA_DIR}; run 'make security-update' first" >&2
  exit 1
fi
exec ./mvnw -B -ntp dependency-check:aggregate \
  "${COMMON_ARGS[@]}" \
  -DautoUpdate=false \
  -Dformats=HTML,JSON \
  -DfailBuildOnCVSS=7.0
