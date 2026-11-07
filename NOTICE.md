# NOTICE

## Our code

- App source: MIK Coders, Nuclear IT Hack 2026 (Hex.Team case «Нейро-стенографист»).
- Repo layout: [hacathon_template](https://github.com/grigorevmp/hacathon_template)
  (`artifacts/`, `config/`, `docs/`, `src/`, `samples/`).

## Third-party runtime code

| Component | License | What we take |
|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (k2-fsa / Xiaomi) | Apache-2.0 | Android AAR `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1` via JitPack — 49 441 341 bytes, sha256 `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7` (recorded in `artifacts/test-results/sherpa-aar-resolve.json`, re-checkable with `scripts/inspect-sherpa-aar.sh`). Provides `OfflineRecognizer`, `Vad` (Silero), `OfflineSpeakerDiarization`. Earlier revisions of this repo pulled the GitHub-Release AAR v1.13.5 instead because the JitPack coordinate was believed unpublished; that was measured to be wrong. |
| [llama.cpp](https://github.com/ggml-org/llama.cpp) through [`dev.ffmpegkit-maintained:llama-android:0.1.1`](https://github.com/ffmpegkit-maintained/llama-android) | MIT (bindings) / MIT (llama.cpp) | Prebuilt arm64 JNI for GGUF completion — AAR 2 062 204 bytes, sha256 `fdccf04c77de470004d7e54679bb918074ee9dbf31ea75af8c014d19c31e7501`. This is the app's local LLM engine; nothing in the pipeline calls a cloud LLM. |
| ONNX Runtime | as shipped inside the sherpa AAR (`libonnxruntime.so`) | inference backend |
| Kotlin, Jetpack Compose / Material3, androidx.activity / lifecycle, kotlinx-serialization, kotlinx-coroutines | Apache-2.0 | UI, JSON, async |
| OkHttp (Square) | Apache-2.0 | WebSocket/HTTP — PresentConnect flavor only |
| JUnit 4 | EPL-1.0 | unit tests |

## Model weights (never committed; pulled by `scripts/download-models.sh`)

| Model | Publisher | License / terms | Role |
|---|---|---|---|
| `sherpa-onnx-zipformer-ru-int8-2025-04-20` | csukuangfj (sherpa) | upstream lineage of alphacep Vosk RU models (Apache-2.0 per the Vosk model page) | primary RU ASR |
| `silero_vad.onnx` | Silero | terms as published on the sherpa VAD page; redistributed inside the sherpa release | VAD |
| `sherpa-onnx-pyannote-segmentation-3-0` → `diar/segmentation.onnx` (float `model.onnx`, sha256 `220ad67c…`) | pyannote | weights reported MIT, but the **upstream Hugging Face repo is gated** — we ship the copy published in the sherpa GitHub release tarball (`speaker-segmentation-models`) and keep this attribution; re-accept the HF terms before redistributing outside the hackathon | diarization segmentation |
| `3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx` (sha256 `1a331345…`) | 3D-Speaker (Alibaba) | model terms on the sherpa speaker-recognition release page | speaker embeddings |
| `qwen2-0_5b-instruct-q4_0.gguf` (352 969 408 bytes, sha256 `aca67983…`) | Qwen (Alibaba) | Apache-2.0 as published on the HF repo | local LLM post-pass |
| Whisper tiny (`DOWNLOAD_WHISPER=1`) | OpenAI via sherpa | OpenAI Whisper model terms as redistributed by sherpa packs | optional bilingual backup |
| Vosk `vosk-model-small-ru-0.22` (`DOWNLOAD_VOSK=1`) | Alphacep | Apache-2.0 | emergency ASR |

Per-file bytes, URLs and sha256 for the 2026-11-07 fetch:
`artifacts/test-results/model-download-run-2026-11-07.csv`,
`artifacts/test-results/models-checksums.sha256`, `docs/MODELS.md`.

## Test corpora used for our own measurements

- Demo-stress **headline** audio is **Mozilla Common Voice 17.0 Russian**
  (test split, **CC0-1.0**), fetched via `scripts/fetch_cv17_ru.py` from the
  `fsicoli/common_voice_17_0` packaging of https://commonvoice.mozilla.org/.
  Gold transcripts are the corpus `sentence` field. Large clips stay gitignored.
- Historical eSpeak NG SYNTHETIC baseline (GPLv3 renderer, not vendored) is
  superseded; measured files live under
  `artifacts/eval/demo-stress/espeak-baseline/`.
- DEMO_STAGE6 (`scripts/generate_demo_stage6.py`, frozen fixture @ `a8b39d5a`)
  is our own synthesized far-field mix — no third-party audio.

## Deliberately not used

Picovoice Falcon/Cheetah/Leopard (AccessKey licensing), any cloud ASR,
diarization or text API (Yandex SpeechKit, Sber SaluteSpeech, OpenAI Whisper
API), whisper.cpp as the primary engine, and OEM `SpeechRecognizer` as a
dependency. The brief forbids audio leaving the device, so these are excluded
by design, not by oversight.
