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

Веса **не** в git. **Check APK и сдача: assets-bundled primary** — Zipformer-ru, VAD, diarization и **локальная LLM** лежат в `assets/models/` внутри APK. Авиарежим после этого сеть не нужен.

Сетевая докачка — **только UX karma**, для ТЗ и check APK не требуется.

Путь разработки (не сдача):

```bash
./scripts/download-models.sh
# adb push models/ into the app filesDir
```

Список: `docs/MODELS.md`.

## Офлайн-стенография (авиарежим) — DEMO_STAGE6

1. Поставить check APK со весами в assets, включая локальную LLM.
2. Авиарежим.
3. Импорт файла или запись (один микрофон, far-field).
4. Дождаться списка реплик после VAD → диаризация → Zipformer-ru → **локальный LLM post-pass**.
5. Экспорт JSON.

Судьи получают реальный on-device LLM, не identity-stub. `IdentityLlmPostPass` в текущем коде — разрыв Impl/Platform против ТЗ: закрыть до сдачи. APK без весов — не результат для судей.

## PresentConnect

Презентация — **DEMO_STAGE6** (6 RU, один микрофон телефона, сцена 3–6 м). Первый замер: aggregate **WER 0.73 / DER 0.52**; ideal recognition **только @ 3 м**. Gain **0.73 / 0.70 / 0.73** комнату не вернул. Flavor `presentConnect` — **опциональный Path B**, не эта заявка: ≤20 отдельных потоков. Под edge только **20/20** stream tracking. **WORST_CASE FAIL:** WER main **0.9130**, DER **1.2985** (PR #4 @ `9908353d`). Clean WER/DER на WORST_CASE и EDGE_* не заявлять. Контракт: `docs/PRESENT_CONNECT.md`. Нужна сеть до презентационного BE, не до облачного ASR.
