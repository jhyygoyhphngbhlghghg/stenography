# Архитектура решения

## Контекст

Hex.Team case «Нейро-стенографист»: Android-приложение локально превращает аудиозапись разговора в структурированную расшифровку (кто сказал, когда, что). Облачный ASR запрещён. Демо офлайн-пути — в авиарежиме.

**Live demo claim = DEMO_STAGE6:** 6 on-stage RU speakers, **one phone mic at 3–6 m** (Nothing-class), sequential turns with light end-overlap, large room + medium noise. Goal: ideal ASR + diarization on that single far-field channel.

PresentConnect Path B (≤20 browser streams) stays as a **secondary codepath**
(flavor `presentConnect`). It is **not** the slide claim. Do not retune Path B
gates here. The live implementation is the platform stream stack
(`PresentConnectStreamTable` + `StreamLabeler` + `PathBSession` +
`StreamPcmGate`); PR #6's parallel protocol classes were dropped in the merge
so the app ships exactly one Path B wire, while its audio helpers
(`SpeechEnergy`, `SpeechEndpointing`, `AudioQualityGate`) are kept and tested
but not wired into the frozen STAGE6 chain.

Команда: **MIK Coders**. Этот репозиторий — Android-«backend» (обработка на
телефоне), показ (Node-бэкенд + React-экраны + пульт, `src/web/`) и контракт
между ними (`docs/PRESENT_CONNECT.md`).

## Компоненты

```
Audio (SAF import / in-app 16 kHz mic)
        │
        ▼
  decode → 16 kHz mono PCM
        │
        ▼
  hard skip CLIPPED / BAD_MIC / empty n==0 only
  (energy / band-limit on bound main = soft, never drop 4–6 m)
        │
        ▼
  soft energy-VAD (20 ms, fire 0.0035 FS; keep speech-like)
        │
        ▼
  per-turn FarFieldGain.stage    # energy windows; 0.08 FS / +24 dB / peak 0.95
  (loud near turns ≤0 dB; NEVER whole-file AGC)
        │
        ▼
  OfflineSpeakerDiarization      # FastClustering numClusters=6
  + VAD↔diar reconcile (orphan / energy-hole / split; protect 3 m)
        │
        ▼
  Zipformer-ru int8 per (speaker, t0, t1)  # gain/EQ frozen
        │
        ▼
  llama.cpp GGUF (Qwen2-0.5B) ADD intonation punct
  → brief JSON {transcript:[{speaker_id,start_ms,end_ms,text}]}
```

Whole-file RMS AGC is **not** the demo fix. Eval **e0e64b85** (historical,
strip-punct series — the canonical cite is the Hex keep-punct 0.7794 → 0.6471
in `docs/TEST_RESULTS.md`, so do not quote these two numbers as the room
result): agg WER 0.73→0.70, **s5 still missing**. Per-turn gain lifts quiet
4–6 m seats without attenuating a hot 3 m peak; gain and EQ are both frozen
because neither recovered the room.

PresentConnect (flavor `presentConnect`, network mode) — **codepath only**:

```
Browser mics (≤20) ──audio──► presentation BE ──WS/HTTP──► phone worker
                                                              │
                                                              ▼
                                                         same pipeline
                                                              │
                                                              ▼
                         presentation display ◄──transcript───┘
```

Живой стенд (проверен agent3/agent4, 2026-11-08): протокол
**`present-connect/1`** по WSS —
`wss://present.144.31.215.37.sslip.io/v1/present-connect?session=<id>&role=worker&device=<id>`
(nginx + Let's Encrypt поверх L4 SNI-роутера sslip.io; fallback
`https://144.31.215.37:8080`, самоподписанный сертификат). Телефон
аутентифицируется `Authorization: Bearer <PHONE_TOKEN>` (демо-токен
`09879078` — слабый по замыслу, см. `docs/LIMITATIONS.md`); пушит
`transcript.final` / `phone.status`, сервер ретранслирует строки на экраны и
в HUD дека. Молчание телефона ~45 с → `fallback`-баннер «показана запись, не
результат модели». Контракт — `docs/PRESENT_CONNECT.md`.

Checkpoint polling (clients follow presenter) lives on the **web BE**, not in
this app. See `docs/PRESENT_CONNECT.md`.

Показ — `src/web/`, и он в этом же дереве: `backend/` держит контракт и
раздаёт статику, `present/` — React-дек (8 чекпоинтов, `role=screen`,
только чтение), `public/` — пульт ведущего и встроенный дек как запасной
вариант. Один процесс, один origin: `SLIDES`/`SLIDE_LABELS` в
`backend/src/config.ts` и `CHECKPOINTS` в `present/src/components/deck/data.ts`
должны совпадать, и это проверяется тестами. Телефон для показа не обязателен —
есть `npm run sim:phone`.

## Основные технические решения

| Слой | Решение | Зачем |
|---|---|---|
| UI | Kotlin + Jetpack Compose, minSdk 28 | Brief 26–28; sherpa Java demo / AAR path uses 28 |
| Flavors | `stenographer` / `presentConnect` | Один код, флаг `BuildConfig.PRESENT_CONNECT` |
| Runtime | sherpa-onnx (JitPack AAR / JNI) | ASR + VAD + diarization в одном runtime |
| ASR primary | Zipformer-ru int8 (~74 MB listing) | RU-first, мобильный размер |
| ASR backup | Whisper tiny multilingual (sherpa) | RU+EN; не whisper.cpp |
| ASR emergency | Vosk small-ru | Если sherpa не встаёт на устройстве |
| VAD | Soft energy-VAD + Silero | High-recall 4–6 m; HVAC not a turn; no distant hard-drop |
| Gain | Per-turn `FarFieldGain.stage` (Platform @ db3afe44) | Quiet distant seats; do not crush 3 m |
| Diarization | sherpa `OfflineSpeakerDiarization` K=6 + hole-fill | Recover quiet far seats (s5 missing @ a8b39d5a); protect 3 m |
| LLM | llama.cpp GGUF Qwen2-0.5B Q4_0 | ADD spoken-intonation `, . ? !` (Zipformer emits NONE; cite 0824df64). Identity stub fails TZ. |
| I/O | MediaExtractor/MediaCodec → PCM; SAF import; AudioRecord | M4A/MP3/WAV + запись в приложении |
| Export | JSON schema брифа; TXT как бонус | `docs/JSON.md` |
| Модели | Не в git; `:app:prepareEmbeddedModels` кладёт ASR/VAD/diar в `assets/models` check-APK (7 файлов, 119 466 051 B), `-PembedLlm=true` добавляет GGUF (8-й файл, +352 969 408 B) | Первый запуск распаковывает в `filesDir` (`AssetModelUnpack` + `ModelStore.ensureUnpacked`), поэтому авиарежим работает сразу после установки |

Если модели не найдены, pipeline **явно** идёт в stub-режим (помечается в UI). Stub не выдаётся за ASR на защите.

## Зависимости

- AndroidX Compose / Material3 / Activity / DocumentFile
- kotlinx.serialization (JSON)
- OkHttp (только PresentConnect / опциональный download)
- sherpa-onnx Android AAR **v2.0.1** — `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1`
  with JitPack (49 441 341 B, sha256 `a8d09d1d…c9bf0ee7`, JNI for 4 ABIs;
  re-check `scripts/inspect-sherpa-aar.sh`). An earlier revision of this doc
  said the coordinate was unpublished and pulled v1.13.5 from GitHub
  Releases; that was measured to be wrong and the extra fetch task/ivy repo
  are gone.
- llama.cpp Android bindings — `dev.ffmpegkit-maintained:llama-android:0.1.1`
  (MIT, arm64-v8a only; on other ABIs the LLM post-pass degrades to the
  intonation heuristic and the UI says so)
- (emergency) Vosk Android — подключается только если primary/backup недоступны

Явно **не** используем: Picovoice (AccessKey), whisper.cpp как primary, OEM `SpeechRecognizer`, облачные SpeechKit/Whisper API.

## Безопасность и приватность

- Офлайн-путь не отправляет аудио с устройства.
- PresentConnect шлёт PCM на телефон (worker) через BE команды; аудио не уходит в облачный ASR.
- Модели и веса — на устройстве к моменту проверки.
- Секреты не коммитим (`.env` в gitignore).

## Модули после мержа всех семи веток

```
src/android/app/src/main/java/team/mikcoders/neurosteno/
  audio/      AudioDecoder (WAV/M4A/MP3→16k mono) · MicRecorder · Pcm ·
              FarFieldGain (per-turn, frozen) · PhoneDistanceEq (250–4500 Hz, frozen) ·
              FarFieldVad · SpeechEnergy / SpeechEndpointing / AudioQualityGate (PR #6, тесты, не wired)
  pipeline/   ModelStore (+ensureUnpacked) · AssetModelUnpack (REQUIRED + OPTIONAL gguf) ·
              DemoPcmPrep (gate→gain→EQ) · SherpaStenoPipeline · VadDiarReconcile (K≤6) ·
              FarFieldScene (stage6 / presentConnect) · SherpaJni · PipelineFactory
  llm/        LocalLlmPostPass (Gguf / Heuristic / Identity) · OnDeviceLlmRunner (llama.cpp) ·
              IntonationPunct (ADD, никогда не strip; ё→е)
  present/    PresentConnectClient · OkHttpPresentConnectClient · PresentConnectStreamTable ·
              StreamLabeler · PathBSession · StreamPcmGate · PresentConnectLimits (≤20)
  export/     TranscriptJson — ровно 4 ключа брифа
  ui/         StenoApp (Compose) · StenoViewModel (async model load, прогресс, флаги)
```

## Замеренный результат этого дерева (не обещание, а факт)

- Hex keep-punct WER: baseline **0.7794** → после hard-cap K≤6 и
  intonation-punct **0.6471** (44/68 слов); DER **0.2760**, JER **0.5454**,
  purity 0.7318, RTF **0.1173** на host Linux, wall 4 659 мс на 39 718 мс аудио.
- Спикеров в гипотезе **6 / 6** (до капа было 12 / 6).
- speaker_5 на 5.4 м по-прежнему WER 1.0; 4.2 м — 1.00 после капа (слияние
  съело островок). Дальние места — известная граница, а не «решённая».
- Он-девайс прогон на **физическом** телефоне не выполнялся: телефона в этой
  машине нет. Пайплайн исполнялся на эмуляторах (LDPlayer, ARM-трансляция):
  установка, распаковка, JNI, полный ASR+LLM прогон — REPORT agent2/agent3,
  `artifacts/compact/ldplayer-real-check.json`.

## Фазы памяти и runtime-оптимизация (ветка agent1-mobile-e2e)

Пик RAM диктуется тем, сколько моделей резидентно одновременно. Решение —
**exclusive memory phases** (`ExclusivePhases.kt`, коммит `1906eaa`):
ASR-сессия выгружается сразу после фазы ASR, **до** загрузки GGUF; LLM
остаётся тёплой между прогонами. Замерено на хосте
(`artifacts/compact/exclusive-phases-experiment.json`):

- ASR-only peak RSS ≈ 273 МБ (включая базовый интерпретатор), LLM-only
  (ctx 1024, ubatch 128, 2 потока) ≈ 630 МБ;
- оценка для Android: до фаз — 0.9–1.3 ГБ пик (ASR+LLM резидентны), после —
  max(diar, ASR, LLM) + UI ≈ 0.7–0.8 ГБ; на эмуляторе LDPlayer фактический
  peak PSS: 244 МБ (ASR-прогон) и 400 МБ (полный прогон с GGUF, ARM-трансляция)
  — `artifacts/compact/ldplayer-real-check.json`;
- цена: ASR перегружается каждый прогон (~590 мс init подготовленного
  encoder на хосте) вместо тёплого ожидания; латентность первого прогона не
  меняется.

Смежные оптимизации той же ветки: переиспользование нативных сессий
инференса с сериализованным жизненным циклом (`bd7b5dd`), lane-merging и фикс
краша на длинной записи (`1906eaa`), offline-подготовленный Zipformer-encoder
(точная паритетность вывода на 9 неизменных wav; init 1370→590 мс медиана) и
compact-сборка 449 907 795 B (`3af1e5a`, `artifacts/compact/verification.json`).

## Ловушки сборки и моделей (найдены и закрыты, но знать обязательно)

1. **Протухшие веса в APK.** `:app:prepareEmbeddedModels` не объявляла
   входы, Gradle считал задачу up-to-date и перекладывал старые ассеты: в
   «успешно собранном» APK оказывался int8 `segmentation.onnx` (1 540 506 B)
   вместо float-модели (5 992 913 B, sha `220ad67c…`), на которой измерены
   все строки STAGE6. Корень — `find … | head -n 1` в `download-models.sh`.
   Исправлено: задача объявляет веса и флаг `embedLlm` входами; скрипт берёт
   float `model.onnx` явно. После любых изменений `models/` сверяйте листинг
   ассетов APK (`docs/TEST_RESULTS.md` §«Два бага сборки»).
2. **Float vs int8 сегментация — разные модели.** Все заявленные метрики
   (DER 0.2760 и др.) измерены на float-экспорте pyannote segmentation-3.0;
   int8-экспорт из того же тарбола не эквивалентен и в check-APK не кладётся.
3. **Оборванный GGUF считался присутствующим.** `AssetModelUnpack.needed`
   проверяла только `!File.isFile`; убитая первым запуском копия оставляла
   0-байтовый GGUF, который засчитывался как «на месте». Исправлено
   (`3a04212`): ретрай при `length() <= 0`, копии через temp+rename, маркер
   `.unpacked` пишется последним.
4. **Windows/cp1251.** `scripts/ci-check.sh` падал с `UnicodeEncodeError`
   (cp1251 не печатал `−` в таблице demo-stress) — добавлен UTF-8 stdout в
   `eval_harness.py` / `eval_demo_stress.py`.

## Правила скоринга, зашитые в код

Mikhail Grigorev (Hex.Team), 2026-11-06: пунктуация входит в точность
транскрипции; запятые следуют интонации говорящего, а не школьной орфографии;
`ё`/`е` не наказывается. Отсюда:

- `IntonationPunct` умеет только добавлять знаки (по паузе и интонации) и
  никогда не удаляет и не переставляет уже стоящие;
- промпт локальной LLM прямо запрещает менять слова и требует `е` вместо `ё`;
- нормализация WER в наших замерах (`scripts/eval_metrics.py`) сохраняет
  `, . ? !` и приводит `ё→е`. A-12 харнес (`eval/metrics/wer_rtf.py`) —
  отдельная speaker-blind strip-punct метрика; её числа не цитируются как
  DEMO_STAGE6.
