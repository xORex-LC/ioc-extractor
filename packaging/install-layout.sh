#!/usr/bin/env bash
# Shared safety contract for host lifecycle scripts. This file is sourced by
# install/deploy/uninstall entry points and intentionally performs no work when
# executed on its own.

IOC_INSTALL_MARKER_FORMAT="1"
IOC_INSTALL_MARKER_NAME="ioc-extractor.installation"

ioc_layout_error() {
  printf '[layout-error] %s\n' "$*" >&2
  return 1
}

ioc_report_config_candidate_conflict() { # incoming-template installed-file
  local template="$1" installed="$2" candidate archive_stamp archive_path
  local candidate_mtime candidate_sha template_sha
  candidate="${installed}.new"
  archive_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  archive_path="${candidate}.obsolete-${archive_stamp}"
  candidate_mtime="$(stat -c '%y' -- "${candidate}")"
  candidate_sha="$(sha256sum -- "${candidate}" | awk '{print $1}')"
  template_sha="$(sha256sum -- "${template}" | awk '{print $1}')"

  {
    printf '\nPACKAGING.CONFIG_CANDIDATE_CONFLICT\n'
    printf 'Deployment stopped before overwriting an operator-owned configuration candidate.\n\n'
    printf 'Live operator configuration:\n  %s\n' "${installed}"
    printf 'Existing unreconciled candidate:\n  %s\n' "${candidate}"
    printf '  modified: %s\n  sha256: %s\n' "${candidate_mtime}" "${candidate_sha}"
    printf 'Incoming packaged template:\n  %s\n' "${template}"
    printf '  sha256: %s\n\n' "${template_sha}"
    printf 'Reason:\n'
    printf '  The live file differs from the packaged template, and the existing .new\n'
    printf '  candidate also differs from the incoming template. The candidate may\n'
    printf '  contain operator edits, so packaging will not replace it automatically.\n\n'
    printf 'Compare locally (the diff may contain sensitive operator values):\n  '
    printf 'sudo diff -u -- %q %q\n\n' "${candidate}" "${template}"
    printf 'After review, preserve the old candidate and rerun the same command:\n  '
    printf 'sudo mv -- %q %q\n\n' "${candidate}" "${archive_path}"
    printf 'Do not use --force only to bypass this conflict; it may overwrite the live configuration.\n\n'
  } >&2
}

ioc_validate_prefix() { # requested-prefix
  local requested="${1:-}" normalized
  [[ -n "${requested}" && "${requested}" == /* ]] \
    || { ioc_layout_error "prefix must be an absolute non-root path: '${requested}'"; return 1; }
  [[ "${requested}" =~ ^/[A-Za-z0-9._/-]+$ ]] \
    || { ioc_layout_error "prefix contains unsupported characters: '${requested}'"; return 1; }

  requested="${requested%/}"
  [[ -n "${requested}" ]] \
    || { ioc_layout_error "installing into the filesystem root is forbidden"; return 1; }
  normalized="$(realpath -m -- "${requested}")" \
    || { ioc_layout_error "cannot normalize prefix: '${requested}'"; return 1; }
  [[ "${normalized}" == "${requested}" ]] \
    || { ioc_layout_error "prefix must already be normalized and must not traverse symlinks: '${requested}' -> '${normalized}'"; return 1; }

  case "${normalized}" in
    /|/bin|/boot|/dev|/etc|/home|/lib|/lib32|/lib64|/media|/mnt|/opt|/proc|/root|/run|/sbin|/srv|/sys|/tmp|/usr|/var)
      ioc_layout_error "system directory cannot be used as an installation prefix: '${normalized}'"
      return 1
      ;;
    /bin/*|/boot/*|/dev/*|/etc/*|/home/*|/lib/*|/lib32/*|/lib64/*|/proc/*|/root/*|/run/*|/sbin/*|/sys/*|/tmp/*|/usr/*|/var/tmp/*)
      ioc_layout_error "installation prefix is inside a protected system tree: '${normalized}'"
      return 1
      ;;
  esac

  # Consumed by the sourcing lifecycle script after this validator returns.
  # shellcheck disable=SC2034
  IOC_VALIDATED_PREFIX="${normalized}"
}

ioc_is_inside_source_tree() { # prefix
  local cursor="$1"
  while [[ "${cursor}" != "/" ]]; do
    if [[ -e "${cursor}/.git" ]]; then
      return 0
    fi
    if [[ -f "${cursor}/pom.xml"
        && -d "${cursor}/bootstrap"
        && -d "${cursor}/adapters"
        && -d "${cursor}/core"
        && -d "${cursor}/platform" ]]; then
      return 0
    fi
    cursor="${cursor%/*}"
    [[ -n "${cursor}" ]] || cursor="/"
  done
  return 1
}

ioc_marker_path() { # prefix
  printf '%s/etc/%s\n' "$1" "${IOC_INSTALL_MARKER_NAME}"
}

ioc_is_release_target() { # relative-current-target
  [[ "${1:-}" =~ ^releases/[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]]
}

ioc_is_valid_marker() { # prefix service expected-user-or-empty
  local prefix="$1" service="$2" expected_user="${3:-}" marker key value
  local format="" actual_service="" actual_prefix="" actual_user="" unknown="false"
  local format_count=0 service_count=0 prefix_count=0 user_count=0
  marker="$(ioc_marker_path "${prefix}")"
  [[ -f "${marker}" && ! -L "${marker}" ]] || return 1

  while IFS='=' read -r key value; do
    case "${key}" in
      format) format="${value}"; ((format_count += 1)) ;;
      service) actual_service="${value}"; ((service_count += 1)) ;;
      prefix) actual_prefix="${value}"; ((prefix_count += 1)) ;;
      run_user) actual_user="${value}"; ((user_count += 1)) ;;
      '') : ;;
      *) unknown="true" ;;
    esac
  done < "${marker}"

  [[ "${unknown}" == "false"
      && "${format}" == "${IOC_INSTALL_MARKER_FORMAT}"
      && "${actual_service}" == "${service}"
      && "${actual_prefix}" == "${prefix}"
      && -n "${actual_user}"
      && "${format_count}" -eq 1
      && "${service_count}" -eq 1
      && "${prefix_count}" -eq 1
      && "${user_count}" -eq 1
      && ( -z "${expected_user}" || "${actual_user}" == "${expected_user}" ) ]]
}

ioc_write_marker() { # prefix service run-user
  local prefix="$1" service="$2" run_user="$3" marker temporary
  marker="$(ioc_marker_path "${prefix}")"
  temporary="${marker}.tmp.$$"
  mkdir -p -- "${prefix}/etc"
  (
    umask 027
    printf 'format=%s\nservice=%s\nprefix=%s\nrun_user=%s\n' \
      "${IOC_INSTALL_MARKER_FORMAT}" "${service}" "${prefix}" "${run_user}" \
      > "${temporary}"
  )
  chmod 0640 "${temporary}"
  mv -f -- "${temporary}" "${marker}"
}

ioc_is_pre_marker_release_layout() { # prefix
  local prefix="$1" target
  [[ -d "${prefix}/releases"
      && -d "${prefix}/etc"
      && -d "${prefix}/var"
      && -d "${prefix}/dataframe"
      && -L "${prefix}/current"
      && -f "${prefix}/etc/application.yml" ]] || return 1
  target="$(readlink "${prefix}/current")"
  ioc_is_release_target "${target}" \
    && [[ -f "${prefix}/${target}/ioc-app.jar" ]]
}

ioc_is_v010_single_dir_installation() { # prefix
  local prefix="$1"
  [[ -d "${prefix}/lib"
      && -d "${prefix}/etc"
      && -d "${prefix}/var"
      && -d "${prefix}/dataframe"
      && -f "${prefix}/lib/ioc-app-0.1.0.jar"
      && -f "${prefix}/etc/application.yml" ]]
}

ioc_directory_is_empty() { # path
  local path="$1"
  [[ ! -e "${path}" ]] && return 0
  [[ -d "${path}" && ! -L "${path}" ]] || return 1
  [[ -z "$(find "${path}" -mindepth 1 -maxdepth 1 -print -quit)" ]]
}

ioc_validate_service_user() { # user
  local run_user="$1" uid passwd_entry shell
  [[ "${run_user}" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] \
    || { ioc_layout_error "invalid service user name: '${run_user}'"; return 1; }
  if getent passwd "${run_user}" >/dev/null; then
    uid="$(id -u "${run_user}")"
    [[ "${uid}" != "0" ]] \
      || { ioc_layout_error "UID 0 cannot be used as the service account"; return 1; }
    passwd_entry="$(getent passwd "${run_user}")"
    shell="${passwd_entry##*:}"
    case "${shell}" in
      /usr/sbin/nologin|/sbin/nologin|/bin/false) : ;;
      *) ioc_layout_error "existing service account must use a non-login shell: '${run_user}'"; return 1 ;;
    esac
  fi
}
