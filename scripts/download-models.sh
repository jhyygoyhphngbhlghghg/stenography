#!/usr/bin/env bash
# Download on-device models into ./models (gitignored). Run on Wi-Fi, then
# adb push onto the phone BEFORE airplane-mode demo.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${MODELS_DIR:-$ROOT/models}"
mkdir -p "$OUT/asr/zipformer-ru-int8" "$OUT/vad" "$OUT/diar" "$OUT/llm" "$OUT/asr/whisper-tiny"

echo "==> models dir: $OUT"
echo "==> LLM weights are NOT downloaded (TODO). See docs/MODELS.md"

need() { command -v "$1" >/dev/null 2>&1 || { echo "need $1"; exit 1; }; }
need curl

hf() {
  # $1 repo $2 file $3 dest
  local url="https://huggingface.co/$1/resolve/main/$2"
  echo "GET $url"
  curl -L --fail --retry 3 -o "$3" "$url"
}

gh_rel() {
  local url="$1" dest="$2"
  echo "GET $url"
  curl -L --fail --retry 3 -o "$dest" "$url"
}

# Zipformer-ru int8 — filenames as listed on the HF tree (2025-04-20 pack).
# If HF names drift, update this script; do not commit the onnx files.
ZIP_REPO="csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20"
echo "==> Zipformer-ru int8 (primary ASR)"
# Common sherpa pack names; skip if already present
if [[ ! -f "$OUT/asr/zipformer-ru-int8/tokens.txt" ]]; then
  echo "Downloading Zipformer-ru from Hugging Face (may 404 if file names changed — then grab the tree manually)."
  hf "$ZIP_REPO" "tokens.txt" "$OUT/asr/zipformer-ru-int8/tokens.txt" || true
  # encoder/decoder/joiner names vary; try the documented int8 trio
  for f in \
    encoder-epoch-99-avg-1.int8.onnx \
    decoder-epoch-99-avg-1.onnx \
    joiner-epoch-99-avg-1.int8.onnx \
    encoder.int8.onnx decoder.onnx joiner.int8.onnx
  do
    hf "$ZIP_REPO" "$f" "$OUT/asr/zipformer-ru-int8/$f" || true
  done
fi

echo "==> Silero VAD"
if [[ ! -f "$OUT/vad/silero_vad.onnx" ]]; then
  gh_rel "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx" \
    "$OUT/vad/silero_vad.onnx" || echo "WARN: silero_vad.onnx download failed"
fi

echo "==> Diarization (pyannote segmentation + embedding) — optional overnight"
# Official tarballs from sherpa releases; extract if present.
SEG_TBZ="$OUT/diar/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
if [[ ! -f "$OUT/diar/model.onnx" && ! -f "$OUT/diar/segmentation.onnx" ]]; then
  gh_rel "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2" \
    "$SEG_TBZ" || echo "WARN: segmentation tarball failed (gated pyannote terms may apply)"
fi

EMB="$OUT/diar/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
if [[ ! -f "$EMB" ]]; then
  gh_rel "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx" \
    "$EMB" || echo "WARN: embedding onnx failed"
fi

echo "==> Whisper tiny (backup, skipped unless DOWNLOAD_WHISPER=1)"
if [[ "${DOWNLOAD_WHISPER:-0}" == "1" ]]; then
  gh_rel "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2" \
    "$OUT/asr/whisper-tiny/sherpa-onnx-whisper-tiny.tar.bz2" || true
fi

echo "==> Vosk small-ru emergency (skipped unless DOWNLOAD_VOSK=1)"
if [[ "${DOWNLOAD_VOSK:-0}" == "1" ]]; then
  mkdir -p "$OUT/vosk-small-ru"
  curl -L --fail --retry 3 -o "$OUT/vosk-small-ru/vosk-model-small-ru-0.22.zip" \
    "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip" || true
fi

echo "done. Contents:"
find "$OUT" -type f | sed "s|$ROOT/||" | head -80
echo "Next: adb push models/ /sdcard/Android/data/team.mikcoders.neurosteno/files/models/"
echo "LLM: drop GGUF into models/llm/ when chosen (Qwen2-0.5B / Phi / Gemma-2B)."
