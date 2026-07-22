#!/usr/bin/env bash
set -Eeuo pipefail

TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "${TEST_DIR}/../.." >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${REPO_ROOT}/tools/dev/common.sh"

fail() {
  printf '[tools-contract] FAIL: %s\n' "$*" >&2
  exit 1
}

WORKSPACE="${DEV_ROOT}/tools-contract.$$"
cleanup() {
  dev_reset_workspace "${WORKSPACE}" 2>/dev/null || true
}
trap cleanup EXIT
dev_prepare_workspace "${WORKSPACE}"

COLORLESS_LOG="$(dev_log "captured output")"
[[ "${COLORLESS_LOG}" != *$'\033'* ]] \
  || fail "captured developer log contains ANSI escapes"
COLORLESS_WARNING="$(NO_COLOR=1 dev_warn "no color" 2>&1)"
[[ "${COLORLESS_WARNING}" != *$'\033'* ]] \
  || fail "NO_COLOR developer warning contains ANSI escapes"

if (dev_validate_workspace "/tmp/ioc-tools-contract" >/dev/null 2>&1); then
  fail "workspace validator accepted an external path"
fi
if (dev_validate_workspace "${DEV_ROOT}" >/dev/null 2>&1); then
  fail "workspace validator accepted the complete .dev root"
fi
mkdir -p "${WORKSPACE}/real"
ln -s "${WORKSPACE}/real" "${WORKSPACE}/linked"
if (dev_validate_workspace "${WORKSPACE}/linked/child" >/dev/null 2>&1); then
  fail "workspace validator accepted symlink traversal"
fi

FIRST="${WORKSPACE}/first.html"
SECOND="${WORKSPACE}/second.html"
THIRD="${WORKSPACE}/third.html"
java "${REPO_ROOT}/tools/dev/GenerateIocFixture.java" \
  --size 60 --seed 73 --duplicate-rate 0 --defang-rate 0.5 --output "${FIRST}" >/dev/null
java "${REPO_ROOT}/tools/dev/GenerateIocFixture.java" \
  --size 60 --seed 73 --duplicate-rate 0 --defang-rate 0.5 --output "${SECOND}" >/dev/null
java "${REPO_ROOT}/tools/dev/GenerateIocFixture.java" \
  --size 60 --seed 74 --duplicate-rate 0 --defang-rate 0.5 --output "${THIRD}" >/dev/null
cmp -s "${FIRST}" "${SECOND}" || fail "same seed did not produce identical fixture bytes"
cmp -s "${FIRST}" "${THIRD}" && fail "different seeds produced identical fixture bytes"
grep -q '"inputRows": 60' "${FIRST}.manifest.json" \
  || fail "manifest lost the requested row count"
grep -q '"uniqueInputValues": 60' "${FIRST}.manifest.json" \
  || fail "zero-duplicate fixture is not unique"
grep -q '<h2>БИБ-0001</h2>' "${FIRST}" \
  || fail "fixture lost source-attribution section markers"
if java "${REPO_ROOT}/tools/dev/GenerateIocFixture.java" \
    --size 1 --output "${FIRST}" >/dev/null 2>&1; then
  fail "generator overwrote an existing fixture without --force"
fi

LOG_WORKSPACE="${WORKSPACE}/logs-runtime"
LOG_FILE="${LOG_WORKSPACE}/var/logs/ioc-extractor.ecs.json"
mkdir -p -- "$(dirname -- "${LOG_FILE}")"
printf '%s\n' \
  '{"log":{"level":"INFO"},"event":{"action":"app_start"},"ioc":{"run":{"id":"run-1"}}}' \
  '{"log":{"level":"ERROR"},"event":{"action":"diagnostic_emit"},"ioc":{"run":{"id":"run-2"},"diagnostic":{"code":"PIPELINE.STAGE_FAILED"}}}' \
  > "${LOG_FILE}"
if command -v jq >/dev/null 2>&1; then
  [[ "$("${REPO_ROOT}/tools/dev/logs.sh" --workspace "${LOG_WORKSPACE}" errors | wc -l)" -eq 1 ]] \
    || fail "ECS error query returned the wrong number of events"
  "${REPO_ROOT}/tools/dev/logs.sh" --workspace "${LOG_WORKSPACE}" event app_start \
    | grep -q 'run-1' || fail "ECS event query missed the matching event"
  "${REPO_ROOT}/tools/dev/logs.sh" --workspace "${LOG_WORKSPACE}" run run-1 \
    | grep -q 'app_start' || fail "ECS run query missed the matching event"
  "${REPO_ROOT}/tools/dev/logs.sh" --workspace "${LOG_WORKSPACE}" \
    diagnostic PIPELINE.STAGE_FAILED \
    | grep -q 'run-2' || fail "ECS diagnostic query missed the matching event"
fi

CONTEXT_STATE="${WORKSPACE}/context-state"
mkdir -p -- "${CONTEXT_STATE}"
CURRENT_FINGERPRINT="$(dev_git_worktree_fingerprint)"
printf '%s\n' \
  'result=passed' \
  "commit=$(git -C "${REPO_ROOT}" rev-parse --verify HEAD)" \
  "fingerprint=${CURRENT_FINGERPRINT}" \
  'finished_at=2026-01-01T00:00:00Z' \
  > "${CONTEXT_STATE}/last-verify.env"
CONTEXT_OUTPUT="$(NO_COLOR=1 DEV_STATE_ROOT="${CONTEXT_STATE}" \
  "${REPO_ROOT}/tools/dev/context.sh" --workspace "${LOG_WORKSPACE}")"
grep -Eq '^project\.version=[^[:space:]]+$' <<< "${CONTEXT_OUTPUT}" \
  || fail "developer context did not expose the Maven project version"
grep -Fqx "runtime.workspace=${LOG_WORKSPACE}" <<< "${CONTEXT_OUTPUT}" \
  || fail "developer context did not expose the selected runtime workspace"
grep -Fqx 'verify.result=passed' <<< "${CONTEXT_OUTPUT}" \
  || fail "developer context did not read verify evidence"
grep -Fqx 'verify.fresh=true' <<< "${CONTEXT_OUTPUT}" \
  || fail "developer context did not recognize current verify evidence"
[[ "${CONTEXT_OUTPUT}" != *$'\033'* ]] \
  || fail "developer context contains ANSI escapes"

SUBMIT_WORKSPACE="${WORKSPACE}/submit-runtime"
mkdir -p "${SUBMIT_WORKSPACE}/var/inbox"
SUBMIT_SOURCE="${WORKSPACE}/submit-source.html"
printf '<p>ioc-1.example.test</p>\n' > "${SUBMIT_SOURCE}"
SUBMITTED="$("${REPO_ROOT}/tools/dev/submit.sh" \
  --workspace "${SUBMIT_WORKSPACE}" "${SUBMIT_SOURCE}")"
[[ -f "${SUBMITTED}" && "${SUBMITTED}" == "${SUBMIT_WORKSPACE}/var/inbox/submit-source.html" ]] \
  || fail "atomic developer inbox submission did not publish the expected file"

if command -v sqlite3 >/dev/null 2>&1; then
  DB_WORKSPACE="${WORKSPACE}/database-runtime"
  mkdir -p "${DB_WORKSPACE}/var/db"
  sqlite3 "${DB_WORKSPACE}/var/db/ioc-dataframe.db" \
    'CREATE TABLE contract_row(id INTEGER PRIMARY KEY); PRAGMA user_version=3;'
  "${REPO_ROOT}/tools/dev/database.sh" \
    --workspace "${DB_WORKSPACE}" --db dataframe tables \
    | grep -q contract_row || fail "read-only database helper did not expose tables"
fi

for script in \
    tools/dev/common.sh \
    tools/dev/context.sh \
    tools/dev/app.sh \
    tools/dev/bootstrap.sh \
    tools/dev/doctor.sh \
    tools/dev/fixture.sh \
    tools/dev/logs.sh \
    tools/dev/release-notes-context.sh \
    tools/dev/database.sh \
    tools/dev/runtime.sh \
    tools/dev/smoke.sh \
    tools/dev/submit.sh \
    tools/ci/build.sh \
    tools/ci/dependency-security.sh \
    tools/ci/docs.sh \
    tools/ci/packaging.sh \
    tools/tests/tools-contract-test.sh; do
  bash -n "${REPO_ROOT}/${script}"
done

"${REPO_ROOT}/tools/dev/doctor.sh" core >/dev/null
"${REPO_ROOT}/tools/dev/bootstrap.sh" --help >/dev/null
"${REPO_ROOT}/tools/dev/runtime.sh" --help >/dev/null
"${REPO_ROOT}/tools/dev/smoke.sh" --help >/dev/null
"${REPO_ROOT}/tools/dev/submit.sh" --help >/dev/null
"${REPO_ROOT}/tools/dev/database.sh" --help >/dev/null
"${REPO_ROOT}/tools/dev/release-notes-context.sh" --help >/dev/null
"${REPO_ROOT}/tools/ci/dependency-security.sh" --help >/dev/null
RELEASE_CONTEXT="$("${REPO_ROOT}/tools/dev/release-notes-context.sh" \
  --previous-tag v0.1.0 --target HEAD)"
grep -Fq '# Release notes context' <<< "${RELEASE_CONTEXT}" \
  || fail "release-notes context lost its document title"
grep -Fq '## Changed Maven modules' <<< "${RELEASE_CONTEXT}" \
  || fail "release-notes context lost the Maven-module inventory"
grep -Fq '## Dependency and security candidates' <<< "${RELEASE_CONTEXT}" \
  || fail "release-notes context lost the security candidate inventory"
if "${REPO_ROOT}/tools/dev/release-notes-context.sh" \
    --previous-tag v0.0.0 --target HEAD >/dev/null 2>&1; then
  fail "release-notes context accepted a missing previous tag"
fi
if "${REPO_ROOT}/tools/dev/release-notes-context.sh" \
    --previous-tag v0.1.0 --target -h >/dev/null 2>&1; then
  fail "release-notes context accepted an option-like target ref"
fi
if env -u NVD_API_KEY DEPENDENCY_CHECK_DATA="${WORKSPACE}/missing-odc-data" \
    "${REPO_ROOT}/tools/ci/dependency-security.sh" scan \
    >"${WORKSPACE}/missing-odc.out" 2>&1; then
  fail "offline security scan accepted a missing local database"
fi
grep -Fq "run 'make security-update' first" "${WORKSPACE}/missing-odc.out" \
  || fail "offline security scan did not explain how to create the local database"
if env -u NVD_API_KEY DEPENDENCY_CHECK_DATA="${WORKSPACE}/odc-update" \
    "${REPO_ROOT}/tools/ci/dependency-security.sh" update >/dev/null 2>&1; then
  fail "security update accepted a missing NVD_API_KEY"
fi
make --no-print-directory -s -C "${REPO_ROOT}" help \
  | grep -q 'test-one' || fail "Make help lost the targeted-test command"
make --no-print-directory -s -C "${REPO_ROOT}" help \
  | grep -q 'context' || fail "Make help lost the cold-start context command"
make --no-print-directory -s -C "${REPO_ROOT}" help \
  | grep -q 'release-notes-context' || fail "Make help lost the release-notes context command"
if grep -REq '^[[:space:]]*(run:[[:space:]]*)?make([[:space:]]|$)' \
    "${REPO_ROOT}/.github/workflows"; then
  fail "GitHub workflow depends on the developer-facing Make facade"
fi
grep -Fq 'run: tools/ci/build.sh' "${REPO_ROOT}/.github/workflows/ci.yml" \
  || fail "GitHub CI build does not call the canonical leaf script"
grep -Fq 'tools/ci/dependency-security.sh update' \
  "${REPO_ROOT}/.github/workflows/dependency-security.yml" \
  || fail "Dependency Security workflow does not update the NVD database explicitly"
grep -Fq 'tools/ci/dependency-security.sh scan' \
  "${REPO_ROOT}/.github/workflows/dependency-security.yml" \
  || fail "Dependency Security workflow does not call the canonical leaf script"
grep -Fq -- '-DautoUpdate=false' \
  "${REPO_ROOT}/tools/ci/dependency-security.sh" \
  || fail "Dependency Security scan is not pinned to the existing local database"
if grep -Fq 'deployment: false' \
    "${REPO_ROOT}/.github/workflows/dependency-security.yml"; then
  fail "Dependency Security workflow uses unsupported environment.deployment"
fi

for workflow in "${REPO_ROOT}"/.github/workflows/*.yml; do
  workflow_header="$(sed -n '1,/^jobs:/p' "${workflow}")"
  grep -Fq 'permissions:' <<<"${workflow_header}" \
    || fail "workflow does not declare top-level permissions: ${workflow}"
  grep -Fq '  contents: read' <<<"${workflow_header}" \
    || fail "workflow does not default to contents: read: ${workflow}"
done

UNPINNED_ACTIONS="$(
  grep -REh '^[[:space:]]*(-[[:space:]]+)?uses:' "${REPO_ROOT}/.github/workflows" \
    | sed 's/[[:space:]]*#.*$//' \
    | grep -Ev 'uses:[[:space:]]+(\./[^[:space:]]+|[^[:space:]@]+@[0-9a-f]{40})$' \
    || true
)"
[[ -z "${UNPINNED_ACTIONS}" ]] \
  || fail "GitHub Actions must use a local path or full commit SHA: ${UNPINNED_ACTIONS}"

printf '[tools-contract] PASS\n'
