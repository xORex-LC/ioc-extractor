#!/usr/bin/env bash
#
# ioc-extractor — installer for Debian 11+ (systemd daemon).
#
# Provisions the host from scratch: installs a JDK 21 (manually, from a tarball
# — Debian repos do not ship JDK 21), creates a dedicated system user and the
# runtime directory layout, deploys the application jar + config, installs and
# starts the systemd service.
#
# Self-contained single-directory layout under the chosen prefix:
#   <prefix>/jdk/                 manually-installed Temurin 21 runtime
#   <prefix>/lib/ioc-app-<v>.jar  application
#   <prefix>/etc/                 application.yml + ioc-extractor.env (operator-editable)
#   <prefix>/var/                 db/ export/ inbox/ processing/ done/ failed/ ledger/ logs/
#   <prefix>/dataframe/           generated CSV projections + lookup seeds
#
# Idempotent: re-running upgrades the jar and unit; existing config is preserved
# (a *.new is written instead) unless --force is given.
#
# Usage:
#   sudo ./install.sh [--prefix DIR] [--jar PATH] [--user NAME]
#                     [--jdk-tarball PATH | --jdk-url URL | --system-java]
#                     [--lookup-seed PATH] [--no-start] [--force] [--help]
#
set -Eeuo pipefail

# ---- defaults --------------------------------------------------------------
APP_VERSION="0.1.0"
SERVICE="ioc-extractor"
DEFAULT_PREFIX="/opt/ioc-extractor"
PREFIX=""
RUN_USER="ioc"
JAR=""
JDK_TARBALL=""
JDK_URL=""
LOOKUP_SEED=""
USE_SYSTEM_JAVA="false"
NO_START="false"
FORCE="false"
SYSTEMD_AVAILABLE="false"
SERVICE_WAS_ACTIVE="false"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"

# ---- output helpers --------------------------------------------------------
log()  { printf '\033[1;34m[install]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }
trap 'die "failed at line $LINENO"' ERR

usage() { sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0; }

# ---- argument parsing ------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix)       PREFIX="${2:?}"; shift 2 ;;
    --jar)          JAR="${2:?}"; shift 2 ;;
    --user)         RUN_USER="${2:?}"; shift 2 ;;
    --jdk-tarball)  JDK_TARBALL="${2:?}"; shift 2 ;;
    --jdk-url)      JDK_URL="${2:?}"; shift 2 ;;
    --system-java)  USE_SYSTEM_JAVA="true"; shift ;;
    --lookup-seed)  LOOKUP_SEED="${2:?}"; shift 2 ;;
    --no-start)     NO_START="true"; shift ;;
    --force)        FORCE="true"; shift ;;
    -h|--help)      usage ;;
    *)              die "unknown argument: $1 (see --help)" ;;
  esac
done

# ---- preflight -------------------------------------------------------------
[[ "${EUID}" -eq 0 ]] || die "must run as root (use sudo)."

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
PREFIX="${PREFIX%/}"
[[ "${PREFIX}" == /* ]] || die "prefix must be an absolute path: ${PREFIX}"
case "${PREFIX}" in
  /|/home|/home/*) warn "prefix under /home conflicts with systemd ProtectHome; /opt is recommended." ;;
esac

# Guard: never install on top of the source checkout.
if [[ -e "${PREFIX}/pom.xml" || -d "${PREFIX}/.git" ]]; then
  [[ "${FORCE}" == "true" ]] || die "refusing to install into a source tree at ${PREFIX} (pom.xml/.git present). Pick another --prefix or pass --force."
fi

# Locate the application jar.
if [[ -z "${JAR}" ]]; then
  for cand in \
      "${SCRIPT_DIR}/ioc-app-${APP_VERSION}.jar" \
      "${SCRIPT_DIR}/lib/ioc-app-${APP_VERSION}.jar" \
      "${SCRIPT_DIR}/../bootstrap/ioc-app/target/ioc-app-${APP_VERSION}.jar"; do
    [[ -f "${cand}" ]] && { JAR="${cand}"; break; }
  done
fi
[[ -n "${JAR}" && -f "${JAR}" ]] || die "application jar not found; pass --jar PATH (looked for ioc-app-${APP_VERSION}.jar)."
log "using jar: ${JAR}"

log "install prefix : ${PREFIX}"
log "service user   : ${RUN_USER}"

# ---- 1. Java (manual, no apt repositories) ---------------------------------
java_major() { "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1; }

JAVA_BIN=""
if [[ "${USE_SYSTEM_JAVA}" == "true" ]]; then
  command -v java >/dev/null 2>&1 || die "--system-java given but no java on PATH."
  sysjava="$(command -v java)"
  [[ "$(java_major "${sysjava}")" -ge 21 ]] || die "--system-java is < 21."
  JAVA_BIN="${sysjava}"
  log "using system java: ${JAVA_BIN}"
elif [[ -x "${PREFIX}/jdk/bin/java" && "$(java_major "${PREFIX}/jdk/bin/java")" -ge 21 ]]; then
  JAVA_BIN="${PREFIX}/jdk/bin/java"
  log "reusing existing JDK at ${PREFIX}/jdk"
else
  # Manual tarball install into <prefix>/jdk.
  tarball="${JDK_TARBALL}"
  if [[ -z "${tarball}" ]]; then
    arch="$(uname -m)"
    case "${arch}" in
      x86_64|amd64) a="x64" ;;
      aarch64|arm64) a="aarch64" ;;
      *) die "unsupported arch '${arch}' for JDK auto-download; provide --jdk-tarball." ;;
    esac
    url="${JDK_URL:-https://api.adoptium.net/v3/binary/latest/21/ga/linux/${a}/jdk/hotspot/normal/eclipse}"
    command -v curl >/dev/null 2>&1 || die "curl required to download the JDK; install curl or pass --jdk-tarball."
    tarball="$(mktemp /tmp/temurin21.XXXXXX.tar.gz)"
    log "downloading Temurin 21 (${a}) from Adoptium…"
    curl -fSL -m 600 -o "${tarball}" "${url}" || die "JDK download failed; on an offline host pass --jdk-tarball PATH."
    CLEANUP_TARBALL="${tarball}"
  fi
  [[ -f "${tarball}" ]] || die "JDK tarball not found: ${tarball}"
  log "installing JDK from tarball into ${PREFIX}/jdk"
  rm -rf "${PREFIX}/jdk"
  mkdir -p "${PREFIX}/jdk"
  tar -xzf "${tarball}" -C "${PREFIX}/jdk" --strip-components=1
  [[ -n "${CLEANUP_TARBALL:-}" ]] && rm -f "${CLEANUP_TARBALL}"
  JAVA_BIN="${PREFIX}/jdk/bin/java"
  [[ -x "${JAVA_BIN}" ]] || die "JDK extraction did not yield ${JAVA_BIN}"
  [[ "$(java_major "${JAVA_BIN}")" -ge 21 ]] || die "extracted JDK is < 21."
fi
log "java: $("${JAVA_BIN}" -version 2>&1 | head -1)"
case "${JAVA_BIN}" in
  /home/*) die "Java under /home is hidden by systemd ProtectHome; use the bundled JDK or a system JDK outside /home." ;;
esac

# ---- 2. service user -------------------------------------------------------
if getent passwd "${RUN_USER}" >/dev/null; then
  log "user ${RUN_USER} already exists"
else
  log "creating system user ${RUN_USER}"
  useradd --system --user-group --home-dir "${PREFIX}" --no-create-home \
    --shell /usr/sbin/nologin "${RUN_USER}"
fi
RUN_GROUP="$(id -gn "${RUN_USER}")"

# Stop an existing instance before replacing its jar or moving live SQLite WAL
# state. The service is started again at the end unless --no-start was requested.
if [[ "$(ps -p 1 -o comm= 2>/dev/null)" == "systemd" ]]; then
  SYSTEMD_AVAILABLE="true"
  if systemctl is-active --quiet "${SERVICE}"; then
    SERVICE_WAS_ACTIVE="true"
    log "stopping active ${SERVICE} for a consistent upgrade"
    systemctl stop "${SERVICE}"
  fi
fi

# ---- 3. directory layout ---------------------------------------------------
log "creating directory layout"
mkdir -p \
  "${PREFIX}/lib" "${PREFIX}/etc" \
  "${PREFIX}/var/db" "${PREFIX}/var/export" \
  "${PREFIX}/var/inbox" "${PREFIX}/var/processing" "${PREFIX}/var/done" \
  "${PREFIX}/var/failed" "${PREFIX}/var/ledger" "${PREFIX}/var/logs" \
  "${PREFIX}/dataframe"

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

# ---- 4. deploy jar + config ------------------------------------------------
log "deploying application jar"
install -m 0644 "${JAR}" "${PREFIX}/lib/ioc-app-${APP_VERSION}.jar"

deploy_config() {  # src dst
  local src="$1" dst="$2"
  if [[ -f "${dst}" && "${FORCE}" != "true" ]]; then
    install -m 0640 "${src}" "${dst}.new"
    warn "kept existing ${dst}; wrote ${dst}.new (use --force to overwrite)"
  else
    install -m 0640 "${src}" "${dst}"
    log "wrote ${dst}"
  fi
}
deploy_config "${SCRIPT_DIR}/templates/application.yml"     "${PREFIX}/etc/application.yml"
deploy_config "${SCRIPT_DIR}/templates/ioc-extractor.env"   "${PREFIX}/etc/ioc-extractor.env"

# ---- 5. seed reference lookups (optional, existing files are preserved) ----
seed_lookup() { # name [explicit-source]
  local name="$1" explicit="${2:-}" source=""
  [[ -e "${PREFIX}/dataframe/${name}" ]] && return 0
  if [[ -n "${explicit}" ]]; then
    source="${explicit}"
  elif [[ -f "${SCRIPT_DIR}/seed/${name}" ]]; then
    source="${SCRIPT_DIR}/seed/${name}"
  elif [[ -f "${SCRIPT_DIR}/../dataframe/${name}" ]]; then
    source="${SCRIPT_DIR}/../dataframe/${name}"
  fi
  if [[ -n "${source}" && -f "${source}" ]]; then
    install -m 0640 "${source}" "${PREFIX}/dataframe/${name}"
    log "seeded lookup: ${name}"
  else
    warn "lookup seed not found: ${name}; the corresponding dedup lookup starts empty."
  fi
}

[[ -z "${LOOKUP_SEED}" || -f "${LOOKUP_SEED}" ]] \
  || die "lookup seed not found: ${LOOKUP_SEED}"
seed_lookup "masks_list.csv" "${LOOKUP_SEED}"
seed_lookup "ip_list.csv"
seed_lookup "hashes_list.csv"
seed_lookup "address_blacklist.csv"

# ---- 6. ownership & permissions --------------------------------------------
# Executables and configuration stay root-owned. Only durable runtime state and
# CSV lookup/projection data are writable by the unprivileged daemon.
log "setting ownership (runtime user ${RUN_USER}:${RUN_GROUP})"
chown root:"${RUN_GROUP}" "${PREFIX}"
chown -R root:"${RUN_GROUP}" "${PREFIX}/lib" "${PREFIX}/etc"
[[ ! -d "${PREFIX}/jdk" ]] || chown -R root:"${RUN_GROUP}" "${PREFIX}/jdk"
chown -R "${RUN_USER}:${RUN_GROUP}" "${PREFIX}/var" "${PREFIX}/dataframe"
chmod 0750 "${PREFIX}"
chmod 0750 "${PREFIX}/lib" "${PREFIX}/etc" "${PREFIX}/var" "${PREFIX}/dataframe"
chmod -R go-w "${PREFIX}/lib" "${PREFIX}/etc"
find "${PREFIX}/var" "${PREFIX}/dataframe" -type d -exec chmod 0750 {} +
find "${PREFIX}/var" "${PREFIX}/dataframe" -type f -exec chmod 0640 {} +
chmod 0640 "${PREFIX}/etc/application.yml" "${PREFIX}/etc/ioc-extractor.env"
[[ ! -f "${PREFIX}/etc/application.yml.new" ]] || chmod 0640 "${PREFIX}/etc/application.yml.new"
[[ ! -f "${PREFIX}/etc/ioc-extractor.env.new" ]] || chmod 0640 "${PREFIX}/etc/ioc-extractor.env.new"

# ---- 7. systemd unit -------------------------------------------------------
UNIT="/etc/systemd/system/${SERVICE}.service"
log "rendering ${UNIT}"
sed -e "s|@PREFIX@|${PREFIX}|g" \
    -e "s|@VERSION@|${APP_VERSION}|g" \
    -e "s|@JAVA_BIN@|${JAVA_BIN}|g" \
    -e "s|@USER@|${RUN_USER}|g" \
    -e "s|@GROUP@|${RUN_GROUP}|g" \
    "${SCRIPT_DIR}/templates/ioc-extractor.service" > "${UNIT}"
chmod 0644 "${UNIT}"

if [[ "${SYSTEMD_AVAILABLE}" == "true" ]]; then
  systemctl daemon-reload
  if [[ "${NO_START}" == "true" ]]; then
    systemctl enable "${SERVICE}" >/dev/null 2>&1 || true
    [[ "${SERVICE_WAS_ACTIVE}" != "true" ]] \
      || warn "${SERVICE} was active before upgrade and remains stopped because --no-start was requested."
    log "installed (not started; --no-start). Start with: systemctl start ${SERVICE}"
  else
    systemctl enable "${SERVICE}" >/dev/null
    if systemctl is-active --quiet "${SERVICE}"; then
      systemctl restart "${SERVICE}"
    else
      systemctl start "${SERVICE}"
    fi
    sleep 2
    systemctl --no-pager --full status "${SERVICE}" | sed -n '1,6p' || true
  fi
else
  warn "systemd is not PID 1 here; unit written but not started. On the target host run: systemctl daemon-reload && systemctl enable --now ${SERVICE}"
fi

cat <<EOF

$(log "done.")
  Service : ${SERVICE}    User: ${RUN_USER}    Prefix: ${PREFIX}
  Feed    : drop *.htm/*.html/*.docx into ${PREFIX}/var/inbox/
  DB      : ${PREFIX}/var/db/  (canonical + service SQLite stores)
  Export  : ${PREFIX}/var/export/  (immutable artifact slices)
  Output  : ${PREFIX}/dataframe/  (*_generated.csv projections)
  Logs    : journalctl -u ${SERVICE} -f   (and ${PREFIX}/var/logs/)
  Config  : ${PREFIX}/etc/application.yml  then: systemctl restart ${SERVICE}
EOF
