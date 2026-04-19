#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

JAVA_HOME_DEFAULT="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"
export PATH="$JAVA_HOME/bin:$PATH"

SRC_DIR="tools/wrapper-src"
BUILD_DIR="tools/wrapper-build"
OUT_JAR="gradle/wrapper/gradle-wrapper.jar"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes"

javac -d "$BUILD_DIR/classes" "$SRC_DIR"/org/gradle/wrapper/*.java
jar cf "$OUT_JAR" -C "$BUILD_DIR/classes" .

echo "Built $OUT_JAR"
