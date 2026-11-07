# APK drop

Built 2026-11-07 on this machine (gradle 8.11.1, AGP 8.7.3, compileSdk 35,
JDK 21). `.apk` files are gitignored — binaries do not go into the git tree;
the hand-in publishes them as Release assets:
**[v0.1.0-stage6](https://github.com/jhyygoyhphngbhlghghg/stenography/releases/tag/v0.1.0-stage6)**
(the pre-runtime-optimization hand-in snapshot; the tag is not moved by later commits).


| file | bytes | MiB | sha256 | what it is |
|---|---|---|---|---|
| `app-stenographer-debug.apk` | 220 529 139 | 210.3 | `c35929764492a7f8f142d53dbf5fc20a87b2294d6af4c1ad1d9af911261890da` | **JUDGE / Hex.Team check APK.** Zipformer-ru int8 + Silero VAD + pyannote segmentation-3.0 + 3D-Speaker embedding inside `assets/models` (119 466 051 bytes, 7 files). Cold install → first launch unpacks to `filesDir` → airplane mode works. |
| `app-stenographer-debug-embedLlm.apk` | 573 498 719 | 546.9 | `f89bc45a318507b462a508ac493c38a3c5045954a814ade93d638ad226ea3678` | same app, plus the 352 969 408-byte Qwen2-0.5B-Instruct Q4_0 GGUF (8 files, 472 435 459 asset bytes). This is the artifact that satisfies «локальная LLM» with nothing else pushed to the phone. Build with `-PembedLlm=true`. |
| `app-presentConnect-debug.apk` | 220 401 103 | 210.2 | `b9afd39f4ee9c7bd80b282e882df792fa911c0e935605285ee57e3490f980d83` | Path B flavor (`applicationIdSuffix .present`), ≤20 per-stream workers. Optional mode, not the defense claim. |
| `app-stenographer-slim.apk` | 97 599 021 | 93.1 | `6c52e259647d300fccf15216e2d8b188f71bd415f4e716d388b5b85eeae78311` | `assembleStenographerSlim`, no weights. Small download, needs models pushed before it can transcribe — UX karma only, **not** the check APK. |

## Local runtime-optimized candidate (2026-11-07)

`app-stenographer-runtime-embedLlm.apk` contains the serialized warm ASR/GGUF
session implementation and all eight unchanged model assets. Size
573 498 719 B; SHA-256
`f80d8ab14ed5fc7d2f5612b3448b562dad0a88835aab5cc6fa574e5fccb6b377`.
APK v2 signature, embedded model hashes, DEX content and 126 JVM tests per
flavor were checked. No phone execution has run. This is a **local candidate**,
not an uploaded release replacement; see
[verification.json](../artifacts/optimization/verification.json). It retains
the existing package/version, so identify this build by the filename/hash.

## Compact candidate (2026-11-07, rollup)

`app-stenographer-compact.apk` — **449 907 795 B** (−123.6 MB vs the runtime
candidate above), all 8 models inside, arm64-v8a only, R8/resource-shrunk,
losslessly compressed model assets, offline-prepared Zipformer encoder
(exact host output parity on 9 unchanged wavs; init 1370→590 ms median).
SHA-256 `d52c72653f7fc1588711b4507b42b52fb8dcdf805f5a6c806a09563fa9e9c58c`,
versionCode 2 / `0.2.0-compact`, debug-signed local candidate, v2 verified.
GGUF byte-identical to the original. Build:
`gradlew -p src/android :app:assembleStenographerCompact -PcompactModels=true`
(requires `scripts/prepare_compact_encoder.py` output + passing report).
Not phone-tested, not published; see
[compact/verification.json](../artifacts/compact/verification.json).

## Which one to install

For the experts' run (install APK → import three dialogues → export JSON, no
network): `app-stenographer-debug-embedLlm.apk` if the download is
affordable; otherwise `app-stenographer-debug.apk` **plus**
`adb push models/llm …` once, or the LLM line in the UI will honestly read
`llm-weights-missing (intonation punct only)`.

## How these were produced

```bash
./scripts/download-models.sh
cd src/android
./gradlew :app:assembleStenographerDebug                 # → check APK
./gradlew :app:assembleStenographerDebug -PembedLlm=true # → + GGUF
./gradlew :app:assemblePresentConnectDebug
./gradlew :app:assembleStenographerSlim
./gradlew :app:testStenographerDebugUnitTest             # 110 tests
```

Reproduce a hash with `sha256sum apk/*.apk`. `copyApkToDrop` copies the check
APK here from `app/build/outputs/apk/stenographer/debug/`.

## Not yet done

No on-device verification has run: this box has adb but no attached phone, so
`scripts/device-sideload-probe.sh` (sideload + launch + live mic probe) is
still a human action on the demo device.
