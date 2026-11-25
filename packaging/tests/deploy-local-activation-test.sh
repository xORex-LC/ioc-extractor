#!/usr/bin/env bash
# Execute the local upgrade path against synthetic files and real SQLite/tar.
set -Eeuo pipefail

TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
PACKAGING_DIR="$(cd -- "${TEST_DIR}/.." >/dev/null 2>&1 && pwd)"
mkdir -p "${PACKAGING_DIR}/target"
scratch="$(mktemp -d "${PACKAGING_DIR}/target/local-activation.XXXXXX")"
trap 'rm -rf -- "${scratch}"' EXIT

fail() {
  printf '[local-activation] FAIL (%s): %s\n' "${FAULT}" "$*" >&2
  cat "${CASE_DIR}/output" >&2
  exit 1
}

mkdir "${scratch}/commands"
ln -s "${PACKAGING_DIR}/install-layout.sh" "${scratch}/install-layout.sh"
ln -s "${PACKAGING_DIR}/templates" "${scratch}/templates"
# Redirect only fixed host paths and the entry-point UID check. There are no
# path/command overrides in the privileged production entry point.
# shellcheck disable=SC2016
sed -e 's|^UNIT=.*|UNIT="${CASE_DIR}/systemd/ioc-extractor.service"|' \
    -e 's|^DEPLOY_LOCK=.*|DEPLOY_LOCK="${CASE_DIR}/deploy.lock"|' \
    -e 's|^CONFIG_LOCK=.*|CONFIG_LOCK="${CASE_DIR}/config.lock"|' \
    -e '/^\[\[ "${EUID}" -eq 0 \]\]/d' \
    "${PACKAGING_DIR}/deploy-local-root.sh" > "${scratch}/deploy-local-root.sh"

cat > "${scratch}/commands/fixture-db" <<'PY'
#!/usr/bin/env python3
import os
from pathlib import Path
import sqlite3
import sys

action, directory, value = sys.argv[1:]
for name in ("ioc-dataframe.db", "ioc-service.db"):
    path = Path(directory) / name
    if action == "check":
        assert path.is_file(), path
    with sqlite3.connect(path) as connection:
        if action == "init":
            connection.execute("CREATE TABLE marker (value TEXT NOT NULL)")
            connection.execute("INSERT INTO marker VALUES (?)", (value + name,))
        elif action == "write":
            connection.execute("UPDATE marker SET value = ?", (value + name,))
        else:
            assert connection.execute("SELECT value FROM marker").fetchone() == (value + name,), path
    if action == "init":
        path.chmod(0o640)
        os.setxattr(path, "user.packaging-contract", name.encode())
    elif action == "check":
        assert path.stat().st_mode & 0o777 == 0o640, path
        assert os.getxattr(path, "user.packaging-contract") == name.encode(), path
PY

cat > "${scratch}/commands/stub" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
case "${0##*/}" in
  chown|journalctl) exit 0 ;;
  stat)
    if [[ "$*" == "-c %U ${TEST_PREFIX}/var" ]]; then
      printf '%s\n' ioc-contract-user
    else
      exec /usr/bin/stat "$@"
    fi
    ;;
  install)
    args=()
    while (( $# )); do
      case "$1" in
        -o|-g) shift 2 ;;
        *) args+=("$1"); shift ;;
      esac
    done
    exec /usr/bin/install "${args[@]}"
    ;;
  mv)
    if [[ "${FAULT}" == partial-files && "${*: -1}" == "${TEST_PREFIX}/bin/ioc-config" \
        && ! -e "${CASE_DIR}/failed-once" ]]; then
      touch "${CASE_DIR}/failed-once"
      exit 43
    fi
    exec /usr/bin/mv "$@"
    ;;
  tar)
    if [[ " $* " == *' -cf '* && ( "${FAULT}" == backup || "${FAULT}" == retry-backup ) ]]; then exit 44; fi
    if [[ " $* " == *' --compare '* && "${FAULT}" == archive-validation ]]; then exit 46; fi
    if [[ " $* " == *' -xf '* && "${FAULT}" == restore ]]; then exit 45; fi
    exec /usr/bin/tar "$@"
    ;;
  systemd-run)
    printf '%s\n' preflight >> "${CASE_DIR}/events"
    cmp "${CASE_DIR}/original/unit" "${CASE_DIR}/systemd/ioc-extractor.service"
    [[ "$(readlink "${TEST_PREFIX}/current")" == releases/previous ]]
    if [[ "${FAULT}" == preflight ]]; then exit 78; fi
    ;;
  systemctl)
    case "$1" in
      show) cat "${CASE_DIR}/state" ;;
      is-active) [[ "$(< "${CASE_DIR}/state")" == active ]] ;;
      stop)
        printf '%s\n' stop >> "${CASE_DIR}/events"
        if [[ "${FAULT}" == stop ]]; then exit 47; fi
        if [[ "${FAULT}" == rollback-stop && "$(readlink "${TEST_PREFIX}/current")" == releases/candidate ]]; then exit 48; fi
        printf '%s\n' inactive > "${CASE_DIR}/state"
        ;;
      daemon-reload)
        printf '%s\n' reload >> "${CASE_DIR}/events"
        if [[ "${FAULT}" == reload && "$(readlink "${TEST_PREFIX}/current")" == releases/candidate ]]; then exit 49; fi
        ;;
      start)
        release="$(readlink "${TEST_PREFIX}/current")"
        printf 'start %s\n' "${release##*/}" >> "${CASE_DIR}/events"
        printf '%s\n' active > "${CASE_DIR}/state"
        if [[ "${release}" == releases/candidate ]]; then
          fixture-db write "${TEST_PREFIX}/var/db" candidate
          if [[ "${FAULT}" == start ]]; then exit 50; fi
          if [[ "${FAULT}" == signal ]]; then kill -TERM "${PPID}"; fi
        fi
        ;;
      reset-failed)
        # systemd may unload a stopped service before recovery starts it.
        if [[ "${FAULT}" == health ]]; then exit 51; fi
        ;;
      --no-pager) : ;;
      *) exit 99 ;;
    esac
    ;;
  curl)
    if [[ "$(readlink "${TEST_PREFIX}/current")" == releases/candidate ]]; then
      [[ "$*" == *:19091/actuator/health/* ]]
      case "${FAULT}" in health|restore|rollback-stop|inactive) exit 1 ;; esac
    else
      [[ "$*" == *:19090/actuator/health/* ]]
    fi
    printf '%s\n' '{"status":"UP"}'
    ;;
  *) exit 99 ;;
esac
SH
chmod +x "${scratch}/commands/stub" "${scratch}/commands/fixture-db"
for command in chown curl install journalctl mv stat systemctl systemd-run tar; do
  ln -s stub "${scratch}/commands/${command}"
done
export PATH="${scratch}/commands:${PATH}"

arrange() {
  local fixture="${2:-$1}"
  export FAULT="$1" CASE_DIR="${scratch}/${fixture}" TEST_PREFIX="${scratch}/${fixture}/host"
  mkdir -p "${CASE_DIR}/original" "${CASE_DIR}/systemd" \
    "${TEST_PREFIX}"/{bin,etc,jdk/bin,backups,releases/previous,var/db}
  touch "${CASE_DIR}/events" "${TEST_PREFIX}/jdk/bin/java"
  chmod +x "${TEST_PREFIX}/jdk/bin/java"
  printf '%s\n' active > "${CASE_DIR}/state"
  printf '%s\n' 'ExecStart=/old/java --server.port=19090' > "${CASE_DIR}/original/unit"
  printf '%s\n' 'old ioc' > "${CASE_DIR}/original/ioc"
  printf '%s\n' 'SERVER_PORT="19090"' > "${CASE_DIR}/original/ioc-config"
  chmod 0640 "${CASE_DIR}/original/unit"
  chmod 0751 "${CASE_DIR}/original/ioc" "${CASE_DIR}/original/ioc-config"
  cp -a "${CASE_DIR}/original/unit" "${CASE_DIR}/systemd/ioc-extractor.service"
  cp -a "${CASE_DIR}/original/ioc" "${CASE_DIR}/original/ioc-config" "${TEST_PREFIX}/bin/"
  if [[ "${FAULT}" == inactive ]]; then
    rm "${TEST_PREFIX}/bin/ioc-config"
    printf '%s\n' inactive > "${CASE_DIR}/state"
  fi
  cp "${PACKAGING_DIR}/templates/"{application.yml,ioc-extractor.env} "${TEST_PREFIX}/etc/"
  printf 'format=1\nservice=ioc-extractor\nprefix=%s\nrun_user=ioc-contract-user\n' \
    "${TEST_PREFIX}" > "${TEST_PREFIX}/etc/ioc-extractor.installation"
  printf '%s\n' original > "${TEST_PREFIX}/releases/previous/ioc-app.jar"
  ln -s releases/previous "${TEST_PREFIX}/current"
  if [[ "${FAULT}" == backup ]]; then
    fixture-db init "${TEST_PREFIX}/var/db" stale
    /usr/bin/tar --acls --xattrs -C "${TEST_PREFIX}/var" -cf "${TEST_PREFIX}/backups/candidate-db.tar" db
    rm "${TEST_PREFIX}/var/db/"*.db
  fi
  fixture-db init "${TEST_PREFIX}/var/db" original
  printf '%s\n' candidate > "${CASE_DIR}/candidate.jar"
}

activate() {
  local expected="$1" sha status=0
  sha="$(sha256sum "${CASE_DIR}/candidate.jar" | cut -d' ' -f1)"
  timeout 20s bash "${scratch}/deploy-local-root.sh" --prefix "${TEST_PREFIX}" \
    --jar "${CASE_DIR}/candidate.jar" --jar-sha256 "${sha}" --release-id candidate \
    --commit 0123456789012345678901234567890123456789 --built-at 2026-01-01T00:00:00Z \
    --port 19091 --health-attempts 1 --health-interval 1 --release-retention 1 --backup-retention 1 \
    > "${CASE_DIR}/output" 2>&1 || status=$?
  [[ "${status}" == "${expected}" ]] || fail "expected exit ${expected}, got ${status}"
}

assert_previous_files() {
  local name live
  [[ "$(readlink "${TEST_PREFIX}/current")" == releases/previous ]] || fail 'current not restored'
  for name in unit ioc ioc-config; do
    live="${TEST_PREFIX}/bin/${name}"
    if [[ "${name}" == unit ]]; then live="${CASE_DIR}/systemd/ioc-extractor.service"; fi
    if [[ "${FAULT}/${name}" == inactive/ioc-config ]]; then
      [[ ! -e "${live}" ]] || fail 'absent helper was installed during rollback'
      continue
    fi
    cmp -s "${live}" "${CASE_DIR}/original/${name}" || fail "${name} bytes changed"
    [[ "$(stat -c '%a %u %g' "${live}")" == "$(stat -c '%a %u %g' "${CASE_DIR}/original/${name}")" ]] \
      || fail "${name} metadata changed"
  done
}

arrange config-lock
exec 7>"${CASE_DIR}/config.lock"
flock 7
activate 1
exec 7>&-
[[ ! -s "${CASE_DIR}/events" ]] || fail 'concurrent config apply reached systemd'
assert_previous_files

arrange preflight
activate 1
[[ "$(< "${CASE_DIR}/events")" == preflight ]] || fail 'preflight failure reached service activation'
[[ -z "$(find "${TEST_PREFIX}/backups" -mindepth 1 -print -quit)" ]] || fail 'rejected preflight created a rollback set'
assert_previous_files
fixture-db check "${TEST_PREFIX}/var/db" original

for scenario in stop:47 backup:44 archive-validation:46 partial-files:43 reload:49 start:50 health:1 signal:143 inactive:1; do
  arrange "${scenario%:*}"
  activate "${scenario#*:}"
  assert_previous_files
  fixture-db check "${TEST_PREFIX}/var/db" original
  expected_state=active
  if [[ "${FAULT}" == inactive ]]; then
    expected_state=inactive
    ! grep -q 'start previous' "${CASE_DIR}/events" || fail 'rollback started an initially inactive service'
  fi
  [[ "$(< "${CASE_DIR}/state")" == "${expected_state}" ]] || fail 'service state not restored'
  grep -q 'previous release restored' "${CASE_DIR}/output" || fail 'rollback result missing'
  case "${FAULT}" in
    stop|backup|archive-validation|partial-files|reload)
      [[ -z "$(find "${TEST_PREFIX}/var" -maxdepth 1 -name 'db.failed-*' -print -quit)" ]] \
        || fail 'databases were moved before candidate could write'
      ;;
    *)
      failed_db=("${TEST_PREFIX}/var/"db.failed-*)
      [[ ${#failed_db[@]} == 1 && -d "${failed_db[0]}" ]] || fail 'candidate databases not preserved'
      fixture-db check "${failed_db[0]}" candidate
      ;;
  esac
done

arrange health retry
activate 1
fixture-db write "${TEST_PREFIX}/var/db" between-attempts
export FAULT=retry-backup
activate 44
fixture-db check "${TEST_PREFIX}/var/db" between-attempts
assert_previous_files
attempts=("${TEST_PREFIX}/backups/"local-deploy.candidate.*)
[[ ${#attempts[@]} == 2 ]] || fail 'same release ID reused the previous recovery directory'
for scenario in restore rollback-stop; do
  arrange "${scenario}"
  activate 1
  grep -q 'rollback incomplete; recovery files:' "${CASE_DIR}/output" || fail 'incomplete rollback not reported'
  ! grep -q 'start previous' "${CASE_DIR}/events" || fail 'previous service started after incomplete rollback'
  fixture-db check "${TEST_PREFIX}/var/db" candidate
  attempts=("${TEST_PREFIX}/backups/"local-deploy.*)
  [[ -f "${attempts[0]}/db.tar" && ! -f "${attempts[0]}/completed" ]] || fail 'manual recovery point missing'
done

arrange success
mkdir "${TEST_PREFIX}/backups/local-deploy.interrupted.000001"
activate 0
[[ "$(readlink "${TEST_PREFIX}/current")" == releases/candidate ]] || fail 'candidate not active'
[[ -d "${TEST_PREFIX}/releases/previous" ]] || fail 'release retention removed rollback binary'
[[ -d "${TEST_PREFIX}/backups/local-deploy.interrupted.000001" ]] || fail 'retention removed interrupted attempt'
[[ "$(< "${CASE_DIR}/events")" == $'preflight\nstop\nreload\nstart candidate' ]] || fail 'wrong activation order'
grep -q -- '--server.port=19091' "${CASE_DIR}/systemd/ioc-extractor.service" || fail 'candidate port missing'
grep -q '^SERVER_PORT="19091"$' "${TEST_PREFIX}/bin/ioc-config" || fail 'candidate helper missing'
fixture-db check "${TEST_PREFIX}/var/db" candidate
printf '%s\n' '[local-activation] configuration, activation and rollback contracts passed'
