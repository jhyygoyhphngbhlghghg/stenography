# Архитектура решения

## Контекст

Hex.Team case «Нейро-стенографист»: Android-приложение локально превращает аудиозапись разговора в структурированную расшифровку (кто сказал, когда, что). Облачный ASR запрещён. Демо офлайн-пути — в авиарежиме. Отдельный сетевой режим **PresentConnect** обслуживает презентационный сайт (до 20 потоков); это не подмена офлайн-ASR.

Команда: **MIK Coders**. Этот репозиторий — Android-«backend» (обработка на телефоне) + контракт API для web FE/BE.

## Компоненты

```
Audio (SAF import / AudioRecord)
        │
        ▼
  decode → 16 kHz mono PCM
        │
        ▼
  Silero VAD (sherpa-onnx)     # chunk speech; drop silence
        │
        ▼
  OfflineSpeakerDiarization    # pyannote seg + embedding + cluster
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

**Live demo claim = DEMO_STAGE6:** one phone mic, 6 RU talkers at 3–6 m, far-field gain + VAD/diar/ASR on device. PresentConnect Path B ≤20 is **optional** (flavor `presentConnect`); not the slide claim.

PresentConnect (flavor `presentConnect`, network mode) is **Path B** (kept, unused for the live claim):

```
Browser mics (≤20) ── per-stream PCM ──► presentation BE ──WS──► phone worker
                                                                  │
                                                    Path B: never mix PCM
                                                    s01→speaker_1 … s20→speaker_20
                                                    ASR each stream’s buffer
                                                                  │
                         display ◄── transcript.final (stream_id on wire only)
                         GET transcript = brief {speaker_id,start_ms,end_ms,text}
```

Path B is **not** a Path A cocktail mix. Eval (PR #4, cite only) on `mix_20talker_per_stream`: DER 0.235, WER_main 0.087, 20/20 speakers. Mapping: `samples/demo-stress/channels.json`.

Single-mic DEMO_STAGE6 PCM: **per-turn gain is frozen** (`FarFieldGain`; Eval per-turn @ `54177a1e` islands already ≥0.08 FS so AGC is a no-op — do not retune). Then **phone-distance EQ** (`PhoneDistanceEq`: 1600 Hz high-shelf on far islands only; near seat RMS ≥ 0.12 FS is identity). CLIPPED / BAD_MIC still hard-skip on raw PCM. Bound-main energy / band-limit stay soft (`SOFT_LOW` / `BAND_LIMITED`).

Checkpoint polling (clients follow presenter) lives on the **web BE**, not in this app. See `docs/PRESENT_CONNECT.md`.

## Основные технические решения

| Слой | Решение | Зачем |
|---|---|---|
| UI | Kotlin + Jetpack Compose, minSdk 26 | Требование кейса; UI намеренно минимальный |
| Flavors | `stenographer` / `presentConnect` | Один код, флаг `BuildConfig.PRESENT_CONNECT` |
| Runtime | sherpa-onnx (JitPack AAR / JNI) | ASR + VAD + diarization в одном runtime |
| ASR primary | Zipformer-ru int8 (~74 MB listing) | RU-first, мобильный размер |
| ASR backup | Whisper tiny multilingual (sherpa) | RU+EN; не whisper.cpp |
| ASR emergency | Vosk small-ru | Если sherpa не встаёт на устройстве |
| VAD | Silero via sherpa | Нарезка до ASR; обязательна на длинном аудио |
| Diarization | sherpa `OfflineSpeakerDiarization` | Сначала диаризация, потом ASR кропа |
| LLM | Модуль `LocalLlmPostPass` | Чекбокс брифа; веса TODO, identity-stub до поставки GGUF |
| I/O | MediaExtractor/MediaCodec → PCM; SAF import; AudioRecord | M4A/MP3/WAV + запись в приложении |
| Export | JSON schema брифа; TXT как бонус | `docs/JSON.md` |
| Модели | Gitignored; **judge APK embeds** Zipformer-ru + VAD + diar (`assets/models` → filesDir on first launch). Slim build type = no weights. | Hex.Team cold install, zero network |

Если модели не найдены, pipeline **явно** идёт в stub-режим (помечается в UI). Stub не выдаётся за ASR на защите.

## Зависимости

- AndroidX Compose / Material3 / Activity / DocumentFile
- kotlinx.serialization (JSON)
- OkHttp (только PresentConnect / опциональный download)
- sherpa-onnx Android AAR (`com.github.k2-fsa.sherpa-onnx:sherpa-onnx`)
- (emergency) Vosk Android — подключается только если primary/backup недоступны

Явно **не** используем: Picovoice (AccessKey), whisper.cpp как primary, OEM `SpeechRecognizer`, облачные SpeechKit/Whisper API.

## Безопасность и приватность

- Офлайн-путь не отправляет аудио с устройства.
- PresentConnect шлёт PCM на телефон (worker) через BE команды; аудио не уходит в облачный ASR.
- Модели и веса — на устройстве к моменту проверки.
- Секреты не коммитим (`.env` в gitignore).
