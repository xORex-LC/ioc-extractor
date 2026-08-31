#!/usr/bin/env bash
set -Eeuo pipefail

TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
PACKAGING_DIR="$(cd -- "${TEST_DIR}/.." >/dev/null 2>&1 && pwd)"
# shellcheck source=packaging/install-layout.sh
. "${PACKAGING_DIR}/install-layout.sh"

fail() {
  printf '[packaging-contract] FAIL: %s\n' "$*" >&2
  exit 1
}

assert_rejected_prefix() {
  local prefix="$1"
  if ioc_validate_prefix "${prefix}" >/dev/null 2>&1; then
    fail "unsafe prefix was accepted: ${prefix}"
  fi
}

assert_accepted_prefix() {
  local prefix="$1"
  ioc_validate_prefix "${prefix}" >/dev/null 2>&1 \
    || fail "safe dedicated prefix was rejected: ${prefix}"
  [[ "${IOC_VALIDATED_PREFIX}" == "${prefix}" ]] \
    || fail "prefix normalization changed an already canonical path"
}

assert_contains() { # file literal description
  local file="$1" literal="$2" description="$3"
  grep -Fq -- "${literal}" "${file}" \
    || fail "${description}: missing '${literal}' in ${file}"
}

assert_not_contains() { # content literal description
  local content="$1" literal="$2" description="$3"
  if grep -Fq -- "${literal}" <<< "${content}"; then
    fail "${description}: unexpectedly contained '${literal}'"
  fi
}

TEMP_ROOT="$(mktemp -d)"
mkdir -p "${PACKAGING_DIR}/target"
LINK_TEST_ROOT="$(mktemp -d "${PACKAGING_DIR}/target/packaging-contract.XXXXXX")"
cleanup() {
  if [[ "${TEMP_ROOT}" == /tmp/* && -d "${TEMP_ROOT}" ]]; then
    rm -rf -- "${TEMP_ROOT}"
  fi
  if [[ "${LINK_TEST_ROOT}" == "${PACKAGING_DIR}/target/"* && -d "${LINK_TEST_ROOT}" ]]; then
    rm -rf -- "${LINK_TEST_ROOT}"
  fi
}
trap cleanup EXIT

for prefix in / /etc /etc/ioc-extractor /home/operator/ioc /opt /srv /tmp/ioc \
    /usr/local/ioc /var /var/tmp/ioc; do
  assert_rejected_prefix "${prefix}"
done
assert_rejected_prefix "${TEMP_ROOT}/with space"
assert_rejected_prefix "${TEMP_ROOT}/ioc&other"

for prefix in /opt/ioc-contract /srv/ioc-contract /var/lib/ioc-contract /mnt/ioc-contract; do
  assert_accepted_prefix "${prefix}"
done
mkdir -p "${LINK_TEST_ROOT}/real"
ln -s "${LINK_TEST_ROOT}/real" "${LINK_TEST_ROOT}/linked"
assert_rejected_prefix "${LINK_TEST_ROOT}/linked/ioc-extractor"

SAFE_PREFIX="${TEMP_ROOT}/ioc-extractor"
mkdir -p "${SAFE_PREFIX}/etc"
ioc_write_marker "${SAFE_PREFIX}" "ioc-extractor" "ioc-test"

FRESH_CONFIG="${PACKAGING_DIR}/templates/application.yml"
CLASSPATH_CONFIG="${PACKAGING_DIR}/../bootstrap/ioc-app/src/main/resources/application.yml"
FRESH_LIFECYCLE="$(sed -n '/^  lifecycle:/,/^  maintenance:/p' "${FRESH_CONFIG}")"
CLASSPATH_LIFECYCLE="$(sed -n '/^  lifecycle:/,/^  maintenance:/p' "${CLASSPATH_CONFIG}")"
grep -Fq 'mode: fixed' <<< "${FRESH_LIFECYCLE}" \
  || fail "fresh-install lifecycle preset is not fixed"
grep -Fq 'fixed-ttl: 12h' <<< "${FRESH_LIFECYCLE}" \
  || fail "fresh-install lifecycle preset is not fixed at 12h"
grep -Fq 'existing-records: reject' <<< "${FRESH_LIFECYCLE}" \
  || fail "fresh-install lifecycle preset can destructively expire unexpected rows"
grep -Fq 'history-retention: 30d' <<< "${FRESH_LIFECYCLE}" \
  || fail "fresh-install history retention is not 30d"
grep -Fq 'receipt-retention: 30d' <<< "${FRESH_LIFECYCLE}" \
  || fail "fresh-install receipt retention is not 30d"
grep -Fq 'mode: disabled' <<< "${CLASSPATH_LIFECYCLE}" \
  || fail "upgrade-compatible classpath lifecycle default is not disabled"
for import_dir in inbox processing snapshots staging terminal quarantine; do
  assert_contains "${PACKAGING_DIR}/install.sh" \
    "\${PREFIX}/var/import/${import_dir}" \
    "fresh installer lost private managed-import directory"
  assert_contains "${PACKAGING_DIR}/deploy-local-root.sh" \
    "\${PREFIX}/var/import/${import_dir}" \
    "upgrade deploy lost managed-import directory reconciliation"
done
assert_contains "${PACKAGING_DIR}/deploy-local-root.sh" \
  "install -d -o \"\${RUN_USER}\" -g \"\${RUN_GROUP}\" -m 0750" \
  "upgrade deploy lost private managed-import directory reconciliation"

FRESH_EXPORT="$(sed -n '/^  export:/,/^  ingestion:/p' "${FRESH_CONFIG}")"
FRESH_INGEST="$(sed -n '/^  ingestion:/,/^  lifecycle:/p' "${FRESH_CONFIG}")"
FRESH_IMPORT="$(sed -n '/^  dataframe-import:/,/^  export:/p' "${FRESH_CONFIG}")"
grep -Fq 'quiet-period: 1s' <<< "${FRESH_EXPORT}" \
  || fail "fresh production export lost the one-second event coalescing window"
grep -Fq 'interval: 10s' <<< "${FRESH_EXPORT}" \
  || fail "fresh production export lost its ten-second correctness backstop"
grep -Fq 'use-watch-service: false' <<< "${FRESH_INGEST}" \
  || fail "fresh local ingestion lost its complete polling correctness path"
grep -Fq 'reconcile-interval: 5s' <<< "${FRESH_INGEST}" \
  || fail "fresh local ingestion lost its bounded correctness backstop"
grep -Fq 'reconcile-interval: 2s' <<< "${FRESH_IMPORT}" \
  || fail "fresh managed import lost its bounded correctness backstop"

# Existing configuration is operator-owned: both privileged deployment paths
# must stage a changed template as .new rather than overwrite it implicitly.
# shellcheck disable=SC2016 # deployment-script variables are matched literally
assert_contains "${PACKAGING_DIR}/install.sh" 'install -m 0640 "${src}" "${dst}.new"' \
  "installer lost upgrade configuration preservation"
# shellcheck disable=SC2016 # deployment-script variables are matched literally
assert_contains "${PACKAGING_DIR}/deploy-local-root.sh" \
  'install -o root -g "${RUN_GROUP}" -m 0640 "${template}" "${installed}.new"' \
  "local deployment lost upgrade configuration preservation"

CONFLICT_TEMPLATE="${TEMP_ROOT}/incoming-application.yml"
CONFLICT_INSTALLED="${TEMP_ROOT}/application.yml"
printf 'template-value-must-not-be-printed\n' > "${CONFLICT_TEMPLATE}"
printf 'live-value-must-not-be-printed\n' > "${CONFLICT_INSTALLED}"
printf 'candidate-value-must-not-be-printed\n' > "${CONFLICT_INSTALLED}.new"
CONFLICT_REPORT="$(ioc_report_config_candidate_conflict \
  "${CONFLICT_TEMPLATE}" "${CONFLICT_INSTALLED}" 2>&1)"
for expected in PACKAGING.CONFIG_CANDIDATE_CONFLICT \
    "${CONFLICT_INSTALLED}" "${CONFLICT_INSTALLED}.new" "${CONFLICT_TEMPLATE}" \
    'Existing unreconciled candidate' 'Incoming packaged template' \
    'sudo diff -u --' 'sudo mv --' 'sha256:'; do
  grep -Fq -- "${expected}" <<< "${CONFLICT_REPORT}" \
    || fail "configuration conflict report omitted '${expected}'"
done
assert_not_contains "${CONFLICT_REPORT}" 'template-value-must-not-be-printed' \
  "configuration conflict report leaked template content"
assert_not_contains "${CONFLICT_REPORT}" 'live-value-must-not-be-printed' \
  "configuration conflict report leaked live content"
assert_not_contains "${CONFLICT_REPORT}" 'candidate-value-must-not-be-printed' \
  "configuration conflict report leaked candidate content"
# shellcheck disable=SC2016 # deployment-script variables are matched literally
assert_contains "${PACKAGING_DIR}/deploy-local-root.sh" \
  'UNIT_BACKUP="${PREFIX}/backups/${RELEASE_ID}-unit.service"' \
  "local deployment lost version-matched systemd unit backup"
# shellcheck disable=SC2016 # deployment-script variables are matched literally
assert_contains "${PACKAGING_DIR}/deploy-local-root.sh" \
  'install -o root -g root -m 0644 "${UNIT_BACKUP}" "${UNIT}"' \
  "local deployment rollback no longer restores the previous systemd unit"
# shellcheck disable=SC2016 # deployment-script variables are matched literally
assert_contains "${PACKAGING_DIR}/deploy-local-root.sh" \
  'tar --acls --xattrs -C "${PREFIX}/var" -cf "${BACKUP}.tmp" db' \
  "local deployment backup no longer preserves database ACLs and xattrs"
# shellcheck disable=SC2016 # deployment-script variables are matched literally
assert_contains "${PACKAGING_DIR}/deploy-local-root.sh" \
  'tar --acls --xattrs -C "${PREFIX}/var" -xf "${BACKUP}"' \
  "local deployment rollback no longer restores database ACLs and xattrs"
# shellcheck disable=SC2016 # deployment-script variables are matched literally
assert_contains "${PACKAGING_DIR}/deploy-local-root.sh" \
  'prune_backup_sets "${PREFIX}/backups" "${BACKUP_RETENTION}"' \
  "local deployment no longer retains database and unit backups as pairs"
assert_contains "${PACKAGING_DIR}/deploy-local.sh" \
  'JDK 21+ is required for verification' \
  "local deployment no longer rejects an incompatible build JVM before Maven"
ioc_is_valid_marker "${SAFE_PREFIX}" "ioc-extractor" "ioc-test" \
  || fail "new installation marker did not validate"
if ioc_is_valid_marker "${SAFE_PREFIX}" "ioc-extractor" "another-user"; then
  fail "installation marker accepted a mismatched service user"
fi
printf 'service=ioc-extractor\n' >> "$(ioc_marker_path "${SAFE_PREFIX}")"
if ioc_is_valid_marker "${SAFE_PREFIX}" "ioc-extractor" "ioc-test"; then
  fail "installation marker accepted duplicate fields"
fi
ioc_write_marker "${SAFE_PREFIX}" "ioc-extractor" "ioc-test"

PRE_MARKER_PREFIX="${TEMP_ROOT}/pre-marker-ioc"
mkdir -p "${PRE_MARKER_PREFIX}/releases/r1" "${PRE_MARKER_PREFIX}/etc" \
  "${PRE_MARKER_PREFIX}/var" "${PRE_MARKER_PREFIX}/dataframe"
touch "${PRE_MARKER_PREFIX}/releases/r1/ioc-app.jar" \
  "${PRE_MARKER_PREFIX}/etc/application.yml"
ln -s releases/r1 "${PRE_MARKER_PREFIX}/current"
ioc_is_pre_marker_release_layout "${PRE_MARKER_PREFIX}" \
  || fail "strict pre-marker recognizer rejected the supported adoption shape"
rm "${PRE_MARKER_PREFIX}/current"
ln -s 'releases/../../unrelated' "${PRE_MARKER_PREFIX}/current"
if ioc_is_pre_marker_release_layout "${PRE_MARKER_PREFIX}"; then
  fail "pre-marker recognizer accepted a traversal current target"
fi

V010_PREFIX="${TEMP_ROOT}/v0.1.0-ioc"
mkdir -p "${V010_PREFIX}/lib" "${V010_PREFIX}/etc" \
  "${V010_PREFIX}/var" "${V010_PREFIX}/dataframe"
touch "${V010_PREFIX}/lib/ioc-app-0.1.0.jar" "${V010_PREFIX}/etc/application.yml"
ioc_is_v010_single_dir_installation "${V010_PREFIX}" \
  || fail "v0.1.0 single-directory layout was not recognized"
if ioc_is_pre_marker_release_layout "${V010_PREFIX}"; then
  fail "v0.1.0 layout was incorrectly accepted as an in-place adoption shape"
fi

SOURCE_TREE="${TEMP_ROOT}/source-tree"
mkdir -p "${SOURCE_TREE}/.git" "${SOURCE_TREE}/nested/install"
ioc_is_inside_source_tree "${SOURCE_TREE}/nested/install" \
  || fail "source-tree ancestry guard missed a nested install prefix"

if ioc_validate_service_user root >/dev/null 2>&1; then
  fail "UID 0 service account was accepted"
fi

RENDER_PREFIX="${TEMP_ROOT}/rendered"
mkdir -p "${RENDER_PREFIX}/etc" "${RENDER_PREFIX}/current" \
  "${RENDER_PREFIX}/var" "${RENDER_PREFIX}/dataframe"
cp "${PACKAGING_DIR}/templates/ioc-extractor.env" "${RENDER_PREFIX}/etc/ioc-extractor.env"
touch "${RENDER_PREFIX}/current/ioc-app.jar"
RENDERED_UNIT="${TEMP_ROOT}/ioc-extractor.service"
sed -e "s|@PREFIX@|${RENDER_PREFIX}|g" \
    -e 's|@JAVA_BIN@|/usr/bin/java|g' \
    -e "s|@USER@|$(id -un)|g" \
    -e "s|@GROUP@|$(id -gn)|g" \
    -e 's|@SERVER_PORT_ARG@|--server.port=19091|g' \
    "${PACKAGING_DIR}/templates/ioc-extractor.service" > "${RENDERED_UNIT}"
grep -q -- '--server.port=19091' "${RENDERED_UNIT}" \
  || fail "rendered unit lost the explicit server port"
# Literal deployment-script contract: ${PORT} must be expanded by that script,
# not by this test.
# shellcheck disable=SC2016
grep -Fq 's|@SERVER_PORT_ARG@|--server.port=${PORT}|g' \
  "${PACKAGING_DIR}/deploy-local-root.sh" \
  || fail "local deployment no longer renders its selected port into the unit"
if grep -q '@[A-Z_][A-Z_]*@' "${RENDERED_UNIT}"; then
  fail "rendered unit still contains an unresolved placeholder"
fi
assert_contains "${RENDERED_UNIT}" 'ExecCondition=' \
  "systemd unit lost the non-restarting YAML condition"
assert_contains "${RENDERED_UNIT}" '--ioc.validate-config=./etc/application.yml' \
  "systemd unit lost pre-start semantic configuration validation"
if grep -q '^ExecStartPre=.*--ioc.validate-config=' "${RENDERED_UNIT}"; then
  fail "configuration validation in ExecStartPre can still trigger Restart=on-failure"
fi
assert_contains "${RENDERED_UNIT}" 'RestartPreventExitStatus=78' \
  "systemd unit can restart-loop on deterministic YAML errors"
if command -v systemd-analyze >/dev/null 2>&1; then
  systemd-analyze verify "${RENDERED_UNIT}"
fi

RENDERED_CONFIG_TOOL="${TEMP_ROOT}/ioc-config"
sed -e "s|@PREFIX@|${RENDER_PREFIX}|g" \
    -e 's|@JAVA_BIN@|/usr/bin/java|g' \
    -e "s|@USER@|$(id -un)|g" \
    -e "s|@GROUP@|$(id -gn)|g" \
    -e 's|@SERVER_PORT@|19091|g' \
    "${PACKAGING_DIR}/templates/ioc-config" > "${RENDERED_CONFIG_TOOL}"
if grep -q '@[A-Z_][A-Z_]*@' "${RENDERED_CONFIG_TOOL}"; then
  fail "rendered config tool still contains an unresolved placeholder"
fi
assert_contains "${RENDERED_CONFIG_TOOL}" 'edit a separate candidate' \
  "config tool permits unsafe live-file apply"
assert_contains "${RENDERED_CONFIG_TOOL}" 'atomic_restore' \
  "config tool lost failed-activation rollback"
assert_contains "${RENDERED_CONFIG_TOOL}" '--ioc.validate-config=' \
  "config tool still performs syntax-only validation"
assert_contains "${RENDERED_CONFIG_TOOL}" 'EnvironmentFile=' \
  "config tool semantic validation lost the installed service environment"
assert_contains "${RENDERED_CONFIG_TOOL}" 'stage_candidate' \
  "config tool no longer validates service-readable staged bytes"
assert_contains "${RENDERED_CONFIG_TOOL}" '--property=ProtectSystem=strict' \
  "config tool semantic validation lost its read-only filesystem sandbox"

assert_contains "${PACKAGING_DIR}/deploy-local-root.sh" \
  'systemd-run --quiet --wait --pipe --collect' \
  "local deployment lost isolated effective configuration preflight"
# shellcheck disable=SC2016 # deployment-script variables are matched literally
assert_contains "${PACKAGING_DIR}/deploy-local-root.sh" \
  '--property="EnvironmentFile=${PREFIX}/etc/ioc-extractor.env"' \
  "local deployment preflight no longer uses the installed service environment"
# shellcheck disable=SC2016 # deployment-script variables are matched literally
assert_contains "${PACKAGING_DIR}/deploy-local-root.sh" \
  '"--ioc.validate-config=${PREFIX}/etc/application.yml"' \
  "local deployment preflight no longer validates the live operator YAML"
assert_contains "${PACKAGING_DIR}/deploy-local-root.sh" \
  '--property=ProtectSystem=strict' \
  "local deployment preflight lost its read-only filesystem sandbox"
CONFIG_CHECK_LINE="$(grep -n 'validating effective configuration' \
  "${PACKAGING_DIR}/deploy-local-root.sh" | cut -d: -f1)"
BACKUP_LINE="$(grep -n "stopping \${SERVICE} and backing up SQLite state" \
  "${PACKAGING_DIR}/deploy-local-root.sh" | cut -d: -f1)"
[[ -n "${CONFIG_CHECK_LINE}" && -n "${BACKUP_LINE}" \
    && "${CONFIG_CHECK_LINE}" -lt "${BACKUP_LINE}" ]] \
  || fail "effective configuration preflight no longer precedes stop/backup/activation"
bash -n "${RENDERED_CONFIG_TOOL}"

bash -n \
  "${PACKAGING_DIR}/install-layout.sh" \
  "${PACKAGING_DIR}/install.sh" \
  "${PACKAGING_DIR}/deploy-local.sh" \
  "${PACKAGING_DIR}/deploy-local-root.sh" \
  "${PACKAGING_DIR}/uninstall.sh" \
  "${PACKAGING_DIR}/templates/ioc" \
  "${PACKAGING_DIR}/templates/ioc-config"

printf '[packaging-contract] PASS\n'
