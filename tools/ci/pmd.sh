#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." >/dev/null 2>&1 && pwd)"
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

exec ./mvnw -B -ntp -T 1C \
  -DskipTests \
  -Djacoco.skip=true \
  -Dspotbugs.skip=true \
  -P"${PMD_PROFILES}" \
  -pl build-support/pmd-report -am verify
