# Установка и использование

## Сборка

Требуется JDK 17+, Android SDK (compileSdk 35, minSdk 28). Copy `src/android/local.properties.example` → `local.properties`.

```bash
cd src/android
./gradlew :app:assembleStenographerDebug
./gradlew :app:assemblePresentConnectDebug
```

APK: `src/android/app/build/outputs/apk/<flavor>/debug/`. Для сдачи скопировать в `apk/` (сами `.apk` в git не кладём).

## Модели

Веса **не** в репозитории. Перед демо:

```bash
./scripts/download-models.sh
# adb push models/ onto the device app filesDir, or pack into assets/models/
```

Список и лицензии: `docs/MODELS.md`. AAR: `scripts/fetch-sherpa-aar.sh` (или Gradle task `fetchSherpaOnnxAar`).

## Офлайн-стенография (авиарежим) — DEMO_STAGE6

Live demo: **один** телефонный mic, 6 спикеров, 3–6 м. Не 20 браузерных потоков.

1. Один раз на Wi-Fi: убедиться, что модели на устройстве.
2. Авиарежим.
3. Импорт файла или запись с телефона (3–6 м).
4. Дождаться списка реплик (per-turn gain внутри pipeline).
5. Экспорт JSON.

Если модели нет — UI покажет **stub**, это не результат для судей.

## PresentConnect

Flavor `presentConnect`. **Path B:** ≤20 отдельных browser/mic потоков → локальный Zipformer-ru + diar; mixdown (Path A) не используется. Контракт: `docs/PRESENT_CONNECT.md`. Нужна сеть до презентационного BE, не до облачного ASR.
