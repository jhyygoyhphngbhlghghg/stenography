# TZ check-APK checklist

Проверка по «TZ check-APK checklist» из ночного хендаута (MIK Coders,
status 2026-11-06 22:32 MSK). Правило хендаута — *not committed = undone*;
здесь каждая строка привязана к коммиту/файлу в этой ветке и к тому, чем она
проверена.

| # | Требование ТЗ | Статус | Чем проверено |
|---|---|---|---|
| 1 | Импорт аудио (файл / микрофон) → пайплайн | **сделано, собрано** | SAF `OpenDocument` + `audio/AudioDecoder.kt` (WAV/M4A/MP3 → 16 кГц моно), `audio/MicRecorder.kt` (16 кГц in-app). UI-кнопки `Импорт` / `Запись` в `ui/StenoApp.kt`; тест `audio/PcmTest.kt` |
| 2 | JSON `transcript[{speaker_id,start_ms,end_ms,text}]` | **сделано, проверено скриптом** | `export/TranscriptJson.kt` (ровно 4 ключа, `matchesBriefShape`), `export/TranscriptJsonTest.kt`, `python3 scripts/verify-transcript-json.py` → `OK` на всех 43 закоммиченных
JSON-файлах (вывод `bash scripts/ci-check.sh`, 2026-11-07) |
| 3 | Zipformer-ru встроен в check-APK | **сделано, APK собран** | `:app:prepareEmbeddedModels` кладёт 4 файла модели в `assets/models`; в `apk/app-stenographer-debug.apk` (220 529 139 B) лежат `encoder.int8.onnx 70 876 638` / `decoder.onnx 2 093 080` / `joiner.int8.onnx 259 417` / `tokens.txt 6 388`. Распаковка на первом старте: `pipeline/AssetModelUnpack.kt` + `ModelStore.ensureUnpacked()`, тесты `AssetModelUnpackTest` (4) |
| 4 | Silero VAD встроен | **сделано, APK** | `assets/models/vad/silero_vad.onnx` 643 854 B, sha256 `9e2449e1…` совпал с `artifacts/test-results/models-checksums.sha256` (PR #3) |
| 5 | Диаризационные паки встроены | **сделано, APK** | `diar/segmentation.onnx` 5 992 913 B (float pyannote-3.0, sha `220ad67c…`) + `diar/embedding.onnx` 39 593 761 B (3D-Speaker ERes2Net, sha `1a331345…`). Баг «find \| head брал int8-версию» исправлен в `scripts/download-models.sh` |
| 6 | Локальная LLM: настоящие GGUF-веса, не Identity stub | **сделано; в APK по флагу** | `llm/OnDeviceLlmRunner.kt` (`dev.ffmpegkit-maintained:llama-android:0.1.1`, проверен на Maven Central: AAR 2 062 204 B, sha256 `fdccf04c…`, классы `Llama/LlamaConfig/LlamaResult`), `llm/GgufLlmPostPassTest` (4 теста). Веса `models/llm/qwen2-0_5b-instruct-q4_0.gguf` 352 969 408 B скачаны (лог `artifacts/test-results/model-download-run-2026-11-07.csv`). `./gradlew :app:assembleStenographerDebug -PembedLlm=true` → `apk/app-stenographer-debug-embedLlm.apk` 573 498 719 B с GGUF внутри |
| 7 | Slim APK по сети (опционально, karma) | **сделано, собрано** | `assembleStenographerSlim` → 97 599 021 B, `EMBEDDED_MODELS=false` |
| 8 | `assembleStenographerDebug` с весами | **сделано, собрано здесь** | 220 529 139 B, sha256 `c3592976…`; ассеты 119 466 051 B / 7 файлов проверены `zipfile`-листом внутри APK |
| 9 | `assembleDebug` зелёный на ночной ветке | **сделано** | На смерженном дереве: `:app:testStenographerDebugUnitTest` → 110 тестов, 0 падений (20 классов); `assembleStenographerDebug`, `assemblePresentConnectDebug`, `assembleStenographerSlim` — BUILD SUCCESSFUL (2026-11-07, gradle 8.11.1, AGP 8.7.3) |
| 10 | Сидeload по adb + живой прогон с микрофона | **не сделано — нужен телефон** | `scripts/device-sideload-probe.sh` готов; на этой машине adb есть, устройств нет (`adb devices` пуст). Human-действие на Nothing-телефоне |
| 11 | Hard-cap диаризации на 6 спикерах | **сделано и измерено** | `pipeline/VadDiarReconcile.kt` (`capToExpected`, `adopt`), тест `twelveVadIslandsNeverInventSpeakerSeven` (`VadDiarReconcileTest`, 8 тестов). Eval-строка `DEMO_STAGE6_impl_hardcap6` @ `3c3f9ce`: гипотеза **6/6** вместо 12/6, JER 0.6821→0.5454, DER 0.2568→0.2760, Hex WER 0.6029→0.6471 |
| 12 | Перезамер Eval после фикса капа | **частично** | Измерено на `3c3f9ce` (см. выше). Тип `ab20d83` («не сливать s2/s3 под капом + карта тихих дальних мест») ещё не переоценён — нужно зеркало Python-ингеста + прогон `scripts/host_asr_diar.py --test-id DEMO_STAGE6_impl_hardcap6b` |

## Ночные «must-do», которые закрыты этим мерджем

1. Встроить Zipformer + VAD + diar в check-APK — **закрыто файлом APK** (строки 3–5, 8).
2. Настоящая локальная LLM с весами — **закрыто** (строка 6; GGUF скачан, биндинг реальный,
   есть отдельный APK со встроенным GGUF).
3. Диаризация: восстановить s5 без перер сплита — **кап закрыт и измерен**;
   восстановление speaker_5 на 5.4 м **не достигнуто** (WER 1.0), это известная граница.
4. Перезамер Eval после капа — **сделан на 3c3f9ce**, не сделан на последнем тибе.
5. Мерж PR #2 → разблокировка #3–#7 — **закрыто**: все семь веток теперь
   достижимы из `main` (`git rev-list <branch> --not main` пусто для каждой).
6. Сидeload на телефоне — **открыто** (строка 10).

## Языковые ограничения на защите

См. `docs/LIMITATIONS.md` §«Формулировки на защите»: не говорим «WER_main 0»,
не приписываем дальним местам улучшение от gain/EQ (оба заморожены), не
называем Path B ≤20 демо, не называем WORST_CASE/EDGE_* чистыми, не
утверждаем «6 спикеров» без строки замера.
