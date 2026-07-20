#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." >/dev/null 2>&1 && pwd)"
cd "${REPO_ROOT}"

command -v shellcheck >/dev/null 2>&1 || {
  echo "shellcheck is required; run tools/dev/doctor.sh ci" >&2
  exit 1
}
shellcheck -x packaging/*.sh packaging/templates/ioc packaging/tests/*.sh \
  tools/dev/*.sh tools/ci/*.sh tools/tests/*.sh
packaging/tests/packaging-contract-test.sh
tools/tests/tools-contract-test.sh
