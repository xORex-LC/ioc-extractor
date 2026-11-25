#!/usr/bin/env bash
# Privileged local activation phase. Do not call directly; deploy-local.sh builds
# and verifies the artifact before invoking this script through sudo.
set -Eeuo pipefail

SERVICE="ioc-extractor"
UNIT="/etc/systemd/system/${SERVICE}.service"
DEPLOY_LOCK="/run/lock/ioc-extractor-deploy.lock"
CONFIG_LOCK="/run/lock/ioc-extractor-config.lock"
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
    mkdir mktemp mv readlink realpath rm rmdir sed sha256sum sleep sort stat systemctl systemd-run tar; do
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

exec 9>"${DEPLOY_LOCK}"
flock -n 9 || die "another privileged deployment is already running"

health_ready() {
  local base="http://127.0.0.1:$1/actuator/health" component body
  for component in jdbcStorage dataframeStorage artifactStorage; do
    body="$(curl --noproxy '*' --silent --fail --max-time 2 \
      "${base}/${component}" 2>/dev/null)" || return 1
    grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' \
      <<< "${body}" || return 1
  done
}

wait_for_health() {
  local port="$1" attempt
  for ((attempt = 1; attempt <= HEALTH_ATTEMPTS; attempt++)); do
    systemctl is-active --quiet "${SERVICE}" && health_ready "${port}" && return 0
    (( attempt == HEALTH_ATTEMPTS )) || sleep "${HEALTH_INTERVAL}"
  done
  return 1
}

prune_releases() {
  local releases="$1" path
  local -a paths=()
  mapfile -t paths < <(find "${releases}" -mindepth 1 -maxdepth 1 -type d ! -name '.deploy.*' -printf '%T@ %p\n' \
    | sort -nr | awk -v keep="${RELEASE_RETENTION}" 'NR > keep { sub(/^[^ ]+ /, ""); print }')
  for path in "${paths[@]:-}"; do
    [[ -n "${path}" && "${path}" != "${RELEASE_DIR}" && "${path}" != "${PREVIOUS_DIR}" ]] || continue
    rm -rf -- "${path}"
  done
}

prune_backup_sets() {
  local backups="$1" entry attempt
  local -a entries=()
  # Retain incomplete attempts for manual recovery. Include legacy archive pairs
  # in the same retention budget as completed attempt directories.
  mapfile -t entries < <(find "${backups}" -maxdepth 2 -type f \
    \( -path "${backups}/local-deploy.*/completed" -o -name '*-db.tar' \) -printf '%T@ %p\n' \
    | sort -nr | awk -v keep="${BACKUP_RETENTION}" 'NR > keep { sub(/^[^ ]+ /, ""); print }')
  for entry in "${entries[@]}"; do
    attempt="${entry%/*}"
    if [[ "${entry##*/}" == completed && "${attempt%/*}" == "${backups}" ]]; then
      [[ "${attempt}" == "${ATTEMPT_DIR}" ]] || rm -rf -- "${attempt}"
    elif [[ "${attempt}" == "${backups}" && "${entry}" == *-db.tar ]]; then
      rm -f -- "${entry}" "${entry%-db.tar}-unit.service"
    fi
  done
}

if [[ ! -e "${PREFIX}/current" || ! -f "${UNIT}" ]]; then
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
  if ! wait_for_health "${PORT}"; then
    systemctl --no-pager --full status "${SERVICE}" || true
    journalctl -u "${SERVICE}" -n 80 --no-pager || true
    systemctl stop "${SERVICE}" 2>/dev/null || true
    die "initial deployment did not become healthy after ${HEALTH_ATTEMPTS} attempts"
  fi
  exit 0
fi

[[ -d "${PREFIX}/releases" && -d "${PREFIX}/var/db" && ! -L "${PREFIX}/var/db" ]] \
  || die "installation layout is incomplete or the database directory is a symlink"

# Share the existing apply lock so the YAML and old unit cannot be replaced
# between candidate validation and activation by an ioc-config invocation.
exec 8>"${CONFIG_LOCK}"
flock -n 8 || die "a configuration apply is already running"

RUN_USER="$(stat -c '%U' "${PREFIX}/var")"
RUN_GROUP="$(stat -c '%G' "${PREFIX}/var")"
ioc_validate_service_user "${RUN_USER}" || die "unsafe installed service account"
MARKER="$(ioc_marker_path "${PREFIX}")"
if [[ -e "${MARKER}" ]]; then
  ioc_is_valid_marker "${PREFIX}" "${SERVICE}" "${RUN_USER}" \
    || die "invalid or mismatched installation marker: ${MARKER}"
elif ! ioc_is_pre_marker_release_layout "${PREFIX}"; then
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

WORK_DIR=""
ATTEMPT_DIR=""
PHASE="preparing"
STEP="preparing candidate"
LIVE_FILES=("${UNIT}" "${PREFIX}/bin/ioc" "${PREFIX}/bin/ioc-config" "${PREFIX}/current")
SNAPSHOT_FILES=("unit.service" "ioc" "ioc-config" "current")

replace_file() {
  local source="$1" target="$2" temporary status=0
  # Stage on the destination filesystem, including for the symlink and unit.
  temporary="$(mktemp "${target%/*}/.ioc-deploy.XXXXXX")" || return 1
  cp -a --remove-destination -- "${source}" "${temporary}" \
    && mv -Tf -- "${temporary}" "${target}" || status=$?
  if (( status != 0 )); then
    rm -f -- "${temporary}" || true
  fi
  return "${status}"
}

service_state() {
  systemctl show --property=ActiveState --value "${SERVICE}"
}

service_stopped() {
  local state
  state="$(service_state)" || return 1
  [[ "${state}" == inactive || "${state}" == failed ]]
}

show_service_failure() {
  systemctl --no-pager --full status "${SERVICE}" || true
  journalctl -u "${SERVICE}" -n 80 --no-pager || true
}

restore_databases() {
  local restore_dir failed_db
  restore_dir="$(mktemp -d "${PREFIX}/var/.db.restore.XXXXXX")" || return 1
  failed_db="${PREFIX}/var/db.failed-${ATTEMPT_DIR##*/}"
  # Extract before replacing anything live so a read/extraction error leaves
  # the stopped candidate's database directory available for investigation.
  if ! tar --acls --xattrs -p -C "${restore_dir}" -xf "${ATTEMPT_DIR}/db.tar" \
      || [[ ! -d "${restore_dir}/db" || -e "${failed_db}" || -L "${failed_db}" ]] \
      || ! mv -T -- "${PREFIX}/var/db" "${failed_db}" \
      || ! mv -T -- "${restore_dir}/db" "${PREFIX}/var/db"; then
    printf '[activate] database restore incomplete; inspect %s and %s\n' \
      "${restore_dir}" "${failed_db}" >&2
    return 1
  fi
  log "preserved candidate databases in ${failed_db}"
  rmdir -- "${restore_dir}" || true
}

restore_host_files() {
  local index saved live failed=false
  for index in "${!LIVE_FILES[@]}"; do
    saved="${ATTEMPT_DIR}/${SNAPSHOT_FILES[index]}"
    live="${LIVE_FILES[index]}"
    if [[ -e "${saved}" || -L "${saved}" ]]; then
      replace_file "${saved}" "${live}" && continue
    else
      rm -f -- "${live}" && continue
    fi
    printf '[activate] could not restore %s\n' "${live}" >&2
    failed=true
  done
  if [[ "${PREVIOUS_BIN_PRESENT}" == false && -d "${PREFIX}/bin" ]] \
      && ! rmdir -- "${PREFIX}/bin"; then
    failed=true
  fi
  if ! systemctl daemon-reload; then
    printf '[activate] could not reload the restored systemd unit\n' >&2
    failed=true
  fi
  [[ "${failed}" == false ]]
}

rollback() {
  local failed=false
  log "recovering the previous release from ${ATTEMPT_DIR}"
  if [[ "${PHASE}" == starting ]]; then
    if ! systemctl stop "${SERVICE}" || ! service_stopped; then
      printf '[activate] could not confirm the candidate stopped; leave the databases in place\n' >&2
      return 1
    fi
    restore_databases || failed=true
  fi
  if [[ "${PHASE}" == activating || "${PHASE}" == starting ]]; then
    restore_host_files || failed=true
  fi
  [[ "${failed}" == false ]] || return 1
  if [[ "${PREVIOUS_STATE}" == active ]]; then
    if ! systemctl is-active --quiet "${SERVICE}"; then
      # A stopped unit may already be unloaded. Reset the start limit if
      # possible, then let start load the restored unit and report its result.
      systemctl reset-failed "${SERVICE}" 2>/dev/null || true
      if ! systemctl start "${SERVICE}"; then
        show_service_failure
        return 1
      fi
    fi
    if ! wait_for_health "${PREVIOUS_PORT}"; then
      show_service_failure
      printf '[activate] previous release did not become healthy on port %s\n' "${PREVIOUS_PORT}" >&2
      return 1
    fi
  else
    service_stopped || return 1
  fi
}

finish_activation() {
  local status=$?
  trap - EXIT
  trap '' INT TERM HUP
  if (( status != 0 )); then
    printf '[activate] %s failed (exit %d)\n' "${STEP}" "${status}" >&2
    if [[ "${PHASE}" == stopping || "${PHASE}" == activating || "${PHASE}" == starting ]]; then
      if rollback; then
        log "previous release restored"
        rm -f -- "${ATTEMPT_DIR}/db.tar.pending" || true
        printf '%s\n' rolled-back > "${ATTEMPT_DIR}/completed" || true
      else
        printf '[activate] rollback incomplete; recovery files: %s\n' "${ATTEMPT_DIR}" >&2
      fi
    fi
  fi
  [[ -z "${WORK_DIR}" ]] || rm -rf -- "${WORK_DIR}" || true
  exit "${status}"
}

trap finish_activation EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP
WORK_DIR="$(mktemp -d "${PREFIX}/releases/.deploy.XXXXXX")"

refresh_config_candidate() { # template installed-file
  local template="$1" installed="$2"
  if [[ -f "${installed}" ]] && cmp -s "${template}" "${installed}"; then
    return
  fi
  if [[ -f "${installed}.new" ]] && ! cmp -s "${template}" "${installed}.new"; then
    ioc_report_config_candidate_conflict "${template}" "${installed}"
    die "configuration candidate reconciliation is required"
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
    "${SCRIPT_DIR}/templates/ioc-extractor.service" > "${WORK_DIR}/unit.service"
chown root:root "${WORK_DIR}/unit.service"
chmod 0644 "${WORK_DIR}/unit.service"
sed -e "s|@PREFIX@|${PREFIX}|g" \
    -e "s|@JAVA_BIN@|${JAVA_BIN}|g" \
    -e "s|@USER@|${RUN_USER}|g" \
    -e "s|@GROUP@|${RUN_GROUP}|g" \
    "${SCRIPT_DIR}/templates/ioc" > "${WORK_DIR}/ioc"
sed -e "s|@PREFIX@|${PREFIX}|g" \
    -e "s|@JAVA_BIN@|${JAVA_BIN}|g" \
    -e "s|@USER@|${RUN_USER}|g" \
    -e "s|@GROUP@|${RUN_GROUP}|g" \
    -e "s|@SERVER_PORT@|${PORT}|g" \
    "${SCRIPT_DIR}/templates/ioc-config" > "${WORK_DIR}/ioc-config"
chown root:"${RUN_GROUP}" "${WORK_DIR}/ioc" "${WORK_DIR}/ioc-config"
chmod 0750 "${WORK_DIR}/ioc" "${WORK_DIR}/ioc-config"
ln -s "releases/${RELEASE_ID}" "${WORK_DIR}/current"

RELEASE_DIR="${PREFIX}/releases/${RELEASE_ID}"
if [[ -e "${RELEASE_DIR}" ]]; then
  [[ -f "${RELEASE_DIR}/ioc-app.jar" ]] || die "existing release is incomplete"
  [[ "${EXPECTED_JAR_SHA256}" == \
     "$(sha256sum "${RELEASE_DIR}/ioc-app.jar" | awk '{print $1}')" ]] \
    || die "release id collision with different artifact bytes"
else
  STAGING="${WORK_DIR}/release"
  install -d -o root -g "${RUN_GROUP}" -m 0750 "${STAGING}"
  install -m 0644 "${JAR}" "${STAGING}/ioc-app.jar"
  [[ "$(sha256sum "${STAGING}/ioc-app.jar" | awk '{print $1}')" \
      == "${EXPECTED_JAR_SHA256}" ]] || die "staged application checksum mismatch"
  printf '%s  ioc-app.jar\n' "${EXPECTED_JAR_SHA256}" \
    > "${STAGING}/ioc-app.jar.sha256"
  printf 'release.id=%s\ncommit=%s\ndirty=%s\nbuilt.at=%s\nartifact.sha256=%s\n' \
    "${RELEASE_ID}" "${COMMIT}" "${DIRTY}" "${BUILT_AT}" "${EXPECTED_JAR_SHA256}" \
    > "${STAGING}/release.properties"
  chmod 0644 "${STAGING}/release.properties" "${STAGING}/ioc-app.jar.sha256"
  mv -T -- "${STAGING}" "${RELEASE_DIR}"
fi

STEP="validating effective configuration"
log "validating effective configuration with ${RELEASE_ID}"
CONFIG_CHECK_UNIT="ioc-extractor-config-check-$$"
if ! systemd-run --quiet --wait --pipe --collect \
    --unit="${CONFIG_CHECK_UNIT}" \
    --property=Type=exec \
    --property="User=${RUN_USER}" \
    --property="Group=${RUN_GROUP}" \
    --property="WorkingDirectory=${PREFIX}" \
    --property="EnvironmentFile=${PREFIX}/etc/ioc-extractor.env" \
    --property=NoNewPrivileges=true \
    --property=ProtectSystem=strict \
    --property=ProtectHome=true \
    --property=PrivateTmp=true \
    "${JAVA_BIN}" "\$JAVA_OPTS" -Xms32m -Xmx128m \
    -Duser.timezone=UTC -Dfile.encoding=UTF-8 \
    -jar "${RELEASE_DIR}/ioc-app.jar" \
    "--ioc.validate-config=${PREFIX}/etc/application.yml" \
    --ioc.runtime.mode=daemon "--server.port=${PORT}"; then
  die "new release rejected the effective installed configuration before activation"
fi

STEP="preparing rollback point"
PREVIOUS_STATE="$(service_state)"
case "${PREVIOUS_STATE}" in
  active|inactive|failed) ;;
  *) die "service is changing state (${PREVIOUS_STATE}); retry when it is settled" ;;
esac
PREVIOUS_BIN_PRESENT=false
[[ ! -d "${PREFIX}/bin" ]] || PREVIOUS_BIN_PRESENT=true
PREVIOUS_PORT="$(sed -nE 's/^ExecStart=.*[[:space:]]--server\.port=([0-9]+)([[:space:]].*)?$/\1/p' "${UNIT}")"
if [[ -z "${PREVIOUS_PORT}" && -f "${PREFIX}/bin/ioc-config" ]]; then
  PREVIOUS_PORT="$(sed -nE 's/^SERVER_PORT="([0-9]+)"$/\1/p' "${PREFIX}/bin/ioc-config")"
fi
PREVIOUS_PORT="${PREVIOUS_PORT:-8081}"
mkdir -p "${PREFIX}/backups"
ATTEMPT_DIR="$(mktemp -d "${PREFIX}/backups/local-deploy.${RELEASE_ID}.XXXXXX")"
for index in "${!LIVE_FILES[@]}"; do
  file="${LIVE_FILES[index]}"
  if [[ -e "${file}" || -L "${file}" ]]; then
    cp -a -- "${file}" "${ATTEMPT_DIR}/${SNAPSHOT_FILES[index]}"
  fi
done
printf 'release=%s\nprevious.target=%s\nprevious.state=%s\nprevious.port=%s\nprevious.bin.present=%s\n' \
  "${RELEASE_ID}" "${PREVIOUS_TARGET}" "${PREVIOUS_STATE}" "${PREVIOUS_PORT}" "${PREVIOUS_BIN_PRESENT}" \
  > "${ATTEMPT_DIR}/attempt.properties"
log "deployment recovery directory: ${ATTEMPT_DIR}"

if [[ ! -e "${MARKER}" ]]; then
  log "adopting validated pre-marker installation at ${PREFIX}"
  ioc_write_marker "${PREFIX}" "${SERVICE}" "${RUN_USER}"
  chown root:"${RUN_GROUP}" "${MARKER}"
  chmod 0640 "${MARKER}"
fi
install -d -o "${RUN_USER}" -g "${RUN_GROUP}" -m 0750 \
  "${PREFIX}/var/import" \
  "${PREFIX}/var/import/inbox" "${PREFIX}/var/import/processing" \
  "${PREFIX}/var/import/snapshots" "${PREFIX}/var/import/staging" \
  "${PREFIX}/var/import/terminal" "${PREFIX}/var/import/quarantine"

STEP="stopping ${SERVICE}"
PHASE="stopping"
systemctl stop "${SERVICE}"
service_stopped || die "could not confirm the installed service stopped"

STEP="backing up SQLite state"
tar --acls --xattrs -C "${PREFIX}/var" -cf "${ATTEMPT_DIR}/db.tar.pending" db
tar --acls --xattrs -C "${PREFIX}/var" --compare -f "${ATTEMPT_DIR}/db.tar.pending"
mv -T -- "${ATTEMPT_DIR}/db.tar.pending" "${ATTEMPT_DIR}/db.tar"

STEP="installing staged host files"
PHASE="activating"
if [[ "${PREVIOUS_BIN_PRESENT}" == false ]]; then
  install -d -o root -g "${RUN_GROUP}" -m 0750 "${PREFIX}/bin"
fi
for index in "${!LIVE_FILES[@]}"; do
  replace_file "${WORK_DIR}/${SNAPSHOT_FILES[index]}" "${LIVE_FILES[index]}"
done
STEP="reloading systemd"
systemctl daemon-reload

STEP="starting ${RELEASE_ID}"
: > "${ATTEMPT_DIR}/candidate-start-attempted"
PHASE="starting"
log "starting ${RELEASE_ID}"
systemctl start "${SERVICE}"
STEP="checking candidate health"
if ! wait_for_health "${PORT}"; then
  show_service_failure
  die "candidate did not become healthy after ${HEALTH_ATTEMPTS} attempts"
fi
PHASE="complete"

log "deployment is healthy; previous release was ${PREVIOUS_DIR}"
STEP="recording completion"
printf '%s\n' activated > "${ATTEMPT_DIR}/completed"
STEP="pruning deployment history"
prune_releases "${PREFIX}/releases"
prune_backup_sets "${PREFIX}/backups"
