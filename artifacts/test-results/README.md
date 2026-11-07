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
