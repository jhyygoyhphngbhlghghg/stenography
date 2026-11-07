# Нейро-стенографист

Team **MIK Coders** · Nuclear IT Hack · Hex.Team case «Нейро-стенографист».

Android app that turns a conversation recording into a structured dialogue
transcript **on device**: who spoke, when, and what they said. Every stage —
VAD, diarization, ASR, the LLM post-pass — runs on the phone; airplane mode is
the demo mode. PresentConnect Path B (≤20 live streams from the presentation
site) is a separate optional network codepath, **not** the slide.

Layout follows [hacathon_template](https://github.com/grigorevmp/hacathon_template),
so this repository is the hand-in as-is.

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

Schema notes: `docs/JSON.md`. Extra or missing keys are rejected by
`python3 scripts/verify-transcript-json.py <export.json>`.

## Mandatory requirements, and where they are met

| Hex.Team brief §«Обязательные требования» | In this app |
|---|---|
| runs under Android OS | Kotlin + Jetpack Compose, minSdk **28**, compileSdk 35, two product flavors — `src/android/app/` |
| uses a **local LLM** | `GgufLlmPostPass` runs Qwen2-0.5B-Instruct Q4_0 through llama.cpp JNI on-device; the prompt asks only for spoken-intonation punctuation, and `IntonationPunct` merges the result so the model can never strip or textbook-rewrite marks — `llm/LocalLlmPostPass.kt`, `llm/OnDeviceLlmRunner.kt`, `llm/IntonationPunct.kt` |
| imports audio from device storage | SAF `OpenDocument` (`audio/*`) → `AudioDecoder` decodes WAV/M4A/MP3 to 16 kHz mono PCM — `audio/AudioDecoder.kt` |
| converts speech to text | sherpa-onnx `OfflineRecognizer`, **Zipformer-ru int8** primary; Whisper tiny backup, Vosk small-ru emergency — `pipeline/SherpaStenoPipeline.kt` |
| detects and separates speakers automatically | sherpa `OfflineSpeakerDiarization` (pyannote segmentation-3.0 + 3D-Speaker ERes2Net + FastClustering) capped at the scene's K, VAD holes filled by `VadDiarReconcile` — `pipeline/VadDiarReconcile.kt`, `pipeline/FarFieldScene.kt` |
| forms timestamps per utterance | `start_ms` / `end_ms` per diarized crop, in ms |
| displays the transcript | Compose list with speaker chips, per-stage progress, error and mic-quality flags — `ui/StenoApp.kt` |
| exports the result to a file | **Сохранить JSON** writes through SAF `CreateDocument`; **Поделиться** offers the same payload to a chooser — `ui/StenoApp.kt` |

Nothing leaves the device: no cloud ASR, no cloud diarization or LLM, no
remote compute, and no pre-baked transcript standing in for the model. See
`docs/LIMITATIONS.md` §«Что мы не делаем».

## The claim, measured

Locked scoring rule (Mikhail Grigorev, Hex.Team expert Q&A, 2026-11-06):
punctuation counts inside transcription accuracy, commas follow the speaker's
intonation rather than textbook orthography, and `ё`/`е` is free. Every number
below uses that normalize (`scripts/eval_metrics.py`: keep punctuation, fold ё→е).

**DEMO_STAGE6** is the presentation claim: 6 Russian speakers on stage, **one**
phone microphone, seats 3–6 m, sequential turns with light end overlap, large
room, medium babble/HVAC. Ideal recognition — not a studio.

| metric | value | source |
|---|---|---|
| Hex keep-punct WER, baseline ingest | **0.7794** (53/68) | `artifacts/eval/demo-stress/metrics.csv` |
| Hex keep-punct WER after diar hard-cap K≤6 | **0.6471** (44/68) | `artifacts/eval/demo-stress/DEMO_STAGE6_impl_hardcap6.json` |
| DER / JER | **0.2760** / **0.5454** | same run |
| speakers in hypothesis | **6 / 6** (was 12 / 6 over-split) | `DEMO_STAGE6_impl_hardcap6.speakers.json` |
| RTF (host Linux, sherpa-onnx 2.0.1) | **0.1173** (wall 4 659 ms / 39 718 ms audio) | `DEMO_STAGE6_impl_hardcap6.wall_ms` |
| per-seat WER | 3.0 m 0.21 · 3.6 m 0.33 · 4.2 m 1.00 · 4.8 m 0.90 · 5.4 m 1.00 · 6.0 m 0.69 | `docs/TEST_RESULTS.md` |

Honest gaps, phrased the way we will say them at defense: the far seats are
still weak, speaker_5 is not recovered, WER is nowhere near 0, the K≤6 row is
measured at Impl `3c3f9ce` while the newer tip `ab20d83` has not been
re-measured, and the on-device sideload + live-room probe still needs the demo
phone. Do **not** cite `WER_main 0`, do not cite WORST_CASE / EDGE_* as clean,
and do not present Path B ≤20 as the demo. Full wording: `docs/LIMITATIONS.md`.

## Build

Needs JDK 17+ and Android SDK 35. Copy `src/android/local.properties.example`
to `src/android/local.properties` and set `sdk.dir` (that file is gitignored).

```bash
cd src/android
./gradlew :app:testStenographerDebugUnitTest    # 126 JVM unit tests, 21 classes
./gradlew :app:assembleStenographerDebug         # check APK: ASR+VAD+diar inside
./gradlew :app:assembleStenographerSlim          # same app, no weights (UX karma)
./gradlew :app:assemblePresentConnectDebug       # optional network worker
```

Measured here 2026-11-07 (gradle 8.11.1, AGP 8.7.3, Windows):

| APK | bytes | MiB |
|---|---|---|
| `app-stenographer-debug.apk` — the check APK | 220 529 139 | 210.3 |
| `app-presentConnect-debug.apk` | 220 401 103 | 210.2 |
| `app-stenographer-slim.apk` — no weights | 97 599 021 | 93.1 |
| `app-stenographer-debug-embedLlm.apk` — also ships the GGUF | 573 498 719 | 546.9 |

The check APK carries 119 466 051 bytes of ASR/VAD/diar assets (7 files),
which unpack into app-private `filesDir/models` on first launch — so a fresh
install plus airplane mode is enough for the offline run. Sha256 of every
artifact: `apk/README.md`.

Weights are never in git. Fetch them once on Wi-Fi:

```bash
./scripts/download-models.sh   # Zipformer-ru + Silero + diar + Qwen2 GGUF, ~470 MB
```

The local LLM is a TZ requirement, so the hand-in APK should contain it too —
one flag packs the 352 969 408-byte GGUF through the same assets mechanism:

```bash
./gradlew :app:assembleStenographerDebug -PembedLlm=true
```

Without that flag the LLM weights have to be pushed
(`adb push models/llm /sdcard/Android/data/team.mikcoders.neurosteno/files/models/`)
before the airplane-mode run; otherwise the app falls back to
`llm-weights-missing (intonation punct only)` and the UI says so in red.
Per-file sizes and sha256: `docs/MODELS.md`.

## Hand-in artifacts

The four APKs are published as release assets (git cannot carry files over
100 MiB, and neither can the reviewers' downloads be expected to):
**[v0.1.0-stage6](https://github.com/jhyygoyhphngbhlghghg/stenography/releases/tag/v0.1.0-stage6)** — check APK 220 529 139 B, LLM-inclusive APK
573 498 719 B, PresentConnect 220 401 103 B, slim 97 599 021 B, each with its
sha256 in `apk/README.md`.

## Install and run (what the experts do)

`docs/INSTALL.md` is the hand-in instruction. Short version: install the APK →
open it once (assets unpack; no network needed afterwards) → airplane mode →
**Импорт** a dialogue or **Запись** → **Расшифровать** → read speakers and
timestamps → **Сохранить JSON**.

## Reproducing the numbers

```bash
./scripts/ci-check.sh                                   # schema + harness + optional gradle
python3 -m unittest eval.metrics.test_wer               # A-12 harness, stdlib only (18 tests)
python3 -m unittest discover -s scripts -p 'test_*.py'  # 61 host-eval tests
```

The 20-talker host stress (secondary regression, Common Voice 17 RU / CC0, no
KVM/ADB needed):

```bash
python3 scripts/fetch_cv17_ru.py
python3 scripts/generate_demo_stress_mix.py
python3 scripts/eval_harness.py --suite demo-stress --run-host
```

The primary STAGE6 chain (fixture + gold frozen @ `a8b39d5a`, so a judge can
replay the claim instead of trusting a table):

```bash
python3 scripts/generate_demo_stage6.py
python3 scripts/host_asr_diar.py --test-id DEMO_STAGE6_impl_hardcap6
python3 scripts/eval_metrics.py --test-id DEMO_STAGE6_impl_hardcap6
```

## Stack (locked)

- Kotlin + Jetpack Compose, minSdk **28** (brief allows 26–28; sherpa's Java demo is 28)
- sherpa-onnx **v2.0.1** Android AAR from JitPack — 49 441 341 bytes, sha256
  `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`, re-checkable
  with `scripts/inspect-sherpa-aar.sh`. An earlier note in this file claimed the
  coordinate was unpublished; that was wrong and is corrected here.
- **Zipformer-ru int8** primary ASR · Whisper tiny backup · Vosk small-ru emergency
- Silero VAD · `OfflineSpeakerDiarization` (pyannote segmentation-3.0 + 3D-Speaker ERes2Net + FastClustering)
- llama.cpp JNI (`dev.ffmpegkit-maintained:llama-android:0.1.1`) + Qwen2-0.5B-Instruct Q4_0 GGUF
- no whisper.cpp as a primary engine · no Picovoice · no OEM `SpeechRecognizer` dependency · no cloud ASR or LLM

Two product flavors, one codebase: **stenographer** (offline file/mic) and
**presentConnect** (≤20 per-stream isolated ASR against the presentation
backend). The web FE/BE is a separate track; this repo owns the Android worker
and the contract in `docs/PRESENT_CONNECT.md`.

## Layout

| Path | Role |
|---|---|
| `apk/` | built-APK drop + which artifact to install (binaries gitignored; publish as a Release asset) |
| `artifacts/demo/` | demo materials and the video slot |
| `artifacts/test-results/` | `results.csv` — one row per run, each labelled with its provenance `status` |
| `artifacts/eval/` | STAGE6 + demo-stress measured JSON/CSV and the frozen listen clips |
| `config/` | env example, flavor flags |
| `docs/` | architecture, models, install, limitations, JSON, PresentConnect contract, gates |
| `eval/` | A-12 three-clip WER/RTF harness + its gate |
| `scripts/` | model download, host-eval mirrors of the Kotlin pipeline, JSON verifier, sideload probe |
| `src/android/` | Kotlin + Compose module `:app` |
| `samples/` | three fixed clip slots + `demo-stress/` mixes (large audio gitignored) |

## Docs index

- `docs/ARCHITECTURE.md` — pipeline, flavors, diar cap, scoring lock
- `docs/MODELS.md` — files, sizes, hashes, licenses
- `docs/INSTALL.md` — build, install, use, airplane-mode run
- `docs/LIMITATIONS.md` — what fails and what we refuse to claim
- `docs/TEST_RESULTS.md` — every measured row, with the commit it was measured at
- `docs/JSON.md` — export schema · `docs/PRESENT_CONNECT.md` — optional Path B contract
- `docs/GATE.md`, `eval/GATE.md` — what must pass before defense
