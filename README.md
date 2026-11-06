# Нейро-стенографист

Team **MIK Coders** · Nuclear IT Hack · Hex.Team case «Нейро-стенографист».

Android app that turns a conversation recording into a structured dialogue transcript **on device**: speakers, timestamps, text. Offline stenography path is airplane-mode. Present-connect path is a separate network mode for the presentation website (≤20 live streams).

This Origin repo is the overnight build. Layout follows [hacathon_template](https://github.com/grigorevmp/hacathon_template) so it can later be mirrored to GitHub `stenography`.

ASR / VAD / diarization runtime is **[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)** (k2-fsa / Xiaomi, Apache-2.0). We consume the Android AAR from GitHub Releases (the file [jitpack.yml](https://github.com/k2-fsa/sherpa-onnx/blob/master/jitpack.yml) wget's). JitPack coordinate `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1` is unpublished; `scripts/fetch-sherpa-aar.sh` pulls `v1.13.5`. See `NOTICE.md`.

## JSON export (mandatory)

```json
{
  "transcript": [
    {
      "speaker_id": "speaker_1",
      "start_ms": 1250,
      "end_ms": 4380,
      "text": "Добрый день!"
    }
  ]
}
```

Schema notes: `docs/JSON.md`. Extra keys are rejected by `scripts/verify-transcript-json.py`.

## Layout

| Path | Role |
|---|---|
| `apk/` | Built APK drop (do not commit binaries) |
| `artifacts/` | Demo materials + own-test tables (template) |
| `config/` | Local env examples, flavor flags |
| `docs/` | Architecture, limits, install, PresentConnect contract |
| `src/android/` | Kotlin + Compose application module |
| `scripts/` | Model download, AAR fetch, JSON verify, eval harness |
| `samples/` | Three fixed eval clip slots (wav gitignored) |

## Stack (locked)

- Kotlin + Jetpack Compose, minSdk **28** (brief 26–28; sherpa Java demo is 28)
- sherpa-onnx: Zipformer-ru int8 primary · Whisper tiny backup · Vosk emergency
- Silero VAD · OfflineSpeakerDiarization
- Local LLM post-pass: heuristic punctuation now; GGUF TODO (no cloud LLM)
- No whisper.cpp primary · no Picovoice · no cloud ASR

Two product flavors, one codebase: **stenographer** (offline file/mic) and **presentConnect** (talks to the presentation backend).

## Build (Android)

```bash
cd src/android
./gradlew :app:assembleDebug          # both flavors
./gradlew :app:assembleStenographerDebug
# APK → app/build/outputs/apk/stenographer/debug/
# copy to ../../apk/ when shipping
```

Needs JDK 17+, Android SDK compileSdk 35 (`src/android/local.properties.example`). First assemble downloads the ~47 MB sherpa-onnx AAR into `app/libs/` (gitignored).

Models are **not** in git. Before an airplane-mode demo:

```bash
./scripts/download-models.sh   # then adb push into app filesDir, or pack assets
```

CI-ish (no invented WER):

```bash
./scripts/ci-check.sh
```

## Docs

- `docs/ARCHITECTURE.md` — pipeline
- `docs/LIMITATIONS.md` — honest limits (no invented WER/RTF)
- `docs/JSON.md` — export schema
- `docs/PRESENT_CONNECT.md` — WebSocket/HTTP contract for Артём/Петя
- `docs/INSTALL.md` — install + use
- `docs/TEST_RESULTS.md` — own tests
- `docs/MODELS.md` — weights (gitignored) + HF filenames

## Modes

1. **Offline stenography** — import/record → VAD → diarize → ASR → LLM stub → list → export JSON. No network.
2. **Present-connect** — phone is the on-device worker: receive ≤20 audio streams from the presentation site, return transcripts. Network required. See the contract doc.

Presentation web FE/BE is a **separate** track (designer + Артём/Пётр). This repo owns the Android worker and the API contract they must implement against.
