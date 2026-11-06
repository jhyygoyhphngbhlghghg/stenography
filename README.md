# Нейро-стенографист

Team **MIK Coders** · Nuclear IT Hack · Hex.Team case «Нейро-стенографист».

Android app that turns a conversation recording into a structured dialogue transcript **on device**: speakers, timestamps, text. Offline stenography path is airplane-mode (one file: VAD → diarize → ASR). Present-connect demo is **Path B**: ≤20 **separate** live streams into local Zipformer-ru, not one mixed room mic. Under edge, the only surviving multi-talker claim is stream tracking **20/20**. **WORST_CASE WER/DER are FAIL** (PR #4 @ `9908353d`: WER main **0.9130**, DER **1.2985**). Do **not** claim clean WER/DER on WORST_CASE or EDGE_*. Killers: overlap/bleed, noise false alarms, bad mic. Reverb alone does not collapse Path B. **Do not claim in slides** otherwise. One-room cocktail diarization of ≥20 talkers is a measured fail (see `docs/ARCHITECTURE.md`).

This Origin repo is the overnight build. Layout follows [hacathon_template](https://github.com/grigorevmp/hacathon_template) so it can later be mirrored to GitHub `stenography`.

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

Schema notes: `docs/JSON.md`.

## Layout

| Path | Role |
|---|---|
| `apk/` | Built APK drop (do not commit binaries) |
| `artifacts/` | Demo materials + own-test tables (template) |
| `config/` | Local env examples, flavor flags |
| `docs/` | Architecture, limits, install, PresentConnect contract |
| `src/android/` | Kotlin + Compose application module |
| `scripts/` | Model download (weights gitignored) |
| `samples/` | Short demo clips (optional; large wav gitignored) |

## Stack (locked)

- Kotlin + Jetpack Compose, minSdk 26, demo on modern Android
- sherpa-onnx: Zipformer-ru int8 primary · Whisper tiny backup · Vosk emergency
- Silero VAD · OfflineSpeakerDiarization
- Local LLM post-pass module (stub + TODO for weights: punctuation/cleanup)
- No whisper.cpp primary · no Picovoice · no cloud ASR

Two product flavors, one codebase: **stenographer** (offline file/mic) and **presentConnect** (talks to the presentation backend).

## Build (Android)

```bash
cd src/android
./gradlew :app:assembleStenographerDebug
# APK → app/build/outputs/apk/stenographer/debug/
# copy to ../../apk/ when shipping
```

Models are **not** in git. Before an airplane-mode demo:

```bash
./scripts/download-models.sh   # then adb push into app filesDir, or pack assets
```

## Docs

- `docs/ARCHITECTURE.md` — pipeline
- `docs/LIMITATIONS.md` — honest limits (Eval-measured Path A/B only; no invented WER/RTF)
- `docs/JSON.md` — export schema
- `docs/PRESENT_CONNECT.md` — WebSocket/HTTP contract for the web team
- `docs/INSTALL.md` — install + use
- `docs/TEST_RESULTS.md` — own tests

## Modes

1. **Offline stenography** — one recording: import/record → VAD → diarize → ASR → LLM stub → list → export JSON. No network. This is not the 20-talker room-mix demo.
2. **Present-connect (Path B)** — phone is the on-device worker: ≤20 **separate** streams (`speaker_id` ← `stream_id`), each PCM → VAD → ASR, merge by time. Network required. Do not mix audience mics into one stream. Under edge only **20/20** stream tracking survives. **WORST_CASE FAIL:** WER main **0.9130**, DER **1.2985** (PR #4 @ `9908353d`). Do not claim clean WER/DER on WORST_CASE or EDGE_*. See `docs/PRESENT_CONNECT.md`.

Presentation web FE/BE is a **separate** track (designer + Артём/Пётр). This repo owns the Android worker and the API contract they must implement against.
