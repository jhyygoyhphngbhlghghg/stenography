# Методика и результаты тестирования

Собственные прогоны команды (не судейский набор «котики»).

**Запрещено выдумывать WER/RTF.** Он-девайс строки ниже остаются `pending`, пока нет своего прогона. Eval-measured таблица — замер H1 / Eval Owner, не новая выдумка: её можно цитировать, новые числа к ней не добавлять.

Шаблон он-девайс строк: `artifacts/test-results/results.csv` (файл есть, строк прогонов нет — только заголовок). Каталога `eval/` и `artifacts/eval/` на `main` нет. Измерения Path A/B описаны в [PR #4](https://cursor.com/codebase/johann-schilling/neuro-stenographer/pull/4) (ветка `cursor/demo-stress-20talker-0106`, не влита; в теле PR указан путь `artifacts/eval/demo-stress/` для более раннего eSpeak-прогона).

## Методика (черновик)

1. Собрать debug APK flavor `stenographer`.
2. Положить модели (`scripts/download-models.sh` + push).
3. Авиарежим.
4. Импорт 2–3 своих диалогов (RU, ≥2 спикера, без перекрытия).
5. Экспорт JSON, сверка `speaker_id` / `start_ms` / `end_ms` / `text` с ручной расшифровкой.
6. Зафиксировать wall time и длительность аудио (RTF = wall / duration) только после реального замера.

## Статус overnight

| Проверка | Статус |
|---|---|
| Gradle assemble (shell) | pending first green build |
| Import → stub list → JSON (no models) | pending |
| Zipformer-ru on-device | pending models |
| Diarization ≥2 speakers (offline, one file) | pending on-device |
| Airplane mode | pending |
| PresentConnect mock on device | pending — контракт Path B в `docs/PRESENT_CONNECT.md`; цифры демо ниже, это host eval, не мок телефона |

## Eval-measured (H1 / Eval Owner) — не выдумано

Источник: [PR #4](https://cursor.com/codebase/johann-schilling/neuro-stenographer/pull/4), real Common Voice 17 RU, host Linux sherpa-onnx Zipformer-ru. H1 принял эти числа и залочил демо на **Path B**. Округление как в DEMO LOCK; в том же замере DER Path B также **0.235**.

| path | speakers recovered | DER | main WER | RTF |
|---|---|---|---|---|
| A one-room cocktail mix | **1/20 FAIL** | 0.95 | 1.00 | 0.18 |
| **B per-stream PresentConnect** | **20/20** | **0.24** (also 0.235) | **0.087** | 2.65 |
| dry main alone | 1/1 | 0.02 | 0.174 | 0.06 |

Path A — documented hard fail, не демо. Path B — ≤20 отдельных потоков, поддерживаемое multi-talker демо.

Overnight-клипы без перекрытия (тот же PR, speaker-blind WER): **~3.5% / 0% / 0%** (в теле PR #4: clip_01 **0.0345**, clip_02 **0**, clip_03 **0**).
