# Нейро-стенографист

Команда **MIK Coders** · Nuclear IT Hack (ноябрь 2026) · кейс Hex.Team
«Нейро-стенографист».

Android-приложение, которое **полностью на устройстве** превращает разговор в
структурированную расшифровку: кто сказал, когда и что. Весь тракт — VAD,
диаризация, ASR (Zipformer-ru int8), локальная LLM-пунктуация (Qwen2-0.5B
Q4_0 через llama.cpp) — работает офлайн, в авиарежиме; поверх него есть
отдельный режим **живой трансляции** расшифровки на экран презентации
(presentConnect, протокол `present-connect/1` по WSS). Никакого облачного
ASR/LLM: аудио не покидает телефон.

Репозиторий следует шаблону
[hacathon_template](https://github.com/grigorevmp/hacathon_template)
(`README` + `artifacts/ config/ docs/ src/`) и сдаётся как есть.

## Отчётные материалы (чеклист эксперта)

| # | Материал | Где |
|---|---|---|
| 1 | **APK-файл приложения** | GitHub Releases: [v0.1.0-stage6](https://github.com/jhyygoyhphngbhlghghg/stenography/releases/tag/v0.1.0-stage6) + финальный демо-релиз на [странице релизов](https://github.com/jhyygoyhphngbhlghghg/stenography/releases); таблица sha256 — ниже и в `apk/README.md` |
| 2 | **Исходный код** | этот репозиторий: `src/android/` (Kotlin+Compose), `src/web/` (Node-бэкенд + React-дек) |
| 3 | **Инструкция по установке и использованию** | `docs/INSTALL.md` (+ быстрый старт ниже) |
| 4 | **Архитектура и применяемые модели** | `docs/ARCHITECTURE.md`, `docs/MODELS.md` |
| 5 | **Ограничения** | `docs/LIMITATIONS.md` |
| 6 | **Результаты собственных тестов** | `docs/TEST_RESULTS.md` (+ `artifacts/test-results/`, `artifacts/eval/`) |
| 7 | **Видеодемонстрация** | `docs/DEMO.md` и `docs/demo/` — сценарий, чеклист стенда и слот под запись (команда выкладывает `docs/demo/demo.mp4` / release-ассет до сдачи) |

## Быстрый старт (что делает эксперт)

1. **Установить APK.** Рекомендуемый артефакт — `app-stenographer-debug-embedLlm.apk`
   (547 МиБ, все веса + локальная LLM внутри). Альтернатива — `app-stenographer-debug.apk`
   (210 МиБ) и один `adb push models/llm …` (см. `docs/INSTALL.md`).
2. **Разрешения.** При первом запуске разрешить микрофон и доступ к файлам;
   сеть приложению не нужна (INTERNET-разрешение использует только flavor
   presentConnect).
3. **Первый запуск.** Приложение распакует ≈114 МиБ моделей из APK в свою
   приватную папку (секунды, шкала в UI). В шапке должно быть
   `Офлайн: Zipformer-ru + Silero VAD + diar (models в APK)` и
   `LLM: llama.cpp GGUF qwen2-0_5b-instruct-q4_0.gguf`. Строка
   `pipeline = stub` означает, что весов нет — такой кадр показывать нельзя.
4. **Запись или импорт.** Включить авиарежим → **Запись** (микрофон) или
   **Импорт** (WAV/M4A/MP3 из памяти) → **Расшифровать**. Стадии: VAD →
   диаризация (K ≤ 6) → Zipformer-ru по репликам → локальная LLM
   (интонационная пунктуация).
5. **Результат.** Список реплик `speaker_N` + `start_ms`/`end_ms` + текст →
   **Сохранить JSON** (SAF) или **Поделиться**. Схема — ровно четыре ключа
   брифа (`docs/JSON.md`), проверка `python3 scripts/verify-transcript-json.py export.json`.

**presentConnect-сборка для живой демонстрации (отдельный режим, не основная
заявка).** `app-presentConnect-debug.apk` подключается к презентационному
бэкенду по WSS (`present-connect/1`): телефон-воркер распознаёт до 20
отдельных аудиопотоков и пушит реплики на экраны. Демо-стенд:
`https://present.144.31.215.37.sslip.io/` (fallback `https://144.31.215.37:8080`,
самоподписанный сертификат), токен телефона по умолчанию `09879078`. Сценарий
показа и чеклист стенда — `docs/DEMO.md`, контракт — `docs/PRESENT_CONNECT.md`.

## APK и контрольные суммы

Бинарники в git не лежат — публикуются как ассеты релизов. Релиз
**[v0.1.0-stage6](https://github.com/jhyygoyhphngbhlghghg/stenography/releases/tag/v0.1.0-stage6)**
(снимок до runtime-оптимизации; тег не двигается поздними коммитами) содержит
все четыре APK; финальный демо-релиз (runtime/compact-кандидаты) выкладывается
командой на [странице релизов](https://github.com/jhyygoyhphngbhlghghg/stenography/releases)
до сдачи. Проверка: `sha256sum *.apk`.

| файл | байт | sha256 | что это |
|---|---|---|---|
| `app-stenographer-debug-embedLlm.apk` | 573 498 719 | `f89bc45a318507b462a508ac493c38a3c5045954a814ade93d638ad226ea3678` | **всё на устройстве**: ASR+VAD+диаризация+GGUF внутри (8 ассетов, 472 435 459 B). Артефакт для проверки требования «локальная LLM» |
| `app-stenographer-debug.apk` | 220 529 139 | `c35929764492a7f8f142d53dbf5fc20a87b2294d6af4c1ad1d9af911261890da` | check-APK: 7 файлов моделей (119 466 051 B) без GGUF; LLM-веса можно доложить `adb push` |
| `app-presentConnect-debug.apk` | 220 401 103 | `b9afd39f4ee9c7bd80b282e882df792fa911c0e935605285ee57e3490f980d83` | Path B flavor (`applicationIdSuffix .present`), ≤20 потоков, демо-стенд |
| `app-stenographer-slim.apk` | 97 599 021 | `6c52e259647d300fccf15216e2d8b188f71bd415f4e716d388b5b85eeae78311` | без весов; нужен `adb push` моделей — UX-вариант, **не** проверочный APK |
| `app-stenographer-runtime-embedLlm.apk` *(локальный кандидат)* | 573 498 719 | `f80d8ab14ed5fc7d2f5612b3448b562dad0a88835aab5cc6fa574e5fccb6b377` | тёплые сессии инференса + memory phases; на телефоне не исполнялся |
| `app-stenographer-compact.apk` *(локальный кандидат)* | 449 907 795 | `d52c72653f7fc1588711b4507b42b52fb8dcdf805f5a6c806a09563fa9e9c58c` | arm64-only, R8, подготовленный encoder (init 1370→590 мс); versionName `0.2.0-compact` |

Подробности сборки и ловушки — `apk/README.md`.

## Обязательные требования брифа — где закрыты

| Требование Hex.Team | Где в коде |
|---|---|
| работает под Android | Kotlin + Jetpack Compose, minSdk **28**, compileSdk 35, два flavor'а — `src/android/app/` |
| использует **локальную LLM** | `GgufLlmPostPass`: Qwen2-0.5B-Instruct Q4_0 через llama.cpp JNI; промпт — только устная интонационная пунктуация, `IntonationPunct.mergeCandidate` отклоняет любой ответ, где изменены содержательные слова, — `llm/LocalLlmPostPass.kt`, `llm/OnDeviceLlmRunner.kt`, `llm/IntonationPunct.kt` |
| импорт аудио из памяти | SAF `OpenDocument` (`audio/*`) → `AudioDecoder`: WAV/M4A/MP3 → 16 кГц моно PCM — `audio/AudioDecoder.kt` |
| речь → текст | sherpa-onnx `OfflineRecognizer`, **Zipformer-ru int8** (основной); Whisper tiny (запас), Vosk small-ru (аварийный) — `pipeline/SherpaStenoPipeline.kt` |
| автоматическое разделение спикеров | `OfflineSpeakerDiarization` (pyannote segmentation-3.0 float + 3D-Speaker ERes2Net + FastClustering), дыры VAD закрывает `VadDiarReconcile` — `pipeline/VadDiarReconcile.kt`, `pipeline/FarFieldScene.kt` |
| таймстемпы на реплику | `start_ms` / `end_ms` каждого диаризованного кропа, миллисекунды |
| отображение расшифровки | Compose-список с чипами спикеров, прогрессом стадий, флагами ошибок и качества микрофона — `ui/StenoApp.kt` |
| экспорт результата в файл | **Сохранить JSON** через SAF `CreateDocument`; **Поделиться** — тот же payload в chooser; на ветке продуктовых фич также TXT/DOCX/PDF/SRT/VTT/CSV — `ui/StenoApp.kt`, `export/TranscriptExport.kt` |

Ничего не уходит с устройства: нет облачного ASR/диаризации/LLM, нет
удалённых вычислений, нет подмены расшифровки заготовленным текстом
(`docs/LIMITATIONS.md` §«Что мы не делаем»).

## Что заявляем и чем это измерено

Заявка защиты — **DEMO_STAGE6**: 6 русскоговорящих спикеров, **один**
микрофон телефона, места 3–6 м, очередь реплик с лёгким перекрытием в конце,
большая комната, средний babble/HVAC. Скоринг зафиксирован экспертом Hex.Team
(Михаил Григорьев, Q&A 2026-11-06): пунктуация входит в точность
транскрипции, запятые следуют интонации говорящего, `ё`/`е` не наказывается.
Все числа ниже используют эту нормализацию (`scripts/eval_metrics.py`:
keep-punct + fold ё→е).

| метрика | значение | источник |
|---|---|---|
| Hex keep-punct WER, baseline | **0.7794** (53/68) | `artifacts/eval/demo-stress/metrics.csv` |
| Hex keep-punct WER после hard-cap K≤6 | **0.6471** (44/68) | `DEMO_STAGE6_impl_hardcap6.json` |
| DER / JER | **0.2760** / **0.5454** | тот же прогон |
| спикеров в гипотезе | **6 / 6** (до капа 12/6) | `DEMO_STAGE6_impl_hardcap6.speakers.json` |
| RTF (host Linux, sherpa-onnx 2.0.1) | **0.1173** (4 659 мс / 39 718 мс аудио) | `DEMO_STAGE6_impl_hardcap6.wall_ms` |
| WER по местам | 3.0 м 0.21 · 3.6 м 0.33 · 4.2 м 1.00 · 4.8 м 0.90 · 5.4 м 1.00 · 6.0 м 0.69 | `docs/TEST_RESULTS.md` |

Честные пробелы (полностью — `docs/LIMITATIONS.md`): дальние места 4.2–5.4 м
слабы, speaker_5 не восстановлен, WER далёк от 0, прогон на физическом
телефоне не выполнен (проверка — эмуляторы), строка K≤6 измерена на Impl
`3c3f9ce`. Не цитировать: `WER_main 0`, «чистые» WORST_CASE/EDGE_*, Path B
≤20 как основную заявку.

## Сборка из исходников

Нужны JDK 17+ и Android SDK 35; `cp src/android/local.properties.example
src/android/local.properties` и указать `sdk.dir` (файл в gitignore). Веса в
git не лежат: `./scripts/download-models.sh` (≈470 МБ, один раз по Wi-Fi).

```bash
cd src/android
./gradlew :app:testStenographerDebugUnitTest              # JVM-юнит-тесты
./gradlew :app:assembleStenographerDebug                  # check-APK
./gradlew :app:assembleStenographerDebug -PembedLlm=true  # + GGUF (547 МиБ)
./gradlew :app:assemblePresentConnectDebug                # демо-стенд Path B
./gradlew :app:assembleStenographerSlim                   # без весов
```

Измерено на этой машине 2026-11-07 (gradle 8.11.1, AGP 8.7.3, JDK 21,
Windows): четыре APK совпадают по байтам и sha256 с таблицей выше.

Веб-часть (Node 20+): один процесс раздаёт API, экраны и пульт.

```bash
cd src/web/backend
npm install
ADMIN_TOKEN=<токен_ведущего> npm run build:all   # tsc + React-дек
ADMIN_TOKEN=<токен_ведущего> npm start           # http://localhost:8080
```

`/` — экраны (8 чекпоинтов), `/presenter` — пульт, `/deck-legacy` — встроенный
дек (запасной), `ws://…/v1/present-connect` — телефон. Для репетиции без
телефона: `npm run sim:phone`. Подробности — `src/web/README.md`, контракт —
`docs/PRESENT_CONNECT.md`, хостинг — `docs/DEPLOY.md`.

Воспроизведение замеров:

```bash
./scripts/ci-check.sh                                   # схема + харнес + пересчёт метрик
python3 -m unittest eval.metrics.test_wer               # A-12 харнес, stdlib (18 тестов)
python3 -m unittest discover -s scripts -p 'test_*.py'  # host-eval (61 тест)
python3 scripts/generate_demo_stage6.py
python3 scripts/host_asr_diar.py --test-id DEMO_STAGE6_impl_hardcap6
python3 scripts/eval_metrics.py --test-id DEMO_STAGE6_impl_hardcap6
```

## Стек (зафиксирован)

- Kotlin + Jetpack Compose, minSdk **28** (бриф допускает 26–28; sherpa Java demo — 28)
- sherpa-onnx **v2.0.1** Android AAR с JitPack — 49 441 341 B, sha256
  `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`
  (перепроверка — `scripts/inspect-sherpa-aar.sh`)
- **Zipformer-ru int8** (основной ASR) · Whisper tiny multilingual (запас) · Vosk small-ru (аварийный)
- Silero VAD · `OfflineSpeakerDiarization` (pyannote segmentation-3.0 **float** + 3D-Speaker ERes2Net + FastClustering)
- llama.cpp JNI (`dev.ffmpegkit-maintained:llama-android:0.1.1`, MIT, arm64-v8a) + Qwen2-0.5B-Instruct Q4_0 GGUF
- явно **не** используем: whisper.cpp как primary, Picovoice (AccessKey), OEM `SpeechRecognizer`, облачный ASR/LLM

Два продуктовых flavor'а на одной кодовой базе: **stenographer** (офлайн
файл/микрофон) и **presentConnect** (≤20 изолированных потоков против
презентационного бэкенда). Презентация тоже в этом репозитории — `src/web/`.

## Состав репозитория

| путь | роль |
|---|---|
| `apk/` | таблица APK, sha256, команды сборки, ловушки (бинарники в gitignore — это release-ассеты) |
| `artifacts/` | замеры (`eval/`, `test-results/`, `optimization/`, `compact/`), демо-материалы (`demo/`, `agent3/`) |
| `config/` | примеры env, флаги flavor'ов |
| `docs/` | архитектура, модели, установка, ограничения, тесты, демо, контракты, гейты |
| `eval/` | A-12 WER/RTF-харнес и его гейт |
| `scripts/` | загрузка моделей, host-зеркала пайплайна, верификатор JSON, sideload-проба |
| `src/android/` | **мобильная часть**: Kotlin + Compose, модуль `:app` |
| `src/web/backend/` | **веб**: Node-сервер — WS/HTTP-контракт, чекпоинты, раздача статики |
| `src/web/present/` | **веб**: React-дек (TanStack Start) — экраны зала, 8 чекпоинтов |
| `src/web/public/` | **веб**: пульт ведущего + встроенный дек как запасной |
| `samples/` | фикстуры демо-сцены + три слота клипов (большие аудио в gitignore) |

## Индекс документации

- `docs/INSTALL.md` — установка каждого APK-варианта, разрешения, офлайн-проверка
- `docs/ARCHITECTURE.md` — пайплайн, flavor'ы, memory phases, веб-часть
- `docs/MODELS.md` — файлы, размеры, sha256, лицензии
- `docs/LIMITATIONS.md` — что не работает и чего мы не утверждаем
- `docs/TEST_RESULTS.md` — все измеренные строки с провенансом
- `docs/DEMO.md`, `docs/demo/` — сценарий и чеклист видеостенда, слот под запись
- `docs/JSON.md` — схема экспорта · `docs/PRESENT_CONNECT.md` — контракт Path B
- `docs/PROTOCOL.md` — что реально передаётся по проводам · `docs/DEPLOY.md` — хостинг показа
- `docs/GATE.md`, `eval/GATE.md` — что должно пройти до защиты
- `NOTICE.md` — лицензии моделей и библиотек
