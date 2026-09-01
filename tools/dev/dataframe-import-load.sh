#!/usr/bin/env bash
# shellcheck disable=SC2016 # Markdown backticks in report format strings are literal
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${SCRIPT_DIR}/common.sh"

PROFILE="insert"
SIZE="100000"
MAX_SECONDS=""
MAX_HEAP_MIB="384"
JVM_MAX_MIB="512"
WORKSPACE=""

usage() {
  cat <<'EOF'
Usage: tools/dev/dataframe-import-load.sh [OPTIONS]

Options:
  --profile insert|mixed  Reference profile (default: insert)
  --size N                Delivery rows, at most 1000000 (default: 100000)
  --max-seconds N         Internal stage+promotion SLO (default: 120 or 600)
  --max-heap-mib N        Maximum observed heap (default: 384)
  --jvm-max-mib N         Forked qualification JVM Xmx (default: 512)
  --workspace PATH        Evidence directory below repo-local .dev

The mixed profile requires SIZE divisible by four. It seeds 3/4 SIZE active
rows, then imports one delivery split equally across update, no-op, conflict
and insert behavior. The ordinary verification gate skips this opt-in test.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) PROFILE="${2:?}"; shift 2 ;;
    --size) SIZE="${2:?}"; shift 2 ;;
    --max-seconds) MAX_SECONDS="${2:?}"; shift 2 ;;
    --max-heap-mib) MAX_HEAP_MIB="${2:?}"; shift 2 ;;
    --jvm-max-mib) JVM_MAX_MIB="${2:?}"; shift 2 ;;
    --workspace) WORKSPACE="${2:?}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; dev_die "unknown dataframe-import-load option: $1" ;;
  esac
done

[[ "${PROFILE}" == "insert" || "${PROFILE}" == "mixed" ]] \
  || dev_die "profile must be insert or mixed"
for value in "${SIZE}" "${MAX_HEAP_MIB}" "${JVM_MAX_MIB}"; do
  [[ "${value}" =~ ^[1-9][0-9]*$ ]] || dev_die "size and memory limits must be positive integers"
done
(( SIZE <= 1000000 )) || dev_die "size must not exceed the approved 1000000-row envelope"
if [[ "${PROFILE}" == "mixed" ]]; then
  (( SIZE % 4 == 0 )) || dev_die "mixed profile size must be divisible by four"
fi
if [[ -z "${MAX_SECONDS}" ]]; then
  if (( SIZE <= 100000 )); then
    MAX_SECONDS="120"
  else
    MAX_SECONDS="600"
  fi
fi
[[ "${MAX_SECONDS}" =~ ^[1-9][0-9]*$ ]] || dev_die "max-seconds must be a positive integer"
(( MAX_HEAP_MIB < JVM_MAX_MIB )) || dev_die "observed heap limit must be below forked JVM Xmx"

dev_require_java21
dev_require_command /usr/bin/time
dev_require_command tee

if [[ -z "${WORKSPACE}" ]]; then
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  WORKSPACE="${DEV_ROOT}/dataframe-import-load/${PROFILE}-${SIZE}-${stamp}"
elif [[ "${WORKSPACE}" != /* ]]; then
  WORKSPACE="${DEV_REPO_ROOT}/${WORKSPACE}"
fi
dev_prepare_workspace "${WORKSPACE}"
WORKSPACE="${DEV_VALIDATED_WORKSPACE}"

MAVEN_LOG="${WORKSPACE}/maven.log"
TIME_LOG="${WORKSPACE}/time.env"
REPORT="${WORKSPACE}/report.md"
commit="$(git -C "${DEV_REPO_ROOT}" rev-parse --verify HEAD)"
fingerprint="$(dev_git_worktree_fingerprint)"

command=(
  "${DEV_REPO_ROOT}/mvnw" -q -ntp -T 1
  -pl adapters/adapter-store-jdbc -am -Dskip.unit.tests=true verify
  -Dit.test=JdbcManagedImportLoadProfileIT
  -Dfailsafe.failIfNoSpecifiedTests=false
  "-DargLine=-Xmx${JVM_MAX_MIB}m"
  -Dioc.import.load.enabled=true
  "-Dioc.import.load.profile=${PROFILE}"
  "-Dioc.import.load.rows=${SIZE}"
  "-Dioc.import.load.max-seconds=${MAX_SECONDS}"
  "-Dioc.import.load.max-heap-mib=${MAX_HEAP_MIB}"
)

set +e
(
  cd -- "${DEV_REPO_ROOT}"
  /usr/bin/time -f $'wall_seconds=%e\nmax_rss_kib=%M\nuser_seconds=%U\nsystem_seconds=%S' \
    -o "${TIME_LOG}" "${command[@]}" 2>&1 | tee "${MAVEN_LOG}"
)
status="${PIPESTATUS[0]}"
set -e
if [[ "${status}" -ne 0 ]]; then
  dev_die "managed import load qualification failed; evidence retained at ${WORKSPACE}"
fi

metric_count="$(grep -c '^IMPORT_LOAD_METRIC ' "${MAVEN_LOG}" || true)"
(( metric_count >= 10 )) || dev_die "load qualification emitted incomplete metrics"

{
  printf '# Managed dataframe import load evidence\n\n'
  printf -- '- generated_at: `%s`\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf -- '- git_commit: `%s`\n' "${commit}"
  printf -- '- worktree_fingerprint: `%s`\n' "${fingerprint}"
  printf -- '- os: `%s`\n' "$(uname -srvmo)"
  printf -- '- cpu: `%s`\n' "$(LC_ALL=C lscpu | awk -F: '/^Model name:/ {sub(/^[[:space:]]+/, "", $2); print $2; exit}')"
  printf -- '- java: `%s`\n' "$(java -version 2>&1 | head -1)"
  printf -- '- filesystem: `%s`\n' "$(findmnt -no FSTYPE -T "${WORKSPACE}")"
  printf -- '- forked_xmx_mib: `%s`\n\n' "${JVM_MAX_MIB}"
  printf '## Import metrics\n\n```text\n'
  sed -n 's/^IMPORT_LOAD_METRIC //p' "${MAVEN_LOG}"
  printf '```\n\n## Process envelope\n\n```text\n'
  cat "${TIME_LOG}"
  printf '```\n'
} > "${REPORT}"

dev_log "managed import ${PROFILE}/${SIZE} qualification passed"
dev_log "report: ${REPORT}"
