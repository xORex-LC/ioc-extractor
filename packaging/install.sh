#!/usr/bin/env bash
#
# ioc-extractor — installer for Debian 11/12 (systemd daemon).
#
# Provisions the host from scratch: installs a JDK 21 from a tarball for a
# portable runtime baseline, creates a dedicated system user and the
# runtime directory layout, deploys the application jar + config, installs and
# starts the systemd service.
#
# Self-contained single-directory layout under the chosen prefix:
#   <prefix>/jdk/                 manually-installed Temurin 21 runtime
#   <prefix>/releases/<id>/       immutable application releases
#   <prefix>/current              atomic symlink to the active release
#   <prefix>/etc/                 operator config + root-owned installation marker
#   <prefix>/var/                 db/ export/ inbox/ processing/ done/ failed/ ledger/ logs/
#                                 import/{inbox,processing,snapshots,staging,terminal,quarantine}/
#   <prefix>/dataframe/           generated CSV projections
#
# Idempotent: re-running upgrades the jar and unit; existing config is preserved
# (a *.new is written instead) unless --force is given.
#
# Usage:
#   sudo ./install.sh [--prefix DIR] [--jar PATH] [--checksum PATH]
#                     [--release-id ID] [--user NAME]
#                     [--jdk-tarball PATH | --jdk-url URL] [--jdk-sha256 HEX]
#                     [--system-java] [--server-port PORT]
#                     [--health-attempts N] [--health-interval SECONDS]
#                     [--no-start] [--force] [--help]
#
set -Eeuo pipefail

# ---- defaults --------------------------------------------------------------
SERVICE="ioc-extractor"
DEFAULT_PREFIX="/opt/ioc-extractor"
PREFIX=""
RUN_USER="ioc"
JAR=""
CHECKSUM=""
JDK_TARBALL=""
JDK_URL=""
JDK_SHA256=""
USE_SYSTEM_JAVA="false"
NO_START="false"
FORCE="false"
RELEASE_ID=""
SERVER_PORT="8081"
SERVER_PORT_EXPLICIT="false"
HEALTH_ATTEMPTS="15"
HEALTH_INTERVAL="2"
SYSTEMD_AVAILABLE="false"
SERVICE_WAS_ACTIVE="false"
CLEANUP_TARBALL=""
CLEANUP_JDK_STAGE=""
RECOVERY_ARMED="false"
PREVIOUS_TARGET=""
UNIT_BACKUP=""
UNIT_WRITTEN="false"
CLEANUP_UNIT_STAGE=""

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=packaging/install-layout.sh
. "${SCRIPT_DIR}/install-layout.sh"

# ---- output helpers --------------------------------------------------------
log()  { printf '\033[1;34m[install]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
die()  {
  printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2
  if [[ "${RECOVERY_ARMED}" == "true" ]]; then
    recover_install "${BASH_LINENO[0]:-?}" 1
  fi
  exit 1
}

recover_install() {
  local failed_line="${1:-?}" status="${2:-1}"
  trap - ERR
  warn "installation failed at line ${failed_line}"
  if [[ "${RECOVERY_ARMED}" == "true" ]]; then
    if [[ -n "${PREVIOUS_TARGET}" ]]; then
      local current_tmp="${PREFIX}/.current.install-rollback.$$"
      ln -s "${PREVIOUS_TARGET}" "${current_tmp}" 2>/dev/null || true
      mv -Tf "${current_tmp}" "${PREFIX}/current" 2>/dev/null || true
    fi
    if [[ "${UNIT_WRITTEN}" == "true" && -n "${UNIT_BACKUP}" && -f "${UNIT_BACKUP}" ]]; then
      install -o root -g root -m 0644 "${UNIT_BACKUP}" "/etc/systemd/system/${SERVICE}.service" 2>/dev/null || true
      [[ "${SYSTEMD_AVAILABLE}" != "true" ]] || systemctl daemon-reload 2>/dev/null || true
    elif [[ "${UNIT_WRITTEN}" == "true" ]]; then
      rm -f "/etc/systemd/system/${SERVICE}.service" 2>/dev/null || true
      [[ "${SYSTEMD_AVAILABLE}" != "true" ]] || systemctl daemon-reload 2>/dev/null || true
    fi
    if [[ "${SERVICE_WAS_ACTIVE}" == "true" ]]; then
      systemctl start "${SERVICE}" 2>/dev/null \
        || warn "previous ${SERVICE} instance could not be restarted automatically"
    fi
  fi
  exit "${status}"
}
trap 'recover_install "$LINENO" "$?"' ERR
cleanup_install_temporary_files() {
  [[ -z "${CLEANUP_TARBALL:-}" ]] || rm -f -- "${CLEANUP_TARBALL}"
  [[ -z "${CLEANUP_JDK_STAGE:-}" ]] || rm -rf -- "${CLEANUP_JDK_STAGE}"
  [[ -z "${UNIT_BACKUP:-}" ]] || rm -f -- "${UNIT_BACKUP}"
  [[ -z "${CLEANUP_UNIT_STAGE:-}" ]] || rm -f -- "${CLEANUP_UNIT_STAGE}"
}
trap cleanup_install_temporary_files EXIT

usage() { sed -n '2,27p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0; }

# ---- argument parsing ------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix)       PREFIX="${2:?}"; shift 2 ;;
    --jar)          JAR="${2:?}"; shift 2 ;;
    --checksum)     CHECKSUM="${2:?}"; shift 2 ;;
    --release-id)   RELEASE_ID="${2:?}"; shift 2 ;;
    --user)         RUN_USER="${2:?}"; shift 2 ;;
    --jdk-tarball)  JDK_TARBALL="${2:?}"; shift 2 ;;
    --jdk-url)      JDK_URL="${2:?}"; shift 2 ;;
    --jdk-sha256)   JDK_SHA256="${2:?}"; shift 2 ;;
    --system-java)  USE_SYSTEM_JAVA="true"; shift ;;
    --server-port)  SERVER_PORT="${2:?}"; SERVER_PORT_EXPLICIT="true"; shift 2 ;;
    --health-attempts) HEALTH_ATTEMPTS="${2:?}"; shift 2 ;;
    --health-interval) HEALTH_INTERVAL="${2:?}"; shift 2 ;;
    --no-start)     NO_START="true"; shift ;;
    --force)        FORCE="true"; shift ;;
    -h|--help)      usage ;;
    *)              die "unknown argument: $1 (see --help)" ;;
  esac
done

# ---- preflight -------------------------------------------------------------
for command in awk chmod chown cmp cp curl date find flock getent grep head id install ln mkdir mktemp \
    mv ps readlink realpath rm sed sha256sum sleep tar uname useradd; do
  command -v "${command}" >/dev/null 2>&1 || die "required command not found: ${command}"
done

[[ "${SERVER_PORT}" =~ ^[1-9][0-9]*$ && "${SERVER_PORT}" -le 65535 ]] \
  || die "server port must be an integer in 1..65535"
for value in "${HEALTH_ATTEMPTS}" "${HEALTH_INTERVAL}"; do
  [[ "${value}" =~ ^[1-9][0-9]*$ ]] || die "health timing values must be positive integers"
done
if [[ "${USE_SYSTEM_JAVA}" == "true" && ( -n "${JDK_TARBALL}" || -n "${JDK_URL}" || -n "${JDK_SHA256}" ) ]]; then
  die "--system-java cannot be combined with JDK archive options"
fi
[[ -z "${JDK_TARBALL}" || -z "${JDK_URL}" ]] \
  || die "--jdk-tarball and --jdk-url are mutually exclusive"
if [[ -n "${JDK_TARBALL}" || -n "${JDK_URL}" ]]; then
  [[ "${JDK_SHA256}" =~ ^[0-9a-fA-F]{64}$ ]] \
    || die "custom JDK archive/URL requires --jdk-sha256"
elif [[ -n "${JDK_SHA256}" ]]; then
  die "--jdk-sha256 requires --jdk-tarball or --jdk-url"
fi

if [[ -r /etc/os-release ]]; then
  # shellcheck disable=SC1091
  . /etc/os-release
  [[ "${ID:-}" == "debian" ]] || warn "tested on Debian; detected ID='${ID:-?}'."
  case "${VERSION_ID:-}" in
    11|12) : ;;
    *) warn "tested on Debian 11/12; detected VERSION_ID='${VERSION_ID:-?}'." ;;
  esac
fi

# Resolve the install prefix (prompt interactively when not given on a TTY).
if [[ -z "${PREFIX}" ]]; then
  if [[ -t 0 ]]; then
    read -r -p "Install directory [${DEFAULT_PREFIX}]: " PREFIX
  fi
  PREFIX="${PREFIX:-${DEFAULT_PREFIX}}"
fi
ioc_validate_prefix "${PREFIX}" || die "unsafe installation prefix"
PREFIX="${IOC_VALIDATED_PREFIX}"

# Guard: never install on top of or anywhere below the source checkout.
if ioc_is_inside_source_tree "${PREFIX}"; then
  die "refusing to install inside a source tree: ${PREFIX}"
fi

# Locate the application jar. Explicit selection is preferred; autodiscovery is
# deliberately strict so stale artifacts can never win by name or mtime.
if [[ -z "${JAR}" ]]; then
  JAR_CANDIDATES=()
  for candidate_dir in \
      "${SCRIPT_DIR}" \
      "${SCRIPT_DIR}/lib" \
      "${SCRIPT_DIR}/../bootstrap/ioc-app/target"; do
    [[ -d "${candidate_dir}" ]] || continue
    while IFS= read -r -d '' cand; do
      JAR_CANDIDATES+=("${cand}")
    done < <(find "${candidate_dir}" -maxdepth 1 -type f \
      \( -name 'ioc-app-*.jar' -o -name 'ioc-extractor-*.jar' \) \
      ! -name '*.original' -print0)
  done
  case "${#JAR_CANDIDATES[@]}" in
    0) die "application jar not found; pass --jar PATH" ;;
    1) JAR="${JAR_CANDIDATES[0]}" ;;
    *)
      printf '%s\n' "${JAR_CANDIDATES[@]}" >&2
      die "multiple application jars found; pass --jar PATH explicitly"
      ;;
  esac
fi
[[ -f "${JAR}" && ! -L "${JAR}" ]] || die "application jar must be a regular non-symlink file: ${JAR}"
log "using jar: ${JAR}"

JAR_SHA256="$(sha256sum "${JAR}" | awk '{print $1}')"
if [[ -z "${CHECKSUM}" && -f "${JAR}.sha256" ]]; then
  CHECKSUM="${JAR}.sha256"
fi
if [[ -n "${CHECKSUM}" ]]; then
  [[ -f "${CHECKSUM}" && ! -L "${CHECKSUM}" ]] \
    || die "checksum must be a regular non-symlink file: ${CHECKSUM}"
  mapfile -t CHECKSUM_LINES < <(sed '/^[[:space:]]*$/d' "${CHECKSUM}")
  [[ "${#CHECKSUM_LINES[@]}" -eq 1 ]] \
    || die "checksum file must contain exactly one non-empty line: ${CHECKSUM}"
  read -r EXPECTED_SHA256 _ <<< "${CHECKSUM_LINES[0]}"
  [[ "${EXPECTED_SHA256}" =~ ^[0-9a-fA-F]{64}$ ]] \
    || die "checksum file does not start with a SHA-256 digest: ${CHECKSUM}"
  [[ "${EXPECTED_SHA256,,}" == "${JAR_SHA256}" ]] \
    || die "application jar checksum mismatch: ${JAR}"
  log "verified checksum: ${CHECKSUM}"
fi

RELEASE_ID="${RELEASE_ID:-sha256-${JAR_SHA256:0:12}}"
[[ "${RELEASE_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] \
  || die "release id must match [A-Za-z0-9][A-Za-z0-9._-]{0,127}: ${RELEASE_ID}"

log "install prefix : ${PREFIX}"
log "service user   : ${RUN_USER}"
log "release id    : ${RELEASE_ID}"
log "artifact sha256: ${JAR_SHA256}"

# Artifact selection and checksum validation are intentionally safe to run
# before privilege validation. No host state is changed above this boundary.
[[ "${EUID}" -eq 0 ]] || die "must run as root (use sudo)."

# ---- 1. installation identity + service user -------------------------------
ioc_validate_service_user "${RUN_USER}" || die "unsafe service account"
MARKER="$(ioc_marker_path "${PREFIX}")"
if [[ -e "${MARKER}" ]]; then
  ioc_is_valid_marker "${PREFIX}" "${SERVICE}" "${RUN_USER}" \
    || die "invalid or mismatched installation marker: ${MARKER}"
elif ioc_is_v010_single_dir_installation "${PREFIX}"; then
  die "v0.1.0 in-place upgrade is unsupported; preserve this prefix and install v0.2.0 into a clean prefix (see docs/guides/deployment.md)"
elif ioc_is_pre_marker_release_layout "${PREFIX}"; then
  warn "adopting validated pre-marker release layout at ${PREFIX}"
elif ! ioc_directory_is_empty "${PREFIX}"; then
  die "refusing non-empty unmarked prefix: ${PREFIX}"
fi

if getent passwd "${RUN_USER}" >/dev/null; then
  log "user ${RUN_USER} already exists"
else
  log "creating system user ${RUN_USER}"
  useradd --system --user-group --home-dir "${PREFIX}" --no-create-home \
    --shell /usr/sbin/nologin "${RUN_USER}"
fi
RUN_GROUP="$(id -gn "${RUN_USER}")"

# Stop an existing instance before changing Java, the active release or live
# SQLite state. Any later failure restores the previous current link and unit,
# then attempts to restart the previous active service.
if [[ -L "${PREFIX}/current" ]]; then
  PREVIOUS_TARGET="$(readlink "${PREFIX}/current")"
  ioc_is_release_target "${PREVIOUS_TARGET}" \
    || die "current symlink points outside releases: ${PREVIOUS_TARGET}"
  RECOVERY_ARMED="true"
fi
UNIT="/etc/systemd/system/${SERVICE}.service"
if [[ -f "${UNIT}" ]]; then
  UNIT_BACKUP="$(mktemp /tmp/${SERVICE}.service.XXXXXX)"
  install -m 0600 "${UNIT}" "${UNIT_BACKUP}"
fi
if [[ "$(ps -p 1 -o comm= 2>/dev/null)" == "systemd" ]]; then
  SYSTEMD_AVAILABLE="true"
  for command in journalctl systemctl; do
    command -v "${command}" >/dev/null 2>&1 || die "${command} is required on a systemd host"
  done
  if systemctl is-active --quiet "${SERVICE}"; then
    SERVICE_WAS_ACTIVE="true"
    log "stopping active ${SERVICE} for a consistent upgrade"
    systemctl stop "${SERVICE}"
    RECOVERY_ARMED="true"
  fi
fi

# ---- 2. directory layout + installation marker -----------------------------
log "creating directory layout"
mkdir -p \
  "${PREFIX}/releases" "${PREFIX}/backups" "${PREFIX}/bin" "${PREFIX}/etc" \
  "${PREFIX}/var/db" "${PREFIX}/var/export" \
  "${PREFIX}/var/inbox" "${PREFIX}/var/processing" "${PREFIX}/var/done" \
  "${PREFIX}/var/failed" "${PREFIX}/var/ledger" "${PREFIX}/var/logs" \
  "${PREFIX}/var/import/inbox" "${PREFIX}/var/import/processing" \
  "${PREFIX}/var/import/snapshots" "${PREFIX}/var/import/staging" \
  "${PREFIX}/var/import/terminal" "${PREFIX}/var/import/quarantine" \
  "${PREFIX}/dataframe"
ioc_write_marker "${PREFIX}" "${SERVICE}" "${RUN_USER}"

# ---- 3. verified Java runtime ----------------------------------------------
java_major() { "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1; }

JAVA_BIN=""
if [[ "${USE_SYSTEM_JAVA}" == "true" ]]; then
  command -v java >/dev/null 2>&1 || die "--system-java given but no java on PATH"
  JAVA_BIN="$(readlink -f "$(command -v java)")"
  [[ "$(java_major "${JAVA_BIN}")" -ge 21 ]] || die "--system-java is < 21"
  log "using system java: ${JAVA_BIN}"
else
  arch="$(uname -m)"
  if [[ -z "${JDK_TARBALL}" && -z "${JDK_URL}" ]]; then
    case "${arch}" in
      x86_64|amd64)
        JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.11%2B10/OpenJDK21U-jdk_x64_linux_hotspot_21.0.11_10.tar.gz"
        JDK_SHA256="4b2220e232a97997b436ca6ab15cbf70171ecff52958a46159dfa5a8c44ca4de"
        ;;
      aarch64|arm64)
        JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.11%2B10/OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.11_10.tar.gz"
        JDK_SHA256="8d498ec88e1c1989fab95c6784240ab92d011e29c54d20a3f9c324b13476f9ad"
        ;;
      *) die "unsupported arch '${arch}' for pinned JDK; provide --jdk-tarball and --jdk-sha256" ;;
    esac
  fi
  JDK_SHA256="${JDK_SHA256,,}"

  if [[ -x "${PREFIX}/jdk/bin/java"
      && -f "${PREFIX}/jdk/.ioc-extractor-archive.sha256"
      && "$(<"${PREFIX}/jdk/.ioc-extractor-archive.sha256")" == "${JDK_SHA256}"
      && "$(java_major "${PREFIX}/jdk/bin/java")" -ge 21 ]]; then
    JAVA_BIN="${PREFIX}/jdk/bin/java"
    log "reusing verified JDK at ${PREFIX}/jdk"
  else
    tarball="${JDK_TARBALL}"
    if [[ -n "${tarball}" ]]; then
      [[ -f "${tarball}" && ! -L "${tarball}" ]] \
        || die "JDK tarball must be a regular non-symlink file: ${tarball}"
    else
      [[ "${JDK_URL}" == https://* ]] || die "JDK URL must use HTTPS"
      tarball="$(mktemp /tmp/temurin21.XXXXXX.tar.gz)"
      CLEANUP_TARBALL="${tarball}"
      log "downloading pinned Temurin 21 archive for ${arch}"
      curl --proto '=https' --tlsv1.2 -fSL -m 600 -o "${tarball}" "${JDK_URL}" \
        || die "JDK download failed; on an offline host pass a tarball and SHA-256"
    fi
    ACTUAL_JDK_SHA256="$(sha256sum "${tarball}" | awk '{print $1}')"
    [[ "${ACTUAL_JDK_SHA256}" == "${JDK_SHA256}" ]] \
      || die "JDK archive checksum mismatch"
    if tar -tzf "${tarball}" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
      die "JDK archive contains an unsafe path"
    fi

    CLEANUP_JDK_STAGE="$(mktemp -d "${PREFIX}/.jdk-stage.XXXXXX")"
    tar -xzf "${tarball}" -C "${CLEANUP_JDK_STAGE}" --strip-components=1 --no-same-owner
    [[ -x "${CLEANUP_JDK_STAGE}/bin/java" ]] \
      || die "JDK extraction did not yield bin/java"
    [[ "$(java_major "${CLEANUP_JDK_STAGE}/bin/java")" -ge 21 ]] \
      || die "extracted JDK is < 21"
    printf '%s\n' "${JDK_SHA256}" > "${CLEANUP_JDK_STAGE}/.ioc-extractor-archive.sha256"

    JDK_PREVIOUS="${PREFIX}/.jdk-previous.$$"
    [[ ! -e "${JDK_PREVIOUS}" ]] || die "temporary JDK path already exists: ${JDK_PREVIOUS}"
    [[ ! -e "${PREFIX}/jdk" ]] || mv "${PREFIX}/jdk" "${JDK_PREVIOUS}"
    mv "${CLEANUP_JDK_STAGE}" "${PREFIX}/jdk"
    CLEANUP_JDK_STAGE=""
    [[ ! -e "${JDK_PREVIOUS}" ]] || rm -rf -- "${JDK_PREVIOUS}"
    JAVA_BIN="${PREFIX}/jdk/bin/java"
    log "installed verified JDK archive (${JDK_SHA256})"
  fi
fi
log "java: $("${JAVA_BIN}" -version 2>&1 | head -1)"
case "${JAVA_BIN}" in
  /home/*) die "Java under /home is hidden by systemd ProtectHome" ;;
esac

# 0011/0012 consolidate both SQLite stores under var/db. Move the complete
# database family only while the service is stopped; never guess if both layouts
# contain a primary DB.
migrate_sqlite() { # legacy-path new-path
  local legacy="$1" target="$2" suffix
  if [[ -e "${legacy}" && -e "${target}" ]]; then
    die "both legacy and current SQLite databases exist: ${legacy} and ${target}; reconcile them manually."
  fi
  if [[ -e "${legacy}" ]]; then
    log "migrating SQLite store: ${legacy} -> ${target}"
    mv -- "${legacy}" "${target}"
    for suffix in -wal -shm; do
      [[ ! -e "${legacy}${suffix}" ]] || mv -- "${legacy}${suffix}" "${target}${suffix}"
    done
  elif [[ -e "${legacy}-wal" || -e "${legacy}-shm" ]]; then
    die "legacy SQLite sidecar exists without primary DB: ${legacy}"
  fi
}
migrate_sqlite "${PREFIX}/var/ioc-service.db" "${PREFIX}/var/db/ioc-service.db"
migrate_sqlite "${PREFIX}/dataframe/ioc-dataframe.db" "${PREFIX}/var/db/ioc-dataframe.db"

# ---- 4. deploy immutable release + config ---------------------------------
RELEASE_DIR="${PREFIX}/releases/${RELEASE_ID}"
if [[ -e "${RELEASE_DIR}" ]]; then
  [[ -f "${RELEASE_DIR}/ioc-app.jar" ]] \
    || die "release path exists but has no application jar: ${RELEASE_DIR}"
  [[ "${JAR_SHA256}" == \
     "$(sha256sum "${RELEASE_DIR}/ioc-app.jar" | awk '{print $1}')" ]] \
    || die "release id already exists with different bytes: ${RELEASE_ID}"
  log "reusing immutable release ${RELEASE_ID}"
else
  log "deploying immutable release ${RELEASE_ID}"
  RELEASE_STAGING="${PREFIX}/releases/.${RELEASE_ID}.tmp"
  rm -rf -- "${RELEASE_STAGING}"
  mkdir -p "${RELEASE_STAGING}"
  install -m 0644 "${JAR}" "${RELEASE_STAGING}/ioc-app.jar"
  INSTALLED_SHA256="$(sha256sum "${RELEASE_STAGING}/ioc-app.jar" | awk '{print $1}')"
  [[ "${INSTALLED_SHA256}" == "${JAR_SHA256}" ]] \
    || die "installed application jar checksum mismatch"
  printf '%s  ioc-app.jar\n' "${INSTALLED_SHA256}" \
    > "${RELEASE_STAGING}/ioc-app.jar.sha256"
  mv "${RELEASE_STAGING}" "${RELEASE_DIR}"
fi
CURRENT_LINK="${PREFIX}/.current.$$"
ln -s "releases/${RELEASE_ID}" "${CURRENT_LINK}"
mv -Tf "${CURRENT_LINK}" "${PREFIX}/current"

deploy_config() {  # src dst
  local src="$1" dst="$2"
  if [[ -f "${dst}" ]] && cmp -s "${src}" "${dst}"; then
    log "configuration is current: ${dst}"
  elif [[ -f "${dst}" && "${FORCE}" != "true" ]]; then
    if [[ -f "${dst}.new" ]] && ! cmp -s "${src}" "${dst}.new"; then
      die "unreconciled configuration candidate would be overwritten: ${dst}.new"
    fi
    install -m 0640 "${src}" "${dst}.new"
    warn "kept existing ${dst}; wrote ${dst}.new (use --force to overwrite)"
  else
    install -m 0640 "${src}" "${dst}"
    log "wrote ${dst}"
  fi
}
deploy_config "${SCRIPT_DIR}/templates/application.yml"     "${PREFIX}/etc/application.yml"
deploy_config "${SCRIPT_DIR}/templates/ioc-extractor.env"   "${PREFIX}/etc/ioc-extractor.env"

sed -e "s|@PREFIX@|${PREFIX}|g" \
    -e "s|@JAVA_BIN@|${JAVA_BIN}|g" \
    -e "s|@USER@|${RUN_USER}|g" \
    -e "s|@GROUP@|${RUN_GROUP}|g" \
    "${SCRIPT_DIR}/templates/ioc" > "${PREFIX}/bin/ioc"
chmod 0750 "${PREFIX}/bin/ioc"
sed -e "s|@PREFIX@|${PREFIX}|g" \
    -e "s|@JAVA_BIN@|${JAVA_BIN}|g" \
    -e "s|@GROUP@|${RUN_GROUP}|g" \
    -e "s|@SERVER_PORT@|${SERVER_PORT}|g" \
    "${SCRIPT_DIR}/templates/ioc-config" > "${PREFIX}/bin/ioc-config"
chmod 0750 "${PREFIX}/bin/ioc-config"

# ---- 5. ownership & permissions --------------------------------------------
# Executables and configuration stay root-owned. Only durable runtime state and
# CSV projection data are writable by the unprivileged daemon.
log "setting ownership (runtime user ${RUN_USER}:${RUN_GROUP})"
chown root:"${RUN_GROUP}" "${PREFIX}"
chown -R root:"${RUN_GROUP}" "${PREFIX}/releases" "${PREFIX}/backups" \
  "${PREFIX}/bin" "${PREFIX}/etc"
[[ ! -d "${PREFIX}/jdk" ]] || chown -R root:"${RUN_GROUP}" "${PREFIX}/jdk"
chown -R "${RUN_USER}:${RUN_GROUP}" "${PREFIX}/var" "${PREFIX}/dataframe"
chmod 0750 "${PREFIX}"
chmod 0750 "${PREFIX}/releases" "${PREFIX}/backups" "${PREFIX}/bin" "${PREFIX}/etc" \
  "${PREFIX}/var" "${PREFIX}/dataframe"
chmod -R go-w "${PREFIX}/releases" "${PREFIX}/backups" "${PREFIX}/etc"
find "${PREFIX}/var" "${PREFIX}/dataframe" -type d -exec chmod 0750 {} +
find "${PREFIX}/var" "${PREFIX}/dataframe" -type f -exec chmod 0640 {} +
chmod 0640 "${PREFIX}/etc/application.yml" "${PREFIX}/etc/ioc-extractor.env"
chmod 0640 "${MARKER}"
[[ ! -f "${PREFIX}/etc/application.yml.new" ]] || chmod 0640 "${PREFIX}/etc/application.yml.new"
[[ ! -f "${PREFIX}/etc/ioc-extractor.env.new" ]] || chmod 0640 "${PREFIX}/etc/ioc-extractor.env.new"

# ---- 6. systemd unit -------------------------------------------------------
log "rendering ${UNIT}"
SERVER_PORT_ARG=""
[[ "${SERVER_PORT_EXPLICIT}" != "true" ]] || SERVER_PORT_ARG="--server.port=${SERVER_PORT}"
CLEANUP_UNIT_STAGE="$(mktemp /tmp/${SERVICE}.rendered.XXXXXX)"
sed -e "s|@PREFIX@|${PREFIX}|g" \
    -e "s|@JAVA_BIN@|${JAVA_BIN}|g" \
    -e "s|@USER@|${RUN_USER}|g" \
    -e "s|@GROUP@|${RUN_GROUP}|g" \
    -e "s|@SERVER_PORT_ARG@|${SERVER_PORT_ARG}|g" \
    "${SCRIPT_DIR}/templates/ioc-extractor.service" > "${CLEANUP_UNIT_STAGE}"
install -o root -g root -m 0644 "${CLEANUP_UNIT_STAGE}" "${UNIT}"
UNIT_WRITTEN="true"
rm -f -- "${CLEANUP_UNIT_STAGE}"
CLEANUP_UNIT_STAGE=""

install_health_ready() {
  local base="http://127.0.0.1:${SERVER_PORT}/actuator/health" component body
  for component in jdbcStorage dataframeStorage artifactStorage; do
    body="$(curl --noproxy '*' --silent --fail --max-time 2 \
      "${base}/${component}")" || return 1
    grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' \
      <<< "${body}" || return 1
  done
}

wait_for_install_health() {
  local attempt
  for ((attempt = 1; attempt <= HEALTH_ATTEMPTS; attempt++)); do
    systemctl is-active --quiet "${SERVICE}" && install_health_ready && return 0
    (( attempt == HEALTH_ATTEMPTS )) || sleep "${HEALTH_INTERVAL}"
  done
  return 1
}

if [[ "${SYSTEMD_AVAILABLE}" == "true" ]]; then
  systemctl daemon-reload
  if [[ "${NO_START}" == "true" ]]; then
    systemctl enable "${SERVICE}" >/dev/null 2>&1 || true
    [[ "${SERVICE_WAS_ACTIVE}" != "true" ]] \
      || warn "${SERVICE} was active before upgrade and remains stopped because --no-start was requested."
    log "installed (not started; --no-start). Start with: systemctl start ${SERVICE}"
  else
    systemctl enable "${SERVICE}" >/dev/null
    systemctl start "${SERVICE}"
    if ! wait_for_install_health; then
      systemctl --no-pager --full status "${SERVICE}" || true
      journalctl -u "${SERVICE}" -n 80 --no-pager || true
      false
    fi
    systemctl --no-pager --full status "${SERVICE}" | sed -n '1,6p'
  fi
else
  warn "systemd is not PID 1 here; unit written but not started. On the target host run: systemctl daemon-reload && systemctl enable --now ${SERVICE}"
fi

RECOVERY_ARMED="false"

cat <<EOF

$(log "done.")
  Service : ${SERVICE}    User: ${RUN_USER}    Prefix: ${PREFIX}
  Release : ${RELEASE_ID}
  Feed    : drop *.htm/*.html/*.docx into ${PREFIX}/var/inbox/
  DB      : ${PREFIX}/var/db/  (canonical + service SQLite stores)
  Export  : ${PREFIX}/var/export/  (immutable artifact slices)
  Output  : ${PREFIX}/dataframe/  (*_generated.csv projections)
  Logs    : journalctl -u ${SERVICE} -f   (and ${PREFIX}/var/logs/)
  Config  : sudo ${PREFIX}/bin/ioc-config apply <candidate.yml>
EOF
