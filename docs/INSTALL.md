# Установка и использование

## Сборка

Требуется JDK 17+, Android SDK (compileSdk 35).

```bash
cd src/android
./gradlew :app:assembleStenographerDebug          # JUDGE / default: embeds Zipformer-ru + Silero VAD + diar
./gradlew :app:assembleStenographerSlim           # karma: no weights
./gradlew :app:assemblePresentConnectDebug        # Path B flavor, also embeds models
```

Judge APK: `src/android/app/build/outputs/apk/stenographer/debug/`. Cold install works **offline** — first launch unpacks `assets/models` into `filesDir/models`. Do **not** rely on `download-models.sh` + adb push for the Hex.Team check. Slim APK has no ONNX (needs push if you want ASR). For сдачи скопировать в `apk/` (сами `.apk` в git не кладём).

## Device probe (Nothing Phone)

On-device JNI (`SherpaJni.probe()` / `UnsatisfiedLinkError`) cannot run on the Cursor cloud VM. From a machine with **adb** and USB or wireless debugging to johann's Nothing Phone (3a):

```bash
# APKs must already exist (see Сборка above), then:
./scripts/device-sideload-probe.sh
```

The script checks `adb devices`, `adb install -r` the stenographer debug APK, launches `MainActivity` so `NeuroStenoApp` loads `libsherpa-onnx-jni.so`, and writes `artifacts/test-results/device-jni-probe-logcat.txt`. Optional: `PRESENT=1` also installs the presentConnect APK; `PUSH_MODELS=1` pushes `models/` (not required for the JNI probe); `DEVICE_SERIAL=…` if more than one phone.

If APKs are missing it prints the exact `./gradlew :app:assembleStenographerDebug` commands and exits non-zero.

## Модели

Weights stay **gitignored**. The **judge APK packs them at assemble** (`prepareEmbeddedModels` from `./models`, downloading via `scripts/download-models.sh` if missing). First app launch copies `assets/models` → `filesDir/models`.

Slim / host cache only:

```bash
./scripts/download-models.sh
# adb push is NOT the judge path; use assembleStenographerDebug instead
```

Список и лицензии: `docs/MODELS.md`.

## Офлайн-стенография (авиарежим)

1. Один раз на Wi-Fi: убедиться, что модели на устройстве.
2. Авиарежим.
3. Импорт файла или запись.
4. Дождаться списка реплик.
5. Экспорт JSON.

Если модели нет — UI покажет **stub**, это не результат для судей.

## PresentConnect

Flavor `presentConnect`. Контракт: `docs/PRESENT_CONNECT.md`. Нужна сеть до презентационного BE, не до облачного ASR.
