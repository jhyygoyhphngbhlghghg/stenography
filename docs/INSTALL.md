# Установка и использование

## Сборка

Требуется JDK 17+, Android SDK (compileSdk 35).

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

Список и лицензии: `docs/MODELS.md` (добавляется следующим коммитом, если ещё нет).

## Офлайн-стенография (авиарежим)

1. Один раз на Wi-Fi: убедиться, что модели на устройстве.
2. Авиарежим.
3. Импорт файла или запись.
4. Дождаться списка реплик.
5. Экспорт JSON.

Если модели нет — UI покажет **stub**, это не результат для судей.

## PresentConnect

Презентация — **DEMO_STAGE6** (6 RU, один микрофон телефона, 3–6 м). WER/DER pending Eval. Flavor `presentConnect` — **опциональный Path B**, не эта заявка: ≤20 отдельных потоков. Под edge только **20/20** stream tracking. **WORST_CASE FAIL:** WER main **0.9130**, DER **1.2985** (PR #4 @ `9908353d`). Clean WER/DER на WORST_CASE и EDGE_* не заявлять. Контракт: `docs/PRESENT_CONNECT.md`. Нужна сеть до презентационного BE, не до облачного ASR.
