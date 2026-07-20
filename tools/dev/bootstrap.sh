#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${SCRIPT_DIR}/common.sh"
# shellcheck source=tools/tool-versions.env
. "${DEV_REPO_ROOT}/tools/tool-versions.env"

COMMAND="${1:-}"
LOCAL_BIN_DIR="${DEV_ROOT}/tools/bin"

usage() {
  cat <<'EOF'
Usage: tools/dev/bootstrap.sh lychee

Downloads the project-pinned lychee release into .dev/tools/bin and verifies
the release SHA-256. No sudo, Snap, Cargo or system-wide installation is used.
EOF
}

case "${COMMAND}" in
  -h|--help) usage; exit 0 ;;
  lychee) : ;;
  *) usage >&2; exit 2 ;;
esac

dev_require_command curl
dev_require_command sha256sum
dev_require_command tar

case "$(uname -s):$(uname -m)" in
  Linux:x86_64)
    TARGET="x86_64-unknown-linux-gnu"
    EXPECTED_SHA256="${LYCHEE_LINUX_X86_64_SHA256}"
    ;;
  Linux:aarch64|Linux:arm64)
    TARGET="aarch64-unknown-linux-gnu"
    EXPECTED_SHA256="${LYCHEE_LINUX_AARCH64_SHA256}"
    ;;
  *) dev_die "repo-local lychee bootstrap supports Linux x86_64 and aarch64" ;;
esac

INSTALL_PATH="${LOCAL_BIN_DIR}/lychee"
EXPECTED_VERSION="lychee ${LYCHEE_VERSION}"
if [[ -x "${INSTALL_PATH}" ]] \
    && "${INSTALL_PATH}" --version 2>/dev/null | grep -Fq "${LYCHEE_VERSION}"; then
  dev_log "lychee ${LYCHEE_VERSION} is already installed at ${INSTALL_PATH}"
  exit 0
fi

ARCHIVE="lychee-${TARGET}.tar.gz"
URL="https://github.com/lycheeverse/lychee/releases/download/lychee-v${LYCHEE_VERSION}/${ARCHIVE}"
TEMP_DIR="$(mktemp -d)"
cleanup_bootstrap() {
  rm -rf -- "${TEMP_DIR}"
}
trap cleanup_bootstrap EXIT

dev_log "downloading lychee ${LYCHEE_VERSION} for ${TARGET}"
curl --fail --location --retry 3 --show-error --silent \
  --output "${TEMP_DIR}/${ARCHIVE}" "${URL}"
printf '%s  %s\n' "${EXPECTED_SHA256}" "${TEMP_DIR}/${ARCHIVE}" | sha256sum --check --status \
  || dev_die "lychee archive checksum mismatch"

mkdir -p -- "${TEMP_DIR}/extract" "${LOCAL_BIN_DIR}"
tar -xzf "${TEMP_DIR}/${ARCHIVE}" -C "${TEMP_DIR}/extract"
EXTRACTED_BINARY="${TEMP_DIR}/extract/lychee-${TARGET}/lychee"
[[ -f "${EXTRACTED_BINARY}" && ! -L "${EXTRACTED_BINARY}" ]] \
  || dev_die "lychee archive does not contain the expected binary"
install -m 0755 "${EXTRACTED_BINARY}" "${INSTALL_PATH}.new"
mv -f -- "${INSTALL_PATH}.new" "${INSTALL_PATH}"

ACTUAL_VERSION="$("${INSTALL_PATH}" --version)"
[[ "${ACTUAL_VERSION}" == *"${LYCHEE_VERSION}"* ]] \
  || dev_die "installed lychee has unexpected version: ${ACTUAL_VERSION}"
dev_log "installed ${EXPECTED_VERSION} at ${INSTALL_PATH}"
