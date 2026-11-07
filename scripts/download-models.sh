#!/usr/bin/env bash
# Download on-device models into ./models (gitignored). Run on Wi-Fi, then
# adb push onto the phone BEFORE airplane-mode demo.
#
# Measured filenames for Zipformer-ru int8 (HF tree 2025-04-20, commit 641de8d):
#   encoder.int8.onnx  decoder.onnx  joiner.int8.onnx  tokens.txt
# Epoch-99 names 404 on this pack — do not retry them.
#
# Primary: Zipformer-ru int8 (HF listing ~74 MB) + Silero VAD + pyannote diar.
# Backup:  Whisper tiny via sherpa (DOWNLOAD_WHISPER=1).
# Emergency: Vosk small-ru (DOWNLOAD_VOSK=1).
# LLM: Qwen2-0.5B-Instruct Q4_0 GGUF (~353 MB listing) → models/llm/, run by
# the llama-android JNI (dev.ffmpegkit-maintained:llama-android:0.1.1).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export ROOT
OUT="${MODELS_DIR:-$ROOT/models}"
LOG="${DOWNLOAD_LOG:-$OUT/_download-log.csv}"
mkdir -p "$OUT/asr/zipformer-ru-int8" "$OUT/vad" "$OUT/diar" "$OUT/llm" "$OUT/asr/whisper-tiny"
mkdir -p "$(dirname "$LOG")"

echo "==> models dir: $OUT"
echo "==> LLM: Qwen2-0.5B-Instruct Q4_0 GGUF → models/llm/ (section near EOF)"

need() { command -v "$1" >/dev/null 2>&1 || { echo "need $1"; exit 1; }; }
need curl
need sha256sum
need python3

if [[ ! -s "$LOG" ]]; then
  echo "file,url,dest,http_code,bytes,wall_ms,sha256,status" > "$LOG"
fi

# Records one fetch. Returns 0 on HTTP 200 + non-empty file.
fetch() {
  local dest="$1" url="$2" label="${3:-$(basename "$dest")}"
  local start end wall code bytes sha status
  echo "GET $url"
  start=$(date +%s%N)
  code=$(curl -L --retry 3 --retry-delay 2 --fail-with-body \
    -o "$dest.part" -w "%{http_code}" -- "$url" || true)
  end=$(date +%s%N)
  wall=$(( (end - start) / 1000000 ))
  if [[ -f "$dest.part" ]]; then
    bytes=$(wc -c < "$dest.part" | tr -d ' ')
  else
    bytes=0
  fi
  if [[ "$code" == "200" && "$bytes" -gt 0 ]]; then
    mv "$dest.part" "$dest"
    sha=$(sha256sum "$dest" | awk '{print $1}')
    status="ok"
  else
    rm -f "$dest.part"
    sha=""
    status="FAIL"
    echo "WARN: $label failed http=$code bytes=$bytes wall_ms=$wall"
  fi
  local rel="${dest#"$ROOT"/}"
  python3 - "$LOG" "$label" "$url" "$rel" "$code" "$bytes" "$wall" "$sha" "$status" <<'PY'
import csv, sys
log, label, url, dest, code, bytes_, wall, sha, status = sys.argv[1:]
with open(log, "a", newline="") as f:
    csv.writer(f).writerow([label, url, dest, code, bytes_, wall, sha, status])
PY
  [[ "$status" == "ok" ]]
}

hf() {
  # $1 repo $2 file $3 dest
  local url="https://huggingface.co/$1/resolve/main/$2?download=true"
  fetch "$3" "$url" "$2"
}

# Zipformer-ru int8 — actual HF filenames (not epoch-99).
ZIP_REPO="csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20"
echo "==> Zipformer-ru int8 (primary ASR)"
ZIP_OK=1
for f in encoder.int8.onnx decoder.onnx joiner.int8.onnx tokens.txt; do
  dest="$OUT/asr/zipformer-ru-int8/$f"
  if [[ -f "$dest" && -s "$dest" ]]; then
    bytes=$(wc -c < "$dest" | tr -d ' ')
    sha=$(sha256sum "$dest" | awk '{print $1}')
    echo "skip existing $f ($bytes bytes)"
    python3 - "$LOG" "$f" "local-cache" "${dest#"$ROOT"/}" "200" "$bytes" "0" "$sha" "cached" <<'PY'
import csv, sys
log, label, url, dest, code, bytes_, wall, sha, status = sys.argv[1:]
with open(log, "a", newline="") as f:
    csv.writer(f).writerow([label, url, dest, code, bytes_, wall, sha, status])
PY
  else
    hf "$ZIP_REPO" "$f" "$dest" || ZIP_OK=0
  fi
done

echo "==> Silero VAD"
VAD_OK=1
VAD_DEST="$OUT/vad/silero_vad.onnx"
if [[ -f "$VAD_DEST" && -s "$VAD_DEST" ]]; then
  bytes=$(wc -c < "$VAD_DEST" | tr -d ' ')
  sha=$(sha256sum "$VAD_DEST" | awk '{print $1}')
  echo "skip existing silero_vad.onnx ($bytes bytes)"
  python3 - "$LOG" "silero_vad.onnx" "local-cache" "${VAD_DEST#"$ROOT"/}" "200" "$bytes" "0" "$sha" "cached" <<'PY'
import csv, sys
log, label, url, dest, code, bytes_, wall, sha, status = sys.argv[1:]
with open(log, "a", newline="") as f:
    csv.writer(f).writerow([label, url, dest, code, bytes_, wall, sha, status])
PY
else
  fetch "$VAD_DEST" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx" \
    "silero_vad.onnx" || VAD_OK=0
fi

echo "==> Diarization (pyannote segmentation + 3D-Speaker embedding)"
DIAR_OK=1
SEG_ONNX="$OUT/diar/segmentation.onnx"
SEG_MODEL="$OUT/diar/model.onnx"
if [[ -f "$SEG_ONNX" || -f "$SEG_MODEL" ]]; then
  echo "skip existing segmentation onnx"
else
  SEG_TBZ="$OUT/diar/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
  if fetch "$SEG_TBZ" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2" \
    "sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
  then
    tar -xjf "$SEG_TBZ" -C "$OUT/diar"
    found="$(find "$OUT/diar" -type f -name '*.onnx' | head -n 1 || true)"
    if [[ -n "$found" ]]; then
      cp -f "$found" "$SEG_ONNX"
      echo "extracted segmentation -> $SEG_ONNX ($(wc -c < "$SEG_ONNX" | tr -d ' ') bytes)"
    else
      echo "WARN: tarball had no .onnx"
      DIAR_OK=0
    fi
  else
    DIAR_OK=0
  fi
fi

EMB="$OUT/diar/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
if [[ ! -f "$EMB" ]]; then
  # Upstream GitHub release tag is misspelled "recongition".
  fetch "$EMB" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx" \
    "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx" || DIAR_OK=0
fi
if [[ -f "$EMB" && ! -f "$OUT/diar/embedding.onnx" ]]; then
  ln -sfn "$(basename "$EMB")" "$OUT/diar/embedding.onnx"
fi

echo "==> Whisper tiny (backup, skipped unless DOWNLOAD_WHISPER=1)"
if [[ "${DOWNLOAD_WHISPER:-0}" == "1" ]]; then
  fetch "$OUT/asr/whisper-tiny/sherpa-onnx-whisper-tiny.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2" \
    "sherpa-onnx-whisper-tiny.tar.bz2" || true
fi

echo "==> Vosk small-ru emergency (skipped unless DOWNLOAD_VOSK=1)"
if [[ "${DOWNLOAD_VOSK:-0}" == "1" ]]; then
  mkdir -p "$OUT/vosk-small-ru"
  fetch "$OUT/vosk-small-ru/vosk-model-small-ru-0.22.zip" \
    "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip" \
    "vosk-model-small-ru-0.22.zip" || true
fi

echo "==> Qwen2-0.5B-Instruct Q4_0 GGUF (local LLM intonation-punct post-pass)"
LLM_OK=1
GGUF="$OUT/llm/qwen2-0_5b-instruct-q4_0.gguf"
if [[ -f "$GGUF" && -s "$GGUF" ]]; then
  bytes=$(wc -c < "$GGUF" | tr -d ' ')
  sha=$(sha256sum "$GGUF" | awk '{print $1}')
  echo "skip existing qwen2-0_5b-instruct-q4_0.gguf ($bytes bytes)"
  python3 - "$LOG" "qwen2-0_5b-instruct-q4_0.gguf" "local-cache" "${GGUF#"$ROOT"/}" "200" "$bytes" "0" "$sha" "cached" <<'PY'
import csv, sys
log, label, url, dest, code, bytes_, wall, sha, status = sys.argv[1:]
with open(log, "a", newline="") as f:
    csv.writer(f).writerow([label, url, dest, code, bytes_, wall, sha, status])
PY
else
  # 352 969 408 bytes, HTTP 200 measured 2026-11-07, ungated Qwen repo.
  hf "Qwen/Qwen2-0.5B-Instruct-GGUF" "qwen2-0_5b-instruct-q4_0.gguf" "$GGUF" || LLM_OK=0
fi

echo "done. Contents:"
find "$OUT" -type f ! -name '_download-log.csv' | sed "s|$ROOT/||" | head -80
echo "download log: $LOG"
echo "Next: adb push models/ /sdcard/Android/data/team.mikcoders.neurosteno/files/models/"
echo "LLM: models/llm/*.gguf (Qwen2-0.5B-Instruct Q4_0) — llama-android JNI loads it on arm64-v8a."

if [[ "$ZIP_OK" != "1" || "$VAD_OK" != "1" ]]; then
  echo "ERROR: required Zipformer-ru and/or Silero VAD missing"
  exit 1
fi
if [[ "$DIAR_OK" != "1" ]]; then
  echo "WARN: diarization pack incomplete (ASR+VAD still usable)"
  exit 0
fi
