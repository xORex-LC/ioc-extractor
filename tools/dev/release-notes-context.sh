#!/usr/bin/env bash
# Markdown backticks in single-quoted format strings are intentional literals.
# shellcheck disable=SC2016
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${SCRIPT_DIR}/common.sh"

PREVIOUS_TAG=""
TARGET_REF="HEAD"
INCLUDE_GITHUB="false"

usage() {
  cat <<'USAGE'
Usage: tools/dev/release-notes-context.sh --previous-tag TAG [OPTIONS]

Build a read-only Markdown inventory for human-curated release notes.

Options:
  --previous-tag TAG  Required previous release tag, for example v0.1.0
  --target REF        Candidate Git ref or commit (default: HEAD)
  --github            Include merged PRs through authenticated GitHub CLI
  -h, --help          Show this help

The output is context, not publication-ready release notes. It never creates or
updates a tag, release, notes file or repository setting.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --previous-tag)
      [[ $# -ge 2 && -n "${2:-}" ]] || dev_die "--previous-tag requires a value"
      PREVIOUS_TAG="$2"
      shift 2
      ;;
    --target)
      [[ $# -ge 2 && -n "${2:-}" ]] || dev_die "--target requires a value"
      TARGET_REF="$2"
      shift 2
      ;;
    --github)
      INCLUDE_GITHUB="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      dev_die "unknown release-notes context option: $1"
      ;;
  esac
done

[[ -n "${PREVIOUS_TAG}" ]] || dev_die "--previous-tag is required"
[[ "${PREVIOUS_TAG}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-rc\.[1-9][0-9]*)?$ ]] \
  || dev_die "previous tag must match vX.Y.Z or vX.Y.Z-rc.N"

dev_require_command git
cd "${DEV_REPO_ROOT}"

git rev-parse --verify --quiet --end-of-options "${PREVIOUS_TAG}^{commit}" >/dev/null \
  || dev_die "previous tag does not resolve to a commit: ${PREVIOUS_TAG}"
git rev-parse --verify --quiet --end-of-options "${TARGET_REF}^{commit}" >/dev/null \
  || dev_die "target does not resolve to a commit: ${TARGET_REF}"

PREVIOUS_COMMIT="$(git rev-parse --verify --end-of-options "${PREVIOUS_TAG}^{commit}")"
TARGET_COMMIT="$(git rev-parse --verify --end-of-options "${TARGET_REF}^{commit}")"
git merge-base --is-ancestor "${PREVIOUS_COMMIT}" "${TARGET_COMMIT}" \
  || dev_die "${PREVIOUS_TAG} is not an ancestor of ${TARGET_REF}"

REMOTE_URL="$(git remote get-url origin 2>/dev/null || true)"
GITHUB_REPOSITORY=""
case "${REMOTE_URL}" in
  https://github.com/*)
    GITHUB_REPOSITORY="${REMOTE_URL#https://github.com/}"
    ;;
  git@github.com:*)
    GITHUB_REPOSITORY="${REMOTE_URL#git@github.com:}"
    ;;
  ssh://git@github.com/*)
    GITHUB_REPOSITORY="${REMOTE_URL#ssh://git@github.com/}"
    ;;
esac
GITHUB_REPOSITORY="${GITHUB_REPOSITORY%.git}"
GITHUB_WEB_ROOT=""
[[ -z "${GITHUB_REPOSITORY}" ]] || GITHUB_WEB_ROOT="https://github.com/${GITHUB_REPOSITORY}"

print_commit_inventory() {
  local emitted="false" sha subject
  while IFS=$'\t' read -r sha subject; do
    [[ -n "${sha}" ]] || continue
    emitted="true"
    if [[ -n "${GITHUB_WEB_ROOT}" ]]; then
      printf -- '- [`%s`](%s/commit/%s) %s\n' "${sha:0:12}" "${GITHUB_WEB_ROOT}" "${sha}" "${subject}"
    else
      printf -- '- `%s` %s\n' "${sha}" "${subject}"
    fi
  done
  [[ "${emitted}" == "true" ]] || printf '%s\n' '- None.'
}

printf '%s\n\n' '# Release notes context'
printf '%s\n\n' \
  '> Generated technical inventory. Curate user/operator impact; do not publish this output verbatim.'
printf '%s\n' \
  "- Previous tag: \`${PREVIOUS_TAG}\` (\`${PREVIOUS_COMMIT}\`)" \
  "- Target: \`${TARGET_REF}\` (\`${TARGET_COMMIT}\`)"
if [[ -n "${GITHUB_WEB_ROOT}" ]]; then
  printf -- '- Compare: [%s...%s](%s/compare/%s...%s)\n' \
    "${PREVIOUS_TAG}" "${TARGET_REF}" "${GITHUB_WEB_ROOT}" "${PREVIOUS_TAG}" "${TARGET_COMMIT}"
fi

printf '%s\n\n' '' '## Changed top-level areas'
mapfile -t CHANGED_AREAS < <(
  git diff --name-only "${PREVIOUS_COMMIT}..${TARGET_COMMIT}" \
    | awk -F/ 'NF {print $1}' \
    | LC_ALL=C sort -u
)
if [[ "${#CHANGED_AREAS[@]}" -eq 0 ]]; then
  printf '%s\n' '- None.'
else
  printf -- '- `%s`\n' "${CHANGED_AREAS[@]}"
fi

declare -A CHANGED_MODULES=()
while IFS= read -r path; do
  [[ -n "${path}" ]] || continue
  directory="${path%/*}"
  [[ "${directory}" != "${path}" ]] || directory="."
  while :; do
    pom_path="pom.xml"
    [[ "${directory}" == "." ]] || pom_path="${directory}/pom.xml"
    if git cat-file -e "${TARGET_COMMIT}:${pom_path}" 2>/dev/null \
        || git cat-file -e "${PREVIOUS_COMMIT}:${pom_path}" 2>/dev/null; then
      CHANGED_MODULES["${directory}"]=1
      break
    fi
    [[ "${directory}" != "." ]] || break
    parent="${directory%/*}"
    [[ "${parent}" != "${directory}" ]] || parent="."
    directory="${parent}"
  done
done < <(git diff --name-only "${PREVIOUS_COMMIT}..${TARGET_COMMIT}")

printf '%s\n\n' '' '## Changed Maven modules'
if [[ "${#CHANGED_MODULES[@]}" -eq 0 ]]; then
  printf '%s\n' '- None.'
else
  printf '%s\n' "${!CHANGED_MODULES[@]}" | LC_ALL=C sort | sed 's/.*/- `&`/'
fi

printf '%s\n\n' '' '## Commit inventory'
print_commit_inventory < <(
  git log --reverse --format=$'%H\t%s' "${PREVIOUS_COMMIT}..${TARGET_COMMIT}"
)

printf '%s\n\n' '' '## PR and issue references found in Git history'
mapfile -t HISTORY_REFERENCES < <(
  git log --format='%s%n%b' "${PREVIOUS_COMMIT}..${TARGET_COMMIT}" \
    | grep -Eo '#[1-9][0-9]*' \
    | LC_ALL=C sort -Vu \
    || true
)
if [[ "${#HISTORY_REFERENCES[@]}" -eq 0 ]]; then
  printf '%s\n' '- None found in commit subjects or bodies.'
elif [[ -n "${GITHUB_WEB_ROOT}" ]]; then
  for reference in "${HISTORY_REFERENCES[@]}"; do
    printf -- '- [%s](%s/issues/%s)\n' "${reference}" "${GITHUB_WEB_ROOT}" "${reference#\#}"
  done
else
  printf -- '- `%s`\n' "${HISTORY_REFERENCES[@]}"
fi

printf '%s\n\n' '' '## Merged pull requests'
if [[ "${INCLUDE_GITHUB}" != "true" ]]; then
  printf '%s\n' '- Not queried. Re-run with `--github` to use authenticated GitHub metadata.'
else
  [[ -n "${GITHUB_REPOSITORY}" ]] \
    || dev_die "origin is not a supported GitHub URL; cannot query merged PRs"
  dev_require_command gh
  dev_require_command jq
  PR_JSON="$(gh pr list --repo "${GITHUB_REPOSITORY}" --state merged --limit 1000 \
    --json number,title,url,mergeCommit)" \
    || dev_die "failed to query merged pull requests"
  MERGED_PR_COUNT=0
  while IFS=$'\t' read -r merge_commit number title url; do
    [[ -n "${merge_commit}" && "${merge_commit}" != "null" ]] || continue
    git cat-file -e "${merge_commit}^{commit}" 2>/dev/null || continue
    git merge-base --is-ancestor "${merge_commit}" "${TARGET_COMMIT}" || continue
    if git merge-base --is-ancestor "${merge_commit}" "${PREVIOUS_COMMIT}"; then
      continue
    fi
    printf -- '- [#%s %s](%s)\n' "${number}" "${title}" "${url}"
    MERGED_PR_COUNT=$((MERGED_PR_COUNT + 1))
  done < <(jq -r '.[] | [(.mergeCommit.oid // ""), (.number | tostring), .title, .url] | @tsv' \
    <<< "${PR_JSON}")
  [[ "${MERGED_PR_COUNT}" -gt 0 ]] \
    || printf '%s\n' '- No merged PR commit belongs to the selected Git range.'
fi

printf '%s\n\n' '' '## Dependency and security candidates'
print_commit_inventory < <(
  git log --reverse --format=$'%H\t%s' "${PREVIOUS_COMMIT}..${TARGET_COMMIT}" -- \
    ':(glob)**/pom.xml' \
    '.github/dependabot.yml' \
    '.github/workflows/dependency-security.yml' \
    'dependency-check-suppressions.xml' \
    'docs/SECURITY-ENGINEERING.md' \
    'docs/THREAT-MODEL.md' \
    'docs/KNOWN-ISSUES.md'
)
