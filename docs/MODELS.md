# Models (not in git)

Weights are **gitignored**. Airplane-mode demo requires them on the device *before* disconnecting.

Do not invent WER/RTF here. Sizes below are **published download/listing sizes** from the cited pages, not our measurements.

## Primary (lock)

| Role | Pack | Cited size | Source |
|---|---|---|---|
| ASR | Zipformer-ru int8 (sherpa) | encoder.int8 70.9 MB + decoder 2.09 MB + joiner.int8 259 KB ≈ **~74 MB** listing | [HF csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20](https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20) |
| VAD | Silero via sherpa | `silero_vad.onnx` **629 KB** (int8 **208 KB**) | [sherpa VAD docs](https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html) |
| Diarization | pyannote segmentation-3.0 ONNX + 3D-Speaker / TitaNet embedding + FastClustering | see sherpa speaker-diarization APK page | [sherpa diarization APKs](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/apk.html) |

## Backup / emergency

| Role | Pack | Notes |
|---|---|---|
| ASR backup | Whisper tiny multilingual **via sherpa-onnx** (~100 MB class in VoicePing table) | Not whisper.cpp |
| ASR emergency | Vosk `small-ru-0.22` **45M**, Apache-2.0 | If sherpa fails to load |
| Quality upgrade (flagship, later) | GigaAM v3 e2e RNNT int8 ≈ **~216 MB**, MIT | Must VAD-chunk ≤~30 s; not default |

## On-device layout

After `scripts/download-models.sh`:

```
models/
  asr/zipformer-ru-int8/
      encoder.int8.onnx   decoder.onnx   joiner.int8.onnx   tokens.txt
  asr/whisper-tiny/        (optional; DOWNLOAD_WHISPER=1, then extract)
  vad/silero_vad.onnx
  diar/segmentation.onnx   # symlink to pyannote model.onnx
      embedding.onnx       # symlink to 3dspeaker_*.onnx
  llm/qwen2-0_5b-instruct-q4_0.gguf   # Qwen2-0.5B-Instruct Q4_0 (~353 MB listing)
  vosk-small-ru/           (optional; DOWNLOAD_VOSK=1)
```

HF filenames for Zipformer-ru are **exact** (`encoder.int8.onnx` / `decoder.onnx` / `joiner.int8.onnx` / `tokens.txt`). The script no longer guesses `encoder-epoch-99-avg-1.int8.onnx`.

ASR/VAD/diar are **embedded by Platform pack** (PR #3 @ `af71d91`). This branch does **not** re-pack `assets/`. GGUF is downloaded into `models/llm/` so Platform unpack can place it at `filesDir/models/llm/*.gguf`. Never commit weights.
