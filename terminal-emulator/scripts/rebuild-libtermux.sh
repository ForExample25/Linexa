#!/usr/bin/env bash
# F-045 / R-11 — rebuild the vendored JNI layer (libtermux.so).
#
# The module ships a prebuilt binary copied verbatim from termux/termux-app
# v0.118.3 (see PROVENANCE.md). This script is the documented, reproducible
# way to rebuild it from the vendored `src/main/jni/termux.c` instead of
# trusting an undocumented binary.
#
# Requirements:
#   * Android NDK r25+ (set ANDROID_NDK_HOME, or let it resolve under
#     $ANDROID_HOME/ndk/<version>)
#   * ndk-build on PATH (or $ANDROID_NDK_HOME/ndk-build)
#
# Usage (from this module's directory):
#   scripts/rebuild-libtermux.sh
#
# After a rebuild: update src/main/jniLibs/SHA256SUMS and PROVENANCE.md in the
# same commit. `PrebuiltLibraryPinTest` fails until both agree with the new
# binaries.
set -euo pipefail

cd "$(dirname "$0")/.."

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "${NDK}" && -n "${ANDROID_HOME:-}" ]]; then
  NDK="$(find "$ANDROID_HOME/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -1 || true)"
fi
if [[ -z "${NDK}" || ! -d "$NDK" ]]; then
  echo "error: Android NDK not found. Set ANDROID_NDK_HOME." >&2
  exit 1
fi

NDK_BUILD="$NDK/ndk-build"
if [[ ! -x "$NDK_BUILD" ]]; then
  echo "error: $NDK_BUILD is not executable." >&2
  exit 1
fi

OUT="build/ndk-libtermux"
rm -rf "$OUT"
mkdir -p "$OUT"

"$NDK_BUILD" \
  NDK_PROJECT_PATH="$OUT" \
  APP_BUILD_SCRIPT="$PWD/src/main/jni/Android.mk" \
  APP_ABI="arm64-v8a armeabi-v7a" \
  APP_PLATFORM="android-28" \
  NDK_OUT="$OUT/obj" \
  NDK_LIBS_OUT="$OUT/libs"

for abi in arm64-v8a armeabi-v7a; do
  src="$OUT/libs/$abi/libtermux.so"
  dst="src/main/jniLibs/$abi/libtermux.so"
  test -f "$src" || { echo "error: missing $src" >&2; exit 1; }
  cp "$src" "$dst"
  echo "updated $dst"
done

echo
echo "New hashes (update src/main/jniLibs/SHA256SUMS and PROVENANCE.md):"
sha256sum src/main/jniLibs/*/libtermux.so
