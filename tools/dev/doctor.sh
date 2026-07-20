#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${SCRIPT_DIR}/common.sh"

MODE="${1:-all}"
case "${MODE}" in
  core|dev|ci|security|all) : ;;
  -h|--help)
    echo "Usage: tools/dev/doctor.sh [core|dev|ci|security|all]"
    exit 0
    ;;
  *) dev_die "unknown doctor mode: ${MODE}" ;;
esac

FAILED="false"

check_command() { # command required description
  local command="$1" required="$2" description="$3"
  if command -v "${command}" >/dev/null 2>&1; then
    printf '[ok]       %-12s %s\n' "${command}" "${description}"
  elif [[ "${required}" == "true" ]]; then
    printf '[missing]  %-12s %s\n' "${command}" "${description}" >&2
    FAILED="true"
  else
    printf '[optional] %-12s %s\n' "${command}" "${description}"
  fi
}

check_command bash true "developer scripts"
check_command java true "JDK 21 application and fixture generator"
check_command git true "source identity and CI checks"
check_command make true "developer command facade"
[[ -x "${DEV_REPO_ROOT}/mvnw" ]] || { echo "[missing]  ./mvnw      Maven wrapper" >&2; FAILED="true"; }

if command -v java >/dev/null 2>&1; then
  JAVA_MAJOR="$(dev_java_major "$(command -v java)")"
  if [[ "${JAVA_MAJOR:-0}" -ge 21 ]]; then
    printf '[ok]       %-12s %s\n' "java" "major ${JAVA_MAJOR}"
  else
    printf '[invalid]  %-12s %s\n' "java" "JDK 21+ required; found ${JAVA_MAJOR:-unknown}" >&2
    FAILED="true"
  fi
fi

if [[ "${MODE}" == dev || "${MODE}" == all ]]; then
  check_command curl true "daemon health/smoke"
  check_command jq false "ECS JSON queries"
  check_command sqlite3 false "manual read-only database inspection"
fi

if [[ "${MODE}" == ci || "${MODE}" == all ]]; then
  check_command shellcheck true "shell quality gate"
  if command -v lychee >/dev/null 2>&1; then
    printf '[ok]       %-12s %s\n' "lychee" "offline documentation links"
  elif [[ -x "${DEV_ROOT}/tools/bin/lychee" ]]; then
    printf '[local]    %-12s %s\n' "lychee" ".dev/tools/bin/lychee"
  else
    printf '[missing]  %-12s %s\n' "lychee" "run 'make bootstrap'" >&2
    FAILED="true"
  fi
fi

if [[ "${MODE}" == security || "${MODE}" == all ]]; then
  if [[ -n "${NVD_API_KEY:-}" ]]; then
    printf '[ok]       %-12s %s\n' "NVD_API_KEY" "available (value hidden)"
  else
    printf '[missing]  %-12s %s\n' "NVD_API_KEY" "required for Dependency-Check" >&2
    [[ "${MODE}" != security ]] || FAILED="true"
  fi
fi

[[ "${FAILED}" == "false" ]] || exit 1
