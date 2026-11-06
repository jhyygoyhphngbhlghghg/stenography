# Методика и результаты тестирования

Собственные прогоны команды (не судейский набор «котики»).

**Запрещено выдумывать WER/RTF.** Пока нет замера на реальном клипе — в таблице `pending`.

Шаблон строк: `artifacts/test-results/results.csv`.

Track B / platform de-risk (seat P1.linq, branch `platform/derisk-sherpa-present`) wrote measured numbers below. Evidence files: `artifacts/test-results/`.

## Методика (черновик)

1. Собрать debug APK flavor `stenographer`.
2. Положить модели (`scripts/download-models.sh` + push).
3. Авиарежим.
4. Импорт 2–3 своих диалогов (RU, ≥2 спикера, без перекрытия).
5. Экспорт JSON, сверка `speaker_id` / `start_ms` / `end_ms` / `text` с ручной расшифровкой.
6. Зафиксировать wall time и длительность аудио (RTF = wall / duration) только после реального замера.

## Статус overnight / Track B

| Проверка | Статус |
|---|---|
| Model download (`scripts/download-models.sh`) | **PASS** — exit 0, wall **4 s**, 119466051 bytes ASR+VAD+diar ONNX. See below. |
| Gradle AAR resolve `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1` | **PASS** — JitPack HTTP 200, 49441341 bytes, 444 ms; Gradle `dependencies` exit 0 in 51 s |
| Gradle assemble debug APK | **PASS** — both flavors. stenographer 143621712 bytes, presentConnect 143621716 bytes |
| JNI packaged (`libsherpa-onnx-jni.so`) | **PASS** — 4 ABIs in APK |
| JNI `UnsatisfiedLinkError` on device | **BLOCKED** — no emulator/device in this VM. Load is wired in `NeuroStenoApp` / `SherpaJni.probe()`. |
| Import → stub list → JSON (no models) | pending (needs a phone) |
| Zipformer-ru on-device | pending models-on-device (weights downloaded on the builder, not pushed) |
| Diarization ≥2 speakers | pending |
| Airplane mode | pending |
| PresentConnect ≤20 streams (mock) | **PASS** — JVM + Python. Stream 21 = `OVER_CAPACITY`. |

## 1. Model download (measured 2026-11-06T17:02:59Z)

```bash
DOWNLOAD_LOG=artifacts/test-results/model-download-run.csv ./scripts/download-models.sh
```

Wall: **4 s** (`elapsed_sec=4`). Exit **0**.

Epoch-99 ONNX names 404 on HF pack `csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20`. Script now fetches the real names.

| File | bytes | wall_ms | status |
|---|---:|---:|---|
| `models/asr/zipformer-ru-int8/encoder.int8.onnx` | 70876638 | 553 | ok |
| `models/asr/zipformer-ru-int8/decoder.onnx` | 2093080 | 927 | ok |
| `models/asr/zipformer-ru-int8/joiner.int8.onnx` | 259417 | 309 | ok |
| `models/asr/zipformer-ru-int8/tokens.txt` | 6388 | 63 | ok |
| `models/vad/silero_vad.onnx` | 643854 | 90 | ok |
| `models/diar/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2` | 6958444 | 423 | ok (extract `segmentation.onnx` 5992913) |
| `models/diar/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx` | 39593761 | 435 | ok |

Failures: none. Not downloaded: LLM GGUF, Whisper tiny, Vosk (opt-in flags).

Manifest + hashes (no weights in git): `artifacts/test-results/models-manifest.json`, `models-checksums.sha256`.

## 2. sherpa-onnx Android AAR / JNI (measured)

VM had **no Android SDK** at boot. Installed for this run only: cmdline-tools + `platforms;android-35` + `build-tools;35.0.0` to `$HOME/android-sdk` (`local.properties` gitignored).

```bash
# AAR without SDK
./scripts/inspect-sherpa-aar.sh
# Gradle (after SDK)
cd src/android
export ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk
./gradlew --no-daemon :app:dependencies --configuration stenographerDebugRuntimeClasspath
./gradlew --no-daemon :app:assembleStenographerDebug :app:assemblePresentConnectDebug
```

| Step | Result | Wall | Notes |
|---|---|---|---|
| JitPack AAR GET | PASS HTTP 200 | 444 ms | 49441341 bytes, packaging=aar |
| Gradle resolve | PASS exit 0 | 51 s | tree contains `sherpa-onnx:v2.0.1` |
| `assembleStenographerDebug` | PASS | 31 s incremental (after API fix) | APK 143621712 bytes |
| `assemblePresentConnectDebug` | PASS | same command | APK 143621716 bytes |
| JNI `.so` in APK | PASS | — | `libsherpa-onnx-jni.so` arm64/armeabi-v7a/x86/x86_64 |
| `UnsatisfiedLinkError` | BLOCKED | — | no emulator; probe exists |

`gradlew` originally died with `ClassNotFoundException: "-Xmx64m"` (quoted `DEFAULT_JVM_OPTS`). Fixed. Pipeline used Java `.builder()`; v2.0.1 AAR is Kotlin data classes — wired to named constructors so compile could finish.

Evidence: `artifacts/test-results/sherpa-aar-resolve.json`, `sherpa-aar-listing.txt`, `gradle-assemble-summary.txt`, `sherpa-apk-jni.txt`.

## 3. PresentConnect ≤20 streams (measured)

Cap is 20 (`PresentConnectConfig.maxStreams`, `PresentConnectStreamTable`). Stream 21 with a new id is `OVER_CAPACITY`; 1..20 accepted.

```bash
python3 scripts/test_present_connect_capacity.py --record
cd src/android && ./gradlew --no-daemon :app:testStenographerDebugUnitTest
```

| Command | Result | Wall |
|---|---|---|
| `python3 scripts/test_present_connect_capacity.py --record` | **PASS** (2 tests) | 0.257 ms |
| `./gradlew :app:testStenographerDebugUnitTest` | **PASS** (7 tests, including 4 stream-table) | 11 s |

`PresentConnectStreamTableTest.acceptsTwentyAndRejectsTwentyFirst`: accepted `s01`..`s20`, rejected `s21`. Concurrent 64 starts: 20 accepted, 44 rejected, `activeCount()==20`.

Evidence: `artifacts/test-results/present-connect-capacity.json`.
