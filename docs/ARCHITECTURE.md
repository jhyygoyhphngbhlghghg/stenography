# Архитектура решения

## DEMO LOCK (H1 overnight)

**Демо-архитектура = Path B.** PresentConnect принимает ≤20 **отдельных** mic/audio-потоков и гоняет каждый в локальный Zipformer-ru (опционально — диаризация *внутри* одного потока). Это **не** один смешанный микрофон комнаты.

Path A (one-room cocktail: 20 говорящих в **один** поток, потом кластеризация) — **задокументированный hard fail**. В архитектуре и на слайдах **не** утверждать, что cocktail-диаризация одной комнаты работает для ≥20 говорящих.

Первая таблица — **ранний** Eval-measured прогон (H1 / Eval Owner, [PR #4](https://cursor.com/codebase/johann-schilling/neuro-stenographer/pull/4), real Common Voice 17 RU, host sherpa-onnx), clean per-stream и Path A. Новые числа не выдумывать. DER Path B в том замере также **0.235** (в таблице округление **0.24**). Эти WER/DER **не** переносить на WORST_CASE и EDGE_*.

| path | speakers recovered | DER | main WER | RTF |
|---|---|---|---|---|
| A one-room cocktail mix | **1/20 FAIL** | 0.95 | 1.00 | 0.18 |
| **B per-stream PresentConnect** | **20/20** | **0.24** (also 0.235) | **0.087** | 2.65 |
| dry main alone | 1/1 | 0.02 | 0.174 | 0.06 |

Overnight-клипы без перекрытия (тот же замер): WER ~3.5% / 0% / 0%. Это тоже clean/no-overlap, не edge suite.

**Claim lock (edge).** Под edge у Path B остаётся только **stream tracking 20/20** (hyp/ref). **WER/DER under WORST_CASE are FAIL.** Clean WER/DER (и 0.087 / 0.24 раннего прогона, и CLEAN-строка suite) на WORST_CASE и EDGE_* **не** заявлять. **Killers:** overlap/bleed, noise false alarms, bad mic. Reverb alone does **not** collapse Path B.

Таблица ниже — Eval PR #4 @ `9908353d`, скопирована как замерена. Фикстуры **не** подкручивали после FAIL. Слайды: WORST_CASE WER≈0.91, DER≈1.30 FAIL.

| test_id | DER 250ms | JER | WER main | RTF | hyp/ref |
|---|---|---|---|---|---|
| CLEAN Path B | 0.0913 | 0.1840 | 0.0870 | 0.1212 | 20/20 |
| EDGE_reverb | 0.0849 | 0.1756 | 0.1304 | 0.1263 | 20/20 |
| EDGE_overlap | 0.9936 | 0.5476 | 0.7391 | 0.2430 | 20/20 |
| EDGE_noise | 1.7005 | 0.5675 | 0.1739 | 0.2602 | 20/20 |
| EDGE_badmic | 0.6569 | 0.2863 | 1.0000 | 0.1910 | 20/20 |
| **WORST_CASE** | **1.2985** | **0.5981** | **0.9130** | **0.2907** | 20/20 |

Overlap real: max 12 concurrent; 91% of speech frames ≥2 speakers. Main vs bleed: +3.0 dB (EDGE_overlap) / +1.5 dB (WORST_CASE). WORST_CASE main hyp collapses to one ~18s blob (`хочу говорить`). DER>1 = false-alarm time > ref speech. Запрещены «should be fine», «robust», «handles noisy rooms», «graceful under echo». **Do not claim in slides** clean WER/DER на edge.

**STOP — hard `BAND_LIMITED` on bound seats is REJECTED** (toxic measured). Gate v2 @ Eval PR #4 `bed868b3` (note `ffe53ba4`) hard-drops bound `s01`..`s20`: EDGE_noise drops main **s01** (hyp **19/20**); EDGE_badmic **6/20**; WORST_CASE **0/20**, empty `{transcript:[]}`. Those hyp counts are not the claim. Bound seats **soft-flag only** (still ASR), same spirit as `SOFT_LOW`. Never hard-drop main/`s01`. Hard `BAND_LIMITED` only for unbound/anonymous (Platform, PR #3). **Ungated** stream tracking **20/20** @ `9908353d` stands. Ungated WORST_CASE stays WER main **0.9130** / DER **1.2985**. Do not claim gated_v2 hyps or clean WER/DER on WORST_CASE. Fixtures not retuned. Hold merge; #2 first.

**Контракт Path B.** Каждый `stream_id` обрабатывается **независимо**: PCM → VAD → ASR. Метка по умолчанию: **`speaker_id` ← `stream_id`** (или стабильная карта stream→speaker / `speaker_hint`). Диаризация опциональна и только *внутри* одного потока — не mix-then-cluster по комнате. Реплики сессии склеиваются по времени. Жёсткий потолок — **20** одновременных потоков; 21-й отклоняется (`OVER_CAPACITY`).

Офлайн-путь (файл или микрофон приложения, **одна** запись) остаётся: VAD → `OfflineSpeakerDiarization` → ASR. Это не PresentConnect и не замена Path B.

## Контекст

Hex.Team case «Нейро-стенографист»: Android-приложение локально превращает аудиозапись разговора в структурированную расшифровку (кто сказал, когда, что). Облачный ASR запрещён. Демо офлайн-пути — в авиарежиме. Отдельный сетевой режим **PresentConnect** — демо Path B: до 20 **отдельных** потоков на локальный Zipformer-ru. Под edge заявка только **20/20** stream tracking. **WORST_CASE FAIL:** WER main **0.9130**, DER **1.2985** (PR #4 @ `9908353d`). Clean WER/DER на WORST_CASE и EDGE_* не заявлять. Это не подмена офлайн-ASR и не one-room cocktail.

Команда: **MIK Coders**. Этот репозиторий — Android-«backend» (обработка на телефоне) + контракт API для web FE/BE.

## Компоненты

Офлайн, одна запись (файл / микрофон приложения):

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

PresentConnect (flavor `presentConnect`, network mode) — **Path B, не «same pipeline»**:

```
Browser mics (≤20 отдельных stream_id; не один room-mix)
        │ PCM per stream
        ▼
presentation BE ──WS/HTTP──► phone worker
                                │
                                │  каждый stream_id независимо:
                                │  PCM → VAD → ASR (Zipformer-ru)
                                │  speaker_id ← stream_id
                                │  (diar только внутри одного потока, опционально)
                                ▼
                         merge by time → session transcript
                                │
                                ▼
                         presentation display
```

21-й `stream.start` → `OVER_CAPACITY`, PCM не принимается. Checkpoint polling (clients follow presenter) lives on the **web BE**, not in this app. See `docs/PRESENT_CONNECT.md`.

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
| Diarization | sherpa `OfflineSpeakerDiarization` | Только офлайн-файл (одна запись) или опционально *внутри* одного PresentConnect-потока. Path A (cocktail ≥20 в один поток) — FAIL, см. DEMO LOCK |
| LLM | Модуль `LocalLlmPostPass` | Чекбокс брифа; веса TODO, identity-stub до поставки GGUF |
| I/O | MediaExtractor/MediaCodec → PCM; SAF import; AudioRecord | M4A/MP3/WAV + запись в приложении |
| Export | JSON schema брифа; TXT как бонус | `docs/JSON.md` |
| Модели | Не в git; `scripts/download-models.sh` + filesDir/assets | APK остаётся собираемым без весов |

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
