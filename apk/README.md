# APK drop

Built 2026-11-07 on this machine (gradle 8.11.1, AGP 8.7.3, compileSdk 35,
JDK 21). `.apk` files are gitignored — binaries do not go into the git tree;
the hand-in publishes them as Release assets (or Git LFS) and links them from
the top-level README.

| file | bytes | MiB | sha256 | what it is |
|---|---|---|---|---|
| `app-stenographer-debug.apk` | 220 529 139 | 210.3 | `c35929764492a7f8f142d53dbf5fc20a87b2294d6af4c1ad1d9af911261890da` | **JUDGE / Hex.Team check APK.** Zipformer-ru int8 + Silero VAD + pyannote segmentation-3.0 + 3D-Speaker embedding inside `assets/models` (119 466 051 bytes, 7 files). Cold install → first launch unpacks to `filesDir` → airplane mode works. |
| `app-stenographer-debug-embedLlm.apk` | 573 498 719 | 546.9 | `f89bc45a318507b462a508ac493c38a3c5045954a814ade93d638ad226ea3678` | same app, plus the 352 969 408-byte Qwen2-0.5B-Instruct Q4_0 GGUF (8 files, 472 435 459 asset bytes). This is the artifact that satisfies «локальная LLM» with nothing else pushed to the phone. Build with `-PembedLlm=true`. |
| `app-presentConnect-debug.apk` | 220 401 103 | 210.2 | `b9afd39f4ee9c7bd80b282e882df792fa911c0e935605285ee57e3490f980d83` | Path B flavor (`applicationIdSuffix .present`), ≤20 per-stream workers. Optional mode, not the defense claim. |
| `app-stenographer-slim.apk` | 97 599 021 | 93.1 | `6c52e259647d300fccf15216e2d8b188f71bd415f4e716d388b5b85eeae78311` | `assembleStenographerSlim`, no weights. Small download, needs models pushed before it can transcribe — UX karma only, **not** the check APK. |

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
