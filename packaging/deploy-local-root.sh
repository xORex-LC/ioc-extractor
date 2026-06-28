#!/usr/bin/env bash
# Privileged local activation phase. Do not call directly; deploy-local.sh builds
# and verifies the artifact before invoking this script through sudo.
set -Eeuo pipefail

SERVICE="ioc-extractor"
PREFIX=""
JAR=""
RELEASE_ID=""
COMMIT=""
DIRTY="false"
BUILT_AT=""
PORT="8081"
RELEASE_RETENTION="5"
BACKUP_RETENTION="5"
HEALTH_ATTEMPTS="4"
HEALTH_INTERVAL="2"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"

log() { printf '\033[1;34m[activate]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix) PREFIX="${2:?}"; shift 2 ;;
    --jar) JAR="${2:?}"; shift 2 ;;
    --release-id) RELEASE_ID="${2:?}"; shift 2 ;;
    --commit) COMMIT="${2:?}"; shift 2 ;;
    --dirty) DIRTY="${2:?}"; shift 2 ;;
    --built-at) BUILT_AT="${2:?}"; shift 2 ;;
    --port) PORT="${2:?}"; shift 2 ;;
    --release-retention) RELEASE_RETENTION="${2:?}"; shift 2 ;;
    --backup-retention) BACKUP_RETENTION="${2:?}"; shift 2 ;;
    --health-attempts) HEALTH_ATTEMPTS="${2:?}"; shift 2 ;;
    --health-interval) HEALTH_INTERVAL="${2:?}"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ "${EUID}" -eq 0 ]] || die "privileged activation must run as root"
[[ "${PREFIX}" == /* && "${PREFIX}" != "/" ]] || die "unsafe prefix: ${PREFIX}"
[[ "${PREFIX}" != *[[:space:]]* ]] || die "prefix must not contain whitespace"
[[ -f "${JAR}" && ! -L "${JAR}" ]] || die "application jar must be a regular non-symlink file"
[[ "${RELEASE_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || die "invalid release id"
[[ "${COMMIT}" =~ ^[0-9a-f]{40}$ ]] || die "invalid commit SHA"
[[ "${DIRTY}" == "true" || "${DIRTY}" == "false" ]] || die "invalid dirty flag"
for value in "${PORT}" "${RELEASE_RETENTION}" "${BACKUP_RETENTION}" "${HEALTH_ATTEMPTS}" "${HEALTH_INTERVAL}"; do
  [[ "${value}" =~ ^[1-9][0-9]*$ ]] || die "numeric arguments must be positive integers"
done
[[ "${PORT}" -le 65535 ]] || die "invalid port"
for command in curl flock sha256sum systemctl tar; do
  command -v "${command}" >/dev/null 2>&1 || die "required command not found: ${command}"
done

exec 9>/run/lock/ioc-extractor-deploy.lock
flock -n 9 || die "another privileged deployment is already running"

health_ready() {
  local base="http://127.0.0.1:${PORT}/actuator/health" component
  for component in jdbcStorage dataframeStorage artifactStorage; do
    curl --silent --fail --max-time 2 --output /dev/null \
      "${base}/${component}" 2>/dev/null || return 1
  done
}

wait_for_health() {
  local attempt
  for ((attempt = 1; attempt <= HEALTH_ATTEMPTS; attempt++)); do
    systemctl is-active --quiet "${SERVICE}" && health_ready && return 0
    (( attempt == HEALTH_ATTEMPTS )) || sleep "${HEALTH_INTERVAL}"
  done
  return 1
}

prune_directories() { # parent keep protected-path
  local parent="$1" keep="$2" protected="${3:-}" path
  mapfile -t paths < <(find "${parent}" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' \
    | sort -nr | awk -v keep="${keep}" 'NR > keep { sub(/^[^ ]+ /, ""); print }')
  for path in "${paths[@]:-}"; do
    [[ -n "${path}" && "${path}" != "${protected}" ]] || continue
    rm -rf -- "${path}"
  done
}

prune_files() { # parent keep
  local parent="$1" keep="$2" path
  mapfile -t paths < <(find "${parent}" -mindepth 1 -maxdepth 1 -type f -printf '%T@ %p\n' \
    | sort -nr | awk -v keep="${keep}" 'NR > keep { sub(/^[^ ]+ /, ""); print }')
  for path in "${paths[@]:-}"; do
    [[ -z "${path}" ]] || rm -f -- "${path}"
  done
}

if [[ ! -e "${PREFIX}/current" || ! -f "/etc/systemd/system/${SERVICE}.service" ]]; then
  INSTALL_JAVA_ARGS=()
  if command -v java >/dev/null 2>&1; then
    SYSTEM_JAVA="$(readlink -f "$(command -v java)")"
    SYSTEM_JAVA_MAJOR="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
    if [[ "${SYSTEM_JAVA_MAJOR:-0}" -ge 21 && "${SYSTEM_JAVA}" != /home/* ]]; then
      INSTALL_JAVA_ARGS+=(--system-java)
      log "using installed Java ${SYSTEM_JAVA_MAJOR}: ${SYSTEM_JAVA}"
    fi
  fi
  log "bootstrapping ${PREFIX}"
  "${SCRIPT_DIR}/install.sh" --prefix "${PREFIX}" --jar "${JAR}" \
    --release-id "${RELEASE_ID}" --no-start "${INSTALL_JAVA_ARGS[@]}"
  printf 'release.id=%s\ncommit=%s\ndirty=%s\nbuilt.at=%s\n' \
    "${RELEASE_ID}" "${COMMIT}" "${DIRTY}" "${BUILT_AT}" \
    > "${PREFIX}/releases/${RELEASE_ID}/release.properties"
  chmod 0644 "${PREFIX}/releases/${RELEASE_ID}/release.properties"
  systemctl start "${SERVICE}"
  if ! wait_for_health; then
    systemctl --no-pager --full status "${SERVICE}" || true
    journalctl -u "${SERVICE}" -n 80 --no-pager || true
    systemctl stop "${SERVICE}" 2>/dev/null || true
    die "initial deployment did not become healthy after ${HEALTH_ATTEMPTS} attempts"
  fi
  exit 0
fi

[[ -d "${PREFIX}/releases" && -d "${PREFIX}/var/db" ]] || die "installation layout is incomplete"

# Regular deployments refresh the root-owned unit as well as the jar. This keeps
# launch-mode and hardening fixes current without overwriting host configuration.
RUN_USER="$(stat -c '%U' "${PREFIX}/var")"
RUN_GROUP="$(stat -c '%G' "${PREFIX}/var")"
if [[ -x "${PREFIX}/jdk/bin/java" ]]; then
  JAVA_BIN="${PREFIX}/jdk/bin/java"
else
  JAVA_BIN="$(readlink -f "$(command -v java)")"
fi
[[ -x "${JAVA_BIN}" && "${JAVA_BIN}" != /home/* ]] || die "safe Java runtime not found for systemd unit"
UNIT="/etc/systemd/system/${SERVICE}.service"
sed -e "s|@PREFIX@|${PREFIX}|g" \
    -e "s|@JAVA_BIN@|${JAVA_BIN}|g" \
    -e "s|@USER@|${RUN_USER}|g" \
    -e "s|@GROUP@|${RUN_GROUP}|g" \
    "${SCRIPT_DIR}/templates/ioc-extractor.service" > "${UNIT}.tmp"
install -o root -g root -m 0644 "${UNIT}.tmp" "${UNIT}"
rm -f "${UNIT}.tmp"
systemctl daemon-reload

mkdir -p "${PREFIX}/bin"
chown root:"${RUN_GROUP}" "${PREFIX}/bin"
chmod 0750 "${PREFIX}/bin"
sed -e "s|@PREFIX@|${PREFIX}|g" \
    -e "s|@JAVA_BIN@|${JAVA_BIN}|g" \
    -e "s|@USER@|${RUN_USER}|g" \
    -e "s|@GROUP@|${RUN_GROUP}|g" \
    "${SCRIPT_DIR}/templates/ioc" > "${PREFIX}/bin/ioc.tmp"
install -o root -g "${RUN_GROUP}" -m 0750 "${PREFIX}/bin/ioc.tmp" "${PREFIX}/bin/ioc"
rm -f "${PREFIX}/bin/ioc.tmp"
PREVIOUS_TARGET="$(readlink "${PREFIX}/current")"
[[ "${PREVIOUS_TARGET}" == releases/* ]] || die "current symlink points outside releases: ${PREVIOUS_TARGET}"
PREVIOUS_DIR="${PREFIX}/${PREVIOUS_TARGET}"

RELEASE_DIR="${PREFIX}/releases/${RELEASE_ID}"
if [[ -e "${RELEASE_DIR}" ]]; then
  [[ -f "${RELEASE_DIR}/ioc-app.jar" ]] || die "existing release is incomplete"
  [[ "$(sha256sum "${JAR}" | awk '{print $1}')" == \
     "$(sha256sum "${RELEASE_DIR}/ioc-app.jar" | awk '{print $1}')" ]] \
    || die "release id collision with different artifact bytes"
else
  STAGING="${PREFIX}/releases/.${RELEASE_ID}.tmp"
  rm -rf -- "${STAGING}"
  mkdir -p "${STAGING}"
  install -m 0644 "${JAR}" "${STAGING}/ioc-app.jar"
  printf '%s  ioc-app.jar\n' "$(sha256sum "${STAGING}/ioc-app.jar" | awk '{print $1}')" \
    > "${STAGING}/ioc-app.jar.sha256"
  printf 'release.id=%s\ncommit=%s\ndirty=%s\nbuilt.at=%s\n' \
    "${RELEASE_ID}" "${COMMIT}" "${DIRTY}" "${BUILT_AT}" > "${STAGING}/release.properties"
  chmod 0644 "${STAGING}/release.properties" "${STAGING}/ioc-app.jar.sha256"
  mv "${STAGING}" "${RELEASE_DIR}"
fi
chown -R root:root "${RELEASE_DIR}"

mkdir -p "${PREFIX}/backups"
BACKUP="${PREFIX}/backups/${RELEASE_ID}-db.tar"
ROLLBACK_ARMED="false"
rollback_on_error() {
  local status=$?
  trap - ERR
  if [[ "${ROLLBACK_ARMED}" == "true" ]]; then
    log "activation failed; restoring ${PREVIOUS_TARGET} and SQLite backup"
    systemctl stop "${SERVICE}" 2>/dev/null || true
    CURRENT_TMP="${PREFIX}/.current.rollback.$$"
    rm -f -- "${CURRENT_TMP}"
    ln -s "${PREVIOUS_TARGET}" "${CURRENT_TMP}"
    mv -Tf "${CURRENT_TMP}" "${PREFIX}/current"
    if [[ -f "${BACKUP}" ]]; then
      rm -rf -- "${PREFIX}/var/db"
      tar -C "${PREFIX}/var" -xf "${BACKUP}"
    fi
    systemctl start "${SERVICE}" || true
    if ! wait_for_health; then
      systemctl --no-pager --full status "${SERVICE}" || true
      journalctl -u "${SERVICE}" -n 100 --no-pager || true
      printf '\033[1;31m[error]\033[0m rollback health check failed\n' >&2
    fi
  fi
  exit "${status}"
}
trap rollback_on_error ERR

log "stopping ${SERVICE} and backing up SQLite state"
ROLLBACK_ARMED="true"
systemctl stop "${SERVICE}"
tar -C "${PREFIX}/var" -cf "${BACKUP}.tmp" db
mv -f "${BACKUP}.tmp" "${BACKUP}"

CURRENT_TMP="${PREFIX}/.current.$$"
ln -s "releases/${RELEASE_ID}" "${CURRENT_TMP}"
mv -Tf "${CURRENT_TMP}" "${PREFIX}/current"

log "starting ${RELEASE_ID}"
systemctl start "${SERVICE}"
if ! wait_for_health; then
  systemctl --no-pager --full status "${SERVICE}" || true
  journalctl -u "${SERVICE}" -n 80 --no-pager || true
  false
fi
ROLLBACK_ARMED="false"
trap - ERR

ACTIVE_DIR="$(readlink -f "${PREFIX}/current")"
prune_directories "${PREFIX}/releases" "${RELEASE_RETENTION}" "${ACTIVE_DIR}"
prune_files "${PREFIX}/backups" "${BACKUP_RETENTION}"
log "deployment is healthy; previous release was ${PREVIOUS_DIR}"
