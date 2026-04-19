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

if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "JDK not found at $JAVA_HOME" >&2
  exit 1
fi

if [ ! -d "$ANDROID_SDK_ROOT" ]; then
  echo "Android SDK not found at $ANDROID_SDK_ROOT" >&2
  exit 1
fi

if [ -x "$GRADLE_FALLBACK" ]; then
  exec "$GRADLE_FALLBACK" assembleDebug "$@"
fi

exec ./gradlew assembleDebug "$@"
