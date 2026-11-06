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
| Host-side JNI smoke (no emulator) | **PARTIAL** — 134 `Java_*` JNI symbols; glibc `dlopen` fails on `libandroid.so`. Not an on-device ULE probe. |
| JNI `UnsatisfiedLinkError` on device | **BLOCKED** — see **Platform gap**. No device probe ran. |
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
| `UnsatisfiedLinkError` on device | BLOCKED | — | **no device probe ran**; see Platform gap |

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

## Platform gap (JNI / emulator / Nothing Phone)

**No on-device JNI probe was run in this cloud-agent session.** Prior PASS numbers for models / JitPack AAR / APK assemble / PresentConnect ≤20 stand; they are not a substitute for `UnsatisfiedLinkError` on a phone.

Measured on this VM (2026-11-06, same Track B run):

| Check | Result |
|---|---|
| `adb devices` | empty (`List of devices attached` then nothing) |
| Android emulator package / system-images | **not installed** (`$ANDROID_SDK_ROOT/emulator` missing; no `emulator` binary) |
| `/dev/kvm` | present (`crw-rw---- root:103`) but agent user `ubuntu` is **not** in group `kvm` — cannot open KVM |
| `cursor-cloud list-self-hosted-workers` | **empty** (`workers: []`, `totalCount: 0`, no pools) |
| ADB / sideload / ListMachines tools in **this** agent | **none** (catalog search for ListMachines/adb/deviceLocal returned no tools) |

Operational consequence (H1 / P1.linq):

- Cursor cloud VMs here are **not a usable Android emulator path** for on-device JNI (no AVD, no KVM access for this uid, no connected device).
- No private Cloud Agent workers were connected (`agent worker start` list empty).
- Captain: johann's Nothing Phone (3a) shows up in Grok Bot ListMachines as android/connected, but that bot only has calendar/contacts/alarm/location `deviceLocalTools` — **no ADB/sideload**. This agent cannot see or drive that phone.
- Therefore a real JNI device probe and the demo-scenario mic test need **one of**:
  1. johann runs `./scripts/device-sideload-probe.sh` locally with USB/wireless debugging to the Nothing Phone (3a), or
  2. a self-hosted worker with Android SDK + a device or emulator, or
  3. a KVM-capable runner **with** emulator/system-images **and** kvm group access.

Host-side `scripts/host-jni-smoke.sh` only inspects the AAR `.so` on Linux x86_64. It does **not** replace the on-device `UnsatisfiedLinkError` probe.

## 4. Host JNI smoke (no emulator) — measured 2026-11-06

```bash
./scripts/host-jni-smoke.sh
```

**PARTIAL.** Wall **1021 ms**. Exit 0. Does **not** replace on-device `UnsatisfiedLinkError`.

AAR source (Gradle still on JitPack; not switched this push): Gradle cache  
`com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1`  
bytes **49441341**, sha256 `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7` (same as earlier JitPack GET).

After Origin PR #2 merges, overnight's canonical URL is  
`https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.5/sherpa-onnx-1.13.5.aar`  
(`SHERPA_AAR_URL=… ./scripts/host-jni-smoke.sh`). Not fetched this run.

| Check | Result |
|---|---|
| `.so` | `jni/x86_64/libsherpa-onnx-jni.so` — ELF 64-bit LSB x86-64, stripped |
| `readelf -d` NEEDED | `libandroid.so`, `liblog.so`, `libonnxruntime.so`, `libm.so`, `libdl.so`, `libc.so` |
| `nm -D` `Java_com_k2fsa_sherpa_onnx_*` | **134** defined (incl. `OfflineRecognizer_newFromFile`, `Vad_*`, `OfflineSpeakerDiarization_process`) |
| `JNI_OnLoad` | absent (explicit `Java_*` names) |
| `dlopen` / `ctypes.CDLL` | **fail**: `libandroid.so: cannot open shared object file` (Android bionic/NDK, expected on glibc host) |

Evidence: `artifacts/test-results/host-jni-smoke.json`, `host-jni-smoke.txt`.

## 5. Path B PresentConnect ingest + golden brief (JVM)

Tech demo = Path B (per-stream ASR, oracle `s0N`→`speaker_N`, merge by time). **Not** Path A cocktail mix. Eval numbers below are **Eval’s** (PR #4 `mix_20talker_per_stream`); this branch did not re-run Eval and does not invent WER/DER.

| Source | DER | WER_main | Speakers |
|---|---|---|---|
| Eval PR #4 `mix_20talker_per_stream` (cite only) | 0.235 | 0.087 | 20/20 |

Golden brief (exact Eval cut, 8 utterances / 7 speakers incl. `speaker_1`): `artifacts/test-results/golden_path_b_brief.json` and `samples/demo-stress/golden_path_b_brief.json`. Mapping: `samples/demo-stress/channels.json`.

```bash
cd src/android && ./gradlew --no-daemon :app:testStenographerDebugUnitTest
```

| Command | Result | Wall |
|---|---|---|
| `./gradlew :app:testStenographerDebugUnitTest` | **PASS** (17 tests, 0 failures) | 21 s then 14 s re-run |
| `./gradlew :app:compilePresentConnectDebugKotlin` | **PASS** | same second run |

JVM tests cover: brief four keys only (no `stream_id`), sorted by `start_ms`, `speaker_1` present and ≥3 distinct speakers (golden has 7), StreamLabeler `s01`→`speaker_1` / `s05`→`speaker_5` / `s20`→`speaker_20`, fake two-stream Path B session (oracle relabel, cancel does not absorb, PCM not mixed), stream table 20 accept / 21 `OVER_CAPACITY`.

No device JNI this push. No Eval re-run.
