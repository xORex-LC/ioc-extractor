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

log()  { printf '\033[1;34m[uninstall]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

usage() { sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix) PREFIX="${2:?}"; shift 2 ;;
    --user)   RUN_USER="${2:?}"; shift 2 ;;
    --purge)  PURGE="true"; shift ;;
    -h|--help) usage ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
done
PREFIX="${PREFIX%/}"
[[ "${EUID}" -eq 0 ]] || die "must run as root (use sudo)."

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
  if [[ -e "${PREFIX}/pom.xml" || -d "${PREFIX}/.git" ]]; then
    die "refusing to purge a source tree at ${PREFIX} (pom.xml/.git present)."
  fi
  [[ -n "${PREFIX}" && "${PREFIX}" != "/" ]] || die "unsafe prefix: '${PREFIX}'"
  log "purging ${PREFIX}"
  rm -rf "${PREFIX}"
  if getent passwd "${RUN_USER}" >/dev/null; then
    userdel "${RUN_USER}" 2>/dev/null || warn "could not delete user ${RUN_USER}"
    log "removed user ${RUN_USER}"
  fi
else
  log "service removed. Data and user kept under ${PREFIX} (use --purge to delete everything)."
fi
