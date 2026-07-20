#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." >/dev/null 2>&1 && pwd)"
cd "${REPO_ROOT}"

command -v lychee >/dev/null 2>&1 || {
  echo "lychee is required; run tools/dev/doctor.sh ci" >&2
  exit 1
}
exec lychee --offline --no-progress \
  docs tools README.md packaging/README.md .github/release-notes
