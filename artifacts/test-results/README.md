# Артефакты тестирования

Таблица: `results.csv`. Не заполнять WER/RTF выдуманными числами.

## `results.csv` и колонки шаблона

Шаблон хакатона (`hacathon_template/artifacts/test-results/results.csv`)
описывает таблицу acoustic-transfer теста: `file_size_bytes`,
`distance_meters`, `noise_condition`, `noise_level_db`, `attempt`,
`checksum_match`, `device_tx`, `device_rx`. Наш прогон — не передача файла
динамиком в микрофон, а распознавание речи на устройстве, поэтому часть этих
полей описывает другую физику, а `checksum_match` и `device_tx/rx` не
определимы вовсе.

Схема таблицы поэтому своя, но поля шаблона сохранены там, где они значимы:

| Шаблон | У нас | Примечание |
|---|---|---|
| `test_id`, `timestamp_utc`, `commit`, `file_name`, `notes` | те же имена | наличие и порядок совпадают |
| `duration_seconds` | `duration_ms` | миллисекунды, целое, без округления |
| `file_size_bytes` | нет | размер входного микса не является результатом замера |
| `distance_meters`, `noise_condition`, `noise_level_db` | нет | для `DEMO_STAGE6` это параметризовано в `samples/demo-stress/stage6/` (места 3–6 м, babble/HVAC) и в `docs/TEST_RESULTS.md`, а не свалено в одну колонку |
| `attempt`, `success` | нет | колонка `status` говорит больше: `measured-host-synthetic` / `pending` / `check_apk_*` |
| `checksum_match`, `device_tx`, `device_rx` | нет | приём и передача файла между устройствами не выполняется |

Плюс колонки, которых в шаблоне нет, но без которых наш результат не
читается: `clip_id`, `wall_ms`, `rtf`, `wer`, `speakers_hyp`, `speakers_ref`.

Заполнять пустые шаблонные поля «чтобы совпало» нельзя — `docs/GATE.md`: *do
not invent metrics*. Строку пишет `scripts/eval_harness.py`.

Platform de-risk evidence (Track B):

| File | What |
|---|---|
| `model-download-run.csv` | per-file HTTP code, bytes, wall_ms, sha256 from `scripts/download-models.sh` |
| `model-download-time.txt` | wall clock for the full download script |
| `models-manifest.json` | expected + measured filenames and sizes (weights themselves gitignored) |
| `models-checksums.sha256` | sha256 of downloaded ONNX/tokens (no weights in git) |
| `sherpa-aar-resolve.json` | JitPack AAR HTTP 200, bytes, sha256, JNI `.so` list |
| `sherpa-aar-listing.txt` | `unzip -l` of the AAR |
| `present-connect-capacity.json` | streams 1..20 accepted, stream 21 `OVER_CAPACITY` |
| `gradle-assemble.txt` | Gradle `dependencies` tree (sherpa-onnx v2.0.1 resolve) |
| `gradle-assemble-summary.txt` | assemble commands, exit codes, APK bytes, JNI notes |
| `gradle-assemble-retry.txt` | incremental assemble that produced APKs |
| `sherpa-apk-jni.txt` | `unzip -l` of native libs inside the debug APK |
| `TEST-PresentConnectStreamTableTest.xml` | JUnit XML for the 20-stream cap |
| `host-jni-smoke.json` / `host-jni-smoke.txt` | Linux x86_64 AAR JNI inspect (`file`/`readelf`/`nm`/`dlopen`) |
| `device-jni-probe-logcat.txt` | produced only when johann runs `scripts/device-sideload-probe.sh` on a phone |

Merged-tree verification (2026-11-07, this machine — see `docs/TEST_RESULTS.md`
§«Сборка и самопроверка на смерженном дереве» for the full table):

| File | What |
|---|---|
| `model-download-run-2026-11-07.csv` | today's re-download of all weights (http code, bytes, wall_ms, sha256) — matched 8/8 against `models-checksums.sha256` above |
| `results.csv` | three `measured-host-synthetic` clip rows + three `pending` `check_apk_*` slots + **ship-сводка 2026-11-08**: unit tests per flavor @ship (PASS, 166→167), emulator e2e (agent1/agent2/agent5, PASS с честными оговорками), offline export matrix (PASS, PDF-кириллица caveat), web sync/QR (PASS), hosted-deck 6 спикеров через curl (PASS, не app-push), present-connect smoke v1 (**FAIL** — hello models-string bug) и v2 re-smoke (handshake **PASS**, app→deck rows **TIMEOUT**); каждая строка несёт провенанс в `status`/`notes` |
| `../SMOKE_PRESENTCONNECT.md` / `../SMOKE_PRESENTCONNECT_V2.md` | полные отчёты дым-теста presentConnect APK → живой hosted deck (v1 FAIL / v2 handshake PASS + rows TIMEOUT), индексы evidence-файлов |

APKs themselves (210 MiB check, 547 MiB with the GGUF, 210 MiB presentConnect,
93 MiB slim) are **not** in git — they are Release assets under
`v0.1.0-stage6`, listed with sha256 in `apk/README.md`.
