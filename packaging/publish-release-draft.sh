#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: publish-release-draft.sh --tag TAG --version VERSION \
  --asset FILE --checksum FILE --notes FILE [--dry-run]

Creates or repairs a GitHub draft release without ever publishing it. Existing
assets are downloaded and compared byte-for-byte; an asset with different bytes
is an integrity failure and is never replaced. A published release is read-only.

GH_TOKEN and GH_REPO are consumed by GitHub CLI in normal mode.
USAGE
}

die() {
  printf 'release publication: %s\n' "$*" >&2
  exit 1
}

require_value() {
  local option=$1
  local value=${2-}
  [[ -n "${value}" ]] || die "${option} requires a value"
}

release_tag=''
release_version=''
asset_file=''
checksum_file=''
notes_file=''
dry_run=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      require_value "$1" "${2-}"
      release_tag=$2
      shift 2
      ;;
    --version)
      require_value "$1" "${2-}"
      release_version=$2
      shift 2
      ;;
    --asset)
      require_value "$1" "${2-}"
      asset_file=$2
      shift 2
      ;;
    --checksum)
      require_value "$1" "${2-}"
      checksum_file=$2
      shift 2
      ;;
    --notes)
      require_value "$1" "${2-}"
      notes_file=$2
      shift 2
      ;;
    --dry-run)
      dry_run=true
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

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
"${script_dir}/prepare-release-artifacts.sh" \
  --tag "${release_tag}" \
  --project-version "${release_version}" \
  --validate-only

[[ -f "${asset_file}" && ! -L "${asset_file}" ]] \
  || die "asset must be a regular non-symlink file: ${asset_file}"
[[ -f "${checksum_file}" && ! -L "${checksum_file}" ]] \
  || die "checksum must be a regular non-symlink file: ${checksum_file}"
[[ -s "${notes_file}" && ! -L "${notes_file}" ]] \
  || die "release notes must be a non-empty regular non-symlink file: ${notes_file}"

asset_file=$(realpath "${asset_file}")
checksum_file=$(realpath "${checksum_file}")
notes_file=$(realpath "${notes_file}")
asset_name=$(basename "${asset_file}")
checksum_name=$(basename "${checksum_file}")
expected_asset_name="ioc-extractor-${release_version}.jar"

[[ "${asset_name}" == "${expected_asset_name}" ]] \
  || die "unexpected asset name ${asset_name}; expected ${expected_asset_name}"
[[ "${checksum_name}" == "${expected_asset_name}.sha256" ]] \
  || die "unexpected checksum name ${checksum_name}; expected ${expected_asset_name}.sha256"

(
  cd "$(dirname "${asset_file}")"
  sha256sum --check --strict "${checksum_file}" >/dev/null
) || die 'checksum sidecar does not verify the release asset'

if [[ "${release_version}" == *-rc.* ]]; then
  expected_prerelease=true
else
  expected_prerelease=false
fi
release_title="ioc-extractor ${release_version}"

if [[ "${dry_run}" == true ]]; then
  printf 'Dry run: would ensure draft %s with %s and %s\n' \
    "${release_tag}" "${asset_name}" "${checksum_name}"
  exit 0
fi

command -v gh >/dev/null 2>&1 || die 'GitHub CLI is required'
[[ -n "${GH_TOKEN:-}" ]] || die 'GH_TOKEN is required'
[[ -n "${GH_REPO:-}" ]] || die 'GH_REPO is required'

if gh release view "${release_tag}" >/dev/null 2>&1; then
  # Parse trusted GitHub CLI JSON without adding a separate jq dependency.
  is_draft=$(gh release view "${release_tag}" --json isDraft --jq '.isDraft')
  is_prerelease=$(gh release view "${release_tag}" --json isPrerelease --jq '.isPrerelease')
  existing_title=$(gh release view "${release_tag}" --json name --jq '.name')
  existing_body=$(gh release view "${release_tag}" --json body --jq '.body')
  expected_body=$(<"${notes_file}")

  [[ "${is_prerelease}" == "${expected_prerelease}" ]] \
    || die "existing release prerelease flag does not match ${release_tag}"

  if [[ "${existing_title}" != "${release_title}" || "${existing_body}" != "${expected_body}" ]]; then
    [[ "${is_draft}" == true ]] \
      || die 'published release metadata differs from the tagged source'
    gh release edit "${release_tag}" --title "${release_title}" --notes-file "${notes_file}"
  fi
else
  create_args=(release create "${release_tag}" --verify-tag --draft \
    --title "${release_title}" --notes-file "${notes_file}")
  if [[ "${expected_prerelease}" == true ]]; then
    create_args+=(--prerelease)
  fi
  gh "${create_args[@]}"
  is_draft=true
fi

download_dir=$(mktemp -d "${TMPDIR:-/tmp}/ioc-release-assets.XXXXXX")
cleanup() {
  if [[ "${download_dir}" == "${TMPDIR:-/tmp}"/ioc-release-assets.* && -d "${download_dir}" ]]; then
    rm -rf -- "${download_dir}"
  fi
}
trap cleanup EXIT

ensure_asset() {
  local local_file=$1
  local name=$2
  local remote_names

  remote_names=$(gh release view "${release_tag}" --json assets --jq '.assets[].name')
  if printf '%s\n' "${remote_names}" | grep -Fqx -- "${name}"; then
    gh release download "${release_tag}" --pattern "${name}" --dir "${download_dir}"
    cmp --silent "${local_file}" "${download_dir}/${name}" \
      || die "existing asset has different bytes and will not be replaced: ${name}"
    printf 'Verified existing release asset %s\n' "${name}" >&2
    return
  fi

  [[ "${is_draft}" == true ]] \
    || die "published release is missing required asset: ${name}"
  gh release upload "${release_tag}" "${local_file}"
  printf 'Uploaded missing draft asset %s\n' "${name}" >&2
}

ensure_asset "${asset_file}" "${asset_name}"
ensure_asset "${checksum_file}" "${checksum_name}"

printf 'Draft release %s contains the expected immutable assets\n' "${release_tag}"
