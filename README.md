# Нейро-стенографист

Team **MIK Coders** · Nuclear IT Hack · Hex.Team case «Нейро-стенографист».

Android app that turns a conversation recording into a structured dialogue transcript **on device**: speakers, timestamps, text. **Live demo claim = DEMO_STAGE6** (Eval owns the wavs): one phone mic, 6 RU speakers at 3–6 m, not studio. Offline stenography path is airplane-mode. PresentConnect Path B ≤20 is an **optional** network mode, not the slide claim.

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
- `docs/LIMITATIONS.md` — honest limits (no invented WER/RTF)
- `docs/JSON.md` — export schema
- `docs/PRESENT_CONNECT.md` — WebSocket/HTTP contract for the web team
- `docs/INSTALL.md` — install + use
- `docs/TEST_RESULTS.md` — own tests

## Modes

1. **Offline stenography / DEMO_STAGE6** — import or in-app mic → per-turn far-field gain → VAD → diarize → ASR → LLM stub → list → export JSON. No network. This is the live demo path.
2. **Present-connect (optional)** — phone is the on-device worker: receive ≤20 audio streams from the presentation site, return transcripts. Network required. **Not** the slide claim. See the contract doc.

Presentation web FE/BE is a **separate** track (designer + Артём/Пётр). This repo owns the Android worker and the API contract they must implement against.
