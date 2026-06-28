#!/usr/bin/env bash
# Builds the current checkout as an ordinary user, then delegates only the
# privileged activation/rollback phase to deploy-local-root.sh.
set -Eeuo pipefail

PREFIX="/srv/ioc-extractor"
PORT="8081"
ALLOW_DIRTY="false"
RELEASE_RETENTION="5"
BACKUP_RETENTION="5"
HEALTH_ATTEMPTS="4"
HEALTH_INTERVAL="2"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." >/dev/null 2>&1 && pwd)"

log() { printf '\033[1;34m[deploy]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
  printf '%s\n' \
    'Usage:' \
    '  ./packaging/deploy-local.sh [--prefix DIR] [--port PORT] [--allow-dirty]' \
    '      [--release-retention N] [--backup-retention N]' \
    '      [--health-attempts N] [--health-interval SECONDS]' \
    '' \
    'Runs the full Maven verify gate, builds the current checkout, then performs' \
    'an atomic local deployment with SQLite backup, health gate and rollback.'
  exit 0
}

# Usage:
#   ./packaging/deploy-local.sh [--prefix DIR] [--port PORT] [--allow-dirty]
#       [--release-retention N] [--backup-retention N]
#       [--health-attempts N] [--health-interval SECONDS]
#
# The full Maven verify gate always runs. A dirty checkout is rejected unless
# --allow-dirty is explicit; dirty releases carry a timestamped identity.
while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix) PREFIX="${2:?}"; shift 2 ;;
    --port) PORT="${2:?}"; shift 2 ;;
    --allow-dirty) ALLOW_DIRTY="true"; shift ;;
    --release-retention) RELEASE_RETENTION="${2:?}"; shift 2 ;;
    --backup-retention) BACKUP_RETENTION="${2:?}"; shift 2 ;;
    --health-attempts) HEALTH_ATTEMPTS="${2:?}"; shift 2 ;;
    --health-interval) HEALTH_INTERVAL="${2:?}"; shift 2 ;;
    -h|--help) usage ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ "${EUID}" -ne 0 ]] || die "run this script as your ordinary user; it invokes sudo only for activation"
[[ "${PREFIX}" == /* && "${PREFIX}" != "/" ]] || die "prefix must be an absolute non-root path"
[[ "${PORT}" =~ ^[0-9]+$ && "${PORT}" -ge 1 && "${PORT}" -le 65535 ]] || die "invalid port: ${PORT}"
for value in "${RELEASE_RETENTION}" "${BACKUP_RETENTION}" "${HEALTH_ATTEMPTS}" "${HEALTH_INTERVAL}"; do
  [[ "${value}" =~ ^[1-9][0-9]*$ ]] || die "retention/time values must be positive integers"
done
for command in git sha256sum sudo flock; do
  command -v "${command}" >/dev/null 2>&1 || die "required command not found: ${command}"
done

cd "${REPO_ROOT}"
[[ -x ./mvnw ]] || die "Maven wrapper not found at ${REPO_ROOT}/mvnw"
[[ "$(git rev-parse --show-toplevel)" == "${REPO_ROOT}" ]] || die "script is not inside the repository root"

LOCK_ROOT="${XDG_RUNTIME_DIR:-/tmp}"
exec 9>"${LOCK_ROOT}/ioc-extractor-local-deploy-${UID}.lock"
flock -n 9 || die "another local deployment is already running"

COMMIT="$(git rev-parse HEAD)"
SHORT_COMMIT="$(git rev-parse --short=12 HEAD)"
BUILT_AT="$(date -u +%Y%m%dT%H%M%SZ)"
DIRTY="false"
if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  DIRTY="true"
  [[ "${ALLOW_DIRTY}" == "true" ]] || die "working tree is dirty; commit changes or pass --allow-dirty"
fi
RELEASE_ID="${SHORT_COMMIT}-${BUILT_AT}"
[[ "${DIRTY}" != "true" ]] || RELEASE_ID="${SHORT_COMMIT}-dirty-${BUILT_AT}"
DIRTY_SUFFIX=""
[[ "${DIRTY}" != "true" ]] || DIRTY_SUFFIX=" (dirty)"

log "verifying ${COMMIT}${DIRTY_SUFFIX}"
./mvnw -B -ntp -T 1C verify

JAR="$(find "${REPO_ROOT}/bootstrap/ioc-app/target" -maxdepth 1 -type f \
  -name 'ioc-app-*.jar' ! -name '*.original' -printf '%T@ %p\n' \
  | sort -nr | head -1 | sed 's/^[^ ]* //')"
[[ -f "${JAR}" ]] || die "verified application jar not found: ${JAR}"
JAR_SHA256="$(sha256sum "${JAR}" | awk '{print $1}')"
log "activating release ${RELEASE_ID} (${JAR_SHA256})"

sudo "${SCRIPT_DIR}/deploy-local-root.sh" \
  --prefix "${PREFIX}" \
  --jar "${JAR}" \
  --release-id "${RELEASE_ID}" \
  --commit "${COMMIT}" \
  --dirty "${DIRTY}" \
  --built-at "${BUILT_AT}" \
  --port "${PORT}" \
  --release-retention "${RELEASE_RETENTION}" \
  --backup-retention "${BACKUP_RETENTION}" \
  --health-attempts "${HEALTH_ATTEMPTS}" \
  --health-interval "${HEALTH_INTERVAL}"

log "release ${RELEASE_ID} is healthy at http://127.0.0.1:${PORT}/actuator/health"
