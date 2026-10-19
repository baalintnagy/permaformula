#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
[[ ${1:-} != --help ]] || {
    echo 'install-rendering.sh: build renderer dependencies and install matching Chromium'
    exit 0
}
[[ $# == 0 ]] || fail 'No arguments expected.'
cd "$local_root"
export PLAYWRIGHT_BROWSERS_PATH="$(native_path "$local_root/.runtime/browsers")"
export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
mvn -pl renderer-core -am install -DskipTests -Dpermaformula.build.directory=target/local
mvn -pl renderer-core dependency:build-classpath -Dpermaformula.build.directory=target/local -Dmdep.outputFile=target/local/render-classpath.txt
classpath=$(tr -d '\r\n' <renderer-core/target/local/render-classpath.txt)
java_program=java
if [[ -n ${JAVA_HOME:-} ]]; then
    java_home=$JAVA_HOME
    command -v cygpath >/dev/null && java_home=$(cygpath -u "$JAVA_HOME")
    java_program="$java_home/bin/java"
fi
"$java_program" -cp "$classpath" com.microsoft.playwright.CLI install chromium --no-shell
