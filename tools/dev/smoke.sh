#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${SCRIPT_DIR}/common.sh"

COMMAND="${1:-all}"
JAR="${DEV_SMOKE_JAR:-}"
SMOKE_PORT="${DEV_SMOKE_PORT:-18082}"
DAEMON_SMOKE_WORKSPACE=""

cleanup_smoke() {
  [[ -z "${DAEMON_SMOKE_WORKSPACE}" ]] || \
    "${SCRIPT_DIR}/runtime.sh" --workspace "${DAEMON_SMOKE_WORKSPACE}" down \
      >/dev/null 2>&1 || true
}
trap cleanup_smoke EXIT

usage() {
  echo "Usage: tools/dev/smoke.sh [cli|oneshot|daemon|all]"
  echo "Optional: DEV_SMOKE_JAR=/path/to/ioc-app.jar DEV_SMOKE_PORT=18082"
}

[[ $# -le 1 ]] || { usage >&2; exit 2; }
case "${COMMAND}" in
  -h|--help) usage; exit 0 ;;
  cli|oneshot|daemon|all) : ;;
  *) usage >&2; exit 2 ;;
esac

dev_require_java21
dev_validate_port "${SMOKE_PORT}" || dev_die "DEV_SMOKE_PORT must be an integer in 1..65535"
dev_resolve_app_jar "${JAR}"
JAR="${DEV_APP_JAR}"

smoke_cli() {
  local output
  output="$(java -jar "${JAR}" --version)"
  [[ "${output}" == ioc-extractor* ]] || dev_die "unexpected --version output: ${output}"
  java -jar "${JAR}" --help | grep -q 'Commands:' \
    || dev_die "root help does not list commands"
  java -jar "${JAR}" extract --help | grep -q -- '--source' \
    || dev_die "extract help does not expose --source"
  dev_log "CLI smoke passed"
}

smoke_oneshot() {
  local workspace fixture
  workspace="${DEV_ROOT}/smoke/oneshot"
  dev_reset_workspace "${workspace}"
  mkdir -p -- "${workspace}"
  fixture="${workspace}/fixture.html"
  java "${SCRIPT_DIR}/GenerateIocFixture.java" \
    --size 120 --seed 101 --duplicate-rate 0.15 --defang-rate 0.50 \
    --output "${fixture}" > "${workspace}/fixture.log"

  (
    cd "${workspace}"
    unset DEBUG TRACE
    java -jar "${JAR}" extract --source "${fixture}" > extract.log 2>&1
    java -jar "${JAR}" export --profile reputation-lists > export.log 2>&1
  )
  [[ -s "${workspace}/var/db/ioc-service.db"
      && -s "${workspace}/var/db/ioc-dataframe.db" ]] \
    || dev_die "oneshot smoke did not create both SQLite stores"
  for artifact in masks_list ip_list hashes_list; do
    [[ -s "${workspace}/dataframe/${artifact}_generated.csv" ]] \
      || dev_die "oneshot smoke did not create ${artifact}_generated.csv"
  done
  find "${workspace}/var/export/reputation-lists" -type f -name _SUCCESS -print -quit \
    | grep -q . || dev_die "oneshot smoke did not complete an export slice"
  dev_log "oneshot storage/export smoke passed"
}

smoke_daemon() {
  local workspace fixture port done_file=""
  workspace="${DEV_ROOT}/smoke/daemon"
  DAEMON_SMOKE_WORKSPACE="${workspace}"
  port="${SMOKE_PORT}"
  "${SCRIPT_DIR}/runtime.sh" --workspace "${workspace}" reset >/dev/null
  "${SCRIPT_DIR}/runtime.sh" \
    --workspace "${workspace}" \
    --port "${port}" \
    --jar "${JAR}" \
    --set ioc.ingestion.detect.use-watch-service=false \
    --set ioc.ingestion.stability.quiet-period=1s \
    --set ioc.ingestion.detect.reconcile-interval=2s \
    up
  java -jar "${JAR}" health --port "${port}" --json \
    | grep -Eq '"status"[[:space:]]*:' \
    || dev_die "health CLI did not return JSON"

  fixture="${workspace}/fixture.html"
  java "${SCRIPT_DIR}/GenerateIocFixture.java" --size 60 --seed 202 \
    --output "${fixture}" > "${workspace}/fixture.log"
  cp -- "${fixture}" "${workspace}/var/inbox/smoke.html.part"
  mv "${workspace}/var/inbox/smoke.html.part" "${workspace}/var/inbox/smoke.html"

  for ((attempt = 1; attempt <= 30; attempt++)); do
    done_file="$(find "${workspace}/var/done" -maxdepth 1 -type f \
      -name '*-smoke.html' -print -quit 2>/dev/null || true)"
    [[ -n "${done_file}" ]] && break
    sleep 1
  done
  [[ -n "${done_file}" && -f "${done_file}" ]] \
    || dev_die "daemon did not archive the smoke source within 30 seconds"
  [[ -s "${workspace}/dataframe/hashes_list_generated.csv" ]] \
    || dev_die "daemon ingest did not refresh canonical projections"
  dev_health_ready "${port}" || dev_die "daemon became unhealthy after ingest"
  "${SCRIPT_DIR}/runtime.sh" --workspace "${workspace}" down >/dev/null
  DAEMON_SMOKE_WORKSPACE=""
  dev_log "daemon ingest/health smoke passed"
}

case "${COMMAND}" in
  cli) smoke_cli ;;
  oneshot) smoke_oneshot ;;
  daemon) smoke_daemon ;;
  all)
    smoke_cli
    smoke_oneshot
    smoke_daemon
    ;;
esac
