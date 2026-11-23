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
# 16 KB page-size compatible (required for new Play uploads). No target-cpu: plain ARMv8, so
# phones with only Cortex-A53-class cores run it too.
# --remap-path-prefix: panic messages and symbols name crates by their registry path, not by
# this machine's folders (they show up in the in-app Logs tab).
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 --remap-path-prefix=$HOME/.cargo/registry/src=crates --remap-path-prefix=$HOME/.cargo/git/checkouts=git --remap-path-prefix=$ROOT=flasharm"

FLAGS=(); [[ "$PROFILE" == release ]] && FLAGS=(--release)

# Ruffle's build compiles its ActionScript runtime (playerglobal) with a Java tool.
if ! java -version >/dev/null 2>&1; then
  export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
cd "$ROOT/rust"
cargo build -p fp-jni --target aarch64-linux-android "${FLAGS[@]}"

OUT="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"
cp "target/aarch64-linux-android/$PROFILE/libfpcore.so" "$OUT/"

# libfpruffle.so: native Ruffle. Its own workspace and toolchain (rust-toolchain.toml there),
# pinned to the Ruffle revision ruffle-android tested (its Cargo.lock).
if [[ "${SKIP_RUFFLE:-0}" != 1 ]]; then
  cd "$ROOT/rust/fp-ruffle"
  cargo build --target aarch64-linux-android "${FLAGS[@]}"
  cp "target/aarch64-linux-android/$PROFILE/libfpruffle.so" "$OUT/"
  # Unstripped copy for symbolizing crashes from devices (llvm-symbolizer --obj=...).
  SYM="$ROOT/symbols/$(git -C "$ROOT" rev-parse --short HEAD)$(git -C "$ROOT" diff --quiet || echo -dirty)"
  mkdir -p "$SYM" && cp "target/aarch64-linux-android/$PROFILE/libfpruffle.so" "$SYM/"
fi
ls -l "$OUT"
