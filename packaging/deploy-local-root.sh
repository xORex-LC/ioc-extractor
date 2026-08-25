#!/usr/bin/env bash
# Privileged local activation phase. Do not call directly; deploy-local.sh builds
# and verifies the artifact before invoking this script through sudo.
set -Eeuo pipefail

SERVICE="ioc-extractor"
PREFIX=""
JAR=""
EXPECTED_JAR_SHA256=""
RELEASE_ID=""
COMMIT=""
DIRTY="false"
BUILT_AT=""
PORT="8081"
RELEASE_RETENTION="5"
BACKUP_RETENTION="5"
HEALTH_ATTEMPTS="15"
HEALTH_INTERVAL="2"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=packaging/install-layout.sh
. "${SCRIPT_DIR}/install-layout.sh"

log() { printf '\033[1;34m[activate]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix) PREFIX="${2:?}"; shift 2 ;;
    --jar) JAR="${2:?}"; shift 2 ;;
    --jar-sha256) EXPECTED_JAR_SHA256="${2:?}"; shift 2 ;;
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
for command in awk chmod chown cmp cp curl date find flock getent grep id install journalctl ln \
    mkdir mktemp mv readlink realpath rm sed sha256sum sleep sort stat systemctl tar; do
  command -v "${command}" >/dev/null 2>&1 || die "required command not found: ${command}"
done
ioc_validate_prefix "${PREFIX}" || die "unsafe installation prefix"
PREFIX="${IOC_VALIDATED_PREFIX}"
[[ -f "${JAR}" && ! -L "${JAR}" ]] || die "application jar must be a regular non-symlink file"
[[ "${EXPECTED_JAR_SHA256}" =~ ^[0-9a-f]{64}$ ]] || die "invalid application SHA-256"
[[ "$(sha256sum "${JAR}" | awk '{print $1}')" == "${EXPECTED_JAR_SHA256}" ]] \
  || die "application jar changed after unprivileged verification"
[[ "${RELEASE_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || die "invalid release id"
[[ "${COMMIT}" =~ ^[0-9a-f]{40}$ ]] || die "invalid commit SHA"
[[ "${DIRTY}" == "true" || "${DIRTY}" == "false" ]] || die "invalid dirty flag"
for value in "${PORT}" "${RELEASE_RETENTION}" "${BACKUP_RETENTION}" "${HEALTH_ATTEMPTS}" "${HEALTH_INTERVAL}"; do
  [[ "${value}" =~ ^[1-9][0-9]*$ ]] || die "numeric arguments must be positive integers"
done
[[ "${PORT}" -le 65535 ]] || die "invalid port"

exec 9>/run/lock/ioc-extractor-deploy.lock
flock -n 9 || die "another privileged deployment is already running"

health_ready() {
  local base="http://127.0.0.1:${PORT}/actuator/health" component body
  for component in jdbcStorage dataframeStorage artifactStorage; do
    body="$(curl --noproxy '*' --silent --fail --max-time 2 \
      "${base}/${component}" 2>/dev/null)" || return 1
    grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' \
      <<< "${body}" || return 1
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

prune_backup_sets() { # parent keep
  local parent="$1" keep="$2" database_backup unit_backup
  local -a database_backups=()
  mapfile -t database_backups < <(find "${parent}" -mindepth 1 -maxdepth 1 \
    -type f -name '*-db.tar' -printf '%T@ %p\n' \
    | sort -nr | awk -v keep="${keep}" 'NR > keep { sub(/^[^ ]+ /, ""); print }')
  for database_backup in "${database_backups[@]:-}"; do
    [[ -n "${database_backup}" ]] || continue
    unit_backup="${database_backup%-db.tar}-unit.service"
    rm -f -- "${database_backup}" "${unit_backup}"
  done
  while IFS= read -r -d '' unit_backup; do
    database_backup="${unit_backup%-unit.service}-db.tar"
    [[ -f "${database_backup}" ]] || rm -f -- "${unit_backup}"
  done < <(find "${parent}" -mindepth 1 -maxdepth 1 \
    -type f -name '*-unit.service' -print0)
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
    --release-id "${RELEASE_ID}" --server-port "${PORT}" --no-start \
    "${INSTALL_JAVA_ARGS[@]}"
  [[ "$(sha256sum "${PREFIX}/releases/${RELEASE_ID}/ioc-app.jar" | awk '{print $1}')" \
      == "${EXPECTED_JAR_SHA256}" ]] || die "installed application checksum mismatch"
  printf 'release.id=%s\ncommit=%s\ndirty=%s\nbuilt.at=%s\nartifact.sha256=%s\n' \
    "${RELEASE_ID}" "${COMMIT}" "${DIRTY}" "${BUILT_AT}" "${EXPECTED_JAR_SHA256}" \
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
ioc_validate_service_user "${RUN_USER}" || die "unsafe installed service account"
install -d -o "${RUN_USER}" -g "${RUN_GROUP}" -m 0750 \
  "${PREFIX}/var/import" \
  "${PREFIX}/var/import/inbox" "${PREFIX}/var/import/processing" \
  "${PREFIX}/var/import/snapshots" "${PREFIX}/var/import/staging" \
  "${PREFIX}/var/import/terminal" "${PREFIX}/var/import/quarantine"
MARKER="$(ioc_marker_path "${PREFIX}")"
if [[ -e "${MARKER}" ]]; then
  ioc_is_valid_marker "${PREFIX}" "${SERVICE}" "${RUN_USER}" \
    || die "invalid or mismatched installation marker: ${MARKER}"
elif ioc_is_pre_marker_release_layout "${PREFIX}"; then
  log "adopting validated pre-marker installation at ${PREFIX}"
  ioc_write_marker "${PREFIX}" "${SERVICE}" "${RUN_USER}"
  chown root:"${RUN_GROUP}" "${MARKER}"
  chmod 0640 "${MARKER}"
else
  die "existing prefix is not a validated ioc-extractor installation"
fi
if [[ -x "${PREFIX}/jdk/bin/java" ]]; then
  JAVA_BIN="${PREFIX}/jdk/bin/java"
else
  JAVA_BIN="$(readlink -f "$(command -v java)")"
fi
[[ -x "${JAVA_BIN}" && "${JAVA_BIN}" != /home/* ]] || die "safe Java runtime not found for systemd unit"

PREVIOUS_TARGET="$(readlink "${PREFIX}/current")"
ioc_is_release_target "${PREVIOUS_TARGET}" \
  || die "current symlink points outside releases: ${PREVIOUS_TARGET}"
PREVIOUS_DIR="${PREFIX}/${PREVIOUS_TARGET}"
UNIT="/etc/systemd/system/${SERVICE}.service"
mkdir -p "${PREFIX}/backups"
BACKUP="${PREFIX}/backups/${RELEASE_ID}-db.tar"
UNIT_BACKUP="${PREFIX}/backups/${RELEASE_ID}-unit.service"
install -o root -g root -m 0644 "${UNIT}" "${UNIT_BACKUP}.tmp"
mv -f "${UNIT_BACKUP}.tmp" "${UNIT_BACKUP}"

ROLLBACK_ARMED="false"
UNIT_REFRESHED="false"
rollback_on_error() {
  local status=$?
  trap - ERR
  if [[ "${ROLLBACK_ARMED}" == "true" ]]; then
    log "activation failed; restoring ${PREVIOUS_TARGET}, systemd unit and SQLite backup"
    systemctl stop "${SERVICE}" 2>/dev/null || true
    CURRENT_TMP="${PREFIX}/.current.rollback.$$"
    rm -f -- "${CURRENT_TMP}"
    ln -s "${PREVIOUS_TARGET}" "${CURRENT_TMP}"
    mv -Tf "${CURRENT_TMP}" "${PREFIX}/current"
    if [[ -f "${BACKUP}" ]]; then
      FAILED_DB="${PREFIX}/var/db.failed-${RELEASE_ID}-$$"
      [[ ! -e "${FAILED_DB}" ]] || FAILED_DB="${FAILED_DB}.retry"
      [[ ! -e "${PREFIX}/var/db" ]] || mv "${PREFIX}/var/db" "${FAILED_DB}"
      tar -C "${PREFIX}/var" -xf "${BACKUP}"
    fi
  fi
  if [[ "${UNIT_REFRESHED}" == "true" && -f "${UNIT_BACKUP}" ]]; then
    install -o root -g root -m 0644 "${UNIT_BACKUP}" "${UNIT}"
    systemctl daemon-reload || true
  fi
  if [[ "${ROLLBACK_ARMED}" == "true" ]]; then
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

refresh_config_candidate() { # template installed-file
  local template="$1" installed="$2"
  if [[ -f "${installed}" ]] && cmp -s "${template}" "${installed}"; then
    return
  fi
  if [[ -f "${installed}.new" ]] && ! cmp -s "${template}" "${installed}.new"; then
    die "unreconciled configuration candidate would be overwritten: ${installed}.new"
  fi
  install -o root -g "${RUN_GROUP}" -m 0640 "${template}" "${installed}.new"
  log "configuration template changed; review ${installed}.new"
}
refresh_config_candidate "${SCRIPT_DIR}/templates/application.yml" "${PREFIX}/etc/application.yml"
refresh_config_candidate "${SCRIPT_DIR}/templates/ioc-extractor.env" "${PREFIX}/etc/ioc-extractor.env"

sed -e "s|@PREFIX@|${PREFIX}|g" \
    -e "s|@JAVA_BIN@|${JAVA_BIN}|g" \
    -e "s|@USER@|${RUN_USER}|g" \
    -e "s|@GROUP@|${RUN_GROUP}|g" \
    -e "s|@SERVER_PORT_ARG@|--server.port=${PORT}|g" \
    "${SCRIPT_DIR}/templates/ioc-extractor.service" > "${UNIT}.tmp"
install -o root -g root -m 0644 "${UNIT}.tmp" "${UNIT}"
UNIT_REFRESHED="true"
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
sed -e "s|@PREFIX@|${PREFIX}|g" \
    -e "s|@JAVA_BIN@|${JAVA_BIN}|g" \
    -e "s|@GROUP@|${RUN_GROUP}|g" \
    -e "s|@SERVER_PORT@|${PORT}|g" \
    "${SCRIPT_DIR}/templates/ioc-config" > "${PREFIX}/bin/ioc-config.tmp"
install -o root -g "${RUN_GROUP}" -m 0750 \
  "${PREFIX}/bin/ioc-config.tmp" "${PREFIX}/bin/ioc-config"
rm -f "${PREFIX}/bin/ioc-config.tmp"
RELEASE_DIR="${PREFIX}/releases/${RELEASE_ID}"
if [[ -e "${RELEASE_DIR}" ]]; then
  [[ -f "${RELEASE_DIR}/ioc-app.jar" ]] || die "existing release is incomplete"
  [[ "${EXPECTED_JAR_SHA256}" == \
     "$(sha256sum "${RELEASE_DIR}/ioc-app.jar" | awk '{print $1}')" ]] \
    || die "release id collision with different artifact bytes"
else
  STAGING="${PREFIX}/releases/.${RELEASE_ID}.tmp"
  rm -rf -- "${STAGING}"
  mkdir -p "${STAGING}"
  install -m 0644 "${JAR}" "${STAGING}/ioc-app.jar"
  [[ "$(sha256sum "${STAGING}/ioc-app.jar" | awk '{print $1}')" \
      == "${EXPECTED_JAR_SHA256}" ]] || die "staged application checksum mismatch"
  printf '%s  ioc-app.jar\n' "${EXPECTED_JAR_SHA256}" \
    > "${STAGING}/ioc-app.jar.sha256"
  printf 'release.id=%s\ncommit=%s\ndirty=%s\nbuilt.at=%s\nartifact.sha256=%s\n' \
    "${RELEASE_ID}" "${COMMIT}" "${DIRTY}" "${BUILT_AT}" "${EXPECTED_JAR_SHA256}" \
    > "${STAGING}/release.properties"
  chmod 0644 "${STAGING}/release.properties" "${STAGING}/ioc-app.jar.sha256"
  mv "${STAGING}" "${RELEASE_DIR}"
fi
chown -R root:root "${RELEASE_DIR}"

log "stopping ${SERVICE} and backing up SQLite state"
ROLLBACK_ARMED="true"
systemctl stop "${SERVICE}"
tar -C "${PREFIX}/var" -cf "${BACKUP}.tmp" db
tar -tf "${BACKUP}.tmp" >/dev/null
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
UNIT_REFRESHED="false"
trap - ERR

ACTIVE_DIR="$(readlink -f "${PREFIX}/current")"
prune_directories "${PREFIX}/releases" "${RELEASE_RETENTION}" "${ACTIVE_DIR}"
prune_backup_sets "${PREFIX}/backups" "${BACKUP_RETENTION}"
log "deployment is healthy; previous release was ${PREVIOUS_DIR}"
