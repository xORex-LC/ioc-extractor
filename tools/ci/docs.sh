#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." >/dev/null 2>&1 && pwd)"
cd "${REPO_ROOT}"

if [[ -n "${LYCHEE_BIN:-}" ]]; then
  [[ -x "${LYCHEE_BIN}" ]] || {
    echo "LYCHEE_BIN is not executable: ${LYCHEE_BIN}" >&2
    exit 1
  }
elif command -v lychee >/dev/null 2>&1; then
  LYCHEE_BIN="$(command -v lychee)"
elif [[ -x "${REPO_ROOT}/.dev/tools/bin/lychee" ]]; then
  LYCHEE_BIN="${REPO_ROOT}/.dev/tools/bin/lychee"
else
  echo "lychee is required; run 'make bootstrap'" >&2
  exit 1
fi
exec "${LYCHEE_BIN}" --offline --no-progress \
  docs tools README.md packaging/README.md .github/release-notes
