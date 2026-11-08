# TECH HIGHLIGHTS — технические прорывы «Нейро-стенографиста»

Команда **MIK Coders**, кейс «Нейро-стенографист», Nuclear IT Hack, ноябрь 2026.
Документ для защиты: только реальные замеры, у каждого числа — путь к
артефакту. Непроверенное явно помечено. Собрано на дереве `ship-docs`
(доки + артефакты; код — `ship`/`main`, merge-base `8eb4bb5` + поезд слияний).

**Дисциплина чисел.** Всё, что ниже, измерено и закоммичено: гипотезы
(`artifacts/eval/`), JSON-отчёты проверок (`artifacts/compact/`,
`artifacts/optimization/`), отчёты финишеров (`artifacts/agent1..5/REPORT.md`).
Где источники расходятся (числа юнит-тестов), приведены все варианты с
провенансом — см. §«Провенанс спорных чисел».

---

## Питч (один абзац)

Полностью офлайн-стенографист на одном телефоне: 7 файлов моделей
(119 466 051 B — Zipformer-ru int8, Silero VAD, pyannote segmentation-3.0
float, 3D-Speaker embedding) плюс Qwen2-0.5B-Instruct Q4_0 GGUF
(352 969 408 B) упакованы в APK (`-PembedLlm=true`), аудио не покидает
устройство ни в одном режиме. Пайплайн: energy-VAD ∪ Silero → диаризация с
hard cap K=6 и Hungarian-style рассадкой → Zipformer → LLM-пост-проход, где
content-words gate физически не даёт слабой модели испортить транскрипт. На
замороженной far-field фикстуре DEMO_STAGE6 (6 RU-спикеров, один микрофон,
3–6 м, комната RT60 1.4 s, HVAC+babble) Hex keep-punct WER **0.7794 → 0.6471**,
DER **0.2760**, гипотеза **6/6** спикеров, RTF **0.1173** (host). Exclusive
memory phases держат пик RAM как max(ASR, LLM), а не сумму: host RSS
273/630 MB, PSS на эмуляторе 244/400 MB. Prepared encoder: init 1370→590 мс
при побайтово идентичном выводе на 9 wav; compact APK −123,6 MB lossless.
Clean-сборки воспроизводимы побайтово (один SHA256 дважды), пересчёт метрик из
закоммиченных гипотез — 0 расхождений на 27 строк. Живой стенд
`present-connect/1`: ≤20 изолированных потоков, 45 с молчания → честный
fallback-баннер, 6 спикеров на hosted deck + GIF. Ночью работало 5
параллельных agent-инженеров с изолированными эмуляторами; каждый APK-claim
привязан форензикой hash/mtime к сессионным логам.

---

## 1. Полностью офлайн-стек на устройстве, LLM-веса внутри APK

**В чём сложность.** Бриф запрещает облачный ASR; требование «локальная LLM»
означает, что 353-мегабайтный GGUF должен лежать в телефоне до всякого
авиарежима; JNI-стек (sherpa-onnx + llama.cpp) arm64-специфичен; убитая первым
запуском распаковка весов оставляла 0-байтовый GGUF, который считался
«присутствующим».

**Решение.** `:app:prepareEmbeddedModels` кладёт 7 файлов (6 ONNX + tokens.txt,
119 466 051 B) в `assets/models`; флаг `-PembedLlm=true` добавляет 8-й ассет —
Qwen2 GGUF (352 969 408 B). Первый запуск: `AssetModelUnpack` +
`ModelStore.ensureUnpacked` атомарно (temp+rename) распаковывают в `filesDir`,
маркер `.unpacked` пишется последним, 0-байтовый GGUF ретраится
(фикс `3a04212`, 3 отдельных теста). Нет весов — пайплайн явно уходит в stub и
**помечается в UI**, stub не выдаётся за ASR.

**Цифры.**
- 8 ассетов, 472 435 459 B в embedLlm-APK (573 498 719 B); check-APK без GGUF — 220 529 139 B.
- Cold install → `models unpack ready=true copied=7`; relaunch → `copied=0` (кэш уважается).
- sha256 host↔device совпали для всех 8 моделей (`model_hashes_matched_host_to_device`).
- INTERNET-permission принадлежит только presentConnect-flavor; офлайн-путь — 0 сетевых вызовов (code audit, `internet_permission_used_in_pipeline=false`).
- Качество ASR на чистых RU-клипах (host): clip_01..03 WER 0.0345 / 0.0000 / 0.0000, RTF 0.12–0.14.

**Честная оговорка.** Прогон с **явно выключенным радио** (airplane-toggle)
не выполнен: офлайн-заявка опирается на code audit + network-free прогоны на
эмуляторах (REPORT agent2 §7, §9.8). Физического телефона не было ни в одном
прогоне (`android_device_execution: false`) — только LDPlayer (ARM-трансляция)
и официальный AVD.

**Evidence.** `docs/MODELS.md`; `apk/README.md`; `artifacts/agent2/REPORT.md` §7;
`artifacts/agent2/evidence.json` (offline);
`artifacts/compact/ldplayer-real-check.json`;
`src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/AssetModelUnpack.kt`;
`artifacts/eval/hyp/clip_0{1,2,3}.json`.

## 2. Exclusive memory phases: пик RAM = max(ASR, LLM), а не сумма

**В чём сложность.** Нативные сессии (sherpa JNI, llama.cpp) нельзя безопасно
преэмптить: отмена coroutine во время нативного вызова теряла JNI-handle;
ASR-веса и GGUF одновременно — это оценочно 0.9–1.3 GB пика на телефоне.

**Решение.** `runExclusivePhases` (`ExclusivePhases.kt`, `1906eaa`): ASR-сессия
выгружается сразу после фазы ASR и **до** загрузки GGUF; высвобождение
выполняется и при failure/cancel (идемпотентно, `NonCancellable`).
`InferenceSession.kt` — одна ленивая нативная модель с сериализованными
транзакциями (Mutex): creation/teardown на своём dispatcher, failed-транзакция
инвалидирует возможно отравленное состояние, evict/close ждут владеющую
транзакцию. `StenoViewModel` регистрирует `ComponentCallbacks2`:
`onTrimMemory ≥ TRIM_MEMORY_RUNNING_LOW` и `onLowMemory` → `pipeline.trimMemory()`.

**Цифры.**
- Host peak RSS: ASR-only **273.4 MB**, LLM-only (ctx 1024, ubatch 128, 2 потока) **630.1 MB**.
- Эмулятор LDPlayer (ARM-трансляция): peak PSS **244.3 MB** (ASR-прогон), **399.5 MB** (полный прогон с GGUF).
- Оценка Android: 0.9–1.3 GB → ~0.7–0.8 GB (на устройстве PSS не перемерен — помечено).
- Цена: ASR перегружается каждый прогон (~590 мс init prepared-encoder, host); первый прогон не замедляется.
- Побочный эксперимент отменил целый JNI-проект: ubatch 512/128/64 → RSS 639.5/630.1/625.3 MB, т.е. кастомный llama.cpp JNI сэкономил бы ~14 MB, а не feared ~311 MB — **не построен**, NDK скачан и удалён.
- Тесты: 126 → 131 (+5 контрактных `ExclusivePhasesTest`), 0 падений на оба flavor.

**Evidence.** `src/android/.../pipeline/ExclusivePhases.kt`,
`src/android/.../pipeline/InferenceSession.kt`,
`src/android/.../ui/StenoViewModel.kt` (memoryCallbacks);
`artifacts/compact/exclusive-phases-experiment.json`;
`artifacts/compact/ldplayer-real-check.json`;
`artifacts/optimization/verification.json`.

## 3. Prepared/compact encoder: точная parity + −123.6 MB lossless

**В чём сложность.** Cold-init 70-мегабайтного int8 Zipformer-энкодера стоил
~1.4 с; APK с весами раздулся до ~573 MB; любая графовая оптимизация обязана
сохранить вывод распознавания **побайтово**, иначе все замеренные строки
STAGE6 теряют силу.

**Решение.** Офлайн-подготовка графа ORT 1.28.0 (`ORT_ENABLE_EXTENDED`, CPU;
без реквантизации весов и без hardware-layout NCHWc): 9133 → 4288 узлов.
Gradle-gate: compact-сборка **отказывается** паковать энкодер, если его sha256
не совпадает с прошедшим validation-отчётом (`require(all_text_tokens_
timestamps_identical == true && prepared_sha256 == …)` в
`prepareEmbeddedModels`). Упаковка моделей lossless; dex/native-libs урезаны
одним arm64-v8a ABI.

**Цифры.**
- Init медиана **1370.5 → 589.6 мс** (5 repeats, 2 потока); decode ~683→702 мс (в пределах шума).
- Паритет: text/tokens/timestamps идентичны на **всех 9 wav** (6 STAGE6-экспортов + 3 smoke), `metadata_identical: true`.
- Compact APK: 573 498 719 → **449 907 795 B** (−**123 590 924 B** ≈ −123.6 MB); signature v2 verified.
- **GGUF byte-identical** оригиналу (`gguf_sha256_matches_original: true`); подготовленный энкодер на устройстве совпал с host по sha256 (`22842904…`).
- Отклонено с замером: Q4_0-реквантизация `token_embd.weight` — 9 из 11 completions изменились, host RSS вырос 631.5→640.0 MB → **rejected**; `ORT_ENABLE_ALL` — эмитит hardware-specific `com.microsoft.nchwc` узлы → **rejected**.

**Evidence.** `artifacts/compact/encoder-validation.json`;
`artifacts/compact/verification.json`; `artifacts/compact/llm-embedding-q4-screen.json`;
`src/android/app/build.gradle.kts` (`prepareEmbeddedModels`); `docs/MODELS.md` §«Порядок загрузки и память».

## 4. Воспроизводимость: byte-identical сборки и rescore с нулём расхождений

**В чём сложность.** Инкрементальная упаковка APK недетерминирована; таблицы
метрик на хакатоне легко «перенастроить» постфактум — нужно было доказать, что
наши числа — воспроизведение, а не retune.

**Решение и цифры.**
- Два независимых **clean**-прогона `assembleStenographerDebug` @ `1906eaa` дали идентичный SHA256 **`5C9E9D76…7789`** (508 675 346 B); инкрементальная сборка — другой хеш (`2A584A53…`). Вывод в процесс: сдавать только clean-артефакты.
- `scripts/ci-check.sh` пересчитывает `metrics.csv` из **уже закоммиченных** hyp-файлов (никакого нового инференса): 27 строк, **`metric differences (excluding timestamp/commit): 0`** на Windows/Python 3.13. Коммит, где у metrics.csv изменились только штамп и SHA, — это restamp (воспроизведение), а не retune.
- Фикстуры и gold заморожены: меняются только с Lead sign-off (`docs/GATE.md`); «Do not retune fixture» зашит в notes каждой строки metrics.csv.
- Две разные машины (Platform PR #3 и эта) вытянули **идентичные байты весов**: 8 из 8 sha256 совпали с `models-checksums.sha256`.

**Evidence.** `artifacts/agent1/REPORT.md` §1.1; `docs/TEST_RESULTS.md`
(§«Репрезентативность сборки», §«Пересчёт замеров на этой машине»);
`artifacts/eval/demo-stress/metrics.csv`; `docs/GATE.md`.

## 5. Диаризация как исследование: reconcile, hard cap 6, voice-merge

**В чём сложность.** Один микрофон на 6 спикеров 3–6 м: pyannote роняет тихое
дальнее место (speaker_5 @ 5.4 m missing в baseline, hyp 5/6); наивный
VAD↔diar reconcile пересплитил сцену до **12/6**; последовательные реплики
одного человека получали новую метку на каждом VAD-острове.

**Решение.** Три слоя, каждый с замером:
1. `VadDiarReconcile.kt` — energy-index + **Hungarian-style seat assignment**: orphan-острова усыновляются только time-adjacent хостами **той же энергии** (`SAME_ENERGY_RATIO 2.5`), fill energy holes, split длинных островов по energy dip, тихое дальнее место получает **своё** место, а не приклеивается к ближнему; near 3 m защищён от расширения (`SUBSTANTIAL_MS 1200`).
2. `capToExpected` — **HARD CAP K=6** (`FarFieldScene.stage6.expectedSpeakers = 6`): никогда не изобретает `speaker_7..12`; юнит `twelveVadIslandsNeverInventSpeakerSeven`.
3. `SpeakerIdentityMerge.kt` — union-find по voice-эмбеддингам 3D-Speaker, **merge-only** (никогда не сплитит, капа и s5-роли не ломает); порог **0.45** выбран замером, а не глазом: тот же голос (разные кропы) p25 0.33 / median 0.51; разные места STAGE6 — min **0.62** (s3 vs s5), median 0.82–0.84.

**Цифры (замороженная фикстура DEMO_STAGE6, Hex keep-punct WER, host Linux).**
- Baseline: hyp **5/6**, DER 0.5174, WER_agg **0.7794** (53/68).
- Reconcile без капа (`552b142`): DER **0.2568**, WER_agg 0.6029, но hyp **12/6** — over-split, JER ухудшился до 0.6821.
- Hard cap (`3c3f9ce`): hyp **6/6**, DER **0.2760**, JER **0.5454**, WER_agg **0.6471** (44/68), RTF 0.1173, wall 4659 мс / 39718 мс аудио.
- Честная цена капа задокументирована: merge смазал s2+s3 в один остров 7456–19066 мс (s3 WER 1.0), s5 остался unmatched. Это не «комната решена» — это 6/6 **измеренных**.
- Path A на 20-голосом коктейле — измеренный провал (hyp 1/20, DER 0.95): кластерные ручки не спасают, `num_clusters=20` всё равно даёт 1 кластер.

**Evidence.** `src/android/.../pipeline/VadDiarReconcile.kt`,
`FarFieldScene.kt`, `SpeakerIdentityMerge.kt`;
`artifacts/eval/demo-stress/metrics.csv` (строки `DEMO_STAGE6*`, `mix_20talker*`);
`docs/TEST_RESULTS.md` (таблицы before/after).

## 6. Ловушки инструментации, которые чуть тихо не изменили все измерения

**В чём сложность.** «Успешно собранный» APK и «успешно скачанная модель» не
означают, что внутри правильные байты; обе найденные ловушки молчали.

**Что нашли и закрыли.**
1. **Stale weights в APK.** `:app:prepareEmbeddedModels` не объявляла входы → Gradle считал задачу up-to-date вечно → в APK лежал int8 `segmentation.onnx` (**1 540 506 B**) вместо float-модели (**5 992 913 B**, sha `220ad67c…`), на которой измерены **все** строки STAGE6. Фикс: `inputs.file` на каждый вес + `inputs.property("embedLlm"/"compactModels")`; в коде остался комментарий «Measured the hard way».
2. **Недетерминированный выбор модели.** `find … | head -n 1` в `scripts/download-models.sh` мог выбрать int8-экспорт pyannote из того же тарбола — другая модель тихо изменила бы каждую измеренную строку. Фикс: явный float `model.onnx` + fallback-список кандидатов, `! -name '*int8*'`.
3. **0-байтовый GGUF считался присутствующим** (`needed` проверяла только `!File.isFile`) — фикс `3a04212`: retry при `length() <= 0`, temp+rename, маркер последним.
4. **cp1251 на Windows** ронял `ci-check.sh` (`UnicodeEncodeError` на `−` в таблице) — добавлен UTF-8 stdout в eval-скрипты.

**Evidence.** `docs/ARCHITECTURE.md` §«Ловушки сборки и моделей»;
`docs/TEST_RESULTS.md` §«Два бага сборки»; `src/android/app/build.gradle.kts`;
`scripts/download-models.sh` (строки ~118–139).

## 7. Честность LLM: content-words gate, extractive fallback, честные статусы

**В чём сложность.** Qwen2-0.5B Q4 — слабая модель: в 5 из 6 host-кейсов она
вернула мусор (только `10000 мс.`). Наивный «LLM улучшает транскрипт» здесь
либо врёт, либо портит текст.

**Решение.** LLM-пост-проход — **только добавление интонационной пунктуации**
на реплику. `IntonationPunct.mergeCandidate` отклоняет кандидата, если
**содержательные слова** ≠ ASR-гипотеза или знаков стало меньше — плохой вывод
GGUF физически не может попасть в транскрипт; цена — впустую потраченные
~1.6–2.2 с и эвристический fallback (`addSpokenCommas`: greeting/vocative/
discourse/contrast). Реплики > **1664** знаков `((1024−96−96)×2)` GGUF не
получают — ctx 1024 не переполняется. Speaker ID приходят исключительно из
диаризации — LLM их никогда не порождает и не меняет (code audit +
`IntonationPunctTest`). Summary/QA (`TranscriptLlm.kt`, продуктовая ветка)
деградирует до **extractive bullets** и не падает. UI говорит правду:
`llm-weights-missing (intonation punct only)`, stub-пайплайн помечен, на ABI
кроме arm64 — честная строка про деградацию до эвристики.

**Цифры.**
- Gate покрыт 20 юнит-тестами (`IntonationPunctTest` + `GgufLlmPostPassTest`).
- Host (идентичные веса, llama-completion b12182): load ~0.33 s, prompt-eval 237–241 tok/s, генерация 47–54 tok/s, 9/9 прогонов rc=0, ни одного hang >10 s.
- On-device доказательство реального llama.cpp-инференса: LLM-фаза ~330 s на 42.5-с фикстуре (LDPlayer ARM-трансляция), транскрипт изменился (плюсы, кавычки) — **запуск доказан**, тайминг demo-hostile и не является телефонной заявкой.

**Evidence.** `src/android/.../llm/IntonationPunct.kt`, `LocalLlmPostPass.kt`,
`TranscriptLlm.kt`; `artifacts/agent2/REPORT.md` §4–5; `artifacts/agent2/evidence.json`;
`artifacts/compact/llm-baseline-bench.json`; `artifacts/compact/ldplayer-real-check.json`.

## 8. present-connect/1: живой транскрипт на дек без cocktail-буфера

**В чём сложность.** До 20 браузерных микрофонов на один телефон-воркер.
Смешать PCM в один буфер (Path A) — измеренный провал (20/20 → hyp 1/20,
DER 0.95): коктейль не диаризируется.

**Решение.** Path B **locked**: каждый поток — свой `stream_id`, свой
изолированный PCM-буфер, свой VAD→ASR; «worker **must not** mix or sum PCM
across streams» (контракт). `PresentConnectLimits`: `MAX_STREAMS = 20`,
21-й поток → `OVER_CAPACITY`, `PROTOCOL = "present-connect/1"`.
`StreamPcmGate` — pre-ASR гейт на **один** поток: RMS/peak/clip/DC пороги +
Goertzel-детектор spectral crush (`BAND_LIMITED`, hf_ratio 0.08, speech bins
250–3500 Hz); привязанные места `s01..s20` — soft-флаги (ASR идёт), главный
`s01` никогда не дропается за энергию/полосу; Kotlin-гейт byte-identical
Python-зеркалу (sha256 сверены). Молчание телефона ~**45 с** → `demo_phase`
`fallback/phone_stall` и баннер «показана запись, не результат модели».
Checkpoint-синхронизация: экраны дедуплицируют чекпоинты по `revision`,
поэтому смена scroll-lock **поднимает revision** (иначе экраны потеряли бы её
как повтор); `scroll_locked:true` держит дек на чекпоинте пульта.

**Цифры и живое доказательство (провенанс: транспорт — curl).**
- 6-спикерный раунд на боевой сервер `https://present.144.31.215.37.sslip.io/`: `accepted:6`, `phone_connected:true`, `scroll_locked:true`, slide `demo` (idx 4); Playwright: `body.innerText` содержит все 6 реплик, LIVE-бейдж, HUD-строки 03–06.
- GIF: `artifacts/agent3/gif/deck-live-6spk.gif` (640×601, 2 fps, 3 с, 159 748 B) + покадровые PNG.
- Gate v3 (вторичная регрессия): WORST_CASE 20/20 потоков прошли (20 soft `BAND_LIMITED`, s01 на месте) — v2-вариант с полным отказом 0/20 отклонён по замеру.
- **Помечено честно:** строки пушил token-аутентифицированный curl (из netns эмулятора и с хоста), не app-UI: путь `PhoneDemoClient` из приложения скомпилирован и unit-green, но e2e из приложения не исполнялся (agent3 rollup §5; APK_PROVENANCE).

**Evidence.** `docs/PRESENT_CONNECT.md` §2/§4/§5; `docs/PROTOCOL.md`;
`src/android/.../present/PresentConnectLimits.kt`, `StreamPcmGate.kt`;
`artifacts/agent3/present-frontend-checkpoint.json`, `transcript-export.json`;
rollup-отчёт agent3 (`neuro-stenographer-agent3/REPORT.md`, в дереве `ship` — корневой `REPORT.md`, коммит `605a8ea`).

## 9. Web-инженерия: hermetic-тесты, checkpoint pinning, QR с сессией

**В чём сложность.** Тестовый harness «врал»: деплойные переменные окружения
и случайные порты ломали прогон так, что это выглядело багом продукта; backend
и React-дек обязаны показывать **одни и те же** 8 чекпоинтов; QR первого слайда
терял `?session=…`.

**Решение и цифры.**
- **Hermetic suite** (`27e440e`): тесты наследовали `process.env` — ambient `PHONE_TOKEN` в shells давал **9 из 20** красных (phone-role сокеты получали 4001), в чистом shell те же тесты **20/20**; harness теперь вычищает конфиг-переменные приложения. Порт `8200 + random(600)` заменён на kernel-выданный ephemeral: 20 suite в одном прогоне сталкивались достаточно часто, чтобы это читалось как «server exited early».
- **Checkpoint pinning** (`9ed231f`, `82da9f0`): `SLIDES`/`SLIDE_LABELS` в `backend/src/config.ts` (8 чекпоинтов: qr/team/case/tester/demo/tech/solutions/end) обязаны совпадать с `CHECKPOINTS` React-дека (`present/src/components/deck/data.ts`) — совпадение проверяется тестами; React-дек стал экранами показа.
- **QR несёт сессию** (`4c5dd6e`): payload = `origin + pathname + search` — единственный `QRCode.create` в репозитории; до фикса QR кодировал голый `/` и ронял join-параметры. Фикс проверен **live** (в бандле `routes-BR_HqwxI.js`) и в репозитории.
- Sync-замеры (agent4): app→site `POST /v1/transcript` **78 мс**; site→app `POST /v1/status` **77 мс**; HUD/чекпоинт распространяются <5 с без reload; `checkpoint.set index=5` → `slide_index:5, revision:3`; backend-тесты **9/9**; QR-URL достижим **10/10** (curl 200), чистый UI-лендинг 6/10 (Chrome First-Run перехватывает тап — ограничение автоматизации, не сайта).

**Evidence.** git-коммиты `27e440e`, `9ed231f`, `82da9f0`, `4c5dd6e`
(`git log` ветки `agent4-web-sync-qr` / `ship`); `artifacts/agent4/REPORT.md`;
`docs/TEST_RESULTS.md` §«Веб: синхронизация и QR-вход».

## 10. Процесс: ночной flock из 5 agent-инженеров + APK-форензика

**В чём сложность.** Пять параллельных ночных сессий (экспорты
2026-11-07 22:02→22:05 UTC, финиш ~07:28 +03) в общих worktree-каталогах,
с несколькими эмуляторами и одним дедлайном; две сессии **погибли**
(agent1 — mid-tool-call, agent5 — mid-edit); на диске оказалось 9 APK, и нужно
было доказать, какой из них что содержит и чем тестировался.

**Решение.**
- Разделение: agent1 mobile-e2e (изолированный AVD `emulator-5554`), agent2 LLM/экспорт (собственный экземпляр LDPlayer `agent2_llm` = `emulator-5556`, 4 CPU / 4096 MB), agent3 server/present-connect (собственный LDPlayer), agent4 web/QR (AVD + Playwright), agent5 продуктовый UI. Каждая ветка — своя; слияние — поезд merge-коммитов с провенанс-маркировкой каждой строки результатов (`d4baf16` «label every results row by provenance»).
- Погибшие сессии реконструированы из экспортов: REPORT agent1 — по 294 сообщениям лога; REPORT agent5 — аудит HEAD преемником.
- **APK-форензика**: 9 APK на диске → sha256/mtime/содержимое (ассеты, dex, нативные либы) сверены с сессионными логами и worktree-состоянием → вердикты DEMO-READY / CHECK-READY / STALE-BUGGY / UNTESTED. Демо-артефакт — `5c9e9d76…` (`1906eaa`, E2E-проверен 07:19); live-deck push честно атрибутирован curl-транспорту, а не приложению.
- Дисциплина claims: устная цифра agent2 «178 тестов» **отозвана в его же отчёте** — по XML ветки их 147 (считали в общем каталоге при чужом checkout); расхождения не замалчиваются, а маркируются (таблица ниже).

**Evidence.** `E:/Projects/neuro-stenographer/artifacts/APK_PROVENANCE.md`
(локальный аудит-файл, в git не коммитился); `artifacts/agent1..5/REPORT.md`;
`git log` веток `main` / `agent2-mobile-llm-export` / `agent3-product-features` /
`agent3-presentation-conn` / `agent4-web-sync-qr` / `ship`.

---

## Решения, которые мы НЕ приняли (anti-hype)

1. **Не разморозили FarFieldGain / PhoneDistanceEq.** Замер на замороженном
   миксе: whole-file AGC дал −3.46 dB crush (RMS 0.1191→0.0800), WER_agg
   0.7500, но диаризация не изменилась и s5 как отсутствовал, так и
   отсутствует; per-turn gain и EQ оставили WER **0.7794→0.7794** (33 из 34
   energy-островов уже ≥ 0.08 FS — identity). Вывод зафиксирован в eval-строках:
   «Gain did not recover the room», «Do not retune fixture». Дальнейшая
   подстройка чисел под фикстуру — подгонка, а не решение. Дальние места
   4.2–5.4 м остаются **измеренной границей** (speaker_5 не восстановлен).
2. **Никакого cloud fallback.** Бриф запрещает аудио вне устройства;
   `docs/MODELS.md` прямым текстом: не добавлять Picovoice или любой облачный
   ASR/LLM «даже как fallback». Host-прогоны в `artifacts/eval/` — наша
   отчётность, а не путь обработки в приложении.
3. **Никакого summarization-overclaim.** В ветке agent2 саммари **не
   существовало** (prompt только на пунктуацию) — и это записано в отчёте, а
   не спрятано. Продуктовая ветка добавила `TranscriptLlm` (summarize/ask), но
   качество ограничено моделью 0.5B, fallback — extractive bullets; у бэкенда
   **нет summary-эндпоинта by design** — «не обещаем экран саммари».
4. **Hard cap K=6 оставили, хотя DER без капа был лучше.** Без капа
   DER 0.2568, но hyp 12/6 — с точки зрения брифа «кто сказал» это провал;
   с капом DER 0.2760 и гипотеза 6/6. Кап работает только в сцене
   DEMO_STAGE6; presentConnect остаётся на auto-кластеризации. Кап измерен на
   host — на физическом устройстве не подтверждён (помечено).
5. **Не построили кастомный llama.cpp JNI.** Эксперимент с ubatch показал
   ~14 MB экономии вместо feared ~311 MB — работа не стоила риска; NDK удалён.
6. **Не реквантизировали GGUF ради размера.** Q4_0-эмбеддинг дал drift вывода
   (9/11 completions) и худший RSS — production-веса не тронуты.
7. **Не смешивали 20 потоков в один буфер.** Path A cocktail — измеренный
   провал (1/20, DER 0.95); контракт Path B запрещает mixing на уровне протокола.
8. **Не цитируем indefensible числа.** «WER_main 0» — старый strip-punct
   скоринг, не результат комнаты; WORST_CASE — честный FAIL (WER_main 0.9130,
   DER 1.2985) и не расслаблен после провала; «диаризация дала 6 спикеров» —
   только с замером (было 12/6, стало 6/6 после капа, host).

---

## Слайд-буллеты (10 строк, ≤12 слов)

1. Полностью офлайн: 8 файлов моделей внутри APK, ноль облачных вызовов.
2. Фазы памяти: пик RAM равен max(ASR, LLM), PSS 244/400 МБ.
3. Prepared encoder: init 1370→590 мс, вывод побайтово совпадает на 9 wav.
4. Compact APK на 123,6 МБ легче; GGUF байт-в-байт идентичен.
5. Чистые сборки байт-воспроизводимы: одинаковый SHA256 дважды; rescore метрик 0 расхождений.
6. Диаризация: over-split 12/6 закрыт hard cap; гипотеза 6/6, DER 0.2760.
7. Content-words gate: слабая LLM физически не может испортить транскрипт.
8. Present-connect/1: 20 изолированных потоков, 45 с молчания — честный fallback-баннер.
9. Live-доказательство: accepted:6 на hosted deck, GIF, QR несёт сессию.
10. Ночь: 5 параллельных agent-инженеров; 9 APK форензики по hash/mtime.

---

## Провенанс спорных чисел

Числа юнит-тестов различаются по состоянию дерева — цитируем с меткой
(сводка: `docs/TEST_RESULTS.md` §«Юнит-тесты»):

| число | дерево/коммит | источник |
|---|---|---|
| **126/126** (21 класс × 2 flavor) | baseline runtime-ветки `8eb4bb5` | `artifacts/optimization/verification.json` |
| **131** (126+5) | прототип exclusive-phases | `artifacts/compact/exclusive-phases-experiment.json` |
| **145** (24 suites, дважды) | `1906eaa`, чистая сборка | `artifacts/agent1/REPORT.md` §1.2 |
| **147** (XML) | ветка agent2 `06da663` | `artifacts/agent2/REPORT.md` §1.9; устные «178» — ошибка общего каталога, отозвана |
| **166 @Test / 27 классов** | продуктовая ветка `7912c20` / сводное дерево ship | `artifacts/agent5/REPORT.md` §1 |
| **9/9** (web, `npx tsx --test`) | `agent4-web-sync-qr` | `artifacts/agent4/REPORT.md` §1 |
| **61 + 18** (python host-зеркала + A-12) | — | `scripts/ci-check.sh`, `artifacts/optimization/verification.json` |

Прочие помеченные расхождения:
- «178 тестов» (устный claim agent2) vs 147 (XML ветки) — цитируем 147.
- Он-девайс мульти-спикерный транскрипт на эмуляторе **не доказан до конца**
  (LLM-фаза шла минуты, финальный JSON не сохранён; содержимое подтверждено
  host-кросс-чеком тех же WAV) — `artifacts/agent1/REPORT.md` §1.4/§3.
- Эмуляторная e2e-фикстура `stage6_sequential.wav` — реконструированный
  чистый монтаж 4/6 реплик, «NOT the frozen room mix and NOT far-field
  proof» — `artifacts/compact/ldplayer-real-check.json`.
- Физический телефон не участвовал ни в одном прогоне; LDPlayer не имеет
  реального микрофона (live-ASR проверялся файловым потоком).
