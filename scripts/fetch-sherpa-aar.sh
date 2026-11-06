#!/usr/bin/env bash
# Download the Android AAR that k2-fsa jitpack.yml wget's from GitHub Releases.
# JitPack coordinate com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1 is unpublished.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VER="${SHERPA_ONNX_VERSION:-1.13.5}"
DEST="${SHERPA_AAR:-$ROOT/src/android/app/libs/sherpa-onnx-$VER.aar}"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VER/sherpa-onnx-$VER.aar"
mkdir -p "$(dirname "$DEST")"
if [[ -f "$DEST" && $(stat -c%s "$DEST") -gt 1000000 ]]; then
  echo "have $DEST ($(stat -c%s "$DEST") bytes)"
  exit 0
fi
echo "GET $URL"
curl -L --fail --retry 3 -o "$DEST" "$URL"
echo "wrote $DEST ($(stat -c%s "$DEST") bytes)"
