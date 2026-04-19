#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

JAVA_HOME_DEFAULT="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
ANDROID_SDK_DEFAULT="$HOME/Library/Android/sdk"

export JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_DEFAULT}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK_DEFAULT}"
export PATH="$JAVA_HOME/bin:$PATH"

GRADLE_FALLBACK="$HOME/.gradle/wrapper/dists/gradle-8.11.1-all/2qik7nd48slq1ooc2496ixf4i/gradle-8.11.1/bin/gradle"

echo "JAVA_HOME=$JAVA_HOME"
if [ -x "$JAVA_HOME/bin/java" ]; then
  java -version
else
  echo "JDK missing: $JAVA_HOME/bin/java" >&2
fi

echo "ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
if [ -d "$ANDROID_SDK_ROOT" ]; then
  echo "Android SDK found"
else
  echo "Android SDK missing" >&2
fi

if [ -f "gradle/wrapper/gradle-wrapper.jar" ]; then
  echo "gradle-wrapper.jar size: $(wc -c < gradle/wrapper/gradle-wrapper.jar) bytes"
else
  echo "gradle-wrapper.jar missing" >&2
fi

if [ -x "./gradlew" ]; then
  set +e
  ./gradlew -v
  status=$?
  set -e
  echo "gradlew exit code: $status"
else
  echo "gradlew missing or not executable" >&2
fi

if [ -x "$GRADLE_FALLBACK" ]; then
  set +e
  "$GRADLE_FALLBACK" -v
  status=$?
  set -e
  echo "fallback gradle exit code: $status"
fi
