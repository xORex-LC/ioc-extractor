#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${REPO_ROOT}/tools/dev/common.sh"
cd "${REPO_ROOT}"

MODE="${1:-policy}"
case "${MODE}" in
  policy)
    PMD_PROFILES="pmd-analysis"
    ;;
  watchlist)
    PMD_PROFILES="pmd-analysis,pmd-watchlist"
    ;;
  -h|--help)
    echo "Usage: tools/ci/pmd.sh [policy|watchlist]"
    exit 0
    ;;
  *)
    echo "Usage: tools/ci/pmd.sh [policy|watchlist]" >&2
    exit 2
    ;;
esac

PMD_COMMAND=(
  ./mvnw -B -ntp -T 1C
  -DskipTests
  -Djacoco.skip=true
  -Dspotbugs.skip=true
  -P"${PMD_PROFILES}"
  -pl build-support/pmd-report -am verify
)

# The watchlist is an explicit diagnostic run and must not make regular policy
# evidence look fresh.
if [[ "${MODE}" == "watchlist" ]]; then
  exec "${PMD_COMMAND[@]}"
fi

dev_validate_workspace "${DEV_STATE_ROOT}"
DEV_STATE_ROOT="${DEV_VALIDATED_WORKSPACE}"

START_FINGERPRINT="$(dev_git_worktree_fingerprint)"
if "${PMD_COMMAND[@]}"; then
  PMD_RESULT="passed"
  PMD_EXIT=0
else
  PMD_EXIT=$?
  PMD_RESULT="failed"
fi

END_FINGERPRINT="$(dev_git_worktree_fingerprint)"
if [[ "${START_FINGERPRINT}" != "${END_FINGERPRINT}" ]]; then
  PMD_RESULT="invalidated"
fi

mkdir -p -- "${DEV_STATE_ROOT}"
EVIDENCE_TMP="$(mktemp "${DEV_STATE_ROOT}/last-pmd.XXXXXX")"
trap 'rm -f -- "${EVIDENCE_TMP}"' EXIT
printf '%s\n' \
  "result=${PMD_RESULT}" \
  "commit=$(git rev-parse --verify HEAD)" \
  "fingerprint=${END_FINGERPRINT}" \
  "finished_at=$(date --utc +'%Y-%m-%dT%H:%M:%SZ')" \
  > "${EVIDENCE_TMP}"
mv -f -- "${EVIDENCE_TMP}" "${DEV_STATE_ROOT}/last-pmd.env"
trap - EXIT

exit "${PMD_EXIT}"
