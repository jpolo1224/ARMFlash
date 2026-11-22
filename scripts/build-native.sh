#!/usr/bin/env bash
# Build libfpcore.so (database + game server) for arm64-v8a into the app's jniLibs.
# Uses the NDK's clang directly; no cargo-ndk needed.
#   scripts/build-native.sh [release|debug]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROFILE="${1:-release}"
API=28
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}"
HOST_TAG=darwin-x86_64; [[ "$(uname)" == Linux ]] && HOST_TAG=linux-x86_64
BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[[ -x "$BIN/clang" ]] || { echo "NDK clang not found under $NDK" >&2; exit 1; }

export CC_aarch64_linux_android="$BIN/aarch64-linux-android$API-clang"
export CXX_aarch64_linux_android="$BIN/aarch64-linux-android$API-clang++"
export AR_aarch64_linux_android="$BIN/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_aarch64_linux_android"
# 16 KB page-size compatible (required for new Play uploads).
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C target-cpu=cortex-a76"

FLAGS=(); [[ "$PROFILE" == release ]] && FLAGS=(--release)
cd "$ROOT/rust"
cargo build -p fp-jni --target aarch64-linux-android "${FLAGS[@]}"

OUT="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"
cp "target/aarch64-linux-android/$PROFILE/libfpcore.so" "$OUT/"
ls -l "$OUT/libfpcore.so"
