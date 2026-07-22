#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${SCRIPT_DIR}/common.sh"

WORKSPACE="${DEV_ROOT}/runtime"
dev_validate_workspace "${DEV_STATE_ROOT}"
DEV_STATE_ROOT="${DEV_VALIDATED_WORKSPACE}"

usage() {
  cat <<'EOF'
Usage: tools/dev/context.sh [--workspace PATH]

Print stable, color-free key=value context for a cold developer/agent start.
Missing runtime or verify evidence is reported as state, not as an error.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --workspace) WORKSPACE="${2:?workspace is required}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) dev_die "unknown context option: $1" ;;
  esac
done

[[ "${WORKSPACE}" == /* ]] || WORKSPACE="${DEV_REPO_ROOT}/${WORKSPACE}"
dev_validate_workspace "${WORKSPACE}"
WORKSPACE="${DEV_VALIDATED_WORKSPACE}"

CONTEXT_RESULT="ok"
PROJECT_VERSION="$(
  cd "${DEV_REPO_ROOT}"
  ./mvnw -q -Dstyle.color=never -DforceStdout \
    help:evaluate -Dexpression=project.version 2>/dev/null
)" || {
  PROJECT_VERSION="unknown"
  CONTEXT_RESULT="degraded"
}
PROJECT_VERSION="${PROJECT_VERSION//$'\r'/}"
[[ -n "${PROJECT_VERSION}" ]] || {
  PROJECT_VERSION="unknown"
  CONTEXT_RESULT="degraded"
}

GIT_BRANCH="$(git -C "${DEV_REPO_ROOT}" symbolic-ref --quiet --short HEAD 2>/dev/null \
  || printf 'DETACHED')"
GIT_COMMIT="$(git -C "${DEV_REPO_ROOT}" rev-parse --verify HEAD)"
if [[ -n "$(git -C "${DEV_REPO_ROOT}" status --porcelain=v1 --untracked-files=normal)" ]]; then
  GIT_DIRTY="true"
else
  GIT_DIRTY="false"
fi

GIT_UPSTREAM="$(git -C "${DEV_REPO_ROOT}" rev-parse \
  --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null || printf 'none')"
GIT_AHEAD="unknown"
GIT_BEHIND="unknown"
if [[ "${GIT_UPSTREAM}" != "none" ]]; then
  read -r GIT_BEHIND GIT_AHEAD < <(
    git -C "${DEV_REPO_ROOT}" rev-list --left-right --count "${GIT_UPSTREAM}...HEAD"
  )
fi

RUNTIME_OUTPUT="$("${SCRIPT_DIR}/runtime.sh" --workspace "${WORKSPACE}" status 2>/dev/null || true)"
case "${RUNTIME_OUTPUT%% *}" in
  HEALTHY) RUNTIME_STATE="healthy" ;;
  RUNNING_UNHEALTHY) RUNTIME_STATE="unhealthy" ;;
  STOPPED) RUNTIME_STATE="stopped" ;;
  *) RUNTIME_STATE="unknown"; CONTEXT_RESULT="degraded" ;;
esac

CURRENT_FINGERPRINT="$(dev_git_worktree_fingerprint)"
VERIFY_EVIDENCE="${DEV_STATE_ROOT}/last-verify.env"
VERIFY_RESULT="unknown"
VERIFY_COMMIT="unknown"
VERIFY_FINISHED_AT="unknown"
VERIFY_FRESH="false"

evidence_value() { # key
  sed -n "s/^$1=//p" "${VERIFY_EVIDENCE}" | tail -n 1
}

if [[ -f "${VERIFY_EVIDENCE}" && ! -L "${VERIFY_EVIDENCE}" ]]; then
  VERIFY_RESULT="$(evidence_value result)"
  VERIFY_COMMIT="$(evidence_value commit)"
  VERIFY_FINISHED_AT="$(evidence_value finished_at)"
  VERIFY_FINGERPRINT="$(evidence_value fingerprint)"
  [[ -n "${VERIFY_RESULT}" ]] || VERIFY_RESULT="unknown"
  [[ -n "${VERIFY_COMMIT}" ]] || VERIFY_COMMIT="unknown"
  [[ -n "${VERIFY_FINISHED_AT}" ]] || VERIFY_FINISHED_AT="unknown"
  if [[ -n "${VERIFY_FINGERPRINT}" && "${VERIFY_FINGERPRINT}" == "${CURRENT_FINGERPRINT}" ]]; then
    VERIFY_FRESH="true"
  fi
fi

printf '%s\n' \
  "context.result=${CONTEXT_RESULT}" \
  "project.root=${DEV_REPO_ROOT}" \
  "project.version=${PROJECT_VERSION}" \
  "git.branch=${GIT_BRANCH}" \
  "git.commit=${GIT_COMMIT}" \
  "git.dirty=${GIT_DIRTY}" \
  "git.upstream=${GIT_UPSTREAM}" \
  "git.ahead=${GIT_AHEAD}" \
  "git.behind=${GIT_BEHIND}" \
  "runtime.workspace=${WORKSPACE}" \
  "runtime.state=${RUNTIME_STATE}" \
  "verify.result=${VERIFY_RESULT}" \
  "verify.commit=${VERIFY_COMMIT}" \
  "verify.finished_at=${VERIFY_FINISHED_AT}" \
  "verify.fresh=${VERIFY_FRESH}"
