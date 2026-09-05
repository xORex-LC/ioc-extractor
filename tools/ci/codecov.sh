#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${REPO_ROOT}/tools/dev/common.sh"
cd "${REPO_ROOT}"

usage() {
  cat <<'EOF'
Usage: tools/ci/codecov.sh verify-input
       tools/ci/codecov.sh require-report

Revalidates the project-owned aggregate JaCoCo evidence before the separate,
best-effort Codecov upload step, or checks the downloaded CI handoff. Neither
command performs network access.
EOF
}

MODE="${1:-}"
case "${1:-}" in
  verify-input|require-report)
    [[ "$#" -eq 1 ]] || dev_die "${1} does not accept arguments"
    ;;
  -h|--help)
    usage
    exit 0
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

REPORT="${REPO_ROOT}/build-support/coverage-report/target/site/jacoco-aggregate/jacoco.xml"
if [[ "${MODE}" == "verify-input" ]]; then
  dev_require_java21
  VERIFIER_DIR="${REPO_ROOT}/target/build-quality-verifier"
  [[ -f "${VERIFIER_DIR}/CoverageVerifier.class" ]] \
    || dev_die "coverage verifier is missing; run tools/ci/build.sh first"

  java -cp "${VERIFIER_DIR}" CoverageVerifier verify-reports \
    "${REPO_ROOT}" \
    "${REPO_ROOT}/build-support/coverage-report/coverage-scope.tsv" \
    "${REPO_ROOT}/build-support/coverage-report/coverage-ratchets.tsv" \
    "${REPO_ROOT}/build-support/coverage-report/coverage-floors.tsv" \
    "${REPO_ROOT}/build-support/coverage-report/pom.xml"
else
  [[ -s "${REPORT}" ]] \
    || dev_die "downloaded aggregate JaCoCo XML is missing or empty: ${REPORT}"
fi

printf '[codecov] upload input verified: %s\n' \
  'build-support/coverage-report/target/site/jacoco-aggregate/jacoco.xml'
