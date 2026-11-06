# Архитектура решения

## DEMO LOCK (H1) — DEMO_STAGE6

**Презентационная заявка = DEMO_STAGE6. Это не Path B и не ≤20 потоков.**

- **6** спикеров на сцене, речь **RU**.
- **Один** микрофон телефона (demo phone). Каждый спикер **3–6 м** от микрофона.
- Очередь **sequential** (по одному). Допустимо только **лёгкое** перекрытие или перебивание в конце реплики.
- **Большая комната** и **средний** фон (babble/HVAC). **Не** студия.
- Характер телефонного микрофона: band-limit, compression, затухание с расстоянием, reverb. **Не** чистый Common Voice.
- Пайплайн: один канал, far-field — VAD → диаризация → Zipformer-ru ASR (офлайн-файл / on-device).

Цель johann: **ideal recognition** — ясные смены спикеров и пригодный текст. **Ideal recognition только @ 3 м**, не на дистанции 3–6 м. Дальше 3 м — far-field gap: комната этим замером не закрыта. Не цитировать 3 м как результат всей комнаты.

Первый замер H1/Eval ([PR #4](https://cursor.com/codebase/johann-schilling/neuro-stenographer/pull/4), baseline `a8b39d5`): aggregate **WER 0.73 / DER 0.52** (точно WER **0.7321**, DER **0.5174**). Ниже этих чисел не заявлять. Серия gain, agg WER baseline / ffgain / perturn: **0.73 / 0.70 / 0.73** (точно **0.7321 / 0.6964 / 0.7321**; ffgain @ `e0e64b8`, perturn @ `db3afe4`). **Gain комнату не вернул.** Следующий рычаг — только пометка, не обещание: spectral + diar; владеют Platform/Eval.

Студийную подмену сцены не заявлять. Запрещены «should be fine», «robust», «handles noisy rooms», «graceful under echo». Фикстуры не подкручивали.

Path A (one-room cocktail ≥20 говорящих в один поток → 1) — measured FAIL (**1/20**, DER **0.95**, main WER **1.00**, RTF **0.18**). Не демо.

**Path B PresentConnect** (≤20 отдельных потоков, `speaker_id` ← `stream_id`) — **опциональный** сетевой режим, не заявка презентации. Контракт: `docs/PRESENT_CONNECT.md`. Измеренные цифры Path B — в следующем разделе и в `docs/LIMITATIONS.md`.

## Optional PresentConnect (Path B) — not the presentation claim

Не DEMO LOCK. Не писать в слайдах «демо = Path B ≤20».

Первая таблица — **ранний** Eval-measured прогон (H1 / Eval Owner, [PR #4](https://cursor.com/codebase/johann-schilling/neuro-stenographer/pull/4), real Common Voice 17 RU, host sherpa-onnx), clean per-stream и Path A. Новые числа не выдумывать. DER Path B в том замере также **0.235** (в таблице округление **0.24**). Эти WER/DER **не** переносить на WORST_CASE и EDGE_*.

| path | speakers recovered | DER | main WER | RTF |
|---|---|---|---|---|
| A one-room cocktail mix | **1/20 FAIL** | 0.95 | 1.00 | 0.18 |
| **B per-stream PresentConnect** | **20/20** | **0.24** (also 0.235) | **0.087** | 2.65 |
| dry main alone | 1/1 | 0.02 | 0.174 | 0.06 |

Overnight-клипы без перекрытия (тот же замер): WER ~3.5% / 0% / 0%. Это тоже clean/no-overlap, не edge suite.

**Claim lock (edge), optional Path B only.** Под edge у Path B остаётся только **stream tracking 20/20** (hyp/ref). Это не заявка DEMO_STAGE6. **WER/DER under WORST_CASE are FAIL.** Clean WER/DER (и 0.087 / 0.24 раннего прогона, и CLEAN-строка suite) на WORST_CASE и EDGE_* **не** заявлять. **Killers:** overlap/bleed, noise false alarms, bad mic. Reverb alone does **not** collapse Path B.

Таблица ниже — Eval PR #4 @ `9908353d`, скопирована как замерена. Фикстуры **не** подкручивали после FAIL. Для optional Path B, не для слайдов DEMO_STAGE6: WORST_CASE WER≈0.91, DER≈1.30 FAIL.

| test_id | DER 250ms | JER | WER main | RTF | hyp/ref |
|---|---|---|---|---|---|
| CLEAN Path B | 0.0913 | 0.1840 | 0.0870 | 0.1212 | 20/20 |
| EDGE_reverb | 0.0849 | 0.1756 | 0.1304 | 0.1263 | 20/20 |
| EDGE_overlap | 0.9936 | 0.5476 | 0.7391 | 0.2430 | 20/20 |
| EDGE_noise | 1.7005 | 0.5675 | 0.1739 | 0.2602 | 20/20 |
| EDGE_badmic | 0.6569 | 0.2863 | 1.0000 | 0.1910 | 20/20 |
| **WORST_CASE** | **1.2985** | **0.5981** | **0.9130** | **0.2907** | 20/20 |

Overlap real: max 12 concurrent; 91% of speech frames ≥2 speakers. Main vs bleed: +3.0 dB (EDGE_overlap) / +1.5 dB (WORST_CASE). WORST_CASE main hyp collapses to one ~18s blob (`хочу говорить`). DER>1 = false-alarm time > ref speech. Запрещены «should be fine», «robust», «handles noisy rooms», «graceful under echo». **Do not claim in slides** clean WER/DER на edge.

**STOP — hard `BAND_LIMITED` on bound seats is REJECTED** (toxic measured). Gate v2 @ Eval PR #4 `bed868b3` (note `ffe53ba4`) hard-drops bound `s01`..`s20`: EDGE_noise drops main **s01** (hyp **19/20**); EDGE_badmic **6/20**; WORST_CASE **0/20**, empty `{transcript:[]}`. Those hyp counts are not the claim. Bound seats **soft-flag only** (still ASR), same spirit as `SOFT_LOW`. Never hard-drop main/`s01`. Hard `BAND_LIMITED` only for unbound/anonymous (Platform, PR #3). **Ungated** Path B stream tracking **20/20** @ `9908353d` stands for this optional mode only. Ungated WORST_CASE stays WER main **0.9130** / DER **1.2985**. Do not claim gated_v2 hyps or clean WER/DER on WORST_CASE. Fixtures not retuned. Hold merge; #2 first.

**Контракт Path B.** Каждый `stream_id` обрабатывается **независимо**: PCM → VAD → ASR. Метка по умолчанию: **`speaker_id` ← `stream_id`** (или стабильная карта stream→speaker / `speaker_hint`). Диаризация опциональна и только *внутри* одного потока — не mix-then-cluster по комнате. Реплики сессии склеиваются по времени. Жёсткий потолок — **20** одновременных потоков; 21-й отклоняется (`OVER_CAPACITY`).

Офлайн-путь (файл или микрофон приложения, **одна** запись): VAD → `OfflineSpeakerDiarization` → ASR. Это пайплайн **DEMO_STAGE6**. PresentConnect Path B его не заменяет.

## Контекст

Hex.Team case «Нейро-стенографист»: Android-приложение локально превращает аудиозапись разговора в структурированную расшифровку (кто сказал, когда, что). Облачный ASR запрещён. Демо офлайн-пути — в авиарежиме. Презентация — **DEMO_STAGE6** (6 RU, один микрофон телефона, сцена 3–6 м). Первый замер: aggregate **WER 0.73 / DER 0.52**; ideal recognition **только @ 3 м**. **PresentConnect / Path B** — опциональный сетевой режим, не эта заявка. Под edge Path B: только stream tracking **20/20**; **WORST_CASE FAIL** WER main **0.9130**, DER **1.2985** (PR #4 @ `9908353d`). Clean WER/DER на WORST_CASE и EDGE_* не заявлять. Это не подмена офлайн-ASR и не one-room cocktail.

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
  Local LLM post-pass          # TZ: required on-device punctuation / cleanup
        │
        ▼
  Dialogue JSON + Compose list + share/export
```

PresentConnect (flavor `presentConnect`, **optional** network mode, не DEMO_STAGE6) — **Path B, не «same pipeline»**:

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
| Diarization | sherpa `OfflineSpeakerDiarization` | DEMO_STAGE6: одна far-field запись (VAD → diar → ASR). Path B: опционально внутри одного потока. Path A (cocktail ≥20 в один поток) — FAIL |
| LLM | `LocalLlmPostPass` | **Требование ТЗ:** локальный on-device post-pass (пунктуация / cleanup), не stub. Веса — в assets check APK. Сейчас в коде `IdentityLlmPostPass` — разрыв Impl/Platform против ТЗ, закрыть до сдачи. ТЗ обратно в stub не смягчать |
| I/O | MediaExtractor/MediaCodec → PCM; SAF import; AudioRecord | M4A/MP3/WAV + запись в приложении |
| Export | JSON schema брифа; TXT как бонус | `docs/JSON.md` |
| Модели | Не в git | **Сдача / check APK: assets-bundled primary** (ASR, VAD, diar, LLM). Сетевая докачка — только UX karma, для ТЗ не нужна. `download-models.sh` + adb push — путь разработки |

Check APK для сдачи несёт веса в assets, включая локальную LLM. Сборка без весов — не результат для судей. Identity-stub LLM в текущем коде — разрыв против ТЗ, не допустимая сдача.

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
