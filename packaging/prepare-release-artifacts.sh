#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  prepare-release-artifacts.sh --tag TAG --project-version VERSION --validate-only
  prepare-release-artifacts.sh --tag TAG --project-version VERSION \
    --commit SHA --jar FILE --output-dir DIR

Validates the release identity contract. In preparation mode it also verifies
the packaged build metadata and lightweight version command, then creates:

  ioc-extractor-VERSION.jar
  ioc-extractor-VERSION.jar.sha256

Metadata for GitHub Actions is written as key=value lines to stdout. Progress
and validation diagnostics are written to stderr.
USAGE
}

die() {
  printf 'release preparation: %s\n' "$*" >&2
  exit 1
}

require_value() {
  local option=$1
  local value=${2-}
  [[ -n "${value}" ]] || die "${option} requires a value"
}

property_value() {
  local property_file=$1
  local property_name=$2
  local values
  local count

  values=$(sed -n "s/^${property_name}=//p" "${property_file}" \
    | sed 's/\\:/:/g' \
    | tr -d '\r')
  count=$(printf '%s\n' "${values}" | sed '/^$/d' | wc -l)
  [[ "${count}" -eq 1 ]] || die "${property_name} must occur exactly once in build-info.properties"
  printf '%s' "${values}"
}

validate_release_identity() {
  local expected_version

  [[ "${release_tag}" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-rc\.([1-9][0-9]*))?$ ]] \
    || die "tag must match vX.Y.Z or vX.Y.Z-rc.N (N starts at 1): ${release_tag}"

  expected_version=${release_tag#v}
  [[ "${project_version}" != *-SNAPSHOT ]] \
    || die "SNAPSHOT versions cannot be released: ${project_version}"
  [[ "${project_version}" == "${expected_version}" ]] \
    || die "tag ${release_tag} does not match Maven version ${project_version}"
}

release_tag=''
project_version=''
commit=''
jar_file=''
output_dir=''
validate_only=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      require_value "$1" "${2-}"
      release_tag=$2
      shift 2
      ;;
    --project-version)
      require_value "$1" "${2-}"
      project_version=$2
      shift 2
      ;;
    --commit)
      require_value "$1" "${2-}"
      commit=$2
      shift 2
      ;;
    --jar)
      require_value "$1" "${2-}"
      jar_file=$2
      shift 2
      ;;
    --output-dir)
      require_value "$1" "${2-}"
      output_dir=$2
      shift 2
      ;;
    --validate-only)
      validate_only=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

[[ -n "${release_tag}" ]] || die '--tag is required'
[[ -n "${project_version}" ]] || die '--project-version is required'
validate_release_identity

if [[ "${validate_only}" == true ]]; then
  [[ -z "${commit}${jar_file}${output_dir}" ]] \
    || die '--validate-only cannot be combined with --commit, --jar, or --output-dir'
  printf 'Validated release identity %s -> %s\n' "${release_tag}" "${project_version}" >&2
  exit 0
fi

[[ "${commit}" =~ ^([0-9a-fA-F]{40}|[0-9a-fA-F]{64})$ ]] \
  || die '--commit must be a full 40- or 64-character hexadecimal Git object ID'
[[ -n "${jar_file}" ]] || die '--jar is required unless --validate-only is used'
[[ -f "${jar_file}" && ! -L "${jar_file}" ]] \
  || die "jar must be a regular non-symlink file: ${jar_file}"
[[ -n "${output_dir}" ]] || die '--output-dir is required unless --validate-only is used'

command -v sha256sum >/dev/null 2>&1 || die 'sha256sum is required'

if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" && -x "${JAVA_HOME}/bin/jar" ]]; then
  java_bin="${JAVA_HOME}/bin/java"
  jar_bin="${JAVA_HOME}/bin/jar"
else
  java_bin=$(command -v java || true)
  jar_bin=$(command -v jar || true)
  [[ -n "${java_bin}" ]] || die 'Java runtime is required'
  [[ -n "${jar_bin}" ]] || die 'JDK jar tool is required (set JAVA_HOME or add it to PATH)'
fi

jar_file=$(realpath "${jar_file}")
mkdir -p "${output_dir}"
output_dir=$(realpath "${output_dir}")

asset_name="ioc-extractor-${project_version}.jar"
checksum_name="${asset_name}.sha256"
asset_path="${output_dir}/${asset_name}"
checksum_path="${output_dir}/${checksum_name}"

[[ ! -e "${asset_path}" && ! -L "${asset_path}" ]] \
  || die "refusing to overwrite existing release asset: ${asset_path}"
[[ ! -e "${checksum_path}" && ! -L "${checksum_path}" ]] \
  || die "refusing to overwrite existing checksum: ${checksum_path}"

metadata_dir=$(mktemp -d "${TMPDIR:-/tmp}/ioc-release-build-info.XXXXXX")
cleanup() {
  if [[ "${metadata_dir}" == "${TMPDIR:-/tmp}"/ioc-release-build-info.* && -d "${metadata_dir}" ]]; then
    rm -rf -- "${metadata_dir}"
  fi
}
trap cleanup EXIT

(
  cd "${metadata_dir}"
  "${jar_bin}" --extract --file "${jar_file}" META-INF/build-info.properties
)
build_info="${metadata_dir}/META-INF/build-info.properties"
[[ -f "${build_info}" ]] || die 'META-INF/build-info.properties is missing from the bootable jar'

embedded_version=$(property_value "${build_info}" 'build\.version')
embedded_commit=$(property_value "${build_info}" 'build\.commit')
embedded_time=$(property_value "${build_info}" 'build\.time')

[[ "${embedded_version}" == "${project_version}" ]] \
  || die "embedded version ${embedded_version} does not match ${project_version}"
[[ "${embedded_commit}" == "${commit}" ]] \
  || die "embedded commit ${embedded_commit} does not match ${commit}"
[[ "${embedded_time}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?Z$ ]] \
  || die "embedded build time is not a UTC instant: ${embedded_time}"

version_output=$("${java_bin}" -jar "${jar_file}" --version 2>&1)
expected_output=$(printf 'ioc-extractor %s\ncommit: %s\nbuilt: %s' \
  "${project_version}" "${commit:0:12}" "${embedded_time}")
[[ "${version_output}" == "${expected_output}" ]] \
  || die "lightweight --version output does not match embedded build identity"

install -m 0644 "${jar_file}" "${asset_path}"
asset_sha256=$(sha256sum "${asset_path}" | awk '{print $1}')
printf '%s  %s\n' "${asset_sha256}" "${asset_name}" > "${checksum_path}"
(
  cd "${output_dir}"
  sha256sum --check --strict "${checksum_name}" >/dev/null
)

if [[ "${project_version}" == *-rc.* ]]; then
  prerelease=true
else
  prerelease=false
fi

printf 'Prepared %s (%s)\n' "${asset_name}" "${asset_sha256}" >&2
printf 'release_tag=%s\n' "${release_tag}"
printf 'release_version=%s\n' "${project_version}"
printf 'prerelease=%s\n' "${prerelease}"
printf 'asset_name=%s\n' "${asset_name}"
printf 'checksum_name=%s\n' "${checksum_name}"
printf 'asset_sha256=%s\n' "${asset_sha256}"
