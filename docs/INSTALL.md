# Установка и использование

## Сборка

Требуется JDK 17+, Android SDK (compileSdk 35).

```bash
cd src/android
./gradlew :app:assembleStenographerDebug
./gradlew :app:assemblePresentConnectDebug
```

APK: `src/android/app/build/outputs/apk/<flavor>/debug/`. Для сдачи скопировать в `apk/` (сами `.apk` в git не кладём).

## Device probe (Nothing Phone)

On-device JNI (`SherpaJni.probe()` / `UnsatisfiedLinkError`) cannot run on the Cursor cloud VM. From a machine with **adb** and USB or wireless debugging to johann's Nothing Phone (3a):

```bash
# APKs must already exist (see Сборка above), then:
./scripts/device-sideload-probe.sh
```

The script checks `adb devices`, `adb install -r` the stenographer debug APK, launches `MainActivity` so `NeuroStenoApp` loads `libsherpa-onnx-jni.so`, and writes `artifacts/test-results/device-jni-probe-logcat.txt`. Optional: `PRESENT=1` also installs the presentConnect APK; `PUSH_MODELS=1` pushes `models/` (not required for the JNI probe); `DEVICE_SERIAL=…` if more than one phone.

If APKs are missing it prints the exact `./gradlew :app:assembleStenographerDebug` commands and exits non-zero.

## Модели

Веса **не** в репозитории. Перед демо:

```bash
./scripts/download-models.sh
# adb push models/ onto the device app filesDir, or pack into assets/models/
```

Список и лицензии: `docs/MODELS.md` (добавляется следующим коммитом, если ещё нет).

## Офлайн-стенография (авиарежим)

1. Один раз на Wi-Fi: убедиться, что модели на устройстве.
2. Авиарежим.
3. Импорт файла или запись.
4. Дождаться списка реплик.
5. Экспорт JSON.

Если модели нет — UI покажет **stub**, это не результат для судей.

## PresentConnect

Flavor `presentConnect`. Контракт: `docs/PRESENT_CONNECT.md`. Нужна сеть до презентационного BE, не до облачного ASR.
