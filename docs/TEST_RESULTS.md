# Методика и результаты тестирования

Собственные прогоны команды (не судейский набор «котики»).

**Запрещено выдумывать WER/RTF.** Он-девайс строки ниже остаются `pending`, пока нет своего прогона. Eval-measured таблица — замер H1 / Eval Owner, не новая выдумка: её можно цитировать, новые числа к ней не добавлять.

Шаблон он-девайс строк: `artifacts/test-results/results.csv` (файл есть, строк прогонов нет — только заголовок). Каталога `eval/` и `artifacts/eval/` на `main` нет. Измерения Path A и optional Path B описаны в [PR #4](https://cursor.com/codebase/johann-schilling/neuro-stenographer/pull/4) (ветка `cursor/demo-stress-20talker-0106`, не влита; в теле PR указан путь `artifacts/eval/demo-stress/` для более раннего eSpeak-прогона). **DEMO_STAGE6 WER/DER здесь нет.**

## DEMO_STAGE6 — заявка презентации, scores pending

6 спикеров **RU**, **один** микрофон телефона, каждый **3–6 м**, очередь sequential и только лёгкое перекрытие в конце реплики, большая комната, средний babble/HVAC, характер телефонного микрофона (band-limit / compression / distance attenuation + reverb). Пайплайн: один канал, far-field, VAD → диаризация → Zipformer-ru. Цель: ideal recognition (ясные смены спикеров + пригодный текст). **Цифр нет.** Фикстуру и WER/DER держит Eval (PR #4). Это не Path B и не студия. Запрещены «should be fine», «robust», «handles noisy rooms», «graceful under echo».

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
| PresentConnect mock on device | pending — optional Path B, не заявка презентации; контракт `docs/PRESENT_CONNECT.md` |

## Optional PresentConnect (Path B) — Eval-measured, не заявка презентации

Источник: [PR #4](https://cursor.com/codebase/johann-schilling/neuro-stenographer/pull/4), real Common Voice 17 RU, host Linux sherpa-onnx Zipformer-ru. Это **ранний** clean/Path A замер. DER Path B в нём также **0.235**. Эти WER/DER **не** ставить на WORST_CASE и EDGE_*. Edge suite — следующая таблица @ `9908353d`.

| path | speakers recovered | DER | main WER | RTF |
|---|---|---|---|---|
| A one-room cocktail mix | **1/20 FAIL** | 0.95 | 1.00 | 0.18 |
| **B per-stream PresentConnect** | **20/20** | **0.24** (also 0.235) | **0.087** | 2.65 |
| dry main alone | 1/1 | 0.02 | 0.174 | 0.06 |

Path A — documented hard fail, не демо. Строка Path B этой таблицы (DER **0.24** / 0.235, main WER **0.087**, RTF **2.65**) — ранний clean per-stream, не WORST_CASE.

Overnight-клипы без перекрытия (тот же PR, speaker-blind WER): **~3.5% / 0% / 0%** (в теле PR #4: clip_01 **0.0345**, clip_02 **0**, clip_03 **0**). Это не edge suite.

## Edge suite @ `9908353d` — measured FAIL

Источник: Eval [PR #4](https://cursor.com/codebase/johann-schilling/neuro-stenographer/pull/4) @ `9908353d`. Таблица скопирована. Фикстуры **не** подкручивали после FAIL.

**Path B under edge: stream tracking still 20/20.** Это единственная заявка **optional Path B**, которая переживает его edge. Не DEMO_STAGE6. **WER/DER under WORST_CASE are FAIL** (WER main **0.9130**, DER 250ms **1.2985**; слайды: WER≈0.91, DER≈1.30). Clean WER/DER на WORST_CASE и EDGE_* не заявлять. **Killers:** overlap/bleed, noise false alarms, bad mic. Reverb alone does **not** collapse Path B.

| test_id | DER 250ms | JER | WER main | RTF | hyp/ref |
|---|---|---|---|---|---|
| CLEAN Path B | 0.0913 | 0.1840 | 0.0870 | 0.1212 | 20/20 |
| EDGE_reverb | 0.0849 | 0.1756 | 0.1304 | 0.1263 | 20/20 |
| EDGE_overlap | 0.9936 | 0.5476 | 0.7391 | 0.2430 | 20/20 |
| EDGE_noise | 1.7005 | 0.5675 | 0.1739 | 0.2602 | 20/20 |
| EDGE_badmic | 0.6569 | 0.2863 | 1.0000 | 0.1910 | 20/20 |
| **WORST_CASE** | **1.2985** | **0.5981** | **0.9130** | **0.2907** | 20/20 |

Overlap real: max 12 concurrent; 91% of speech frames ≥2 speakers. Main vs bleed: +3.0 dB (EDGE_overlap) / +1.5 dB (WORST_CASE). WORST_CASE main hyp collapses to one ~18s blob (`хочу говорить`). DER>1 = false-alarm time > ref speech. Запрещены «should be fine», «robust», «handles noisy rooms», «graceful under echo». **Do not claim in slides** clean WER/DER на edge.

## Gate v2 `BAND_LIMITED` — REJECTED (cross-ref)

Eval [PR #4](https://cursor.com/codebase/johann-schilling/neuro-stenographer/pull/4) @ `ffe53ba4` (`docs/TEST_RESULTS.md`, `docs/GATE.md` on `cursor/demo-stress-20talker-0106`) **rejects** hard `BAND_LIMITED` on bound seats `s01`..`s20`. Measured @ `bed868b3` (gate pinned Implementer `e3ad908` / Platform `b12a2bb`): EDGE_noise drops main **s01** (hyp **19/20**); EDGE_badmic **6/20**; WORST_CASE **0/20**, empty `{transcript:[]}`. Full gated_v2 table stays on that PR. Do not claim those hyp counts. Do not claim clean WER/DER on WORST_CASE. No new WER/DER here.

**Для optional Path B** заявка под edge остаётся ungated stream tracking **20/20** @ `9908353d` (WORST_CASE WER main **0.9130**, DER **1.2985**). Не DEMO_STAGE6. Bound seats: soft-flag only, still ASR, same spirit as `SOFT_LOW`. Never hard-drop main/`s01`. Hard `BAND_LIMITED` only for unbound/anonymous. Platform code fix is PR #3. Fixtures not retuned. Hold merge; #2 first.
