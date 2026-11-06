# Нейро-стенографист

Team **MIK Coders** · Nuclear IT Hack · Hex.Team case «Нейро-стенографист».

Android app that turns a conversation recording into a structured dialogue transcript **on device**: speakers, timestamps, text. **Presentation claim = DEMO_STAGE6:** 6 on-stage RU speakers, one phone mic, scene 3–6 m, sequential turns with light end overlap, large room, medium babble/HVAC, phone-mic band-limit / compression / distance / reverb. Pipeline: VAD → diarize → Zipformer-ru → **required local LLM**. First measure: aggregate **WER 0.73 / DER 0.52**. **Ideal recognition only at 3 m.** Gain series agg WER **0.73 / 0.70 / 0.73** did not recover the room. Not a studio. Do not cite a lower aggregate. PresentConnect **Path B** (≤20 separate streams) is an **optional** network mode, not this claim. Its ungated edge tracking is 20/20; **WORST_CASE WER/DER are FAIL** (PR #4 @ `9908353d`: WER main **0.9130**, DER **1.2985**). Do not cite those as DEMO_STAGE6. One-room cocktail diarization of ≥20 talkers is a measured fail (see `docs/ARCHITECTURE.md`).

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
- Local LLM post-pass is **required**. It may clean only to match spoken punctuation / intonation. Textbook orthography is not the gold. Weights are assets-bundled for hand-in. `IdentityLlmPostPass` in code is an Impl gap, not the accepted path
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
./scripts/download-models.sh   # dev only; hand-in APK bundles assets/models (download is UX karma)
```

## Docs

- `docs/ARCHITECTURE.md` — pipeline
- `docs/LIMITATIONS.md` — DEMO_STAGE6 WER 0.73 / DER 0.52 (ideal only at 3 m); Path A/B numbers stay measured
- `docs/JSON.md` — export schema
- `docs/PRESENT_CONNECT.md` — WebSocket/HTTP contract for the web team
- `docs/INSTALL.md` — install + use
- `docs/TEST_RESULTS.md` — own tests

## Modes

1. **DEMO_STAGE6 / offline stenography** — one far-field recording: import/record → VAD → diarize → ASR → **local LLM** → list → export JSON. No network on the check APK (weights are assets-bundled). Scene is 6 RU speakers, one phone mic. First measure WER **0.73** / DER **0.52**, ideal only at 3 m. This is not the 20-talker room-mix.
2. **Present-connect (Path B), optional** — not the presentation claim. Phone is the on-device worker: ≤20 **separate** streams (`speaker_id` ← `stream_id`), each PCM → VAD → ASR, merge by time. Network required. Do not mix audience mics into one stream. Under edge only **20/20** stream tracking survives. **WORST_CASE FAIL:** WER main **0.9130**, DER **1.2985** (PR #4 @ `9908353d`). Do not claim clean WER/DER on WORST_CASE or EDGE_*. See `docs/PRESENT_CONNECT.md`.

Presentation web FE/BE is a **separate** track (designer + Артём/Пётр). This repo owns the Android worker and the API contract they must implement against.
