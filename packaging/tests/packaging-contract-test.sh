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
if command -v systemd-analyze >/dev/null 2>&1; then
  systemd-analyze verify "${RENDERED_UNIT}"
fi

bash -n \
  "${PACKAGING_DIR}/install-layout.sh" \
  "${PACKAGING_DIR}/install.sh" \
  "${PACKAGING_DIR}/deploy-local.sh" \
  "${PACKAGING_DIR}/deploy-local-root.sh" \
  "${PACKAGING_DIR}/uninstall.sh" \
  "${PACKAGING_DIR}/templates/ioc"

printf '[packaging-contract] PASS\n'
