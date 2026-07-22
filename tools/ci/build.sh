#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${REPO_ROOT}/tools/dev/common.sh"
cd "${REPO_ROOT}"
dev_validate_workspace "${DEV_STATE_ROOT}"
DEV_STATE_ROOT="${DEV_VALIDATED_WORKSPACE}"

START_FINGERPRINT="$(dev_git_worktree_fingerprint)"
if ./mvnw -B -ntp -T 1C verify; then
  BUILD_RESULT="passed"
  BUILD_EXIT=0
else
  BUILD_EXIT=$?
  BUILD_RESULT="failed"
fi

END_FINGERPRINT="$(dev_git_worktree_fingerprint)"
if [[ "${START_FINGERPRINT}" != "${END_FINGERPRINT}" ]]; then
  BUILD_RESULT="invalidated"
fi

mkdir -p -- "${DEV_STATE_ROOT}"
EVIDENCE_TMP="$(mktemp "${DEV_STATE_ROOT}/last-verify.XXXXXX")"
trap 'rm -f -- "${EVIDENCE_TMP}"' EXIT
printf '%s\n' \
  "result=${BUILD_RESULT}" \
  "commit=$(git rev-parse --verify HEAD)" \
  "fingerprint=${END_FINGERPRINT}" \
  "finished_at=$(date --utc +'%Y-%m-%dT%H:%M:%SZ')" \
  > "${EVIDENCE_TMP}"
mv -f -- "${EVIDENCE_TMP}" "${DEV_STATE_ROOT}/last-verify.env"
trap - EXIT

exit "${BUILD_EXIT}"
