#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${SCRIPT_DIR}/common.sh"

WORKSPACE="${DEV_ROOT}/runtime"

usage() {
  echo "Usage: tools/dev/submit.sh [--workspace PATH] SOURCE"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --workspace) WORKSPACE="${2:?}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) break ;;
  esac
done
[[ $# -eq 1 ]] || { usage >&2; exit 2; }
SOURCE="$(realpath -e -- "$1")" || dev_die "source does not exist: $1"
[[ -f "${SOURCE}" && ! -L "$1" ]] || dev_die "source must be a regular non-symlink file"
case "${SOURCE,,}" in
  *.htm|*.html|*.docx) : ;;
  *) dev_die "daemon input must be .htm, .html or .docx" ;;
esac

[[ "${WORKSPACE}" == /* ]] || WORKSPACE="${DEV_REPO_ROOT}/${WORKSPACE}"
dev_validate_workspace "${WORKSPACE}"
WORKSPACE="${DEV_VALIDATED_WORKSPACE}"
INBOX="${WORKSPACE}/var/inbox"
[[ -d "${INBOX}" && ! -L "${INBOX}" ]] \
  || dev_die "developer daemon inbox is unavailable; start runtime first: ${INBOX}"

NAME="$(basename -- "${SOURCE}")"
DESTINATION="${INBOX}/${NAME}"
STAGING="${DESTINATION}.part.$$"
[[ ! -e "${DESTINATION}" && ! -e "${STAGING}" ]] \
  || dev_die "inbox destination already exists: ${DESTINATION}"
cleanup_submit() {
  rm -f -- "${STAGING}"
}
trap cleanup_submit EXIT
cp -- "${SOURCE}" "${STAGING}"
mv -- "${STAGING}" "${DESTINATION}"
trap - EXIT
printf '%s\n' "${DESTINATION}"
