# Models (not in git)

Weights are **gitignored**. They do not live in this repository.

**Check APK / TZ hand-in: assets-bundled is primary.** Zipformer-ru, Silero VAD, diarization ONNX, and the local LLM weights ship inside the APK `assets/` so the airplane-mode demo does not need a network. **Network download is UX karma only** — optional convenience, not required for hand-in or the check APK. `scripts/download-models.sh` plus adb push into `filesDir` remains a **dev** path.

Do not invent WER/RTF here. Sizes below are **published download/listing sizes** from the cited pages, not our measurements. DEMO_STAGE6 room score is aggregate WER **0.73** / DER **0.52** (see `docs/LIMITATIONS.md`); do not cite a lower aggregate.

## Primary (lock)

| Role | Pack | Cited size | Source |
|---|---|---|---|
| ASR | Zipformer-ru int8 (sherpa) | encoder.int8 70.9 MB + decoder 2.09 MB + joiner.int8 259 KB ≈ **~74 MB** listing | [HF csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20](https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20) |
| VAD | Silero via sherpa | `silero_vad.onnx` **629 KB** (int8 **208 KB**) | [sherpa VAD docs](https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html) |
| Diarization | pyannote segmentation-3.0 ONNX + 3D-Speaker / TitaNet embedding + FastClustering | see sherpa speaker-diarization APK page | [sherpa diarization APKs](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/apk.html) |
| LLM | Local on-device post-pass weights. **Required by TZ**, not a stub and not a cloud API. Cleanup must match spoken punctuation / intonation; textbook rewrite is not the target | size is the chosen GGUF; do not invent one here | Pack into `assets/` with the check APK. Candidates named in code (Qwen2-0.5B / Phi / Gemma) are Impl’s choice, not a measured score |

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
  llm/                     required GGUF for hand-in (not committed; assets-bundled)
  vosk-small-ru/           (optional emergency)
```

**Hand-in:** copy this tree into `assets/models/` and ship it in the check APK. Dev-only: adb push into `filesDir/models/` after `download-models.sh`. A network fetch inside the app is karma, not the TZ path. Never commit weights.

`IdentityLlmPostPass` in the tree today returns the ASR text unchanged (`weightsPresent = false`). That is an **Impl/Platform gap against TZ**. Hand-in must run a real local LLM. Do not document the identity class as an accepted stub.
