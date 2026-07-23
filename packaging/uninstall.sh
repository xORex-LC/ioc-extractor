#!/usr/bin/env bash
#
# ioc-extractor — uninstaller.
#
# Stops & disables the service and removes the systemd unit. By default the data
# under the prefix (inbox/artifacts/ledger) and the service user are KEPT.
#
# Usage:
#   sudo ./uninstall.sh [--prefix DIR] [--user NAME] [--purge] [--help]
#
#   --purge   also delete the install prefix (jar, jdk, config, ALL data) and the user.
#
set -Eeuo pipefail

SERVICE="ioc-extractor"
PREFIX="/opt/ioc-extractor"
RUN_USER="ioc"
PURGE="false"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=packaging/install-layout.sh
. "${SCRIPT_DIR}/install-layout.sh"

log()  { printf '\033[1;34m[uninstall]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

usage() { sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix) PREFIX="${2:?}"; shift 2 ;;
    --user)   RUN_USER="${2:?}"; shift 2 ;;
    --purge)  PURGE="true"; shift ;;
    -h|--help) usage ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
done
[[ "${EUID}" -eq 0 ]] || die "must run as root (use sudo)."
for command in find getent id ps readlink realpath rm; do
  command -v "${command}" >/dev/null 2>&1 || die "required command not found: ${command}"
done
if [[ "$(ps -p 1 -o comm= 2>/dev/null)" == "systemd" ]]; then
  command -v systemctl >/dev/null 2>&1 || die "required command not found: systemctl"
fi
if [[ "${PURGE}" == "true" ]]; then
  command -v userdel >/dev/null 2>&1 || die "required command not found: userdel"
fi
ioc_validate_prefix "${PREFIX}" || die "unsafe installation prefix"
PREFIX="${IOC_VALIDATED_PREFIX}"
ioc_validate_service_user "${RUN_USER}" || die "unsafe service account"

MARKER="$(ioc_marker_path "${PREFIX}")"
if [[ -e "${MARKER}" ]]; then
  ioc_is_valid_marker "${PREFIX}" "${SERVICE}" "${RUN_USER}" \
    || die "invalid or mismatched installation marker: ${MARKER}"
elif ioc_is_v010_single_dir_installation "${PREFIX}"; then
  die "v0.1.0 installation must be removed with its matching uninstaller or preserved as a rollback point"
elif ioc_is_pre_marker_release_layout "${PREFIX}"; then
  [[ "${PURGE}" != "true" ]] \
    || die "pre-marker release layout has no safety marker; run install.sh once to adopt it before purge"
  warn "removing service for a validated pre-marker release layout without purging data"
else
  die "prefix is not a validated ioc-extractor installation: ${PREFIX}"
fi

UNIT="/etc/systemd/system/${SERVICE}.service"
if [[ "$(ps -p 1 -o comm= 2>/dev/null)" == "systemd" ]]; then
  systemctl stop "${SERVICE}" 2>/dev/null || true
  systemctl disable "${SERVICE}" 2>/dev/null || true
fi
if [[ -f "${UNIT}" ]]; then
  rm -f "${UNIT}"
  log "removed ${UNIT}"
  systemctl daemon-reload 2>/dev/null || true
fi

if [[ "${PURGE}" == "true" ]]; then
  ioc_is_valid_marker "${PREFIX}" "${SERVICE}" "${RUN_USER}" \
    || die "refusing purge without a valid installation marker"
  log "purging ${PREFIX}"
  rm -rf "${PREFIX}"
  if getent passwd "${RUN_USER}" >/dev/null; then
    userdel "${RUN_USER}" 2>/dev/null || warn "could not delete user ${RUN_USER}"
    log "removed user ${RUN_USER}"
  fi
else
  log "service removed. Data and user kept under ${PREFIX} (use --purge to delete everything)."
fi
