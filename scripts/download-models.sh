#!/usr/bin/env bash
# Download on-device models into ./models (gitignored). Run on Wi-Fi, then
# adb push onto the phone BEFORE airplane-mode demo.
#
# Primary: Zipformer-ru int8 (HF listing ~74 MB) + Silero VAD + pyannote diar.
# Backup:  Whisper tiny via sherpa (DOWNLOAD_WHISPER=1).
# Emergency: Vosk small-ru (DOWNLOAD_VOSK=1).
# LLM: Qwen2-0.5B-Instruct Q4_0 GGUF (~353 MB listing). Packed by Platform
# (PR #3 @ af71d91) — this repo does not copy assets/.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${MODELS_DIR:-$ROOT/models}"
mkdir -p "$OUT/asr/zipformer-ru-int8" "$OUT/vad" "$OUT/diar" "$OUT/llm" "$OUT/asr/whisper-tiny"

echo "==> models dir: $OUT"

need() { command -v "$1" >/dev/null 2>&1 || { echo "need $1"; exit 1; }; }
need curl

hf() {
  local url="https://huggingface.co/$1/resolve/main/$2"
  echo "GET $url"
  curl -L --fail --retry 3 -o "$3" "$url"
}

gh_rel() {
  local url="$1" dest="$2"
  echo "GET $url"
  curl -L --fail --retry 3 -o "$dest" "$url"
}

# Zipformer-ru int8 — exact filenames on HF tree (csukuangfj, 2025-04-20).
ZIP_REPO="csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20"
ZIP_DIR="$OUT/asr/zipformer-ru-int8"
echo "==> Zipformer-ru int8 (primary ASR)"
hf "$ZIP_REPO" "tokens.txt" "$ZIP_DIR/tokens.txt"
hf "$ZIP_REPO" "encoder.int8.onnx" "$ZIP_DIR/encoder.int8.onnx"
hf "$ZIP_REPO" "decoder.onnx" "$ZIP_DIR/decoder.onnx"
hf "$ZIP_REPO" "joiner.int8.onnx" "$ZIP_DIR/joiner.int8.onnx"

echo "==> Silero VAD"
if [[ ! -f "$OUT/vad/silero_vad.onnx" ]]; then
  gh_rel "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx" \
    "$OUT/vad/silero_vad.onnx"
fi

echo "==> Diarization (pyannote segmentation-3.0 + 3D-Speaker embedding)"
SEG_TBZ="$OUT/diar/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
if [[ ! -f "$OUT/diar/segmentation.onnx" && ! -f "$OUT/diar/model.onnx" ]]; then
  gh_rel "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2" \
    "$SEG_TBZ"
  if [[ -f "$SEG_TBZ" ]]; then
    tar -xjf "$SEG_TBZ" -C "$OUT/diar" --strip-components=1 || tar -xjf "$SEG_TBZ" -C "$OUT/diar"
    # Normalise name the app looks for.
    if [[ -f "$OUT/diar/model.onnx" && ! -f "$OUT/diar/segmentation.onnx" ]]; then
      ln -sf model.onnx "$OUT/diar/segmentation.onnx"
    fi
    find "$OUT/diar" -name 'model.onnx' -o -name 'segmentation.onnx' | head
  fi
fi

EMB="$OUT/diar/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
if [[ ! -f "$EMB" ]]; then
  gh_rel "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx" \
    "$EMB"
fi
if [[ -f "$EMB" && ! -f "$OUT/diar/embedding.onnx" ]]; then
  ln -sf "$(basename "$EMB")" "$OUT/diar/embedding.onnx"
fi

echo "==> Whisper tiny (backup, skipped unless DOWNLOAD_WHISPER=1)"
if [[ "${DOWNLOAD_WHISPER:-0}" == "1" ]]; then
  WT="$OUT/asr/whisper-tiny/sherpa-onnx-whisper-tiny.tar.bz2"
  gh_rel "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2" "$WT"
  tar -xjf "$WT" -C "$OUT/asr/whisper-tiny" --strip-components=1 || tar -xjf "$WT" -C "$OUT/asr/whisper-tiny"
fi

echo "==> Vosk small-ru emergency (skipped unless DOWNLOAD_VOSK=1)"
if [[ "${DOWNLOAD_VOSK:-0}" == "1" ]]; then
  mkdir -p "$OUT/vosk-small-ru"
  curl -L --fail --retry 3 -o "$OUT/vosk-small-ru/vosk-model-small-ru-0.22.zip" \
    "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
fi

echo "==> Qwen2-0.5B-Instruct Q4_0 GGUF (local LLM punct post-pass)"
GGUF="$OUT/llm/qwen2-0_5b-instruct-q4_0.gguf"
if [[ ! -f "$GGUF" ]]; then
  hf "Qwen/Qwen2-0.5B-Instruct-GGUF" "qwen2-0_5b-instruct-q4_0.gguf" "$GGUF"
fi

echo "done. Contents:"
find "$OUT" -type f | sed "s|$ROOT/||" | head -80
echo "Next: Platform pack (PR #3 @ af71d91) embeds models/ into APK assets."
echo "This branch does not re-pack assets. GGUF path: models/llm/*.gguf"
