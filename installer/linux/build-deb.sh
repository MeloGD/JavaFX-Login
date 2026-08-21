#!/usr/bin/env bash
#
# Builds the Ubuntu package: a runtime trimmed by jlink, and the .deb jpackage makes of it.
#
# Neither tool needs the application to be modular, which is what ADR-0007 said this would
# cost and why it costs nothing: jlink is given the module list below rather than deriving
# one from a module-info.java this project deliberately does not have.
#
# Nothing here needs privileges, and nothing here touches the machine it runs on. What the
# package does to a machine is in installer/linux/debian/, and the wiring those scripts
# perform is install.sh — the same file a developer runs by hand.
#
# Usage: ./build-deb.sh [--skip-tests]
#
# The .deb is left in target/package/dist.

set -euo pipefail

readonly PACKAGE_NAME='javafx-login'
readonly SERVICE_LAUNCHER='javafx-login-authd'
readonly LAUNCHER_CLASS='com.javafxlogin.feature.ProtectedFeatureLauncher'
readonly SERVICE_CLASS='com.javafxlogin.core.authentication.ServiceProcess'
readonly STORE_FILE='/var/lib/javafx-login/credentials.db'
readonly MAINTAINER='crmdev@tutanota.com'
readonly HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY="$(cd "${HERE}/../.." && pwd)"
readonly WORK="${REPOSITORY}/target/package"

# The modules the trimmed runtime carries. Written out rather than derived, for the same
# reason the migrations are: jdeps reads bytecode, and the two things this product needs a
# module for at run time are things bytecode does not mention. java.sql is reached through
# a JDBC driver loaded by name, and jdk.localedata holds the names the languages call
# themselves — without it the selector offers "Spanish" instead of "Español", which is a
# packaged build quietly disagreeing with ADR-0014 and nothing failing anywhere.
readonly RUNTIME_MODULES='java.base,java.desktop,java.logging,java.scripting,java.sql,java.xml,jdk.jfr,jdk.localedata,jdk.net,jdk.unsupported'

# The locales that data is kept for, which are the languages this build offers. A language
# added to languages.properties belongs here too, and
# TheTrimmedRuntimeCarriesEveryOfferedLanguageTest is what fails when it is forgotten.
readonly RUNTIME_LOCALES='en,es'

fail() {
  printf 'build-deb.sh: %s\n' "$1" >&2
  exit 1
}

require_tools() {
  # dpkg-deb and fakeroot are jpackage's, not this script's: it shells out to both, and
  # without them it skips the DEB bundler and says so in a line that is easy to read past.
  local tool
  for tool in mvn jlink jpackage dpkg-deb fakeroot; do
    command -v "${tool}" >/dev/null 2>&1 \
      || fail "${tool} is not on the PATH; on Ubuntu the last two are apt install dpkg fakeroot"
  done
}

version_from_the_pom() {
  # jpackage takes a Debian version, which -SNAPSHOT is not: it must begin with a digit and
  # hold nothing but digits and dots.
  # The first <version> in the root pom is the project's own: it has no parent to declare
  # one before it, and the modules take theirs from it.
  grep -m 1 '<version>' "${REPOSITORY}/pom.xml" \
    | sed -e 's#.*<version>##' -e 's#</version>.*##' -e 's/-SNAPSHOT$//'
}

build_the_jars() {
  local skip="$1"
  ( cd "${REPOSITORY}" && mvn -q ${skip} package )
  ( cd "${REPOSITORY}" \
      && mvn -q -DskipTests -DincludeScope=runtime -DoutputDirectory="${WORK}/input" \
             dependency:copy-dependencies )
  local module
  for module in login-core login-ui protected-feature; do
    cp "${REPOSITORY}/${module}/target/${module}-"*.jar "${WORK}/input/"
  done
}

link_the_runtime() {
  jlink --add-modules "${RUNTIME_MODULES}" \
        --include-locales "${RUNTIME_LOCALES}" \
        --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
        --output "${WORK}/runtime"
}

stage_the_deployment_files() {
  # What the package carries besides the application: the units, the script that registers
  # them, and the documents both units name in Documentation=. They travel inside the
  # payload, which is the only place jpackage puts files, and the maintainer scripts reach
  # them there.
  #
  # jpackage copies --app-content into the image's lib/ rather than beside it, so these
  # land at /opt/javafx-login/lib/systemd and /opt/javafx-login/lib/doc. The postinst and
  # both unit files name those paths, and DebianPackageTest holds the four together.
  install -d "${WORK}/stage/systemd" "${WORK}/stage/doc"
  install -m 0644 "${HERE}/${SERVICE_LAUNCHER}.socket" "${HERE}/${SERVICE_LAUNCHER}.service" \
    "${WORK}/stage/systemd/"
  install -m 0755 "${HERE}/install.sh" "${WORK}/stage/systemd/install.sh"
  install -m 0644 "${REPOSITORY}/docs/manual-checks"/linux-*.md "${WORK}/stage/doc/"
  install -m 0644 "${HERE}/THIRD-PARTY-NOTICES.md" "${WORK}/stage/doc/"
}

build_the_application_image() {
  local version="$1"
  # No menu entry for the second launcher. --linux-shortcut applies to every launcher, and
  # a shortcut for this one would offer a person the privileged process itself: started by
  # hand it inherits no socket from systemd, refuses to start, and says so to a desktop
  # that shows nobody. The window a person wants is the other launcher.
  cat > "${WORK}/${SERVICE_LAUNCHER}.properties" <<PROPERTIES
main-class=${SERVICE_CLASS}
linux-shortcut=false
PROPERTIES

  # Both launchers are handed the same classpath — every jar in the payload — and differ in
  # the class they start: the window a person sees, and the privileged process systemd
  # activates. One payload, because the two halves share the protocol between them and a
  # package that shipped two copies of it could ship two versions of it.
  local main_jar
  main_jar="$(cd "${WORK}/input" && ls protected-feature-*.jar)"

  jpackage --type app-image \
    --name "${PACKAGE_NAME}" \
    --app-version "${version}" \
    --input "${WORK}/input" \
    --main-jar "${main_jar}" \
    --main-class "${LAUNCHER_CLASS}" \
    --runtime-image "${WORK}/runtime" \
    --add-launcher "${SERVICE_LAUNCHER}=${WORK}/${SERVICE_LAUNCHER}.properties" \
    --app-content "${WORK}/stage/systemd,${WORK}/stage/doc" \
    --dest "${WORK}/image"
}

smoke_check_the_image() {
  # The cheapest question worth asking of a freshly built image: does the launcher the
  # .service unit starts run at all on the runtime that was just linked? It is asked in the
  # upgrade mode, which reads a CredentialStore that is not there, writes nothing and says
  # so — the one mode with nothing to serve and no privileges to need.
  local said
  said="$("${WORK}/image/${PACKAGE_NAME}/bin/${SERVICE_LAUNCHER}" \
            "${WORK}/smoke/credentials.db" --upgrade 2>&1)" \
    || fail "the packaged AuthenticationService would not start: ${said}"
  [[ ${said} == *"no CredentialStore"* ]] \
    || fail "the packaged AuthenticationService said something unexpected: ${said}"
  [[ ! -e ${WORK}/smoke ]] || fail 'the upgrade mode created a deployment out of nothing'
}

build_the_package() {
  local version="$1"
  jpackage --type deb \
    --app-image "${WORK}/image/${PACKAGE_NAME}" \
    --name "${PACKAGE_NAME}" \
    --app-version "${version}" \
    --description 'Offline login gating a host product feature behind an Account and a password' \
    --vendor 'JavaFX Login' \
    --copyright 'Copyright the JavaFX Login authors' \
    --about-url 'https://github.com/MeloGD/JavaFX-Login' \
    --resource-dir "${HERE}/debian" \
    --install-dir '/opt' \
    --linux-package-name "${PACKAGE_NAME}" \
    --linux-deb-maintainer "${MAINTAINER}" \
    --linux-app-category 'utils' \
    --linux-menu-group 'System' \
    --linux-shortcut \
    --dest "${WORK}/dist"
}

report() {
  printf '\n%s\n' "$(ls "${WORK}/dist"/*.deb)"
  printf 'Install it with: sudo apt install %s\n' "$(ls "${WORK}/dist"/*.deb)"
  printf 'Then verify it against docs/manual-checks/linux-packaging.md\n'
}

main() {
  local skip=''
  case "${1-}" in
    --skip-tests) skip='-DskipTests' ;;
    '') ;;
    *) fail "unknown argument: $1" ;;
  esac

  require_tools
  local version
  version="$(version_from_the_pom)"
  [[ -n ${version} ]] || fail 'no version could be read out of pom.xml'

  rm -rf "${WORK}"
  install -d "${WORK}"
  build_the_jars "${skip}"
  link_the_runtime
  stage_the_deployment_files
  build_the_application_image "${version}"
  smoke_check_the_image
  build_the_package "${version}"
  report
}

main "$@"
