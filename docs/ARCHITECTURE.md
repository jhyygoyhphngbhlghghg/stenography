# Архитектура решения

## Контекст

Hex.Team case «Нейро-стенографист»: Android-приложение локально превращает аудиозапись разговора в структурированную расшифровку (кто сказал, когда, что). Облачный ASR запрещён. Демо офлайн-пути — в авиарежиме. Отдельный сетевой режим **PresentConnect** обслуживает презентационный сайт (до 20 потоков); это не подмена офлайн-ASR.

Команда: **MIK Coders**. Этот репозиторий — Android-«backend» (обработка на телефоне) + контракт API для web FE/BE.

## Компоненты

```
Audio (SAF import / AudioRecord / Path B per-stream PCM)
        │
        ▼
  decode → 16 kHz mono PCM
        │
        ▼
  quality gate                 # clipped/non-finite → BAD_AUDIO (no ASR hallucination)
        │                      # cite-only bar: bad mic WER 1.0
        ▼
  energy gate                  # hush / stationary hiss → skip diar/ASR
        │                      # cite-only bar: noise false alarms DER 1.70
        ▼
  Silero VAD + endpoint trim   # drop silence & quieter bleed crops
        │                      # cite-only bar: EDGE_overlap WER 0.74 DER 0.99
        ▼
  OfflineSpeakerDiarization    # pyannote seg + embedding + cluster (speech-masked)
        │
        ▼
  ASR per (speaker, t0, t1)    # Zipformer-ru int8 primary
        │                      # Whisper tiny backup; Vosk emergency
        ▼
  Local LLM post-pass          # punctuation / cleanup (stub + TODO weights)
        │
        ▼
  Dialogue JSON + Compose list + share/export
```

Worst-case Eval is the regression bar. Reverb was fine in that suite — not hardened here. Harness CSV stays `pending` until a real wav/ref/hyp run (do not copy these H1 cites into results.csv).

PresentConnect (flavor `presentConnect`, network mode) is **Path B** (locked for demo):

```
Browser mic / stream_id s01 ──PCM──► isolated buffer s01 ──► Zipformer-ru + diar (this stream only)
Browser mic / stream_id s02 ──PCM──► isolated buffer s02 ──► Zipformer-ru + diar (this stream only)
 … up to 20 …
Browser mic / stream_id s21 ──► OVER_CAPACITY (not accepted, no PCM)

Web FE maps stream_id → seat/QR. speaker_id = diarization *inside* that stream.
```

Hard cap 20. Each `stream_id` keeps its **own** PCM path into the on-device pipeline. **Do not mix PCM across streams** before ASR/diar (no cocktail / room-mic mixdown).

**Path A rejected** (one mixed room mic / 20 talkers summed into one buffer → one ASR/diar). H1 reported (cite only, not recomputed): Path A mix DER 0.95, 1/20 FAIL; Path B 20/20, DER 0.235, main WER 0.087. Harness rows in `docs/TEST_RESULTS.md` stay `pending` until a real wav/ref/hyp run.

Checkpoint polling (clients follow presenter) lives on the **web BE**, not in this app. See `docs/PRESENT_CONNECT.md`.

## Основные технические решения

| Слой | Решение | Зачем |
|---|---|---|
| UI | Kotlin + Jetpack Compose, minSdk 28 | Brief 26–28; sherpa Java demo / AAR path uses 28 |
| Flavors | `stenographer` / `presentConnect` | Один код, флаг `BuildConfig.PRESENT_CONNECT` |
| Runtime | sherpa-onnx Android AAR v1.13.5 (GitHub Releases; not unpublished JitPack as the sole path) | ASR + VAD + diarization в одном runtime |
| PresentConnect | **Path B**: ≤20 isolated PCM streams | Path A cocktail mix отклонён |
| ASR primary | Zipformer-ru int8 (~74 MB listing) | RU-first, мобильный размер |
| ASR backup | Whisper tiny multilingual (sherpa) | RU+EN; не whisper.cpp |
| ASR emergency | Vosk small-ru | Если sherpa не встаёт на устройстве |
| VAD | Silero via sherpa | Нарезка до ASR; обязательна на длинном аудио |
| Diarization | sherpa `OfflineSpeakerDiarization` | Сначала диаризация, потом ASR кропа |
| LLM | Модуль `LocalLlmPostPass` | Чекбокс брифа; веса TODO, identity-stub до поставки GGUF |
| I/O | MediaExtractor/MediaCodec → PCM; SAF import; AudioRecord | M4A/MP3/WAV + запись в приложении |
| Export | JSON schema брифа; TXT как бонус | `docs/JSON.md` |
| Модели | Не в git; `scripts/download-models.sh` + filesDir/assets | APK остаётся собираемым без весов |

Если модели не найдены, pipeline **явно** идёт в stub-режим (помечается в UI). Stub не выдаётся за ASR на защите.

## Зависимости

- AndroidX Compose / Material3 / Activity / DocumentFile
- kotlinx.serialization (JSON)
- OkHttp (только PresentConnect / опциональный download)
- sherpa-onnx Android AAR from GitHub Releases v1.13.5 (JitPack `v2.0.1` unpublished; `scripts/fetch-sherpa-aar.sh`)
- (emergency) Vosk Android — подключается только если primary/backup недоступны

Явно **не** используем: Picovoice (AccessKey), whisper.cpp как primary, OEM `SpeechRecognizer`, облачные SpeechKit/Whisper API.

## Безопасность и приватность

- Офлайн-путь не отправляет аудио с устройства.
- PresentConnect шлёт PCM на телефон (worker) через BE команды; аудио не уходит в облачный ASR.
- Модели и веса — на устройстве к моменту проверки.
- Секреты не коммитим (`.env` в gitignore).
