#!/usr/bin/env bash
#
# Runs the machine-state half of docs/manual-checks/linux-packaging.md and
# docs/manual-checks/linux-service-activation.md, and says per step what it expected and what it
# found. Run as root, on a machine that can be thrown away.
#
# WHAT THIS IS FOR. Not discovery: a script only checks what somebody already knew to check, and
# neither defect the hand-runs of those checklists found would have been caught by one. It is for
# regression. Nothing in the suite can see any of what is asserted here, because every automated
# test in this repository reads the installer as text — the distance between a green build and a
# working machine is the whole risk surface of the packaging, and this is what stops that distance
# growing back silently at the next release.
#
# WHAT IT DOES NOT COVER, and where that is written down. Whether a person gets a login window,
# whether the wizard reads well, and whether the applications menu looks right in a real session
# are judgement and stay in the checklists, for a person. Each step below names the section it came
# from; each section it covers says so; and TheMachineVerifierAndTheChecklistsNameEachOtherTest
# fails when those two stop agreeing.
#
# WHAT IT DESTROYS. Every step is run in order on one machine and the last of them is a purge, so
# this ends by destroying every Account and its password, the SecretVault and every secret in it,
# the configuration, and the record of every authentication ever attempted. It therefore refuses to
# run where there is a deployment it did not make itself: the mark it leaves in the ones it did
# make is the whole of how it tells them apart, and there is deliberately no flag that overrides
# that.
#
# Usage: sudo ./verify-on-a-machine.sh [--account <os-account>]
#
# The account is the one the installation is performed on behalf of, as sudo names it, and the one
# the group membership is then asserted for. It defaults to whoever ran sudo.

set -euo pipefail

# Every transcript below is read for the words in it, and apt, dpkg and systemd all translate
# theirs. Read in any other language, half the assertions here would silently stop meaning
# anything — a phrase that is never found is a check that never passes, and worse, a phrase
# searched for in a language the machine does not speak is a check that fails for the wrong reason.
export LC_ALL=C
export LANGUAGE=C
export DEBIAN_FRONTEND=noninteractive

readonly PACKAGE_NAME='javafx-login'
readonly UNIT='javafx-login-authd'
readonly DEDICATED_GROUP='javafx-login'
readonly PAYLOAD_DIRECTORY='/opt/javafx-login'
readonly STATE_DIRECTORY='/var/lib/javafx-login'
readonly STORE="${STATE_DIRECTORY}/credentials.db"
readonly SOCKET="/run/${UNIT}.sock"
readonly UNIT_DIRECTORY='/etc/systemd/system'
readonly HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY="$(cd "${HERE}/../.." && pwd)"

# The file this script leaves in a deployment of its own, and looks for before it destroys one.
readonly MARK_IT_LEAVES='.made-by-verify-on-a-machine'

# The Administrator this script creates over the socket, and never through a window. The password
# is here in the clear on purpose: it guards a deployment this script is about to purge, on a
# machine that can be thrown away, and a secret nobody may read is a secret nobody can check. It
# satisfies the PasswordRules — the length, an upper case, a digit and a symbol — because
# the AuthenticationService applies them to the bootstrap like any other password.
readonly ADMINISTRATOR='verifier'
readonly ADMINISTRATOR_PASSWORD='Verify-On-A-Machine-9134'

# About 55 MB, most of which is the trimmed runtime. A band rather than a number: what is being
# caught is a package with no runtime in it, or one with a whole JDK.
readonly SMALLEST_SENSIBLE_PACKAGE=45000000
readonly LARGEST_SENSIBLE_PACKAGE=75000000

# The service exits five minutes after the last client has gone. Waited for with a band around it
# for the same reason: a service that stopped at once was never idle-shutting-down, and one that
# never stopped is the defect.
readonly EARLIEST_HONEST_IDLE_EXIT=240
readonly LONGEST_WAIT_FOR_AN_IDLE_EXIT=480
readonly HOW_OFTEN_TO_ASK=15

# How many Sessions one connection is asked for, which is what asks whether one process serves
# them all rather than one JVM per connection.
readonly SESSIONS_OVER_ONE_CONNECTION=3

# How long every apt invocation waits for the dpkg lock rather than failing on it. A machine that
# has just booted is a machine unattended-upgrades is holding dpkg on for the first few minutes,
# and a rolled-back machine is exactly what this is written to be pointed at. An installation
# refused over that is a fact about the timing of somebody else's cron job and not about this
# package, and a step of this run that failed over it would be reporting a lie.
readonly LONGEST_WAIT_FOR_THE_DPKG_LOCK=600

# The tools this script needs that a stock Ubuntu has not got, against the package that brings
# each of them. Everything else it uses — systemd, dpkg, apt, coreutils, iproute2 — is on every
# machine this product is ever installed on, and is refused by name rather than installed.
readonly TOOLS_IT_INSTALLS=(
  'mvn:maven'
  'fakeroot:fakeroot'
  'sqlite3:sqlite3'
)
readonly TOOLS_THE_MACHINE_MUST_ALREADY_HAVE=(
  systemctl journalctl apt-get dpkg dpkg-deb dpkg-query getent id install
  find sort diff sha256sum stat ss awk sed grep
)

account=''
work=''
current_step=''
transcript=''
last_status=0
checks_passed=0
checks_failed=0
failures=()
pid_before_the_idle_exit=''
schema_this_build_understands=''
package=''

fail() {
  printf 'verify-on-a-machine.sh: %s\n' "$1" >&2
  exit 1
}

# ---------------------------------------------------------------------------------------------
# Saying what happened
# ---------------------------------------------------------------------------------------------

# A step, and the checklist section it came from. The section is named in full and matched against
# the document's own headings by a test, so that a box added to a checklist the script never
# learned about, or a step the script kept running after the checklist stopped asking for it, is
# something a build says out loud rather than something nobody notices.
step() {
  local checklist="$1" section="$2" what="$3"
  local document
  case "${checklist}" in
    packaging) document='linux-packaging.md' ;;
    activation) document='linux-service-activation.md' ;;
    *) fail "there is no checklist called ${checklist}" ;;
  esac
  current_step="${document} §${section}"
  printf '\n=== %s\n    %s\n' "${current_step}" "${what}"
}

# Something this script does to the machine so that a step has something to assert about. It is
# not a check and it comes from no checklist: it is the arrangement a person doing this by hand
# would be performing without writing it down.
arrangement() {
  current_step="$1"
  printf '\n--- %s\n' "$1"
}

note() {
  printf '      %s\n' "$1"
}

# The one place a verdict is recorded. Everything below decides; this prints and tallies, and
# every check says both halves whether it passed or not — a report that only shows the failures
# is a report nobody can tell an unrun step from a passing one in.
report() {
  local verdict="$1" what="$2" expected="$3" found="$4"
  if [[ ${verdict} == ok ]]; then
    checks_passed=$((checks_passed + 1))
    printf '  ok    %s\n' "${what}"
  else
    checks_failed=$((checks_failed + 1))
    failures+=("${current_step} — ${what}")
    printf '  FAIL  %s\n' "${what}"
  fi
  printf '        expected  %s\n' "${expected}"
  printf '        found     %s\n' "${found}"
}

expect_equal() {
  local what="$1" expected="$2" found="$3"
  if [[ ${found} == "${expected}" ]]; then
    report ok "${what}" "${expected}" "${found}"
  else
    report no "${what}" "${expected}" "${found}"
  fi
}

# A transcript names a phrase, read as an extended regular expression so that a message whose
# exact wording is not worth pinning can be matched by the part of it that is. The line it was
# found on is what gets reported: the line is what a person reading a failure wants, and the
# phrase is only how it was looked for.
expect_said() {
  local what="$1" phrase="$2" said="$3"
  local line
  if line="$(grep -m 1 -E -- "${phrase}" <<<"${said}")"; then
    report ok "${what}" "a line holding: ${phrase}" "${line}"
  else
    report no "${what}" "a line holding: ${phrase}" "nothing it said held that"
  fi
}

expect_silent() {
  local what="$1" phrase="$2" said="$3"
  local line
  if line="$(grep -m 1 -E -- "${phrase}" <<<"${said}")"; then
    report no "${what}" "nothing holding: ${phrase}" "${line}"
  else
    report ok "${what}" "nothing holding: ${phrase}" "nothing it said held that"
  fi
}

# One phrase before another in the same transcript. The order is the assertion: an installation
# that reports the CredentialStore after it has wired the machine has already done the thing the
# report was supposed to be able to stop.
expect_said_before() {
  local what="$1" first="$2" second="$3" said="$4"
  # Both are read with `|| true`, and that is load-bearing rather than tidy. Under `set -e` an
  # assignment whose command substitution failed ends the script, and the substitution that fails
  # here is a phrase that is not in the transcript — which is the finding this check exists to
  # report. Unguarded, the one machine state worth stopping on is the one that stops the run
  # before it can say so.
  local at_first at_second
  at_first="$(grep -n -m 1 -E -- "${first}" <<<"${said}" | cut -d: -f1 || true)"
  at_second="$(grep -n -m 1 -E -- "${second}" <<<"${said}" | cut -d: -f1 || true)"
  local expected="${first} before ${second}"
  if [[ -z ${at_first} || -z ${at_second} ]]; then
    report no "${what}" "${expected}" "it did not say both of those"
  elif ((at_first < at_second)); then
    report ok "${what}" "${expected}" "line ${at_first} before line ${at_second}"
  else
    report no "${what}" "${expected}" "line ${at_first} after line ${at_second}"
  fi
}

expect_gone() {
  local what="$1" path="$2"
  expect_equal "${what}" "${path} is gone" "$(whether_there "${path}")"
}

expect_there() {
  local what="$1" path="$2"
  expect_equal "${what}" "${path} is there" "$(whether_there "${path}")"
}

whether_there() {
  if [[ -e $1 ]]; then printf '%s is there' "$1"; else printf '%s is gone' "$1"; fi
}

# ---------------------------------------------------------------------------------------------
# Asking the machine
# ---------------------------------------------------------------------------------------------

# Runs something that is allowed to fail, keeping what it said in ${transcript} and how it ended
# in ${last_status}. Streamed as it goes as well as kept, because half of these take minutes and a
# script that says nothing for that long is one nobody lets finish.
attempt() {
  local status
  set +e
  "$@" 2>&1 | tee "${work}/transcript" | sed 's/^/      | /'
  status=${PIPESTATUS[0]}
  set -e
  transcript="$(cat "${work}/transcript")"
  last_status=${status}
}

mode_owner_and_group() {
  if [[ -e $1 ]]; then stat -c '%a %U %G' "$1"; else printf 'not there'; fi
}

whether_the_group_admits() {
  local account="$1" members
  if ! members="$(getent group "${DEDICATED_GROUP}" 2>/dev/null | cut -d: -f4)"; then
    printf 'there is no %s group' "${DEDICATED_GROUP}"
  elif [[ ",${members}," == *",${account},"* ]]; then
    printf '%s admits %s' "${DEDICATED_GROUP}" "${account}"
  else
    printf '%s admits: %s' "${DEDICATED_GROUP}" "${members:-nobody}"
  fi
}

unit_state() {
  systemctl "$1" "${UNIT}.$2" 2>/dev/null || true
}

main_pid() {
  systemctl show -p MainPID --value "${UNIT}.service" 2>/dev/null || true
}

package_status() {
  local status
  if status="$(dpkg-query -W -f='${db:Status-Abbrev}' "${PACKAGE_NAME}" 2>/dev/null)"; then
    printf '%s' "${status// /}"
  else
    printf 'not known to dpkg'
  fi
}

whether_anything_is_listening() {
  if ss -lx 2>/dev/null | grep -q "${UNIT}"; then
    printf 'something is listening on %s' "${SOCKET}"
  else
    printf 'nothing is listening'
  fi
}

# Whether the last thing `attempt` ran ended badly, which for two steps below is the assertion
# rather than the accident: an installation that a store from a later build did not stop, and an
# `apt install --reinstall` that recovered a half-configured package, are both this script finding
# the machine wrong.
whether_it_failed() {
  if ((last_status != 0)); then printf 'a failure'; else printf 'it succeeded'; fi
}

whether_the_journal_has_anything_since() {
  if [[ -n "$(journalctl -u "${UNIT}.service" --since "$1" --no-pager -q 2>/dev/null)" ]]; then
    printf 'the journal has something to say'
  else
    printf 'the journal is silent'
  fi
}

# systemd's word for it, and the one that matters. A unit left failed by an ending that was
# perfectly ordinary names a machine that is installed and well in `systemctl --failed`.
whether_the_service_failed() {
  if [[ "$(unit_state is-failed service)" == 'failed' ]]; then
    printf 'failed'
  else
    printf 'not failed'
  fi
}

# What is on the machine of this product, in the four places it leaves anything.
what_is_on_the_machine() {
  printf '%s, %s, %s, %s' \
    "$(if [[ "$(package_status)" == 'not known to dpkg' ]]; then printf 'no package'; \
       else printf 'the package is %s' "$(package_status)"; fi)" \
    "$(if [[ -e ${PAYLOAD_DIRECTORY} ]]; then printf 'a payload'; else printf 'no payload'; fi)" \
    "$(if [[ -e ${STATE_DIRECTORY} ]]; then printf 'a deployment'; else printf 'no deployment'; fi)" \
    "$(if getent group "${DEDICATED_GROUP}" >/dev/null 2>&1; then printf 'a group'; \
       else printf 'no group'; fi)"
}

registered_menu_entries() {
  find /usr/share/applications /usr/local/share/applications -maxdepth 1 -iname '*javafx*' \
    2>/dev/null | sort
}

what_the_store_says_its_schema_is() {
  sqlite3 "${STORE}" 'PRAGMA user_version' 2>/dev/null || printf 'unreadable'
}

# Every file of the deployment against the digest of its contents. This is what "preserved" is
# measured with below, and the marker file is in it like any other: it is part of what an upgrade
# must not rewrite.
checksums_of_the_deployment() {
  (cd "${STATE_DIRECTORY}" 2>/dev/null && find . -type f -print0 | sort -z \
     | xargs -0 -r sha256sum) || true
}

remember_the_deployment() {
  checksums_of_the_deployment >"${work}/$1"
}

expect_the_deployment_came_through() {
  local what="$1" remembered="$2"
  checksums_of_the_deployment >"${work}/now"
  local files changed
  files="$(wc -l <"${work}/${remembered}")"
  changed="$(diff "${work}/${remembered}" "${work}/now" | grep -c '^[<>]' || true)"
  local expected="${files} files, every one byte-identical"
  if ((files == 0)); then
    # Nothing to compare is not a deployment that came through: it is one that was never there,
    # and a check that passed on it would pass on a purge nobody asked for.
    report no "${what}" 'a deployment with files in it' 'it held nothing to compare'
  elif [[ ${changed} == 0 ]]; then
    report ok "${what}" "${expected}" "${expected}"
  else
    report no "${what}" "${expected}" \
      "$(diff "${work}/${remembered}" "${work}/now" | head -8 | tr '\n' ' ')"
  fi
}

# ---------------------------------------------------------------------------------------------
# Speaking the protocol
# ---------------------------------------------------------------------------------------------
#
# THE DESIGN DECISION. Several steps below assert that a deployment survives byte for byte, and by
# ADR-0017 the package never creates one. The way out taken here is to speak the protocol rather
# than to drive a wizard or to compare whatever files happen to be there, and the argument for it
# is written once, on installer/linux/verify/SpeakTheProtocol.java, which is the thing that makes
# it true. What it buys the report below is one sentence: "preserved" here means an Administrator
# that really logs in again afterwards, over the same socket, with the same password.

compile_the_client() {
  install -d "${work}/client"
  javac -d "${work}/client" -cp "${PAYLOAD_DIRECTORY}/lib/app/*" \
    "${HERE}/verify/SpeakTheProtocol.java"
}

speak() {
  "${PAYLOAD_DIRECTORY}/lib/runtime/bin/java" \
    -cp "${work}/client:${PAYLOAD_DIRECTORY}/lib/app/*" SpeakTheProtocol "${SOCKET}" "$@"
}

# ---------------------------------------------------------------------------------------------
# Before anything is touched
# ---------------------------------------------------------------------------------------------

require_root() {
  [[ ${EUID} -eq 0 ]] || fail 'this must be run as root: it installs, purges and reads root-owned files'
}

# The criterion this script exists under. It purges Accounts, secrets and audit records by design,
# so a deployment it did not make is one it must never be pointed at — and the only evidence it
# can have of having made one is the mark it left in it. There is no flag that overrides this: a
# machine holding a real deployment is one somebody should be taking a Backup off, not arguing
# with a script about.
refuse_a_deployment_this_script_did_not_make() {
  local directory="$1"
  [[ -d ${directory} ]] || return 0
  [[ -e ${directory}/${MARK_IT_LEAVES} ]] && return 0

  local held
  held="$(find "${directory}" -mindepth 1 -maxdepth 1 -printf '%f ' 2>/dev/null || true)"
  [[ -n ${held} ]] || return 0

  printf 'verify-on-a-machine.sh: there is a deployment at %s that this script did not make.\n' \
    "${directory}" >&2
  printf '  It holds: %s\n' "${held}" >&2
  printf '  This script purges the product as its last step, which destroys every Account and\n' >&2
  printf '  its password, the SecretVault and every secret in it, the configuration, and the\n' >&2
  printf '  record of every authentication ever attempted. It will not do that to a deployment\n' >&2
  printf '  somebody else made.\n' >&2
  printf '  Take a Backup off this machine, or run this on one that can be thrown away.\n' >&2
  exit 1
}

# It installs what it needs, or refuses and names what is missing. Nothing here may be left to
# whatever happened to be on the machine the checklists were last run on by hand: that machine had
# imagemagick, xdotool, sqlite3 and a JDK put on it for an afternoon, and a script that leaned on
# any of them would pass there and nowhere else.
install_what_it_needs() {
  apt_get update -qq || true

  local missing=() tool_and_package tool
  for tool in "${TOOLS_THE_MACHINE_MUST_ALREADY_HAVE[@]}"; do
    command -v "${tool}" >/dev/null 2>&1 || missing+=("${tool}")
  done
  ((${#missing[@]} == 0)) \
    || fail "this machine has not got ${missing[*]}, and none of those is something to install here"

  local wanted=() package_for_it
  for tool_and_package in "${TOOLS_IT_INSTALLS[@]}"; do
    tool="${tool_and_package%%:*}"
    package_for_it="${tool_and_package#*:}"
    if ! command -v "${tool}" >/dev/null 2>&1 \
        && [[ " ${wanted[*]-} " != *" ${package_for_it} "* ]]; then
      wanted+=("${package_for_it}")
    fi
  done
  if ((${#wanted[@]} > 0)); then
    note "installing what this script needs and this machine has not got: ${wanted[*]}"
    apt_get install -y "${wanted[@]}" || true
  fi

  missing=()
  for tool_and_package in "${TOOLS_IT_INSTALLS[@]}"; do
    tool="${tool_and_package%%:*}"
    command -v "${tool}" >/dev/null 2>&1 || missing+=("${tool_and_package}")
  done
  ((${#missing[@]} == 0)) \
    || fail "these are still missing, as tool:package — ${missing[*]}; install them and run this again"
}

# The JDK is chosen by version, and it is the version in the pom rather than whatever javac the
# machine has. Two things make that worth the twenty lines: `default-jdk` on Ubuntu 26.04 is 25,
# and installing maven pulls a default JDK in whether or not anything asked for one — so a machine
# that had no Java at all ends up with 25 in front on the PATH.
#
# A .deb linked by a jlink and packaged by a jpackage nobody here has ever built with is not the
# .deb this repository ships, and the difference would land in the one place this script cannot
# see it: the trimmed runtime. What guards that runtime is
# TheTrimmedRuntimeCarriesEveryOfferedLanguageTest, and this script does not run the suite.
use_the_jdk_this_product_is_built_with() {
  local release
  release="$(sed -n \
    's#.*<maven.compiler.release>\([0-9][0-9]*\)</maven.compiler.release>.*#\1#p' \
    "${REPOSITORY}/pom.xml" | head -1)"
  [[ -n ${release} ]] || fail 'no <maven.compiler.release> could be read out of pom.xml'

  local home
  home="$(jdk_directory_for "${release}")"
  if [[ -z ${home} ]]; then
    note "installing openjdk-${release}-jdk, which is the JDK this product is built with"
    apt_get install -y "openjdk-${release}-jdk" || true
    home="$(jdk_directory_for "${release}")"
  fi
  [[ -n ${home} ]] \
    || fail "openjdk-${release}-jdk is what this builds with, and apt would not put one here"

  # In front of everything, so that mvn, jlink, jpackage and the javac that compiles the client
  # are all the same JDK — including on a machine where installing maven has just left a newer
  # one as the default.
  export JAVA_HOME="${home}"
  export PATH="${home}/bin:${PATH}"
  local tool
  for tool in javac jlink jpackage; do
    command -v "${tool}" >/dev/null 2>&1 || fail "there is no ${tool} in ${JAVA_HOME}"
  done
  note "building with $("${home}/bin/java" -version 2>&1 | head -1), at ${home}"
}

# Where Debian puts a JDK of that release, and nothing if it has not got one.
jdk_directory_for() {
  local found
  found="$(find /usr/lib/jvm -maxdepth 1 -name "java-$1-openjdk-*" 2>/dev/null | sort | head -1 \
    || true)"
  if [[ -n ${found} && -x ${found}/bin/javac && -x ${found}/bin/jlink && -x ${found}/bin/jpackage ]]
  then
    printf '%s' "${found}"
  fi
}

# The machine is put back to nothing of this product before step 1, because "a first installation
# on a clean machine" is a sentence about a machine and not about a command. What is removed here
# is either nothing at all or a deployment this script made, which the refusal above has already
# established.
take_the_product_off_the_machine() {
  if [[ "$(package_status)" != 'not known to dpkg' ]]; then
    note "dpkg knows ${PACKAGE_NAME} here, as $(package_status), and it is purged first"
    apt_get purge -y "${PACKAGE_NAME}" >/dev/null 2>&1 || dpkg --purge "${PACKAGE_NAME}" || true
  fi
  # And whatever a run that was interrupted left behind, which dpkg would not know about: the
  # units are stopped before their files go, because a socket unit systemd is still holding open
  # keeps listening on a machine whose unit file has been deleted out from under it.
  systemctl disable --now "${UNIT}.socket" >/dev/null 2>&1 || true
  systemctl stop "${UNIT}.service" >/dev/null 2>&1 || true
  rm -f "${UNIT_DIRECTORY}/${UNIT}.socket" "${UNIT_DIRECTORY}/${UNIT}.service"
  systemctl daemon-reload >/dev/null 2>&1 || true
  systemctl reset-failed "${UNIT}.service" >/dev/null 2>&1 || true
  rm -rf "${STATE_DIRECTORY}"
  rm -f "${SOCKET}"
  if getent group "${DEDICATED_GROUP}" >/dev/null 2>&1; then
    groupdel "${DEDICATED_GROUP}" >/dev/null 2>&1 || true
  fi
  note "the machine now holds: $(what_is_on_the_machine)"
}

# ---------------------------------------------------------------------------------------------
# Installing, the way sudo does it
# ---------------------------------------------------------------------------------------------

# Every apt invocation this script makes, carrying the two things that are true of all of them.
#
# SUDO_USER is how sudo says who an installation was for, and the postinst reads it to decide whom
# to admit to the group. It is set here rather than inherited, because this script is root: an
# installation that said nothing would admit nobody, which is a different step of the checklist and
# never the one being run. A person running these by hand under sudo has it set on every one of
# them too, which is the whole reason it is set on every one of these.
#
# The lock wait is the other. Neither belongs to any one step, and this is not a middle man: what
# it adds is what makes the difference between working on the machines this is for and not.
apt_get() {
  env "SUDO_USER=${account}" \
    apt-get -o "DPkg::Lock::Timeout=${LONGEST_WAIT_FOR_THE_DPKG_LOCK}" "$@"
}

install_the_package() {
  attempt apt_get install -y "${package}"
}

reinstall_the_package() {
  attempt apt_get install -y --reinstall "${package}"
}

# ---------------------------------------------------------------------------------------------
# The steps
# ---------------------------------------------------------------------------------------------

building_the_package() {
  step packaging '0. Building the package' \
    'the .deb this whole run is about, built from this clone'

  note 'built with --skip-tests: what the suite says is a build machine question, and this'
  note 'script is only ever about what a machine is left like.'
  attempt "${REPOSITORY}/installer/linux/build-deb.sh" --skip-tests

  expect_equal 'the build ended without an error' '0' "${last_status}"
  # jpackage reports a missing fakeroot by skipping the DEB bundler with a message, and then
  # exits successfully having built nothing. A build that says this has produced no package and
  # has not said so in its exit status.
  expect_silent 'it said nothing about a skipped bundler' \
    '[Bb]undler .*skipped|skipp(ed|ing) .*bundler' "${transcript}"

  # `|| true` for the reason expect_said_before carries one: a build that produced no directory
  # at all is what the next line is written to abandon on, and an assignment that ended the run
  # would never reach it.
  package="$(find "${REPOSITORY}/target/package/dist" -maxdepth 1 -name '*.deb' 2>/dev/null \
    | sort | tail -1 || true)"
  expect_equal "a .deb was produced: ${package:-none}" 'a .deb' \
    "$(if [[ -n ${package} ]]; then printf 'a .deb'; else printf 'no .deb at all'; fi)"
  [[ -n ${package} ]] || abandon 'there is no package to install, so there is nothing below to run'

  local bytes
  bytes="$(stat -c '%s' "${package}")"
  local smallest="$((SMALLEST_SENSIBLE_PACKAGE / 1000000))"
  local largest="$((LARGEST_SENSIBLE_PACKAGE / 1000000))"
  expect_equal "it is about 55 MB, most of which is the trimmed runtime: ${bytes} bytes" \
    "between ${smallest} and ${largest} MB" \
    "$(if ((bytes >= SMALLEST_SENSIBLE_PACKAGE && bytes <= LARGEST_SENSIBLE_PACKAGE)); then \
         printf 'between %s and %s MB' "${smallest}" "${largest}"; \
       else printf '%s MB' "$((bytes / 1000000))"; fi)"

  # Every entry root/root is the only thing jpackage wants fakeroot for, and a package built
  # without it carries the building account's uid into a machine's /opt.
  local owners
  owners="$(dpkg-deb -c "${package}" | awk '{print $2}' | sort -u | tr '\n' ' ')"
  expect_equal 'every entry in it belongs to root/root' 'root/root ' "${owners}"
}

a_first_installation() {
  step packaging '1. A first installation' \
    'what one apt install leaves on a machine nothing has been installed on'

  expect_equal 'nothing of this product was on the machine to begin with' \
    'no package, no payload, no deployment, no group' "$(what_is_on_the_machine)"

  install_the_package
  local installation="${transcript}"
  expect_equal 'the installation ended without an error' '0' "${last_status}"
  expect_equal 'dpkg has the package installed and configured' 'ii' "$(package_status)"

  expect_equal 'the group exists and holds the installing account' \
    "${DEDICATED_GROUP} admits ${account}" "$(whether_the_group_admits "${account}")"

  expect_equal 'the deployment directory is root-owned and 0700' \
    '700 root root' "$(mode_owner_and_group "${STATE_DIRECTORY}")"

  # ADR-0017: the package installs the application and never a deployment. A store, a SecretVault
  # or an event log here would be a machine nobody has logged in to looking like one somebody has.
  local held
  held="$(find "${STATE_DIRECTORY}" -mindepth 1 -printf '%f ' 2>/dev/null || true)"
  expect_equal 'it holds nothing: no store, no SecretVault, no event log' \
    'nothing at all' "${held:-nothing at all}"

  expect_there 'the socket unit is installed' "${UNIT_DIRECTORY}/${UNIT}.socket"
  expect_there 'the service unit is installed' "${UNIT_DIRECTORY}/${UNIT}.service"
  expect_equal 'the socket is enabled at boot' 'enabled' "$(unit_state is-enabled socket)"
  # The word, never the exit status: `static` is what systemd calls a unit with no [Install]
  # section, which is exactly what the service is built to be, and is-enabled exits 0 for it.
  expect_equal 'the service is static, and so cannot be enabled at boot' \
    'static' "$(unit_state is-enabled service)"

  local entries
  mapfile -t entries < <(registered_menu_entries)
  expect_equal "exactly one menu entry is registered: ${entries[*]:-none}" \
    '1 entry' "${#entries[@]} entry"
  expect_equal 'and it is not the AuthenticationService, which is not something a person starts' \
    'no entry starts the service launcher' \
    "$(if ((${#entries[@]} > 0)) && grep -l "${UNIT}" "${entries[@]}" >/dev/null 2>&1; then \
         printf 'an entry starts %s' "${UNIT}"; \
       else printf 'no entry starts the service launcher'; fi)"

  # The order is the assertion. Migrations run in the postinst before anything is wired, so that a
  # machine whose upgrade was refused is one nothing can be activated on. An installation that
  # reported the files after wiring the machine has already done what the report could have stopped.
  expect_said 'it said there is no CredentialStore, and that an upgrade does not make one' \
    'there is no CredentialStore' "${installation}"
  expect_said_before 'and it said that before it said anything about the machine' \
    'there is no CredentialStore' 'created group javafx-login|group javafx-login already exists' \
    "${installation}"
}

the_product_is_where_the_unit_says_it_is() {
  step activation '0. The product is where the unit says it is' \
    'the launcher ExecStart= names, the jars beside it, and the directory nothing may read'

  local launcher
  launcher="$(sed -n 's/^ExecStart=\([^ ]*\).*/\1/p' "${UNIT_DIRECTORY}/${UNIT}.service" \
    2>/dev/null || true)"
  expect_equal 'the launcher the unit starts exists and is executable' \
    "${launcher} is executable" \
    "$(if [[ -x ${launcher} ]]; then printf '%s is executable' "${launcher}"; \
       else printf '%s is not' "${launcher}"; fi)"

  local jars
  jars="$(find "${PAYLOAD_DIRECTORY}/lib/app" -maxdepth 1 -name '*.jar' 2>/dev/null | wc -l \
    || true)"
  expect_equal "there are jars for it to run: ${jars} of them" 'at least one jar' \
    "$(if ((jars > 0)); then printf 'at least one jar'; else printf 'none'; fi)"

  expect_equal 'the runtime the package ships is there and runs' \
    'the packaged runtime runs' \
    "$(if "${PAYLOAD_DIRECTORY}/lib/runtime/bin/java" -version >/dev/null 2>&1; then \
         printf 'the packaged runtime runs'; else printf 'it does not'; fi)"

  expect_equal 'nothing unprivileged may read the deployment' \
    '700 root root' "$(mode_owner_and_group "${STATE_DIRECTORY}")"
}

before_anything_has_connected() {
  step activation '1. Before anything has connected' \
    'a socket that is listening and a privileged process that is not running'

  expect_equal 'the socket unit is enabled' 'enabled' "$(unit_state is-enabled socket)"
  expect_equal 'the socket unit is listening' 'active' "$(unit_state is-active socket)"
  expect_equal 'the service is static, never enabled' 'static' "$(unit_state is-enabled service)"
  # A privileged JVM up on a machine nobody has logged in to is the whole of what socket
  # activation exists to avoid, and it would go on working perfectly.
  expect_equal 'and the service is not running' 'inactive' "$(unit_state is-active service)"
}

the_sockets_ownership_and_mode() {
  step activation "2. The socket's ownership and mode are what was declared" \
    'a node systemd made, that never existed with any other permissions'

  expect_equal 'the socket is a socket, root-owned, group-readable by the dedicated group only' \
    "srw-rw---- root ${DEDICATED_GROUP}" \
    "$(if [[ -S ${SOCKET} ]]; then stat -c '%A %U %G' "${SOCKET}"; \
       else printf 'there is nothing at %s' "${SOCKET}"; fi)"

  # Membership of it means "may reach the AuthenticationService" and must mean nothing else, so it
  # is never anybody's primary group.
  expect_equal 'the dedicated group is not the primary group of the installing account' \
    "${account} has another primary group" \
    "$(if [[ "$(id -gn "${account}")" == "${DEDICATED_GROUP}" ]]; then \
         printf 'it is the primary group of %s' "${account}"; \
       else printf '%s has another primary group' "${account}"; fi)"
}

connecting_starts_the_service() {
  step activation '3. Connecting starts the service, and the connection waits' \
    'the first connection, answered by a process that was not running when it was made'

  local before after
  before="$(unit_state is-active service)"
  local since
  since="$(date '+%Y-%m-%d %H:%M:%S')"

  attempt speak protocol
  after="$(unit_state is-active service)"

  expect_equal 'the connection was answered' '0' "${last_status}"
  expect_said 'and answered with a message of the protocol, not with whatever the JVM printed' \
    'protocol ' "${transcript}"
  expect_equal 'the service was not running before the connection, and is after it' \
    'inactive, then active' "${before}, then ${after}"
  expect_equal 'the journal records the service starting since the connection was made' \
    'the journal has something to say' "$(whether_the_journal_has_anything_since "${since}")"
}

make_a_deployment_over_the_socket() {
  arrangement 'creating the Administrator by speaking the protocol'

  note 'ADR-0017: the package installs the application and never a deployment, so this makes the'
  note 'one the steps below assert survives — over the socket, on the packaged runtime, against'
  note 'the packaged jars. See installer/linux/verify/SpeakTheProtocol.java for why that way.'

  attempt speak bootstrap "${ADMINISTRATOR}" "${ADMINISTRATOR_PASSWORD}"
  expect_equal 'the Administrator was created over the socket' '0' "${last_status}"
  expect_said 'and the service made it' 'bootstrap (ok|granted)' "${transcript}"

  printf 'This deployment was made by installer/linux/verify-on-a-machine.sh and is a fixture.\n' \
    >"${STATE_DIRECTORY}/${MARK_IT_LEAVES}"
  chmod 0600 "${STATE_DIRECTORY}/${MARK_IT_LEAVES}"
  note "marked ${STATE_DIRECTORY} as made here, so that a later run may destroy it"
}

one_process_serves_several_sessions() {
  step activation '4. One process serves several Sessions in turn' \
    'three Sessions over one connection, and the same PID before and after'

  local before after
  before="$(main_pid)"
  attempt speak sessions "${ADMINISTRATOR}" "${ADMINISTRATOR_PASSWORD}" \
    "${SESSIONS_OVER_ONE_CONNECTION}"
  after="$(main_pid)"

  expect_equal 'every Session was granted and ended' '0' "${last_status}"
  expect_equal "the Administrator logged in and out ${SESSIONS_OVER_ONE_CONNECTION} times" \
    "${SESSIONS_OVER_ONE_CONNECTION} Sessions" \
    "$(grep -c 'ended ok' <<<"${transcript}" || true) Sessions"
  # Accept=no in the socket unit: one process serving every connection in turn rather than one
  # JVM per connection.
  expect_equal 'one process served all of them' "PID ${before}, unchanged" \
    "$(if [[ ${before} == "${after}" ]]; then printf 'PID %s, unchanged' "${before}"; \
       else printf 'PID %s became %s' "${before}" "${after}"; fi)"
  pid_before_the_idle_exit="${after}"
}

diagnostics_reach_the_journal_and_never_the_client() {
  step activation '5. Diagnostics reach the journal and never the client' \
    'what the service has said, and where it said it'

  expect_equal 'the journal holds what the service said' 'the journal has something to say' \
    "$(whether_the_journal_has_anything_since '15 min ago')"

  # The trap this step exists for: StandardOutput= left at its default inherits the socket, and a
  # JVM warning is then written straight into whatever client is connected. Every answer this
  # script has had was decoded by the shipped MessageCodec, which refuses anything that is not a
  # message of the protocol — so a client that got this far never received a line of diagnostics.
  attempt speak protocol
  expect_equal 'no request was answered with anything that is not a response of the protocol' \
    '0' "${last_status}"
  # The client decoded every answer with the shipped MessageCodec, which refuses anything that is
  # not a message of the catalogue. A JVM warning written into the connection would have arrived
  # as a malformed frame and ended this, rather than being read past.
  expect_said 'the client was told the protocol and nothing else' 'protocol ' "${transcript}"
}

it_stops_by_itself_once_nobody_is_using_it() {
  step activation '6. It stops by itself once nobody is using it' \
    'the five minutes after the last client closed, waited out'

  note 'every client is closed; this waits, and is the slow step of the run'
  local waited=0
  while [[ "$(unit_state is-active service)" == 'active' ]]; do
    ((waited < LONGEST_WAIT_FOR_AN_IDLE_EXIT)) || break
    sleep "${HOW_OFTEN_TO_ASK}"
    waited=$((waited + HOW_OFTEN_TO_ASK))
    if ((waited % 60 == 0)); then
      note "still running after ${waited}s"
    fi
  done

  local ended_as
  ended_as="$(unit_state is-active service)"
  expect_equal "the service stopped by itself, ${waited}s after the last client closed" \
    "inactive between ${EARLIEST_HONEST_IDLE_EXIT}s and ${LONGEST_WAIT_FOR_AN_IDLE_EXIT}s" \
    "$(if [[ ${ended_as} != 'inactive' ]]; then \
         printf 'still %s after %ss' "${ended_as}" "${waited}"; \
       elif ((waited < EARLIEST_HONEST_IDLE_EXIT)); then \
         printf 'inactive after only %ss' "${waited}"; \
       else printf 'inactive between %ss and %ss' "${EARLIEST_HONEST_IDLE_EXIT}" \
              "${LONGEST_WAIT_FOR_AN_IDLE_EXIT}"; fi)"

  # Exiting when nobody is using it is this service's normal ending, and systemd has to agree:
  # a unit left failed by it would name a machine that is installed and well in systemctl --failed.
  expect_equal 'and systemd recorded an ordinary ending, not a failure' \
    'not failed' "$(whether_the_service_failed)"
  expect_said 'the journal calls it a successful deactivation' 'Deactivated successfully' \
    "$(journalctl -u "${UNIT}.service" --since '20 min ago' --no-pager -q 2>/dev/null || true)"

  # The socket belongs to systemd and stays listening either way, which is what the next
  # connection arrives on.
  expect_equal 'the socket is still listening' 'active' "$(unit_state is-active socket)"
  expect_there 'and the node is still there' "${SOCKET}"
}

it_comes_back() {
  step activation '7. It comes back, repeatedly' \
    'the connection after the idle exit, and the new process that answers it'

  attempt speak protocol
  expect_equal 'the next connection was answered' '0' "${last_status}"
  expect_equal 'the service is running again' 'active' "$(unit_state is-active service)"
  # An activation that works once and not twice usually means the service deleted the socket on
  # the way out. The node belongs to systemd and is what the next activation arrives on.
  local serving_now
  serving_now="$(main_pid)"
  expect_equal "and it is a new process: PID ${serving_now}" \
    "not PID ${pid_before_the_idle_exit}" \
    "$(if [[ ${serving_now} == "${pid_before_the_idle_exit}" ]]; then \
         printf 'PID %s again' "${pid_before_the_idle_exit}"; \
       else printf 'not PID %s' "${pid_before_the_idle_exit}"; fi)"
}

reinstalling_reasserts_what_an_upgrade_could_have_loosened() {
  step packaging '5. Reinstalling reasserts what an upgrade could have loosened' \
    'a deployment loosened by hand, put back by a reinstall that touches nothing in it'

  schema_this_build_understands="$(what_the_store_says_its_schema_is)"
  remember_the_deployment 'before-the-reinstall'

  chmod 0755 "${STATE_DIRECTORY}"
  note "loosened ${STATE_DIRECTORY} to 0755 by hand, as an upgrade could have"
  # The service is left running on purpose. The prerm stops it, and a JVM asked to stop ends at
  # 143 — so this is the only place SuccessExitStatus= in the unit is exercised at all, and
  # without it every upgrade leaves the unit failed for as long as the machine is up.
  expect_equal 'the service is running, so that the prerm stopping it means something' \
    'active' "$(unit_state is-active service)"

  reinstall_the_package
  expect_equal 'the reinstall ended without an error' '0' "${last_status}"

  expect_equal 'the mode and the owner are back, without anybody having repaired them' \
    '700 root root' "$(mode_owner_and_group "${STATE_DIRECTORY}")"
  expect_said 'the installation reported the schema version it found' \
    "is at schema version ${schema_this_build_understands}" "${transcript}"
  expect_silent 'rather than saying there was nothing there' \
    'there is no CredentialStore' "${transcript}"
  expect_the_deployment_came_through 'the deployment came through the reinstall untouched' \
    'before-the-reinstall'

  expect_equal 'the service is inactive, and never failed' 'inactive, not failed' \
    "$(printf '%s, %s' "$(unit_state is-active service)" "$(whether_the_service_failed)")"

  attempt speak sessions "${ADMINISTRATOR}" "${ADMINISTRATOR_PASSWORD}" 1
  expect_equal 'the same Administrator logs in again with the same password' '0' "${last_status}"
}

a_store_from_a_later_build_stops_the_installation() {
  step packaging '6. A store from a later build stops the installation' \
    'the downgrade path, refused before the machine is wired rather than at the next login'

  # The service is stopped first, and it is the checklist's own premise: that step is written for
  # somebody who has closed the application. Writing the header of a store a privileged process
  # holds open would be asking SQLite to arbitrate a race nothing here is about.
  systemctl stop "${UNIT}.service" >/dev/null 2>&1 || true
  sqlite3 "${STORE}" 'PRAGMA user_version = 99'
  note 'set the schema version in the store to 99, as a later build would have left it'

  reinstall_the_package
  expect_equal 'the installation fails' 'a failure' "$(whether_it_failed)"
  expect_said 'and names the version it found' 'schema version 99' "${transcript}"
  expect_said 'and the version this build understands' \
    "understands only version ${schema_this_build_understands}" "${transcript}"

  # The refusal happens before the socket is enabled, on purpose: a refused upgrade must leave
  # nothing for anybody to connect to rather than a service that dies on activation.
  expect_equal 'nothing is listening' 'nothing is listening' "$(whether_anything_is_listening)"
  expect_gone 'and the socket node went with the unit' "${SOCKET}"

  sqlite3 "${STORE}" "PRAGMA user_version = ${schema_this_build_understands}"
  note "put the schema version back to ${schema_this_build_understands}"

  # What the checklist used to say, and it was wrong. A package whose postinst failed is
  # half-configured, and apt refuses it rather than running the postinst again.
  reinstall_the_package
  expect_equal 'apt install --reinstall is not the way back' 'a failure' "$(whether_it_failed)"
  expect_equal 'and the machine is still half-configured after it' 'not ii' \
    "$(if [[ "$(package_status)" == 'ii' ]]; then printf 'ii'; else printf 'not ii'; fi)"

  attempt dpkg --configure "${PACKAGE_NAME}"
  expect_equal 'dpkg --configure is what re-runs the postinst' '0' "${last_status}"
  expect_equal 'and the machine is installed and configured again' 'ii' "$(package_status)"
  expect_equal 'the socket is listening once more' 'active' "$(unit_state is-active socket)"
  expect_there 'and the node is back' "${SOCKET}"
}

removing_keeps_the_deployment() {
  step packaging '7. Removing keeps the deployment' \
    'the application taken away, and everything that is not the application left alone'

  remember_the_deployment 'before-the-removal'
  attempt apt_get remove -y "${PACKAGE_NAME}"
  expect_equal 'the removal ended without an error' '0' "${last_status}"

  expect_said 'it says the deployment has been kept' 'has been kept' "${transcript}"
  expect_said 'and names what is in it' 'the Accounts, the SecretVault' "${transcript}"
  expect_said 'and says which word destroys it' 'apt purge javafx-login' "${transcript}"

  expect_gone 'the application is gone' "${PAYLOAD_DIRECTORY}"
  expect_equal 'the deployment is still there, still 0700' \
    '700 root root' "$(mode_owner_and_group "${STATE_DIRECTORY}")"
  expect_gone 'the socket unit is gone' "${UNIT_DIRECTORY}/${UNIT}.socket"
  expect_gone 'the service unit is gone' "${UNIT_DIRECTORY}/${UNIT}.service"
  expect_equal 'nothing is listening' 'nothing is listening' "$(whether_anything_is_listening)"
  # RemoveOnStop= in the socket unit is what takes the node with it. systemd's default would leave
  # a root-owned socket in /run naming the product's group on a machine the product has left.
  expect_gone 'and the socket node went with the unit' "${SOCKET}"

  local entries
  mapfile -t entries < <(registered_menu_entries)
  expect_equal "the menu entry is gone: ${entries[*]:-none}" \
    '0 entries' "${#entries[@]} entries"

  # The group goes with the purge and not with the removal: a gid later handed to another group
  # would quietly give "may reach the AuthenticationService" to a different set of people.
  expect_equal 'the group is kept' "${DEDICATED_GROUP} admits ${account}" \
    "$(whether_the_group_admits "${account}")"

  arrangement 'installing it again over the deployment that was kept'
  install_the_package
  expect_equal 'it installs again without an error' '0' "${last_status}"
  expect_the_deployment_came_through \
    'and finds the deployment byte-identical: a reinstall is not a way to lose one' \
    'before-the-removal'
  attempt speak sessions "${ADMINISTRATOR}" "${ADMINISTRATOR_PASSWORD}" 1
  expect_equal 'the same Administrator logs in again with the same password' '0' "${last_status}"
}

purging_destroys_it_and_says_so() {
  step packaging '8. Purging destroys it, and says so' \
    'the one word that destroys a deployment, and the sentence that is the last record of it'

  attempt apt_get purge -y "${PACKAGE_NAME}"
  expect_equal 'the purge ended without an error' '0' "${last_status}"

  # By the time it has run, that sentence is the only record left of what was there. That it is
  # said *before* the directory goes is the postrm's own order and DebianPackageTest holds it; what
  # a machine can show from outside is that it was said and that the directory then went, which is
  # the pair asserted here.
  expect_said 'it names every Account and its password' \
    'every Account and its password' "${transcript}"
  expect_said 'the SecretVault and every secret in it' \
    'SecretVault and every secret in it' "${transcript}"
  expect_said 'the configuration and the record of every authentication' \
    'the record of every' "${transcript}"
  expect_said 'and that a Backup taken earlier is the only copy that survives' \
    'Backup exported before now' "${transcript}"

  expect_gone 'the deployment is gone' "${STATE_DIRECTORY}"
  expect_equal 'the group is gone' "there is no ${DEDICATED_GROUP} group" \
    "$(whether_the_group_admits "${account}")"
  expect_gone 'the socket unit is gone' "${UNIT_DIRECTORY}/${UNIT}.socket"
  expect_gone 'the service unit is gone' "${UNIT_DIRECTORY}/${UNIT}.service"
  # A node left here would name a gid the groupdel above has just freed, and would go on meaning
  # it to whoever is handed that gid next.
  expect_gone 'and the socket node is gone' "${SOCKET}"
  expect_equal 'dpkg no longer knows the package' 'not known to dpkg' "$(package_status)"
}

# ---------------------------------------------------------------------------------------------
# Ending
# ---------------------------------------------------------------------------------------------

abandon() {
  printf '\n  ABANDONED  %s\n' "$1"
  failures+=("${current_step} — abandoned: $1")
  exit 1
}

# The machine is left somewhere it is named rather than wherever the last step happened to leave
# it. Run whatever ends this script from the first step onwards, including a step that abandoned
# it. The two refusals before that name their own state and are the whole of it: nothing has been
# touched yet when either of them fires.
name_the_state_the_machine_is_left_in() {
  local status=$?
  printf '\n=== The machine is left like this\n'
  printf '  package     %s\n' "$(package_status)"
  printf '  payload     %s\n' "$(whether_there "${PAYLOAD_DIRECTORY}")"
  printf '  deployment  %s\n' "$(whether_there "${STATE_DIRECTORY}")"
  printf '  group       %s\n' "$(whether_the_group_admits "${account:-root}")"
  printf '  units       %s\n' "$(whether_there "${UNIT_DIRECTORY}/${UNIT}.socket")"
  printf '  socket      %s\n' "$(whether_anything_is_listening)"

  printf '\n=== %s checks passed, %s failed\n' "${checks_passed}" "${checks_failed}"
  local failure
  for failure in ${failures[@]+"${failures[@]}"}; do
    printf '  FAIL  %s\n' "${failure}"
  done
  if [[ -n ${work} && -d ${work} ]]; then
    rm -rf "${work}"
  fi
  if ((checks_failed > 0)) && ((status == 0)); then
    exit 1
  fi
  exit "${status}"
}

read_the_arguments() {
  while (($# > 0)); do
    case "$1" in
      --account)
        [[ $# -ge 2 ]] || fail '--account wants the name of an operating-system account'
        account="$2"
        shift 2
        ;;
      *) fail "unknown argument: $1" ;;
    esac
  done
  account="${account:-${SUDO_USER:-}}"
  [[ -n ${account} ]] \
    || fail 'nothing said whose installation this is: run this under sudo, or pass --account <name>'
  id "${account}" >/dev/null 2>&1 || fail "there is no operating-system account called ${account}"
}

main() {
  require_root
  read_the_arguments "$@"
  refuse_a_deployment_this_script_did_not_make "${STATE_DIRECTORY}"
  install_what_it_needs
  use_the_jdk_this_product_is_built_with

  work="$(mktemp -d)"
  trap name_the_state_the_machine_is_left_in EXIT

  printf '=== verify-on-a-machine.sh\n'
  printf '    %s, installing on behalf of %s\n' "$(uname -srm)" "${account}"
  printf '    this machine will end with the product purged and its deployment destroyed\n'

  arrangement 'putting the machine back to nothing of this product'
  take_the_product_off_the_machine

  building_the_package
  a_first_installation
  compile_the_client
  the_product_is_where_the_unit_says_it_is
  before_anything_has_connected
  the_sockets_ownership_and_mode
  connecting_starts_the_service
  make_a_deployment_over_the_socket
  one_process_serves_several_sessions
  diagnostics_reach_the_journal_and_never_the_client
  it_stops_by_itself_once_nobody_is_using_it
  it_comes_back
  reinstalling_reasserts_what_an_upgrade_could_have_loosened
  a_store_from_a_later_build_stops_the_installation
  removing_keeps_the_deployment
  purging_destroys_it_and_says_so
}

# Run, unless something is reading this file rather than running it. Sourcing is how the suite
# reaches the refusal above: what this script does to a machine cannot be exercised by a test, but
# the one decision it makes before touching anything can be. See
# TheMachineVerifierRefusesADeploymentItDidNotMakeTest.
if [[ ${BASH_SOURCE[0]} == "${0}" ]]; then
  main "$@"
fi
