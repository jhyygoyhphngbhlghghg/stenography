# Models on device

**Кратко (RU):** на устройстве работают Zipformer-ru int8 (ASR, 3 файла +
tokens), Silero VAD, pyannote segmentation-3.0 **float** ONNX (5 992 913 B,
sha `220ad67c…` — не int8-экспорт!), 3D-Speaker ERes2Net (эмбеддинги) и
Qwen2-0.5B-Instruct Q4_0 GGUF (352 969 408 B) через llama.cpp. Веса в git не
лежат; в check-APK их кладёт `:app:prepareEmbeddedModels`, GGUF — только с
`-PembedLlm=true`. Полный список размеров и sha256 — ниже; лицензии —
`NOTICE.md`.

Weights are **not in git** (`*.onnx`, `*.gguf`, `**/models/**` are ignored).
They must be on the phone *before* the airplane-mode check, either packed in
the APK assets (the judge path) or pushed with adb (the dev path).

Everything below was re-downloaded and re-hashed on **2026-11-07** with
`scripts/download-models.sh` on this machine; the hashes match the ones
Platform recorded in `artifacts/test-results/models-checksums.sha256`
(PR #3 @ `af71d91`), so the two machines pull identical bytes.

## Primary stack (locked)

| Role | File | bytes | sha256 (first 8 / last 8) | source |
|---|---|---|---|---|
| ASR | `asr/zipformer-ru-int8/encoder.int8.onnx` | 70 876 638 | `eb6c12fb` … `d9ec407` | [HF csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20](https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20) |
| ASR | `asr/zipformer-ru-int8/decoder.onnx` | 2 093 080 | `dcbe1ffa` … `2df12136` | same |
| ASR | `asr/zipformer-ru-int8/joiner.int8.onnx` | 259 417 | `93f2e1d1` … `a1fa40f7` | same |
| ASR | `asr/zipformer-ru-int8/tokens.txt` | 6 388 | `93bbbc0b` … `05e0fdf6` | same |
| VAD | `vad/silero_vad.onnx` | 643 854 | `9e2449e1` … `9cbb1fd6` | [sherpa VAD docs](https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html) |
| Diar seg | `diar/segmentation.onnx` (pyannote segmentation-3.0, float `model.onnx`) | 5 992 913 | `220ad67c` … `938e1079` | [sherpa diarization APKs](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/apk.html) |
| Diar embed | `diar/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx`, copied to `diar/embedding.onnx` | 39 593 761 | `1a331345` … `e3c7a5e4b` | same |
| **LLM** | `llm/qwen2-0_5b-instruct-q4_0.gguf` | 352 969 408 | `aca67983` … `e0596f` | [HF Qwen/Qwen2-0.5B-Instruct-GGUF](https://huggingface.co/Qwen/Qwen2-0.5B-Instruct-GGUF) |

Full 64-character hashes are in `artifacts/test-results/models-checksums.sha256`
and in the per-fetch log `artifacts/test-results/model-download-run-2026-11-07.csv`.

ASR + VAD + diarization together = **119 466 051 bytes** (≈114 MiB across the
seven files; the Gradle line `embedded 7 model files …` prints ~250 bytes more
because it counts the `.embedded` marker it just wrote). That is what
`:app:prepareEmbeddedModels` copies into `assets/models` for the check APK.
The GGUF adds 353 MB and is opt-in: `-PembedLlm=true`.

Do not replace `segmentation.onnx` with the int8 export in that tarball
(1 540 506 bytes, sha `d582f4b4…`). It is a different model than the one the
STAGE6 table was measured with, and an earlier version of the script picked it
nondeterministically (`find … | head -n 1`). The script now takes the float
`model.onnx` explicitly.

## Backup and emergency ASR

| Role | Pack | Notes |
|---|---|---|
| backup | Whisper tiny multilingual via **sherpa-onnx** (`DOWNLOAD_WHISPER=1`) | bilingual RU/EN demo only; not a second pipeline |
| emergency | Vosk `small-ru-0.22`, 45 M, Apache-2.0 (`DOWNLOAD_VOSK=1`) | only if the sherpa pack fails to load on the device |
| considered, not used | GigaAM v3 e2e RNNT int8 ≈216 MB | better RU story, but needs `feature_dim=64`, `nemo_transducer` and ≤30 s VAD chunking; not the locked path |

Sizes in this table are published listing sizes, not our measurements — the
two rows above were not re-downloaded on 2026-11-07.

## Where the app looks

```
filesDir/models/
  asr/zipformer-ru-int8/   encoder*.onnx  decoder*.onnx  joiner*.onnx  tokens.txt
  asr/whisper-tiny/        (optional)
  vad/silero_vad.onnx      vad/silero_vad.int8.onnx
  diar/segmentation.onnx   diar/model.onnx
  diar/embedding.onnx      diar/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx
  llm/*.gguf
  vosk-small-ru/           (optional)
```

`ModelStore.resolve()` reads that tree; `ModelStore.ensureUnpacked()` fills it
from `assets/models` on first launch when the build set `EMBEDDED_MODELS`.
A missing ASR pack is reported as a stub pipeline in the UI, never faked.
A missing GGUF reports `llm-weights-missing (intonation punct only)`.

## Порядок загрузки и память

Модели не резидентны одновременно: по design-решению «exclusive memory
phases» (`ExclusivePhases.kt`, ветка agent1) ASR-сессия выгружается после
фазы ASR и **до** загрузки GGUF; LLM остаётся тёплой между прогонами. Хостовые
пики RSS: ASR-only ≈273 МБ, LLM-only (ctx 1024, 2 потока) ≈630 МБ; на
эмуляторе LDPlayer полный прогон дал peak PSS ≈400 МБ
(`artifacts/compact/exclusive-phases-experiment.json`,
`artifacts/compact/ldplayer-real-check.json`). В compact-кандидате
(`app-stenographer-compact.apk`) все 8 ассетов те же, GGUF **байт-в-байт**
идентичен оригиналу, а Zipformer-encoder offline-подготовлен (init 1370→590 мс,
вывод побайтово совпадает с оригиналом на 9 валидационных wav —
`artifacts/compact/encoder-validation.json`).

## Runtime dependency

sherpa-onnx Android AAR **v2.0.1** via JitPack
(`com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1`): 49 441 341 bytes,
sha256 `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`,
JNI for arm64-v8a / armeabi-v7a / x86 / x86_64. Reproduce with
`scripts/inspect-sherpa-aar.sh`; the recorded result is
`artifacts/test-results/sherpa-aar-resolve.json`.

llama.cpp bindings: `dev.ffmpegkit-maintained:llama-android:0.1.1` (MIT,
2 062 204-byte AAR, sha256 `fdccf04c77de470004d7e54679bb918074ee9dbf31ea75af8c014d19c31e7501`,
arm64-v8a only — on other ABIs the LLM post-pass degrades to intonation
heuristics and the UI labels it as such).

## Licenses

See `NOTICE.md`. Short version: sherpa-onnx Apache-2.0; Zipformer-ru lineage
from the Vosk RU models (Apache-2.0 per the vosk model page); Silero VAD is
distributed by sherpa under its own terms; pyannote segmentation-3.0 weights
are MIT but the upstream HF repo is gated — we ship the copy published in the
sherpa release tarball and keep attribution; Qwen2-0.5B-Instruct is Apache-2.0;
llama-android is MIT and llama.cpp is MIT.

Do not add Picovoice (AccessKey) or any cloud ASR/LLM, including as a
"fallback" — the brief forbids audio leaving the device.
