#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${REPO_ROOT}/tools/dev/common.sh"
cd "${REPO_ROOT}"

usage() {
  cat <<'EOF'
Usage:
  tools/ci/test-pilots.sh mutation
  tools/ci/test-pilots.sh stability [--seed N] [--repeat N]

Both modes are report-only diagnostics. Stability failures are preserved and
never retried automatically.
EOF
}

require_positive_integer() {
  local name="$1" value="$2"
  [[ "${value}" =~ ^[1-9][0-9]*$ ]] \
    || dev_die "${name} must be a positive integer: '${value}'"
}

write_common_summary() { # file mode result started elapsed
  local file="$1" mode="$2" result="$3" started="$4" elapsed="$5"
  local end_fingerprint
  end_fingerprint="$(dev_git_worktree_fingerprint)"
  if [[ "${START_FINGERPRINT}" != "${end_fingerprint}" ]]; then
    result="invalidated"
  fi
  printf '%s\n' \
    "mode=${mode}" \
    "result=${result}" \
    "commit=$(git rev-parse --verify HEAD)" \
    "fingerprint=${end_fingerprint}" \
    "started_at=${started}" \
    "finished_at=$(date --utc +'%Y-%m-%dT%H:%M:%SZ')" \
    "elapsed_seconds=${elapsed}" \
    > "${file}"
  [[ "${result}" != "invalidated" ]]
}

archive_test_reports() { # destination
  local destination="$1" module
  local report relative target
  while IFS=$'\t' read -r module _; do
    [[ "${module}" != "." && "${module}" != \#* && "${module}" != build-support/* ]] \
      || continue
    while IFS= read -r -d '' report; do
      relative="${report#"${REPO_ROOT}"/}"
      target="${destination}/${relative}"
      mkdir -p -- "$(dirname -- "${target}")"
      cp -- "${report}" "${target}"
    done < <(find "${module}/target" -type f \
      \( -path '*/surefire-reports/TEST-*.xml' \
        -o -path '*/failsafe-reports/TEST-*.xml' \) -print0 2>/dev/null)
  done < build-support/coverage-report/coverage-scope.tsv
}

run_mutation() {
  local report_dir summary_dir summary_file started started_seconds elapsed result exit_code
  report_dir="${REPO_ROOT}/core/ioc-domain/target/pit-reports"
  summary_dir="${REPO_ROOT}/target/test-pilots"
  summary_file="${summary_dir}/mutation-summary.env"
  rm -rf -- "${report_dir}"
  mkdir -p -- "${summary_dir}"
  started="$(date --utc +'%Y-%m-%dT%H:%M:%SZ')"
  started_seconds="${SECONDS}"

  if ./mvnw -B -ntp -T 1 -pl core/ioc-domain -Pmutation-pilot \
      test-compile org.pitest:pitest-maven:mutationCoverage; then
    result="passed"
    exit_code=0
  else
    exit_code=$?
    result="failed"
  fi
  elapsed="$((SECONDS - started_seconds))"

  if [[ ! -s "${report_dir}/mutations.xml" || ! -s "${report_dir}/index.html" ]]; then
    dev_warn "PIT did not produce both mutations.xml and index.html"
    result="failed"
    [[ "${exit_code}" -ne 0 ]] || exit_code=1
  fi
  write_common_summary \
    "${summary_file}" "mutation" "${result}" "${started}" "${elapsed}" \
    || exit_code=1
  if [[ -s "${report_dir}/mutations.xml" ]]; then
    printf '%s\n' \
      "mutations=$(grep -c '<mutation ' "${report_dir}/mutations.xml" || true)" \
      "killed=$(grep -c "status='KILLED'" "${report_dir}/mutations.xml" || true)" \
      "survived=$(grep -c "status='SURVIVED'" "${report_dir}/mutations.xml" || true)" \
      "timed_out=$(grep -c "status='TIMED_OUT'" "${report_dir}/mutations.xml" || true)" \
      "no_coverage=$(grep -c "status='NO_COVERAGE'" "${report_dir}/mutations.xml" || true)" \
      >> "${summary_file}"
  fi
  exit "${exit_code}"
}

run_stability() {
  local base_seed=42 repeat=3
  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      --seed)
        [[ "$#" -ge 2 ]] || dev_die "--seed requires a value"
        base_seed="$2"
        shift 2
        ;;
      --repeat)
        [[ "$#" -ge 2 ]] || dev_die "--repeat requires a value"
        repeat="$2"
        shift 2
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        dev_die "unknown stability option: '$1'"
        ;;
    esac
  done
  require_positive_integer "seed" "${base_seed}"
  require_positive_integer "repeat" "${repeat}"

  local output_dir summary_file started started_seconds module_selector
  local run seed run_dir run_started run_elapsed run_result run_exit verifier_exit
  local overall_result="passed" overall_exit=0
  output_dir="${REPO_ROOT}/target/test-pilots/stability"
  summary_file="${output_dir}/summary.env"
  rm -rf -- "${output_dir}"
  mkdir -p -- "${output_dir}"
  started="$(date --utc +'%Y-%m-%dT%H:%M:%SZ')"
  started_seconds="${SECONDS}"
  module_selector="$(awk -F '\t' \
    '$1 != "." && $1 !~ /^build-support\// && $1 !~ /^#/ {print $1}' \
    build-support/coverage-report/coverage-scope.tsv | paste -sd, -)"
  [[ -n "${module_selector}" ]] || dev_die "functional Maven module inventory is empty"

  ./mvnw -B -ntp -N validate
  for ((run = 1; run <= repeat; run++)); do
    seed="$((base_seed + run - 1))"
    run_dir="${output_dir}/run-${run}-seed-${seed}"
    mkdir -p -- "${run_dir}/reports"
    run_started="${SECONDS}"
    dev_log "stability run ${run}/${repeat}; seed=${seed}"
    if ./mvnw -B -ntp -T 1 \
        -Pstability-pilot \
        -Dstability.seed="${seed}" \
        -Djacoco.skip=true \
        -Dspotbugs.skip=true \
        -pl "${module_selector}" verify; then
      run_result="passed"
      run_exit=0
    else
      run_exit=$?
      run_result="failed"
    fi

    verifier_exit=0
    if [[ "${run_exit}" -eq 0 ]]; then
      java -cp target/build-quality-verifier TestLifecycleVerifier verify-reports \
        "${REPO_ROOT}" \
        "${REPO_ROOT}/build-support/test-quality/test-lifecycle.properties" \
        || verifier_exit=$?
      if [[ "${verifier_exit}" -ne 0 ]]; then
        run_result="failed-report-integrity"
        run_exit="${verifier_exit}"
      fi
    fi
    archive_test_reports "${run_dir}/reports"
    run_elapsed="$((SECONDS - run_started))"
    printf '%s\n' \
      "run=${run}" \
      "seed=${seed}" \
      "result=${run_result}" \
      "exit_code=${run_exit}" \
      "elapsed_seconds=${run_elapsed}" \
      > "${run_dir}/run-summary.env"

    if [[ "${run_exit}" -ne 0 ]]; then
      overall_result="failed"
      overall_exit="${run_exit}"
      break
    fi
  done

  write_common_summary \
    "${summary_file}" "stability" "${overall_result}" "${started}" \
    "$((SECONDS - started_seconds))" \
    || overall_exit=1
  printf '%s\n' \
    "base_seed=${base_seed}" \
    "requested_repetitions=${repeat}" \
    "completed_repetitions=$(find "${output_dir}" -mindepth 1 -maxdepth 1 -type d -name 'run-*' | wc -l)" \
    >> "${summary_file}"
  exit "${overall_exit}"
}

MODE="${1:-}"
case "${MODE}" in
  mutation)
    shift
    [[ "$#" -eq 0 ]] || dev_die "mutation mode accepts no options"
    START_FINGERPRINT="$(dev_git_worktree_fingerprint)"
    run_mutation
    ;;
  stability)
    shift
    START_FINGERPRINT="$(dev_git_worktree_fingerprint)"
    run_stability "$@"
    ;;
  -h|--help)
    usage
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
