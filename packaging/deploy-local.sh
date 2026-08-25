#!/usr/bin/env bash
# Builds the current checkout as an ordinary user, then delegates only the
# privileged activation/rollback phase to deploy-local-root.sh.
set -Eeuo pipefail

PREFIX="/srv/ioc-extractor"
PORT="8081"
ALLOW_DIRTY="false"
RELEASE_RETENTION="5"
BACKUP_RETENTION="5"
HEALTH_ATTEMPTS="15"
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
for command in find git sha256sum sudo flock; do
  command -v "${command}" >/dev/null 2>&1 || die "required command not found: ${command}"
done

if [[ -n "${JAVA_HOME:-}" ]]; then
  BUILD_JAVA="${JAVA_HOME}/bin/java"
  [[ -x "${BUILD_JAVA}" ]] || die "JAVA_HOME does not contain an executable bin/java: ${JAVA_HOME}"
else
  BUILD_JAVA="$(command -v java 2>/dev/null || true)"
  [[ -n "${BUILD_JAVA}" ]] || die "JDK 21+ is required; java was not found"
fi
BUILD_JAVA_MAJOR="$("${BUILD_JAVA}" -version 2>&1 \
  | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
[[ "${BUILD_JAVA_MAJOR:-0}" -ge 21 ]] \
  || die "JDK 21+ is required for verification; found ${BUILD_JAVA_MAJOR:-unknown}. Set JAVA_HOME and PATH to a JDK 21 installation"

cd "${REPO_ROOT}"
[[ -x ./mvnw ]] || die "Maven wrapper not found at ${REPO_ROOT}/mvnw"
[[ "$(git rev-parse --show-toplevel)" == "${REPO_ROOT}" ]] || die "script is not inside the repository root"

LOCK_ROOT="${XDG_RUNTIME_DIR:-/tmp}"
exec 9>"${LOCK_ROOT}/ioc-extractor-local-deploy-${UID}.lock"
flock -n 9 || die "another local deployment is already running"

COMMIT="$(git rev-parse HEAD)"
SHORT_COMMIT="$(git rev-parse --short=12 HEAD)"
BUILT_AT="$(date -u +%Y%m%dT%H%M%SZ)"
INITIAL_WORKTREE_STATUS="$(git status --porcelain --untracked-files=all)"
DIRTY="false"
if [[ -n "${INITIAL_WORKTREE_STATUS}" ]]; then
  DIRTY="true"
  [[ "${ALLOW_DIRTY}" == "true" ]] || die "working tree is dirty; commit changes or pass --allow-dirty"
fi
RELEASE_ID="${SHORT_COMMIT}-${BUILT_AT}"
[[ "${DIRTY}" != "true" ]] || RELEASE_ID="${SHORT_COMMIT}-dirty-${BUILT_AT}"
DIRTY_SUFFIX=""
[[ "${DIRTY}" != "true" ]] || DIRTY_SUFFIX=" (dirty)"

log "verifying ${COMMIT}${DIRTY_SUFFIX}"
MAVEN_ARGS=(-B -ntp -T 1C clean verify)
if [[ "${DIRTY}" != "true" ]]; then
  MAVEN_ARGS+=("-Dbuild.commit=${COMMIT}")
fi
./mvnw "${MAVEN_ARGS[@]}"

FINAL_WORKTREE_STATUS="$(git status --porcelain --untracked-files=all)"
[[ "${FINAL_WORKTREE_STATUS}" == "${INITIAL_WORKTREE_STATUS}" ]] \
  || die "build or a concurrent edit changed the source tree; refusing ambiguous artifact identity"

mapfile -d '' -t JAR_CANDIDATES < <(find "${REPO_ROOT}/bootstrap/ioc-app/target" \
  -maxdepth 1 -type f -name 'ioc-app-*.jar' ! -name '*.original' -print0)
[[ "${#JAR_CANDIDATES[@]}" -eq 1 ]] \
  || die "clean build must produce exactly one application jar; found ${#JAR_CANDIDATES[@]}"
JAR="${JAR_CANDIDATES[0]}"
JAR_SHA256="$(sha256sum "${JAR}" | awk '{print $1}')"
log "activating release ${RELEASE_ID} (${JAR_SHA256})"

sudo "${SCRIPT_DIR}/deploy-local-root.sh" \
  --prefix "${PREFIX}" \
  --jar "${JAR}" \
  --jar-sha256 "${JAR_SHA256}" \
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
