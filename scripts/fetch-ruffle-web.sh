#!/usr/bin/env bash
# Fetch Ruffle's self-hosted web build (MIT OR Apache-2.0) into the app's assets.
# Interim Flash player until the native Ruffle core lands.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VER="${1:-0.8.0}"
DEST="$ROOT/android/app/src/main/assets/ruffle"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fsSL -o "$TMP/r.zip" "https://github.com/ruffle-rs/ruffle/releases/download/v$VER/ruffle-$VER-web-selfhosted.zip"
rm -rf "${DEST:?}"; mkdir -p "$DEST"
unzip -q "$TMP/r.zip" -d "$DEST" -x '*.map'
echo "$VER" > "$DEST/VERSION"
ls "$DEST"
