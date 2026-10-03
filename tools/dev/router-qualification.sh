#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${SCRIPT_DIR}/common.sh"
cd "${DEV_REPO_ROOT}"

SIZE="1000"
while (( $# > 0 )); do
  case "$1" in
    --size) SIZE="${2:-}"; shift 2 ;;
    -h|--help)
      echo "Usage: tools/dev/router-qualification.sh [--size 1000|100000]"
      exit 0 ;;
    *) dev_die "unknown router qualification option: $1" ;;
  esac
done
[[ "${SIZE}" == "1000" || "${SIZE}" == "100000" ]] \
  || dev_die "router qualification size must be 1000 or 100000"
dev_require_java21
dev_require_command timeout

WORKSPACE="${DEV_ROOT}/router-qualification"
dev_prepare_workspace "${WORKSPACE}"
MODULE="${DEV_REPO_ROOT}/adapters/adapter-processing-camel"
./mvnw -B -ntp -pl adapters/adapter-processing-camel -am test-compile \
  > "${WORKSPACE}/compile.log" 2>&1
./mvnw -B -ntp -pl adapters/adapter-processing-camel dependency:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile="${WORKSPACE}/classpath.txt" \
  > "${WORKSPACE}/classpath.log" 2>&1
CLASSPATH="${MODULE}/target/test-classes:${MODULE}/target/classes:$(cat "${WORKSPACE}/classpath.txt")"
REPORT="${WORKSPACE}/router-${SIZE}.csv"
printf '%s\n' 'size,branches,callers,mode,compile_ms,start_ms,run_ms,rows_per_second,allocated_bytes_per_input,started_heap_bytes,retained_growth_bytes,prepared,blocked,recovered' > "${REPORT}"
for branches in 1 4 16 64; do
  for callers in 1 4; do
    for mode in SUCCESS FAILURE RECOVERY; do
      dev_log "router qualification: size=${SIZE} branches=${branches} callers=${callers} mode=${mode}"
      RUN_PREFIX="${WORKSPACE}/router-${SIZE}-${branches}-${callers}-${mode}"
      timeout 180s java -Xms128m -Xmx512m -cp "${CLASSPATH}" \
        com.iocextractor.adapter.processing.camel.RouterQualification \
        "${SIZE}" "${branches}" "${callers}" "${mode}" \
        > "${RUN_PREFIX}.out" 2> "${RUN_PREFIX}.err"
      tail -n 1 "${RUN_PREFIX}.out" >> "${REPORT}"
    done
  done
done
{
  printf 'head=%s\n' "$(git rev-parse HEAD)"
  printf 'dirty=%s\n' "$(if [[ -z "$(git status --porcelain)" ]]; then echo false; else echo true; fi)"
  printf 'size=%s\n' "${SIZE}"
  printf 'java=%s\n' "$(java -version 2>&1 | head -n 1)"
  printf 'processors=%s\n' "$(getconf _NPROCESSORS_ONLN)"
  printf 'jvm=-Xms128m -Xmx512m\n'
  printf 'report=%s\n' "${REPORT}"
} > "${WORKSPACE}/router-${SIZE}.metadata"
dev_log "router qualification report: ${REPORT}"
