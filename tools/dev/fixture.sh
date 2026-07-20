#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=tools/dev/common.sh
. "${SCRIPT_DIR}/common.sh"

SIZE="1000"
SEED="42"
FORMAT="html"
OUTPUT=""
MANIFEST=""
FORWARD_ARGS=()

usage() {
  cat <<'EOF'
Usage: tools/dev/fixture.sh [OPTIONS]

Defaults: --size 1000 --seed 42 --format html
Default output: .dev/fixtures/ioc-<size>-seed-<seed>.<html|txt>

All generator options are supported: --manifest, --duplicate-rate,
--defang-rate and --force.
EOF
  java "${SCRIPT_DIR}/GenerateIocFixture.java" --help
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --size) SIZE="${2:?}"; shift 2 ;;
    --seed) SEED="${2:?}"; shift 2 ;;
    --format) FORMAT="${2:?}"; shift 2 ;;
    --output) OUTPUT="${2:?}"; shift 2 ;;
    --manifest) MANIFEST="${2:?}"; shift 2 ;;
    --duplicate-rate|--defang-rate)
      FORWARD_ARGS+=("$1" "${2:?}")
      shift 2
      ;;
    --force) FORWARD_ARGS+=("$1"); shift ;;
    -h|--help) usage; exit 0 ;;
    *) dev_die "unknown fixture option: $1" ;;
  esac
done

dev_require_java21
[[ "${SIZE}" =~ ^[1-9][0-9]*$ ]] || dev_die "size must be a positive integer"
[[ "${SEED}" =~ ^-?[0-9]+$ ]] || dev_die "seed must be an integer"
case "${FORMAT}" in
  html) EXTENSION="html" ;;
  text|txt) FORMAT="text"; EXTENSION="txt" ;;
  *) dev_die "format must be html or text" ;;
esac

if [[ -z "${OUTPUT}" ]]; then
  OUTPUT="${DEV_ROOT}/fixtures/ioc-${SIZE}-seed-${SEED}.${EXTENSION}"
fi
if [[ -z "${MANIFEST}" ]]; then
  MANIFEST="${OUTPUT}.manifest.json"
fi

exec java "${SCRIPT_DIR}/GenerateIocFixture.java" \
  --size "${SIZE}" \
  --seed "${SEED}" \
  --format "${FORMAT}" \
  --output "${OUTPUT}" \
  --manifest "${MANIFEST}" \
  "${FORWARD_ARGS[@]}"
