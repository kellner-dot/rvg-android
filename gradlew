#!/bin/sh
# Gradle wrapper bootstrap. Requires the gradle-wrapper.jar next to this script.
# Fetch it on a machine with network access:
#   curl -L -o gradle/wrapper/gradle-wrapper.jar \
#     https://github.com/gradle/gradle/raw/master/gradle/wrapper/gradle-wrapper.jar
APP_BASE_NAME=${0##*/}
APP_HOME=$(cd "${0%/*}" >/dev/null && pwd)
exec java -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
