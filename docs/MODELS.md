# Models (not in git; packed into the judge APK)

Weights are **gitignored**. The **default/judge APK** embeds Zipformer-ru int8 + Silero VAD + diar ONNX in `assets/models` at assemble. First launch copies them to `filesDir/models` (`ModelStore.ensureUnpacked`). Airplane-mode / Hex.Team auto-check: install that APK, no network, no adb push.

Slim: `./gradlew :app:assembleStenographerSlim` — no weights (karma).

Do not invent WER/RTF here. Listing sizes below are from the cited pages. **Measured** sizes (2026-11-06, `scripts/download-models.sh`, wall **4 s**, exit 0) are in [artifacts/test-results/models-manifest.json](../artifacts/test-results/models-manifest.json).

## Primary (lock)

| Role | Pack | Cited size | Source |
|---|---|---|---|
| ASR | Zipformer-ru int8 (sherpa) | encoder.int8 70.9 MB + decoder 2.09 MB + joiner.int8 259 KB ≈ **~74 MB** listing | [HF csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20](https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20) |
| VAD | Silero via sherpa | `silero_vad.onnx` **629 KB** (int8 **208 KB**) | [sherpa VAD docs](https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html) |
| Diarization | pyannote segmentation-3.0 ONNX + 3D-Speaker / TitaNet embedding + FastClustering | see sherpa speaker-diarization APK page | [sherpa diarization APKs](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/apk.html) |

### Measured download (2026-11-06, this VM)

Command: `DOWNLOAD_LOG=artifacts/test-results/model-download-run.csv ./scripts/download-models.sh`  
Wall: **4 s**, exit **0**. Epoch-99 filenames 404 on this HF pack — script now uses the real names.

| File | bytes | sha256 (prefix) | wall_ms |
|---|---:|---|---:|
| `asr/zipformer-ru-int8/encoder.int8.onnx` | 70876638 | `eb6c12fb` | 553 |
| `asr/zipformer-ru-int8/decoder.onnx` | 2093080 | `dcbe1ffa` | 927 |
| `asr/zipformer-ru-int8/joiner.int8.onnx` | 259417 | `93f2e1d1` | 309 |
| `asr/zipformer-ru-int8/tokens.txt` | 6388 | `93bbbc0b` | 63 |
| `vad/silero_vad.onnx` | 643854 | `9e2449e1` | 90 |
| `diar/segmentation.onnx` (from pyannote tarball `model.onnx`) | 5992913 | `220ad67c` | 423 (tarball) |
| `diar/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx` | 39593761 | `1a331345` | 435 |

Ready-for-offline-demo total (ASR+VAD+diar ONNX): **119466051 bytes**. LLM GGUF still not downloaded. Full hashes: `artifacts/test-results/models-checksums.sha256`.

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

Judge path: `assembleStenographerDebug` (Gradle copies this tree into `assets/models`). First run unpacks to `filesDir/models`. Never commit weights. Slim/dev-only: adb push the same tree.
