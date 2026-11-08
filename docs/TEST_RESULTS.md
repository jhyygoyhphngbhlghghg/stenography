# Методика и результаты тестирования

Собственные прогоны команды (не судейский набор «котики»).

**Запрещено выдумывать WER/RTF/DER.** Пока нет замера — `pending` / `blocked` с причиной.

Шаблон строк: `artifacts/test-results/results.csv`. Demo-stress: `artifacts/eval/demo-stress/metrics.csv`.

## Сводка финальных прогонов (ночь 2026-11-07/08, по отчётам финишеров)

Провенанс каждой строки: ветка/коммит + артефакт. «REPORT agentN» — отчёт
соответствующего финишера; у agent1 отчёт на момент сведения не появился,
строки agent1 взяты из его закоммиченных артефактов.

### Юнит-тесты (JVM, gradle)

| состояние дерева | коммит | тестов | падений | источник |
|---|---|---|---|---|
| baseline runtime-ветки (stenographer + presentConnect) | `8eb4bb5` | **126 / 126** (21 класс на flavor) | 0 | agent1 `artifacts/optimization/verification.json` |
| ветка agent2 (LLM+экспорт) | `06da663` | **147** (gradle-прогон `testStenographerDebugUnitTest`, XML-результаты) | 0 | wt-a2 `src/android/app/build/test-results/` |
| ветка продуктовых фич / сводное дерево ship | `7912c20` / ship | **166 @Test-методов, 27 классов**; последний полный gradle-прогон green | 0 | REPORT agent5 §1 |
| веб-бэкенд (`npx tsx --test`) | `agent4-web-sync-qr` | **9/9** после фикса desync в `server.test.ts`; `typecheck`/`build` exit 0 | 0 | REPORT agent4 §1 |
| host-зеркала (python) | — | 61 + 18 (A-12) | 0 | `ci-check`, agent1 verification |

Расхождение: в задании на сведение фигурировало «178 тестов (agent2 state)»;
по фактическим XML ветки agent2 их **147**, по сводному дереву — **166**
@Test-методов. Цитируем измеренное.

### Эмуляторные e2e-прогоны

| прогон | окружение | результат | источник |
|---|---|---|---|
| Cold-boot, установка debug-APK, распаковка моделей офлайн | LDPlayer `agent2_llm` (android-34, Houdini ARM) | `models unpack ready=true copied=7`, GGUF 352 969 408 B на месте, JNI loaded, строка LLM корректна; GGUF-инференс 1.6–2.2 с/реплику, без пустых выводов/зависаний/галлюцинаций спикеров | REPORT agent2 |
| Полный пайплайн с GGUF на устройстве (ARM-трансляция) | LDPlayer SOV44, commit `3af1e5a` | ASR-only: wall 23.7 с, RTF 0.557, peak PSS **244 МБ**; полный прогон с LLM: LLM-фаза ~330 с, peak PSS **400 МБ**, транскрипт изменился (плюс/кавычки) — доказательство реального llama.cpp-инференса; фикстура — чистый монтаж, 4/6 реплик, «not far-field proof» | agent1 `artifacts/compact/ldplayer-real-check.json` |
| presentConnect: установка, запуск, JNI, распаковка | LDPlayer `emulator-5554`, build `-x prepareEmbeddedModels` | `SherpaJni loaded=true`, модели распакованы (70 МБ encoder + vad/diar), **не stub** | REPORT agent3 §3 |
| Продуктовый UI: запись → расшифровка e2e | emulator-5554, commit `7912c20` | ~45 adb-взаимодействий, `alternating_two_voices.wav`, прогресс и результат по скриншотам; gradle test+assemble green | REPORT agent5 §1 |

Физический телефон: **не подключался ни в одном прогоне**
(`android_device_execution: false`; sideload-проба — human-действие).

### Офлайн-экспорт (матрица agent2, host-генерация тех же кодов, что на устройстве)

| формат | UTF-8 | спикеры | таймстемпы | открывается | оговорка |
|---|---|---|---|---|---|
| JSON | ✓ без BOM | ✓ | ✓ `start_ms`/`end_ms` | round-trip + `verify-transcript-json.py` ✓ | — |
| TXT | ✓ | ✓ | ✓ `[mm:ss.d - mm:ss.d]` | 234 B | — |
| DOCX | ✓ (UTF-8 XML) | ✓ | ✓ | валидный OOXML zip (1080 B) | — |
| PDF | — | — | — | валидный PDF-1.4 (799 B) | **кириллица требует встроенного шрифта** (Helvetica её не кодирует) |
| аудио-экспорт | — | — | — | — | **не существует** (in-memory PCM, ре-энкода нет) |

Артефакты: `artifacts/agent2/export_canonical.{json,txt,docx,pdf}`,
`evidence.json`. GGUF-качество: 6 кейсов, длинный/EN вход часто даёт мусор —
нейтрализуется гейтом `mergeCandidate` (содержательные слова ≠ hyp → отказ);
реплики >1664 знаков GGUF пропускает (эвристика), ctx 1024 не переполняется.

### Present-connect: живой hosted-deck тест (6 спикеров, RU)

Эмулятор → боевой сервер `https://present.144.31.215.37.sslip.io/` (REPORT agent3 §4):

| проверка | результат |
|---|---|
| `POST /v1/transcript` (Bearer PHONE_TOKEN) | `accepted:1`, `demo_phase:live` |
| вторая строка (`speaker_2`) | `rows_pushed:2` |
| экспорт | 4-ключевая схема брифа ✓ |
| 6-спикерный раунд (800–39 800 мс, последовательно, RU-реплики формы DEMO_STAGE6) | `accepted:6`, `phone_connected:true`, `rows:6`; экспорт содержит все 6 |
| дек на слайде `demo` (index 4) | LIVE-бейдж, HUD-строки 03–06, `banner:null`, `scroll_locked:true`, `body.innerText` содержит все 6 реплик |
| видео-подтверждение | **GIF `artifacts/agent3/gif/deck-live-6spk.gif`** (640×601, 2 fps, 3 с) + покадровые PNG |

### Веб: синхронизация и QR-вход (agent4)

| проверка | результат |
|---|---|
| app → site `POST /v1/transcript` | **78 мс**; HUD на `/join?session=…` обновился без reload (<5 с): «телефон: запись», «демо: в эфире», чип сессии |
| site → app `POST /v1/status` | **77 мс**; `phone.recording=true` в деке <5 с |
| чекпоинт-навигация | `checkpoint.set index=5` → `slide_index:5 slide_id:live revision:3`, счётчик `06 / 09`, кольцо мест speaker_1…6 |
| cross-device | desktop + эмулятор на одной сессии одновременно, без конфликтов |
| QR-вход с эмулятора | URL достижим **10/10** (curl 200); чистый UI-лендинг 6/10 (Chrome First-Run перехватывает тап — ограничение автоматизации, не сайта) |
| QR-фикс первого слайда | payload = `origin+pathname+search` — live на сервере и в репозитории (`4c5dd6e`) |
| live-состояние стенда (read-only, 2026-11-08) | `/` 200, `/v1/health` 200 (`screens:2`), `/api/invite` 200, `/join` 200, `/presenter` 200 |

### Сервер: эндпоинты, WebSocket, нагрузка (agent3, первая половина)

| группа | результат |
|---|---|
| HTTP-эндпоинты (`/v1/health`, `/v1/presentation`, `/v1/sessions/{id}/transcript`, `/v1/transcript`, `/v1/status`, `/v1/workers/*`, `/api/state`, `/api/invite`, `/api/login`, `/api/command`) | все отвечают корректно; `POST /api/login` 200/401 по токену; `checkpoint.step/goto` bumps revision; 429-троттлинг |
| WebSocket | `hello → hello.ok`, `transcript.partial` (relay `partial:true`), `transcript.final` (broadcast), ping/pong 15 с, reconnect с тем же `session_id`, mid-stream drop recovery |
| нагрузка | 5-минутный 2-клиентский стрим — сервер выжил; ручные 5× POST-бёрсты приняты без падения; RSS ~56→58 МБ, утечек нет; `npm run typecheck` ✓ |
| найденное | чекпоинты **не переживают рестарт** (in-memory); LLM/SSE-эндпоинтов нет (404 — by design); BOM-баг PowerShell-клиента (не сервера) |

### WER/RTF-замеры (host) и restamp-оговорка

Все строки — ниже в этом файле и в `artifacts/eval/`. Ключевые: DEMO_STAGE6
Hex keep-punct WER **0.7794** (baseline) → **0.6471** (hard-cap K≤6, Impl
`3c3f9ce`), DER 0.2760, JER 0.5454, гипотеза 6/6, RTF 0.1173 (host Linux).
**Restamp-оговорка ci-check:** `scripts/ci-check.sh` пересчитывает метрики из
уже закоммиченных hyp-файлов и перезаписывает `metrics.csv/json/run.log` —
сверка до/после на Windows/Python 3.13 дала `metric differences (excluding
timestamp/commit): 0` (27 строк). Коммит, где в metrics.csv изменились только
штамп времени и SHA коммита, — это воспроизведение, а не перенастройка
(подробности — §«Пересчёт замеров на этой машине»).

## Методика (черновик)

1. Собрать debug APK flavor `stenographer`.
2. Положить модели (`scripts/download-models.sh` + push).
3. Авиарежим.
4. Импорт 2–3 своих диалогов (RU, ≥2 спикера, без перекрытия).
5. Экспорт JSON, сверка `speaker_id` / `start_ms` / `end_ms` / `text` с ручной расшифровкой.
6. Зафиксировать wall time и длительность аудио (RTF = wall / duration) только после реального замера.

Host-Linux shortcut (no KVM/ADB): Common Voice 17 RU clips + Python sherpa-onnx (`scripts/eval_harness.py --run-host`).

## Статус overnight

| Проверка | Статус |
|---|---|
| Gradle `assembleDebug` | green on this machine (sherpa-onnx 1.13.5 AAR + Kotlin API) |
| Unit tests (`testStenographerDebugUnitTest`) | run via `scripts/ci-check.sh` |
| JSON schema (`verify-transcript-json.py`) | fixture OK; extra keys rejected |
| Eval WER+RTF (clip_01..03) | **measured** on host Linux (see below) |
| Demo-stress 20-talker (host Linux) | **measured** REAL CV17; eSpeak baseline superseded |
| Import → stub list → JSON (no models) | pending on-device |
| Zipformer-ru on-device | pending models + adb (host path already ran) |
| Diarization ≥2 speakers | pending on-device; host Path B recovered 20/20 |
| Airplane mode | pending on-device |
| PresentConnect ≤20 streams | unit cap test; WS needs web BE |

## clip_01..03 (overnight harness, host Linux)

Same Common Voice 17 RU talkers as demo-stress (CC0). Turn-taking, **no overlap**. Gold = corpus `sentence`. Speaker-blind WER (concatenated transcript text) + RTF. Wavs gitignored; refs committed.

| test_id | status | WER | RTF | duration_ms | wall_ms |
|---|---|---|---|---|---|
| clip_01 | measured | **0.0345** | **0.1372** | 22908 | 3142 |
| clip_02 | measured | **0.0000** | **0.1219** | 18336 | 2236 |
| clip_03 | measured | **0.0000** | **0.1291** | 23904 | 3086 |

JSON: `artifacts/eval/hyp/clip_0{1,2,3}.json` validates. CSV: `artifacts/test-results/results.csv`. Stack: Python `sherpa-onnx==2.0.1` Zipformer-ru + OfflineSpeakerDiarization, `num_clusters` 2/2/3. Morning handoff: `docs/overnight_clips_handoff.md`.

## DEMO_STAGE6 — PRIMARY demo fixture (one mic, 6 talkers)

H1 / Johann format change: **stop treating 20-stream soft-bound as the presentation target.** This row is the demo. 20-stream Path B / `*_gated_v3` is **secondary regression only**.

Recipe: `samples/demo-stress/stage6/` (CV17 CC0, 6 distinct `client_id`s). One-mic mix, turn-taking with **200 ms** end overlap only (max 2 concurrent; 2.56% of speech frames ≥2 speakers). Distances 3.0–6.0 m. SYNTHETIC large-room IR RT60 1.4 s. Medium HVAC (~14 dB) + quiet babble bed (~18 dB). Nothing-class phone mic on the mix (not EDGE_badmic crush). Gold = corpus `sentence`. **Not retuned after seeing scores.** WER tokenize = Hex.Team lock 2026-11-06 (keep `,` `.` `?` `!`; fold ё→е).

Stack: Path A `OfflineSpeakerDiarization` `num_clusters=6` + Zipformer-ru on the mix. Wall **3690 ms** / **39718 ms**, RTF **0.0929**. Same frozen hyp JSON as @ `a8b39d5a` (no new ASR); WER rescored only.

### Hex.Team WER lock — DEMO_STAGE6 before / after

Prior tokenize stripped punct (56 word tokens). New tokenize keeps punct as tokens (68). Same hyp, same gold wording, same wav.

| speaker | m | WER strip-punct (prior @ a8b39d5a) | WER Hex keep-punct (now) |
|---|---|---|---|
| speaker_1 | 3.0 | **0.0000** | **0.2857** |
| speaker_2 | 3.6 | **0.5455** | **0.5833** |
| speaker_3 | 4.2 | **1.0000** | **1.0000** |
| speaker_4 | 4.8 | **1.0000** | **1.0000** |
| speaker_5 | 5.4 | **1.0000** (missing) | **1.0000** (missing) |
| speaker_6 | 6.0 | **1.0000** | **1.0000** |
| **aggregate** | — | **0.7321** (41/56) | **0.7794** (53/68) |

| test_id | DER | JER | purity | WER_main | WER_agg | RTF | hyp/ref |
|---|---|---|---|---|---|---|---|
| DEMO_STAGE6 prior (strip punct) | **0.5174** | **0.6280** | 1.0000 | **0.0000** | **0.7321** | **0.0929** | **5 / 6** |
| DEMO_STAGE6 Hex WER lock | **0.5174** | **0.6280** | 1.0000 | **0.2857** | **0.7794** | **0.0929** | **5 / 6** |

Punct audit (dry CV pauses, not mix HVAC): s1/s3/s6 literary commas sit on real pauses; s2/s4/s5 stay period-only. **No gold wording or punct change.** Zipformer hyp has no commas/periods, so s1 WER 0.0→0.2857 is four punct deletions, not a word-error change.

Honest: IDEAL ≪ 0.1 does **not** hold. Do not cite WER_main 0 (that number is the old strip-punct score). Soft-flag only. Do not retune gold.

### DEMO_STAGE6_ffgain — Platform-only delta @ `b66581c`

Same **frozen** wav/gold as baseline @ `a8b39d5a`. Host ingest mirrors Platform `FarFieldGain` + `DemoPcmPrep` (`5bfc582` / `b66581c`): RMS-target **0.08 FS**, max **+24 dB**, peak 0.95; CLIPPED/BAD_MIC/empty hard; distant never hard-dropped for energy. Impl far-field still in flight. **Not a fixture retune.**

Measured ingest on this mix: RMS **0.1191 → 0.0800** (**−3.46 dB**, `boosted=false`) because the frozen room mix already sat **above** the 0.08 target (peak 0.89). Energy-VAD fire 1.000 → 0.977. Whole-file AGC cannot lift 5–6 m seats relative to the 3 m talker here. Synth 5–6 m VAD 0→1 still holds on Platform sine scenes (`artifacts/test-results/farfield-gain.json`).

Prior strip-punct WER (cite only). Hex keep-punct rescore of the same hyps is the STAGE6 table above. ffgain/perturn Hex WER_agg: **0.7500** / **0.7794** (DER/JER/hyp unchanged).

Honest: diarization **unchanged** on ffgain (same 5 segments, speaker_5 still missing). 4–6 m still WER 1.0. Tiny speaker_2 ASR delta on whole-file AGC only. Do **not** claim far-field gain recovered the room. Do **not** cite old strip-punct WER_main 0 as the room result.

### DEMO_STAGE6_perturn — Platform per-turn @ `db3afe4`

Same **frozen** wav/gold. Host ingest mirrors `FarFieldGain.stagePerSegment` (`ae86903` / `db3afe4`): quiet energy windows independently target 0.08 FS (max +24 dB, peak 0.95); loud turns stay **0 dB** (never crush). CLIPPED/BAD_MIC/empty still hard. Soft-flag only.

On Platform **synth** sequential mix: near 3 m 0 dB; far 5 m **+16.8 dB**; far 6 m **+18.2 dB**; whole-file AGC would have been −3.55 dB. On this **frozen STAGE6 mix**, 34 energy islands: **33 already ≥ 0.08 FS → 0 dB**; only the 80 ms lead-in got **+16.55 dB**. Mix RMS 0.1191 → 0.1192, `loud_crushed=false`. Far seats on this fixture are not the quiet 0.01 FS sine scenes.

Measured (Hex WER): WER_agg **0.7794** (same hyp as baseline), DER **0.5231** / JER **0.6330**, hyp **5 / 6**, speaker_5 still missing, 4–6 m still WER 1.0. **Gain did not recover the room.**

### DEMO_STAGE6_phone_eq — Platform PhoneDistanceEq @ `af71d91`

Same **frozen** wav/gold @ `a8b39d5a`. Hex keep-punct tokenize @ `0824df64`. Host ingest mirrors Platform `PhoneDistanceEq` + `DemoPcmPrep` on `platform/derisk-sherpa-present` HEAD **`af71d91fce841e02d841afb0b093d16f51e5bddb`** (feat `5deb55a` intro, `040274d` 250–4500 Hz band). Files used (not cherry-picked onto this eval branch — Python mirror):

- `src/android/.../audio/PhoneDistanceEq.kt`
- `src/android/.../pipeline/DemoPcmPrep.kt`
- `src/android/.../audio/FarFieldGain.kt` (`FarFieldSynth.phoneDistanceScene`)
- `src/android/.../audio/PhoneDistanceEqTest.kt`
- `scripts/measure-phone-distance-eq.py`

Gain **stays frozen** (per-turn; whole-file crush not re-enabled). EQ: 1600 Hz 1-pole high-shelf up to +9 dB on **far** islands; near seat (active RMS ≥ 0.12 FS) is identity. CLIPPED/BAD_MIC/empty still hard. Soft-flag only. **Not a fixture retune.**

On Platform **synth** (host `measure-phone-distance-eq.py --record`, numbers match Platform §8): isolated 3/4/5/6 m speech 250–4500 Hz lifted (e.g. 6 m 5.92e4 → 7.79e4); sequential mix near 3 m identity 0.137 → 0.137. On this **frozen STAGE6 mix**: 34 energy islands, **33 near-identity**; EQ applied only to the **80 ms lead-in** (+5.13 dB shelf, RMS 0.080 → 0.103). Mix RMS 0.1191 → 0.1192, `loud_crushed=false`, `near_crushed=false`. Far seats on this fixture are already ≥ 0.12 FS — not the quiet 0.01 FS synth scenes.

Hex keep-punct WER vs baseline @ `0824df64` (same gold, same wav):

| speaker | m | DEMO_STAGE6 Hex @ 0824df64 | DEMO_STAGE6_phone_eq |
|---|---|---|---|
| speaker_1 | 3.0 | **0.2857** | **0.2857** |
| speaker_2 | 3.6 | **0.5833** | **0.5833** |
| speaker_3 | 4.2 | **1.0000** | **1.0000** |
| speaker_4 | 4.8 | **1.0000** | **1.0000** |
| speaker_5 | 5.4 | **1.0000** (missing) | **1.0000** (missing) |
| speaker_6 | 6.0 | **1.0000** | **1.0000** |
| **aggregate** | — | **0.7794** (53/68) | **0.7794** (53/68) |

| test_id | DER | JER | purity | WER_main | WER_agg | RTF | hyp/ref |
|---|---|---|---|---|---|---|---|
| DEMO_STAGE6 Hex @ 0824df64 | **0.5174** | **0.6280** | 1.0000 | **0.2857** | **0.7794** | **0.0929** | **5 / 6** |
| DEMO_STAGE6_phone_eq @ af71d91 | **0.5208** | **0.6310** | 1.0000 | **0.2857** | **0.7794** | **0.0945** | **5 / 6** |

ASR text is the same as baseline (s1 words unchanged; Zipformer still emits no punctuation). Only s1 diar edges moved (672–6511 ms → 723–6443 ms), which nudges DER/JER. speaker_5 still missing. 4–6 m still WER 1.0. Wall **3754 ms** / **39718 ms**.

Honest: PhoneDistanceEq **did not recover the room** on this frozen mix. Do **not** cite WER_main 0 as the room result (that is the old strip-punct s1 score). Soft-flag only.

### DEMO_STAGE6_impl_second_pass — Impl VadDiarReconcile + IntonationPunct @ `552b142`

Same **frozen** wav/gold @ `a8b39d5a`. Hex keep-punct tokenize @ `0824df64`. Host ingest mirrors Implementer PR #7 HEAD **`552b142898f3cf3a8bddb939f0864730dbafb261`** (`impl/far-field-6spk-demo`). Files used (Python mirror, not cherry-picked onto this eval branch):

- `src/android/.../pipeline/VadDiarReconcile.kt`
- `src/android/.../pipeline/FarFieldScene.kt` (K=6, orphan 400 ms, expand ≤1500 ms, merge 280 ms)
- `src/android/.../audio/FarFieldVad.kt`
- `src/android/.../llm/IntonationPunct.kt` / `LocalLlmPostPass.kt`
- unit: `orphanFifthTurnIsRecoveredAsSpeaker`, `punctlessZipformerAcceptsAddedIntonationPunct`

Frozen per-turn FarFieldGain (no whole-file crush). EQ not re-applied (stays frozen). Energy VAD ∪ Silero → `VadDiarReconcile` → Zipformer (+200 ms crop pad) → **IntonationPunct ADD** spoken `,` `.` `?` `!` (never strip). Host GGUF / llama.cpp JNI **absent** (`gguf-missing`); heuristic ADD only, same merge rules as `GgufLlmPostPass`. Soft-flag only. **Not a fixture retune.**

Reconcile on this mix: pyannote **5→12** segments (energy 34 ∪ silero 3 → 4 VAD islands). Near 3 m seat kept (807–6443 ms). Orphans filled holes after s1/s2/s4; s5/s6 time is covered but ASR empty on those islands.

Hex keep-punct WER vs baselines (same gold, same wav):

| speaker | m | Hex @ 0824df64 | phone_eq @ 5ffae114 | impl_second_pass @ 552b142 |
|---|---|---|---|---|
| speaker_1 | 3.0 | **0.2857** | **0.2857** | **0.2143** |
| speaker_2 | 3.6 | **0.5833** | **0.5833** | **0.3333** |
| speaker_3 | 4.2 | **1.0000** | **1.0000** | **0.7692** |
| speaker_4 | 4.8 | **1.0000** | **1.0000** | **0.5000** |
| speaker_5 | 5.4 | **1.0000** (missing) | **1.0000** (missing) | **1.0000** (empty ASR) |
| speaker_6 | 6.0 | **1.0000** | **1.0000** | **1.0000** (empty ASR) |
| **aggregate** | — | **0.7794** (53/68) | **0.7794** (53/68) | **0.6029** (41/68) |

| test_id | DER | JER | purity | WER_main | WER_agg | RTF | hyp/ref |
|---|---|---|---|---|---|---|---|
| DEMO_STAGE6 Hex @ 0824df64 | **0.5174** | **0.6280** | 1.0000 | **0.2857** | **0.7794** | **0.0929** | **5 / 6** |
| DEMO_STAGE6_phone_eq @ af71d91 | **0.5208** | **0.6310** | 1.0000 | **0.2857** | **0.7794** | **0.0945** | **5 / 6** |
| DEMO_STAGE6_impl_second_pass | **0.2568** | **0.6821** | 0.9002 | **0.2143** | **0.6029** | **0.1198** | **12 / 6** |

s1 WER 0.2857→0.2143 is the added terminal period (3 comma deletions remain; Zipformer still emits no mid-sentence commas). Heuristic `что …` got `?` on s3/s8 — not gold intonation. GGUF did not run.

Honest: DER improved (time coverage). hyp is **12 / 6**, not 6/6 — JER slightly worse. s5/s6 still WER 1.0. Do **not** cite WER_main 0 as the room result. Soft-flag only.

### DEMO_STAGE6_impl_hardcap6 — Impl HARD CAP ≤6 + comma heuristics @ `3c3f9ce`

Same **frozen** wav/gold @ `a8b39d5a`. Hex keep-punct tokenize @ `0824df64`. Host ingest mirrors Implementer PR #7 HEAD **`3c3f9ce61f803d716276f95fa7e6098cc2fe7be4`** (`impl/far-field-6spk-demo`). Python mirror (not cherry-picked onto this eval branch). Prior over-split row @ `552b142` / `9b29b9c1` kept.

- `VadDiarReconcile.kt` — `adopt` / `capToExpected`: extra VAD/energy islands merge into existing K=6; never `speaker_7..12`; near 3 m protected
- `IntonationPunct.kt` — heuristic ADD commas (greeting / vocative / discourse / contrast) when GGUF absent
- unit: `twelveVadIslandsNeverInventSpeakerSeven` → unique 6

Frozen per-turn FarFieldGain (no whole-file crush). PhoneDistanceEq **not** re-applied. Energy VAD ∪ Silero → HARD CAP reconcile → Zipformer (+200 ms crop pad) → IntonationPunct ADD (never strip). Host GGUF / llama.cpp **absent** (`gguf-missing`). Soft-flag only. **Not a fixture retune.**

Reconcile on this mix: pyannote **5→6** segments / **5→6** speakers (energy 34 ∪ silero 3 → 4 VAD islands). Near 3 m seat kept (807–6443 ms). Unique hyp speakers **6** — measured, never `speaker_7`. Hungarian left hyp `speaker_2` (`да.` stub 6443–7456) unmapped and ref speaker_5 unmatched.

Hex keep-punct WER vs baselines (same gold, same wav):

| speaker | m | Hex @ 0824df64 | impl_second_pass @ 552b142 / 9b29b9c1 | impl_hardcap6 @ 3c3f9ce |
|---|---|---|---|---|
| speaker_1 | 3.0 | **0.2857** | **0.2143** | **0.2143** |
| speaker_2 | 3.6 | **0.5833** | **0.3333** | **0.3333** |
| speaker_3 | 4.2 | **1.0000** | **0.7692** | **1.0000** |
| speaker_4 | 4.8 | **1.0000** | **0.5000** | **0.9000** |
| speaker_5 | 5.4 | **1.0000** (missing) | **1.0000** (empty ASR) | **1.0000** (unmatched) |
| speaker_6 | 6.0 | **1.0000** | **1.0000** (empty ASR) | **0.6923** |
| **aggregate** | — | **0.7794** (53/68) | **0.6029** (41/68) | **0.6471** (44/68) |

| test_id | DER | JER | purity | WER_main | WER_agg | RTF | hyp/ref |
|---|---|---|---|---|---|---|---|
| DEMO_STAGE6 Hex @ 0824df64 | **0.5174** | **0.6280** | 1.0000 | **0.2857** | **0.7794** | **0.0929** | **5 / 6** |
| DEMO_STAGE6_impl_second_pass @ 552b142 | **0.2568** | **0.6821** | 0.9002 | **0.2143** | **0.6029** | **0.1198** | **12 / 6** |
| DEMO_STAGE6_impl_hardcap6 @ 3c3f9ce | **0.2760** | **0.5454** | 0.7318 | **0.2143** | **0.6471** | **0.1173** | **6 / 6** |

HARD CAP did what the unit claims: hyp **6 / 6**, never speaker_7..12. JER 0.6821→0.5454 (over-split tax gone). DER 0.2568→0.2760 and WER_agg 0.6029→0.6471 are slightly worse — merge smeared s2+s3 into one 7456–19066 island. s6 recovered some words (empty→0.6923). s5 still WER 1.0. Discourse/contrast commas did not fire on this Zipformer text (no `ну`/`вот`/`но` mid-utterance); s1 still period-only + 3 comma deletions. GGUF did not run. Wall **4659 ms** / **39718 ms**.

Honest: hyp **6 / 6 is measured**. That is not a room solve. Do **not** cite WER_main 0 as the room result. Soft-flag only.

## Demo-stress (20 talkers) — SECONDARY regression, host Linux only

Cloud agents / this VM have **no KVM and no ADB**.

Fixture **REAL**: Mozilla Common Voice 17.0 Russian, test split, **CC0-1.0**, 20 distinct `client_id`s, measured SNR **3.00 dB**, 90 ms stagger, 18.736 s mix. Gold = corpus sentences. Recipe: `samples/demo-stress/README.md`.

```bash
python3 scripts/fetch_cv17_ru.py
python3 scripts/generate_demo_stress_mix.py
python3 scripts/eval_harness.py --suite demo-stress --run-host
python3 scripts/diar_param_sweep.py
```

### Headline (REAL Common Voice) — this VM

Stack: Python `sherpa-onnx==2.0.1`, Zipformer-ru int8 + pyannote segmentation-3.0 + 3D-Speaker embedding. No Android.

| test_id | status | DER 250ms | DER uncollared | JER | purity | WER main | RTF | hyp/ref spk |
|---|---|---|---|---|---|---|---|---|
| mix_20talker (Path A) | measured | **0.9500** | 0.9497 | **0.9520** | 0.9983 | **1.0000** | **0.1784** | 1 / 20 |
| mix_20talker_main_dry | measured | 0.0192 | 0.0921 | 0.0843 | 0.9157 | **0.1739** | 0.0587 | 1 / 1 |
| mix_20talker_per_stream (Path B) | measured | **0.0913** | 0.1728 | **0.1840** | 0.9983 | **0.0870** | **0.1212** | **20 / 20** |
| smoke | measured | n/a (collar ate 1.24 s) | 0.9025 | 0.9620 | 0.3774 | 1.0000 | 0.7855 | 2 / 20 |

Path A mix wall **3343 ms** / **18736 ms**. Path B wall **2271 ms** / **18736 ms** (20 dry tracks, RMS VAD + Zipformer, no pyannote). Dry-main Zipformer reads the three CV sentences (WER 0.1739 vs eSpeak 1.0). Mix hyp still one blob — cocktail overlap, not OOD TTS. JSON schema unchanged: `transcript[].{speaker_id,start_ms,end_ms,text}` only.

### Path B DER try (same branch, measured)

Hypothesis from the 0.2350 row: residual DER was looping babble vs one-utterance refs + VAD/segment edges, not identity collapse. Path A mix/ref untouched.

| Path B `mix_20talker_per_stream` | DER 250ms | JER | WER main | RTF | wall_ms | segs | hyp/ref |
|---|---|---|---|---|---|---|---|
| before (looped dry + per-stream diar) | **0.2350** | **0.2716** | **0.0870** | 2.6481 | 49615 | 60 | 20 / 20 |
| after (unlooped dry + Path-B-only ref + RMS VAD) | **0.0913** | **0.1840** | **0.0870** | **0.1212** | 2271 | 27 | 20 / 20 |

Real improvement on DER/JER/RTF; WER_main held. Knobs: unlooped babble dry tracks + `ref/mix_20talker_per_stream.json` (original clip spans, not offset→mix_end); RMS VAD `rel_thresh=0.04`, `pad_ms=250`, `min_duration_on=0.20`, `min_duration_off=0.45`, re-merge after pad. First VAD pass (`pad_ms=80`, `rel_thresh=0.12`) over-split main (WER_main 0.2609, JER 0.3133) — discarded. Residual DER is mostly CV clip silence in refs vs speech-only VAD edges. Regression: `python3 scripts/test_path_b_regression.py`.

### Path A vs Path B (diarization collapse 20→1)

**A. One-room mix** (`scripts/diar_param_sweep.py`, diar only, measured):

| num_clusters | threshold | min_on/off | hyp speakers | segments | DER | JER |
|---|---|---|---|---|---|---|
| 20 | 0.5 | 0.2/0.5 | **1** | 1 | 0.95 | 0.952 |
| 20 | 0.3 | 0.2/0.5 | **1** | 1 | 0.95 | 0.952 |
| 20 | 0.8 | 0.2/0.5 | **1** | 1 | 0.95 | 0.952 |
| 20 | 0.5 | 0.1/0.2 | **1** | 1 | 0.95 | 0.952 |
| -1 (auto) | 0.5 | 0.2/0.5 | 3 | 5 | 0.9375 | 0.9376 |
| -1 (auto) | 0.3 | 0.1/0.3 | 5 | 7 | 0.9372 | 0.9361 |

Forcing `num_clusters=20` still yields **one** cluster: pyannote segmentation sees the overlapped cocktail as a single speech region, so FastClustering has nothing to split. Auto-threshold finds 3–5 speakers; DER stays ~0.94 because 19 babble tracks occupy the same time. Clustering knobs do not rescue a 20-way additive mix.

**B. Per-stream / PresentConnect (tech demo):** RMS VAD + ASR each unlooped `dry/spk_XX.wav` with oracle stream→`speaker_id` (`s0N`↔`speaker_N`, main=`s01`/`speaker_1`), merge by time. **20/20 speakers recovered.** DER **0.0913** / JER **0.1840** / WER_main **0.0870** / RTF **0.1212**. Identity did not collapse. Residual DER is clip-silence in refs vs VAD speech edges.

Demo implication: the 20-mic PresentConnect path is the one that can track talkers; a single room mic with 20 overlapping voices is still a measured fail.

### Path B regression gate — FROZEN, SECONDARY (H1 accepted WORST_CASE @ 9908353)

**Not the presentation target** (see DEMO_STAGE6 above). These six Path B rows are **frozen**. They change only with Lead sign-off. Do **not** retune fixtures, VAD, or refs to make numbers pretty. Re-run host inference only when Implementer/Platform claim a harden fix. Policy: `docs/GATE.md`. EDGE_* / hybrid Path B stay green.

**Lock claim: 20/20 stream tracking holds under edge; do NOT claim clean WER/DER on WORST_CASE.**

WER columns in the frozen Path B tables below are **strip-punct historical**. Hex.Team lock (keep `,` `.` `?` `!`) rescored CLEAN `WER_main` 0.0870→**0.2759** (gold periods now count). DER/JER/20/20 unchanged. See `metrics.csv`.

Frozen ids: `mix_20talker_per_stream` (CLEAN), `EDGE_reverb`, `EDGE_overlap`, `EDGE_noise`, `EDGE_badmic`, `WORST_CASE`. All six stay in `artifacts/eval/demo-stress/metrics.csv` / `metrics.json` / this table.

### Johann hard requirement — adversarial Path B (worst-case demo room)

CLEAN Path B (`mix_20talker_per_stream`) is **optimistic**: isolated dry CV clips, 90 ms stagger, no bleed, no room. H1/Johann: measure the **real demo room**, expect FAIL, **do not tune fixtures to look good**. H1 accepted this WORST_CASE table at `9908353`.

Same Path B stack (20 streams, RMS VAD + Zipformer, oracle `s0N`↔`speaker_N`). Speech still CV17 CC0. All IR / HVAC / chair / door / phone / white are **SYNTHETIC** (`scripts/generate_adversarial_path_b.py`). Overlap timeline: max **12** concurrent talkers; **90.99%** of speech frames have ≥2 speakers; mean **8.28** active. Bleed on s01 measured **+3.00 dB** (EDGE_overlap) / **+1.50 dB** (WORST_CASE) vs other talkers on main-active frames. Recipe: `samples/demo-stress/adversarial/`.

DER can exceed 1.0 when false-alarm time (noise/VAD) exceeds ref speech.

| test_id | status | DER 250ms | DER uncollared | JER | purity | WER main | RTF | hyp/ref spk |
|---|---|---|---|---|---|---|---|---|
| mix_20talker_per_stream **CLEAN Path B** | measured | **0.0913** | 0.1728 | **0.1840** | 0.9983 | **0.0870** | **0.1212** | 20 / 20 |
| EDGE_reverb | measured | **0.0849** | 0.1633 | **0.1756** | 0.9984 | **0.1304** | 0.1263 | 20 / 20 |
| EDGE_overlap | measured | **0.9936** | 1.1753 | **0.5476** | 0.9807 | **0.7391** | 0.2430 | 20 / 20 |
| EDGE_noise | measured | **1.7005** | 1.2941 | **0.5675** | 0.9203 | **0.1739** | 0.2602 | 20 / 20 |
| EDGE_badmic | measured | **0.6569** | 0.5246 | **0.2863** | 0.9497 | **1.0000** | 0.1910 | 20 / 20 |
| WORST_CASE | measured | **1.2985** | 1.4881 | **0.5981** | 0.9491 | **0.9130** | 0.2907 | 20 / 20 |

Wall: EDGE_reverb 2367 ms / 18736 ms; EDGE_overlap 4392 / 18076; EDGE_noise 4875 / 18736; EDGE_badmic 3578 / 18736; WORST_CASE **5255 / 18076**.

Honest read (not spun):

- Isolated-mic **reverb alone** does not collapse Path B (WER 0.0870→0.1304). Echo is not the demo-room killer if each stream is still a clean close mic.
- **True overlap + bleed** is: DER **0.99**, WER_main **0.74**. Leaky mics + 8-way concurrent speech.
- **Room noises** blow DER to **1.70** (VAD false alarms on HVAC/chairs/phone); main WER stays 0.1739.
- **Bad phone mic** zeros ASR: WER_main **1.0000**.
- **WORST_CASE** (all stacked, main +1.5 dB on s01): DER **1.30**, JER **0.60**, WER_main **0.91**. Hyp main is one 18 s blob whose text is `хочу говорить`. This is the demo-room row.

JSON validates (four fields). No invented numbers. Fixtures were not relaxed after seeing FAIL.

### StreamPcmGate remeasure (harden claim — fixtures still FROZEN)

Implementer Path B E2E PR #6 @ `dae416f` (StreamPcmGate byte-identical to Platform `f5ff5e2`). Host ingest now runs that gate on each dry stream **before** VAD/ASR. Frozen wavs/refs/VAD knobs **unchanged**. New rows `*_gated` sit beside the frozen baseline (do not replace it). Baseline snapshot: H1-accepted table @ `a863b2a6` / measured @ `9908353`.

Hard reject: no ASR, no brief row. Codes: `LOW_ENERGY` (RMS below 0.008 FS or peak below 0.012 FS or fewer than 800 samples), `CLIPPED` (at least 2% of samples `|s16| >= 32760`), `BAD_MIC` (`|DC| >= 0.20` FS or stuck). Soft flags still ASR.

| test_id | baseline @ a863b2a6 DER / JER / WER / RTF / hyp | gated @ HEAD DER / JER / WER / RTF / hyp | gate |
|---|---|---|---|
| EDGE_noise | **1.7005** / 0.5675 / **0.1739** / 0.2602 / **20/20** | **1.2940** / 0.7312 / **0.1739** / 0.2656 / **11/20** | passed **11/20**, dropped **9** `LOW_ENERGY` |
| EDGE_badmic | **0.6569** / 0.2863 / **1.0000** / 0.1910 / **20/20** | **0.6569** / 0.2863 / **1.0000** / 0.2726 / **20/20** | passed **20/20**, dropped **0** |
| WORST_CASE | **1.2985** / 0.5981 / **0.9130** / 0.2907 / **20/20** | **1.2985** / 0.5981 / **0.9130** / 0.3491 / **20/20** | passed **20/20**, dropped **0** |

Honest: dropping 9 quiet EDGE_noise streams cut collared DER 1.7005→1.2940; JER got worse (0.5675→0.7312) because those speakers are now misses; main WER held (s01 passed). StreamPcmGate does **not** catch EDGE_badmic (clip is 0.38 FS, not |s16|≥32760) or WORST_CASE (energy well above RMS/peak floors) — WER 1.0 / 0.91 unchanged. Do not claim clean WER/DER on WORST_CASE. Gated EDGE_noise is **11/20** speakers — the frozen 20/20 lock is the ungated baseline, not this ingest.

### StreamPcmGate post-hush-fix (Implementer `b142a4d`) vs prior gated @ `3b31e24`

Implementer removed pre-diar `SpeechEnergy` stream wipe; StreamPcmGate **thresholds unchanged**. Host eval never had that wipe; the 9-speaker loss was whole-file LOW_ENERGY on padded dry RMS (speech-active RMS still ≥ 0.016). `b142a4d` ingest: do not wipe the seat on padded-file LOW_ENERGY; still inspect() each ASR crop. Frozen wavs untouched. Prior `*_gated` rows @ `3b31e24` kept.

| test_id | prior gated @ 3b31e24 DER / JER / WER / RTF / hyp | post-hush @ HEAD DER / JER / WER / RTF / hyp | gate |
|---|---|---|---|
| EDGE_noise | **1.2940** / 0.7312 / **0.1739** / 0.2656 / **11/20** | **0.7092** / 0.4395 / **0.1739** / 0.4204 / **17/20** | streams **20 passed / 0 dropped**; 62 crops skipped LOW_ENERGY |
| EDGE_badmic | **0.6569** / 0.2863 / **1.0000** / 0.2726 / **20/20** | **0.6569** / 0.2863 / **1.0000** / 0.3729 / **20/20** | **20/0** streams; 0 crops skipped |
| WORST_CASE | **1.2985** / 0.5981 / **0.9130** / 0.3491 / **20/20** | **1.2985** / 0.5981 / **0.9130** / 0.5201 / **20/20** | **20/0** streams; 0 crops skipped |

Honest: EDGE_noise hyp **11/20 → 17/20** (closer to 20/20; 3 seats still have no surviving crop). DER 1.2940→0.7092 (crop-level LOW_ENERGY dropped HVAC FA). WER_main held 0.1739. badmic/WORST_CASE content unchanged. Do not claim clean WER/DER on WORST_CASE.

### StreamPcmGate v2 (Implementer `e3ad908` == Platform `b12a2bb`) — headline ingest

Python Path B ingest mirrors StreamPcmGate.kt @ Implementer PR #6 `e3ad908` (sha256 `3caa3a6a…` == Platform `b12a2bb`, not a divergent port): Goertzel `BAND_LIMITED` (hf_ratio &lt; 0.08, occ_speech ≥ 2, occ_hf ≤ 0, only if rms ≥ 0.020) plus bound-seat `SOFT_LOW` (s01–s20 near/below RMS floor still ASR; hard `LOW_ENERGY` only if unbound or n==0). CLIPPED/BAD_MIC still hard-reject. `BAND_LIMITED_SPEECH_OCC = 2` matches Kotlin, not the measure-script JSON comment of 3. Frozen wavs/refs/VAD **unchanged**. Prior `*_gated` @ `3b31e24` and `*_gated_b142a4d` @ `a42900e` kept as history. Headline rows: `*_gated_v2`.

| test_id | baseline DER / JER / WER / RTF / hyp | gated_v2 DER / JER / WER / RTF / hyp | gate pass/drop + reasons |
|---|---|---|---|
| EDGE_noise | **1.7005** / 0.5675 / **0.1739** / 0.2602 / **20/20** | **1.6234** / 0.5851 / **0.9565** / 0.4672 / **19/20** | **19/20** pass (s02–s20 `SOFT_LOW`); drop **1** `BAND_LIMITED` **s01** (rms 0.064, hf_ratio 0.0166, occ_speech 7, occ_hf 0) |
| EDGE_badmic | **0.6569** / 0.2863 / **1.0000** / 0.1910 / **20/20** | **0.7444** / 0.7707 / **0.2609** / 0.2961 / **6/20** | **6/20** pass (s01,s05,s06,s08,s09,s17); drop **14** `BAND_LIMITED` (s02–s04,s07,s10–s16,s18–s20) |
| WORST_CASE | **1.2985** / 0.5981 / **0.9130** / 0.2907 / **20/20** | **1.0000** / 1.0000 / **1.0000** / 0.1976 / **0/20** | **0/20** pass; drop **20** `BAND_LIMITED` (empty `{transcript:[]}`) |

Honest: v2 does what v1 could not — phone-crush seats fail `BAND_LIMITED` instead of sailing through RMS/clip/DC. Bound quiet EDGE_noise seats survive (`SOFT_LOW`, 19/20 vs v1 gated 11/20). Cost: EDGE_noise **s01** is loud enough for the band check and is rejected (HVAC/phone-like whole-file spectrum), so WER_main 0.1739→**0.9565**. EDGE_badmic WER_main 1.0000→0.2609 because s01 still ASR (hf_occupancy=1, so not `BAND_LIMITED`) and 14 crushed babble seats are gone; JER got worse (0.2863→0.7707) from those misses. WORST_CASE is a **full refuse**: 20/20 `BAND_LIMITED`, hyp **0/20**, DER/JER/WER **1.0**. Do not claim clean WER/DER on WORST_CASE. Frozen 20/20 lock remains the ungated baseline.

**Idle (closed @ `a5b7aed`):** gate v2 hard `BAND_LIMITED` was rejected for bound seats. Measured consequence @ `bed868b`: WORST_CASE **0/20** empty `{transcript:[]}`; EDGE_badmic **6/20**; EDGE_noise drops main **s01**. Platform / Implementer then softened bound crush — see `*_gated_v3` below. `*_gated_v2` rows stay as history.

### StreamPcmGate v3 (Implementer `a5b7aed` == Platform `686ac4a`) — SECONDARY regression

Not the DEMO_STAGE6 presentation fixture. Bound-seat soft `BAND_LIMITED` ingest on frozen 20-stream EDGE_noise / EDGE_badmic / WORST_CASE.

Python Path B ingest mirrors StreamPcmGate.kt @ Implementer PR #6 `a5b7aed` (sha256 `e29a34bb…` == Platform `686ac4a`). Bound `s01`–`s20`: `BAND_LIMITED` is a **soft** flag + still ASR (like `SOFT_LOW`). Bound main `s01` is never hard-dropped for energy or band-limit. `CLIPPED` / `BAD_MIC` stay hard. Empty `n==0` still skips. Frozen wavs/refs/VAD **unchanged**. Prior `*_gated_v2` @ `bed868b` kept. Rows: `*_gated_v3` (secondary).

| test_id | gated_v2 DER / JER / WER / RTF / hyp | gated_v3 DER / JER / WER / RTF / hyp | gate soft flags / hard drops |
|---|---|---|---|
| EDGE_noise | **1.6234** / 0.5851 / **0.9565** / 0.4672 / **19/20** | **1.7005** / 0.5675 / **0.1739** / 0.4649 / **20/20** | **20/20** pass; hard **0**. Soft: **1** `BAND_LIMITED` (**s01** present), **19** `SOFT_LOW` |
| EDGE_badmic | **0.7444** / 0.7707 / **0.2609** / 0.2961 / **6/20** | **0.6569** / 0.2863 / **1.0000** / 0.3823 / **20/20** | **20/20** pass; hard **0**. Soft: **14** `BAND_LIMITED` (s01 present, no crush flag — hf_occupancy=1) |
| WORST_CASE | **1.0000** / 1.0000 / **1.0000** / 0.1976 / **0/20** | **1.2985** / 0.5981 / **0.9130** / 0.4978 / **20/20** | **20/20** pass; hard **0**. Soft: **20** `BAND_LIMITED`. **s01 present** (not empty) |

Honest: v3 does what H1 asked — bound crush no longer empties the brief. WORST_CASE is **20/20**, not 0/20 from band-limit. EDGE_noise WER_main 0.9565→**0.1739** because **s01** is back (soft `BAND_LIMITED`, still ASR). EDGE_badmic WER_main 0.2609→**1.0000** matches the ungated phone-crush baseline: 14 crushed seats now reach ASR again and mapping/WER on main is still a fail. WORST_CASE DER/JER/WER match the frozen ungated row (1.2985 / 0.5981 / 0.9130); hyp main text is still `хочу говорить`. Do **not** claim clean WER/DER on WORST_CASE. Frozen 20/20 lock remains the ungated baseline.

#### Ablation (interpretive — no new run)

Read off the frozen table above, not a new measurement. Single-factor vs CLEAN Path B (DER 0.0913 / WER_main 0.0870):

- **WER** is dominated by **badmic** (1.0000). Overlap is next (0.7391). Reverb is mild (0.1304). Noise barely moves WER (0.1739, same order as dry-main 0.1739).
- **DER** is dominated by **noise** false alarms (1.7005, DER can exceed 1). Overlap collapses time alignment (0.9936). Badmic is moderate (0.6569). Reverb does not hurt DER (0.0849).
- **WORST_CASE** is the stack (DER 1.2985, WER_main 0.9130): overlap+bleed plus badmic WER, with noise FA in the mix. Do not cite CLEAN WER/DER as the demo-room result.

### Superseded eSpeak SYNTHETIC baseline (historical)

H1-ACK'd cocktail FAIL. Files: `artifacts/eval/demo-stress/espeak-baseline/`. Do not use as headline.

| test_id | DER 250ms | JER | WER main | RTF | hyp/ref |
|---|---|---|---|---|---|
| mix_20talker | 0.9500 | 0.9503 | 1.0000 | 0.1592 | 1 / 20 |
| mix_20talker_main_dry | 0.0217 | 0.0933 | **1.0000** | 0.0591 | 1 / 1 |

Dry WER=1.0 was eSpeak out-of-domain (hyp text `с`), not only overlap. REAL dry WER is 0.1739 on the same recipe.

## Сборка и самопроверка на смерженном дереве (2026-11-07, эта машина)

Все строки выше — хостовые замеры Eval-трека. Здесь — что проверено при
сборке смерженного `main` (Windows, JDK 21.0.4, gradle 8.11.1, AGP 8.7.3,
compileSdk 35, build-tools 35.0.0). Команды повторяемые.

| проверка | команда | результат |
|---|---|---|
| компиляция приложения + тестов | `./gradlew :app:compileStenographerDebugKotlin` | успешно (Kotlin 2.0.21 / jvmTarget 17) |
| JVM-юнит-тесты пайплайна | `./gradlew :app:testStenographerDebugUnitTest` | **110 тестов, 0 падений**, 20 классов |
| python-тесты host-зеркал | `python3 -m unittest discover -s scripts -p 'test_*.py'` | **61 тест, OK** |
| A-12 WER/RTF харнес | `python3 -m unittest eval.metrics.test_wer` | **18 тестов, OK** |
| схема JSON брифа | `bash scripts/ci-check.sh` | **43** закоммиченных JSON → `OK`, `ci-check OK` |
| check-APK с весами | `./gradlew :app:assembleStenographerDebug` | `app-stenographer-debug.apk` = 220 529 139 B, sha256 `c3592976…90da` |
| тот же APK + локальная LLM | `… -PembedLlm=true` | 573 498 719 B, sha256 `f89bc45a…3678` (8 ассетов, 472 435 459 B) |
| PresentConnect flavor | `:app:assemblePresentConnectDebug` | 220 401 103 B, sha256 `b9afd39f…0d83` |
| slim (без весов) | `:app:assembleStenographerSlim` | 97 599 021 B, sha256 `6c52e259…8311` |
| веса совпадают с PR #3 | `sha256sum` против `artifacts/test-results/models-checksums.sha256` | **8 из 8** записанных файлов идентичны (encoder `eb6c12fb…`, decoder `dcbe1ffa…`, joiner `93f2e1d1…`, tokens `93bbbc0b…`, silero `9e2449e1…`, pyannote tarball `24615ee8…`, `segmentation.onnx` `220ad67c…`, 3dspeaker `1a331345…`) — то есть две разные машины вытянули одни и те же байты |
| AAR sherpa-onnx v2.0.1 доступен | `curl` + sha256 | 49 441 341 B, sha256 `a8d09d1d…f0ee7` — тот же, что записан Platform в `sherpa-aar-resolve.json` |
| llama-android 0.1.1 существует | Maven Central | AAR 2 062 204 B, sha256 `fdccf04c…7501`; классы `Llama/LlamaConfig/LlamaResult` проверены `javap` |

### Два бага сборки, найденных этой проверкой

1. **Протухшие веса внутри APK.** `:app:prepareEmbeddedModels` не объявлял
   входов, поэтому Gradle считал задачу up-to-date и перекладывал старые
   ассеты: в «успешно собранном» APK лежал int8 `segmentation.onnx`
   (1 540 506 B) вместо float-модели (5 992 913 B), на которой считаны все
   строки STAGE6. Причина — `find … | head -n 1` в `download-models.sh`.
   Исправлено: задача объявляет файлы весов и флаг `embedLlm` входами, скрипт
   берёт `model.onnx` явно. Проверено листингом содержимого APK.
2. **Потерянный экспорт в файл.** В UI из PR #3 остался только share sheet;
   требование брифа «экспортирует результат в файл» закрывал SAF
   `CreateDocument` из PR #2. Вернули кнопку **Сохранить JSON**,
   «Поделиться» осталась рядом.

Плюс: `scripts/ci-check.sh` падал на Windows с `UnicodeEncodeError` (cp1251
не мог напечатать `−` в таблице demo-stress) — добавлен UTF-8 stdout в
`eval_harness.py` / `eval_demo_stress.py`.

### Пересчёт замеров на этой машине (re-score, не retune)

`bash scripts/ci-check.sh` прогоняет `scripts/eval_metrics.py` по **уже
закоммиченным** hyp-файлам (никакого нового инференса) и перезаписывает
`artifacts/eval/demo-stress/metrics.csv` / `metrics.json` / `run.log`.
Сравнение до/после (27 строк, игнорируя колонки `timestamp_utc` и `commit`):

```
metric differences (excluding timestamp/commit): 0
```

То есть таблица воспроизводима из hyp-ов на Windows/Python 3.13 — те же DER,
JER, purity, WER_main, WER aggregate, RTF. Фикстура и gold не трогались
(`docs/GATE.md`: fixtures change only with Lead sign-off). Поэтому в истории
есть коммит, где у metrics.csv изменились только штамп времени и SHA — это
следствие воспроизведения, а не перенастройки.

### Чего эта проверка не доказывает

- Приложение не устанавливалось и не запускалось на телефоне: adb есть,
  устройств нет. Всё, что касается «на устройстве», — `pending`
  (`artifacts/test-results/results.csv`, слоты `check_apk_*`).
- Строка K≤6 измерена на Impl `3c3f9ce`; тип `ab20d83` не переоценён.
- GGUF на хосте не исполнялся (arm64-only биндинг), поэтому в таблице
  `DEMO_STAGE6_impl_hardcap6` пунктуация — эвристика `IntonationPunct`, и в
  заголовке замера это помечено `gguf-missing`.
