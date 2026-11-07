# Артефакты тестирования

Таблица: `results.csv`. Не заполнять WER/RTF выдуманными числами.

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
| `results.csv` | three `measured-host-synthetic` clip rows + three `pending` `check_apk_*` slots; every row carries its provenance `status` |

APKs themselves (210 MiB check, 547 MiB with the GGUF, 210 MiB presentConnect,
93 MiB slim) are **not** in git — they are Release assets under
`v0.1.0-stage6`, listed with sha256 in `apk/README.md`.
