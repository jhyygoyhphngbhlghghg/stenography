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
| `gradle-assemble.txt` | assemble command, exit code, APK path/size if built |
