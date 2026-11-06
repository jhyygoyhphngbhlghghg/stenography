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
  asr/zipformer-ru-int8/   encoder*.onnx decoder*.onnx joiner*.onnx tokens.txt
  asr/whisper-tiny/        (optional)
  vad/silero_vad.onnx
  diar/segmentation.onnx embedding.onnx
  llm/                     TODO GGUF — empty overnight
  vosk-small-ru/           (optional emergency)
```

Copy into the app `filesDir/models/` (adb push) or `assets/models/` before freeze. Hybrid: bundle Zipformer-ru int8 if APK size allows; never commit weights.
