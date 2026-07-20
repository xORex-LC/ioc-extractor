#!/usr/bin/env bash
# Shared developer-tool contract. Source only; no work is performed on load.

if [[ -n "${DEV_TOOLS_COMMON_LOADED:-}" ]]; then
  return 0
fi
DEV_TOOLS_COMMON_LOADED="true"

DEV_TOOLS_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
DEV_REPO_ROOT="$(cd -- "${DEV_TOOLS_DIR}/../.." >/dev/null 2>&1 && pwd)"
DEV_ROOT="${DEV_REPO_ROOT}/.dev"

dev_log() {
  printf '\033[1;34m[dev]\033[0m %s\n' "$*"
}

dev_warn() {
  printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2
}

dev_die() {
  printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2
  exit 1
}

dev_require_command() {
  command -v "$1" >/dev/null 2>&1 || dev_die "required command not found: $1"
}

dev_java_major() {
  "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1
}

dev_require_java21() {
  dev_require_command java
  local major
  major="$(dev_java_major "$(command -v java)")"
  [[ "${major:-0}" -ge 21 ]] || dev_die "JDK 21+ is required; detected ${major:-unknown}"
}

dev_validate_port() {
  [[ "${1:-}" =~ ^[1-9][0-9]*$ && "$1" -le 65535 ]]
}

dev_validate_workspace() { # requested workspace; must be a real path below .dev
  local requested="${1:-}" normalized root
  [[ -n "${requested}" && "${requested}" == /* ]] \
    || dev_die "workspace must be an absolute path below ${DEV_ROOT}: '${requested}'"
  [[ "${requested}" =~ ^/[A-Za-z0-9._/-]+$ ]] \
    || dev_die "workspace contains unsupported characters: '${requested}'"
  normalized="$(realpath -m -- "${requested}")"
  root="$(realpath -m -- "${DEV_ROOT}")"
  [[ "${normalized}" == "${requested%/}" ]] \
    || dev_die "workspace must be normalized and must not traverse symlinks: '${requested}'"
  [[ "${normalized}" == "${root}/"* ]] \
    || dev_die "workspace escapes ${root}: '${normalized}'"
  DEV_VALIDATED_WORKSPACE="${normalized}"
}

dev_prepare_workspace() { # workspace
  dev_validate_workspace "$1"
  mkdir -p -- "${DEV_VALIDATED_WORKSPACE}"
  [[ -d "${DEV_VALIDATED_WORKSPACE}" && ! -L "${DEV_VALIDATED_WORKSPACE}" ]] \
    || dev_die "workspace is not a regular directory: ${DEV_VALIDATED_WORKSPACE}"
}

dev_reset_workspace() { # workspace
  dev_validate_workspace "$1"
  local workspace="${DEV_VALIDATED_WORKSPACE}"
  if [[ -e "${workspace}" ]]; then
    [[ -d "${workspace}" && ! -L "${workspace}" ]] \
      || dev_die "refusing to reset non-directory workspace: ${workspace}"
    rm -rf -- "${workspace}"
  fi
}

dev_resolve_app_jar() { # optional explicit jar
  local explicit="${1:-}" candidate
  local -a candidates=()
  if [[ -n "${explicit}" ]]; then
    candidate="$(realpath -e -- "${explicit}")" \
      || dev_die "application jar does not exist: ${explicit}"
    [[ -f "${candidate}" && ! -L "${explicit}" ]] \
      || dev_die "application jar must be a regular non-symlink file: ${explicit}"
    # shellcheck disable=SC2034 # output variable consumed by sourcing scripts
    DEV_APP_JAR="${candidate}"
    return
  fi

  while IFS= read -r -d '' candidate; do
    candidates+=("${candidate}")
  done < <(find "${DEV_REPO_ROOT}/bootstrap/ioc-app/target" -maxdepth 1 -type f \
    -name 'ioc-app-*.jar' ! -name '*.original' -print0 2>/dev/null)
  [[ "${#candidates[@]}" -eq 1 ]] || dev_die \
    "expected exactly one bootable jar; run './mvnw -B -ntp -T 1C -DskipTests package' or pass --jar"
  # shellcheck disable=SC2034 # output variable consumed by sourcing scripts
  DEV_APP_JAR="$(realpath -e -- "${candidates[0]}")"
}

dev_pid_matches_jar() { # pid jar
  local pid="$1" jar="$2" argument
  [[ "${pid}" =~ ^[1-9][0-9]*$ && -r "/proc/${pid}/cmdline" ]] || return 1
  while IFS= read -r argument; do
    [[ "${argument}" == "${jar}" ]] && return 0
  done < <(tr '\0' '\n' < "/proc/${pid}/cmdline")
  return 1
}

dev_health_ready() { # port
  local port="$1" base component body
  base="http://127.0.0.1:${port}/actuator/health"
  for component in jdbcStorage dataframeStorage artifactStorage; do
    body="$(curl --noproxy '*' --silent --fail --max-time 2 \
      "${base}/${component}" 2>/dev/null)" \
      || return 1
    grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' \
      <<< "${body}" || return 1
  done
}
