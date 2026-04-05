#!/bin/zsh

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_DIR="$ROOT_DIR/vendor/whisper.cpp/examples/whisper.android"
OUTPUT_DIR="$ROOT_DIR/build"
SOURCE_APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
FINAL_APK="$OUTPUT_DIR/whisper-meeting-transcriber.apk"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle-home}"
export JAVA_OPTS="${JAVA_OPTS:--Xmx2048m -Dfile.encoding=UTF-8}"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "$OUTPUT_DIR"
mkdir -p "$GRADLE_USER_HOME"

cleanup() {
  ./gradlew --stop -Dorg.gradle.jvmargs= >/dev/null 2>&1 || true
}

cd "$PROJECT_DIR"
trap cleanup EXIT
./gradlew --no-daemon -Dorg.gradle.jvmargs= assembleRelease

cp "$SOURCE_APK" "$FINAL_APK"

echo
echo "Whisper APK built successfully:"
echo "$FINAL_APK"
