#!/usr/bin/env bash
#
# Installs the AuthenticationService as a socket-activated systemd service.
#
# Elevation is needed here and nowhere else. Once this has run, nobody is asked for a
# password by the operating system again: the socket is always listening, connecting to it
# is what starts the privileged process, and that process stops by itself five minutes
# after the last client has gone. See ADR-0002.
#
# This script wires systemd to a product that is already on the machine: the runtime and
# the jars must be under /opt/javafx-login before it is run, and it refuses to enable
# anything if they are not. Putting them there belongs to whatever built the product — the
# .deb built by build-deb.sh, or a hand-assembled payload on a development machine.
#
# The package runs this same script from its postinst rather than repeating it. There is
# one implementation of what a machine needs and one place a mistake in it can be, and
# every line of it is written to be run again: the group, the directory and its mode are
# reasserted on an upgrade, never assumed to have survived one.
#
# Usage: sudo ./install.sh [os-account ...]
#
# Each named operating-system account is added to the dedicated group, which is what lets
# the person logged in as it reach the socket at all. They must log out and back in before
# the new group membership applies to their session.

set -euo pipefail

readonly UNIT_NAME='javafx-login-authd'
readonly DEDICATED_GROUP='javafx-login'
readonly UNIT_DIRECTORY='/etc/systemd/system'
readonly STATE_DIRECTORY='/var/lib/javafx-login'
readonly PAYLOAD_DIRECTORY='/opt/javafx-login'
readonly DOC_DIRECTORY='/opt/javafx-login/lib/doc'
readonly HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

fail() {
  printf 'install.sh: %s\n' "$1" >&2
  exit 1
}

require_root() {
  [[ ${EUID} -eq 0 ]] || fail 'this must be run as root: the units and the credential files are root-owned'
}

require_systemd() {
  command -v systemctl >/dev/null 2>&1 || fail 'systemd is not on this machine, and socket activation is the whole of the Linux design'
}

systemd_is_running() {
  # Installed and running are different questions. A container image being built, or a
  # chroot, has the units and the group and everything else this script puts in place, and
  # a systemctl that refuses because nothing was booted. Everything that does not need a
  # running systemd is still done there, and what is left is said out loud.
  [[ -d /run/systemd/system ]]
}

require_payload() {
  # The application itself — the runtime and the jars — is put in place by whatever built
  # this product, and this script only wires systemd to it. Checked before anything is
  # enabled, because the failure it prevents is the quiet one: a socket that listens, and
  # an activation that dies on ExecStart the first time somebody tries to log in.
  local launcher
  launcher="$(sed -n 's/^ExecStart=\([^ ]*\).*/\1/p' "${HERE}/${UNIT_NAME}.service")"
  [[ -x ${launcher} ]] || fail "the unit starts ${launcher}, and there is no such executable: install the product under ${PAYLOAD_DIRECTORY} first"
  compgen -G "${PAYLOAD_DIRECTORY}/lib/app/*.jar" >/dev/null \
    || fail "there are no jars in ${PAYLOAD_DIRECTORY}/lib/app: install the product there first"
}

create_dedicated_group() {
  # A group of its own, never a person's primary group: membership of it is exactly
  # "may reach the AuthenticationService" and must not mean anything else.
  if getent group "${DEDICATED_GROUP}" >/dev/null; then
    printf 'group %s already exists\n' "${DEDICATED_GROUP}"
  else
    groupadd --system "${DEDICATED_GROUP}"
    printf 'created group %s\n' "${DEDICATED_GROUP}"
  fi
}

create_state_directory() {
  # The CredentialStore, the SecretVault, the Lockout records and the AuthenticationEvents
  # live here, and nothing unprivileged may read them — that is what the split is for.
  install -d -o root -g root -m 0700 "${STATE_DIRECTORY}"
}

install_units() {
  install -o root -g root -m 0644 "${HERE}/${UNIT_NAME}.socket" "${UNIT_DIRECTORY}/${UNIT_NAME}.socket"
  install -o root -g root -m 0644 "${HERE}/${UNIT_NAME}.service" "${UNIT_DIRECTORY}/${UNIT_NAME}.service"
  systemd_is_running && systemctl daemon-reload
}

install_documentation() {
  # The manual checks, one of which is what both units name in Documentation= — a path that
  # must be a file that is there. This copies them out of a clone, and finds nothing to copy
  # when it is the package running it: there they are already in the payload, put there by
  # dpkg, and this returns.
  local source="${HERE}/../../docs/manual-checks"
  [[ -d ${source} ]] || return 0
  install -d -o root -g root -m 0755 "${DOC_DIRECTORY}"
  install -o root -g root -m 0644 "${source}"/linux-*.md "${DOC_DIRECTORY}/"
}

enable_the_socket_only() {
  # The socket is enabled; the service is not, and has no [Install] section to be enabled
  # by. An enabled service would be a privileged JVM running on a machine nobody has
  # logged in to, which is the thing socket activation exists to avoid.
  if ! systemd_is_running; then
    printf 'systemd is not running here, so nothing was enabled. On a machine that has\n'
    printf 'booted it: systemctl enable --now %s.socket\n' "${UNIT_NAME}"
    return 0
  fi
  systemctl enable --now "${UNIT_NAME}.socket"
  if systemctl is-enabled --quiet "${UNIT_NAME}.service" 2>/dev/null; then
    fail "${UNIT_NAME}.service is enabled and must not be: run systemctl disable ${UNIT_NAME}.service"
  fi
}

admit() {
  local account="$1"
  id "${account}" >/dev/null 2>&1 || fail "there is no operating-system account called ${account}"
  usermod --append --groups "${DEDICATED_GROUP}" "${account}"
  printf 'added %s to %s — they must log out and back in for it to apply\n' "${account}" "${DEDICATED_GROUP}"
}

report() {
  systemd_is_running || return 0
  printf '\n%s is listening. Nothing is running yet, and nothing will until somebody connects.\n' \
    "${UNIT_NAME}.socket"
  systemctl --no-pager status "${UNIT_NAME}.socket" || true
  printf '\nVerify the installation against %s/linux-service-activation.md\n' "${DOC_DIRECTORY}"
}

main() {
  require_root
  require_systemd
  require_payload
  create_dedicated_group
  create_state_directory
  install_units
  install_documentation
  enable_the_socket_only
  for account in "$@"; do
    admit "${account}"
  done
  report
}

main "$@"
