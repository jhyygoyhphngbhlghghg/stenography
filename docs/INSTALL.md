# Установка и использование

Пункт 3 отчётного материала. Русский — основной язык инструкции; проверяющему
достаточно раздела «Быстрая проверка»: **установить APK → импортировать/записать
→ расшифровать → сохранить JSON**, всё в авиарежиме.

## Что сдаётся

| Материал | Где |
|---|---|
| APK | GitHub Releases: [v0.1.0-stage6](https://github.com/jhyygoyhphngbhlghghg/stenography/releases/tag/v0.1.0-stage6) + финальный демо-релиз ([страница релизов](https://github.com/jhyygoyhphngbhlghghg/stenography/releases)); в git бинарники не лежат — таблица sha256 в `apk/README.md` и в корневом `README.md` |
| исходный код | `src/android/`, `src/web/` |
| инструкция | этот файл |
| архитектура и модели | `docs/ARCHITECTURE.md`, `docs/MODELS.md` |
| ограничения | `docs/LIMITATIONS.md` |
| результаты собственных тестов | `docs/TEST_RESULTS.md`, `artifacts/test-results/results.csv`, `artifacts/eval/` |
| видеодемонстрация | `docs/DEMO.md`, `docs/demo/` |

## Требования к устройству

- Android 9.0+ (minSdk **28**); ABI: arm64-v8a / armeabi-v7a / x86_64.
  Локальная LLM (llama-android AAR) работает только на **arm64-v8a** — на
  других ABI пунктуация деградирует до интонационной эвристики, и UI это
  честно пишет.
- Свободное место: ≈1.2 ГБ для embedLlm-варианта (APK 547 МиБ + распаковка
  ≈472 МиБ ассетов), ≈600 МБ для check-APK.
- Разрешения: **микрофон** (запись), **доступ к файлам/хранилищу** (SAF-импорт
  и экспорт), **сеть** — только для flavor presentConnect; офлайн-режим сеть
  не использует (аудит кода + эмуляторный прогон без сети — REPORT agent2).

**Статус on-device проверки на момент сдачи:** прогон на физическом телефоне
не выполнялся (нет устройства); приложение проверено на эмуляторах
(LDPlayer/AVD, ARM-трансляция) — установка, распаковка моделей, JNI, живой
пайплайн, экспорт. Подробности и провенанс — `docs/TEST_RESULTS.md`.

## Вариант 1 (рекомендуемый): app-stenographer-debug-embedLlm.apk — всё на устройстве

573 498 719 B, sha256 `f89bc45a…3678`. Внутри 8 ассетов (472 435 459 B):
ASR+VAD+диаризация (7 файлов) **и** GGUF локальной LLM. Ничего докачивать не
нужно — артефакт, который закрывает требование «локальная LLM» сам по себе.

```bash
adb install -r app-stenographer-debug-embedLlm.apk
```

1. Запустить приложение, выдать разрешения (микрофон, файлы).
2. Дождаться окончания распаковки моделей в `filesDir/models` (шкала в UI;
  marker `.unpacked` пишется последним, обрыв копии ретраится — фикс
   `3a04212`, REPORT agent2).
3. Проверить шапку: `Офлайн: Zipformer-ru + Silero VAD + diar (models в APK)`,
   `JNI libsherpa-onnx-jni.so: loaded`,
   `LLM: llama.cpp GGUF qwen2-0_5b-instruct-q4_0.gguf`.
4. Включить **авиарежим** — дальше сеть не нужна.

## Вариант 2: app-stenographer-debug.apk (+ один adb push для LLM)

220 529 139 B, sha256 `c3592976…90da`. Те же 7 файлов моделей внутри
(119 466 051 B), но без GGUF. Полная офлайн-стенография работает сразу;
LLM-строка в UI будет красным `llm-weights-missing (intonation punct only)`,
пока веса не доложены:

```bash
adb install -r app-stenographer-debug.apk
# один раз, до авиарежима:
./scripts/download-models.sh   # если на хосте ещё нет models/
adb push models/llm /sdcard/Android/data/team.mikcoders.neurosteno/files/models/
```

После push перезапустить приложение — строка `LLM:` должна смениться на
`llama.cpp GGUF qwen2-0_5b-instruct-q4_0.gguf`.

## Вариант 3: app-presentConnect-debug.apk — демо-стенд живой трансляции

220 401 103 B, sha256 `b9afd39f…0d83`. Flavor `presentConnect`
(`applicationIdSuffix .present`), телефон-воркер ≤20 изолированных потоков,
протокол `present-connect/1` (контракт — `docs/PRESENT_CONNECT.md`).
**Это сетевой режим**: нужен доступ до презентационного бэкенда (не до
облачного ASR — распознавание всё равно на телефоне).

Параметры демо-стенда:

| параметр | значение |
|---|---|
| URL (основной) | `https://present.144.31.215.37.sslip.io/` (nginx + Let's Encrypt, L4 SNI-роутер) |
| URL (fallback) | `https://144.31.215.37:8080` (самоподписанный сертификат — принять предупреждение) |
| WSS воркера | `wss://present.144.31.215.37.sslip.io/v1/present-connect?session=<id>&role=worker&device=<id>` |
| токен телефона | `09879078` (значение по умолчанию; `Authorization: Bearer`) |
| токен пульта (ADMIN_TOKEN) | `09879078` |

Порядок: установить APK → в UI ввести токен `09879078` → **Connect + Live** →
`⏺ Live ASR → web`. Экран зала: `https://present.144.31.215.37.sslip.io/`
(первый слайд — QR на `/join?session=…`), пульт — `/presenter`. Важно: при
молчании телефона ~45 с сервер уходит в `fallback` с баннером «показана
запись, не результат модели» — приложение должно пушить на протяжении всего
показа (REPORT agent3, риск №4).

## Вариант 4: app-stenographer-slim.apk — не для проверки

97 599 021 B, sha256 `6c52e259…8311`. Без весов: маленький download, но до
транскрипции нужно запушить весь набор моделей (`adb push models/…` в ту же
папку `files/models/`). UX-вариант, **не** проверочный APK.

## Быстрая проверка офлайн-стенографии (авиарежим)

1. Приложение открывалось хотя бы раз после установки (распаковка завершена,
   в шапке нет `pipeline = stub`).
2. Включить авиарежим (в ролике — показать значок в том же кадре).
3. **Импорт** → WAV/M4A/MP3 из памяти (декодируется в 16 кГц моно), либо
   **Запись** с микрофона.
4. **Расшифровать**. Стадии: VAD → диаризация (K ≤ 6 в сцене DEMO_STAGE6;
   auto-cluster в presentConnect) → Zipformer-ru int8 по кропам → локальная
   LLM (интонационная пунктуация; GGUF никогда не меняет слова — гейт
   `mergeCandidate`).
5. Список реплик: `speaker_N`, `start_ms`/`end_ms`, текст с запятыми.
6. **Сохранить JSON** (SAF, `transcript.json`) или **Поделиться**; на ветке
   продуктовых фич также TXT/DOCX/PDF/SRT/WebVTT/CSV (PDF с кириллицей —
   известное ограничение шрифта, см. `docs/LIMITATIONS.md`).
7. Проверка схемы без телефона:

   ```bash
   python3 scripts/verify-transcript-json.py export.json
   ```

Формат: `docs/JSON.md` — ровно четыре ключа реплики (`speaker_id`,
`start_ms`, `end_ms`, `text`), лишние/недостающие ключи отклоняются.

**Аудио-экспорта нет**: `MicRecorder` держит PCM в памяти, импортный файл
остаётся исходником; сравнивать контрольные суммы нужно между исходным
файлом и экспортированным JSON (см. чеклист `docs/demo/README.md`).

## Сборка из исходников

Нужны JDK 17+ и Android SDK 35 (измерено: gradle 8.11.1, AGP 8.7.3,
compileSdk 35, JDK 21).

```bash
cp src/android/local.properties.example src/android/local.properties  # sdk.dir=...
./scripts/download-models.sh        # веса в host models/ (~470 МБ), в git не лежат
cd src/android
./gradlew :app:testStenographerDebugUnitTest              # JVM-юнит-тесты
./gradlew :app:assembleStenographerDebug                  # check-APK
./gradlew :app:assembleStenographerDebug -PembedLlm=true  # + GGUF
./gradlew :app:assemblePresentConnectDebug                # демо-стенд
./gradlew :app:assembleStenographerSlim                   # без весов
```

Первая сборка тянет sherpa-onnx AAR v2.0.1 (JitPack, 49 441 341 B, sha256
`a8d09d1d…c9bf0ee7`) и llama-android 0.1.1. Ловушка сборки: задача
`:app:prepareEmbeddedModels` раньше не объявляла входы и могла переложить в
APK **протухшие** веса (int8-сегментацию вместо float) — исправлено, но после
любых изменений `models/` проверяйте листинг ассетов APK (подробности —
`docs/TEST_RESULTS.md` §«Два бага сборки»). `copyApkToDrop` копирует готовые
APK в `apk/`.

## Если что-то не работает

| Симптом | Причина | Что делать |
|---|---|---|
| `pipeline = stub` | нет весов в `filesDir/models` | переустановить APK с ассетами или `adb push models/ …` |
| `JNI … FAIL` | не та ABI / не тот AAR | пересобрать, проверить `scripts/inspect-sherpa-aar.sh` |
| `LLM: llm-weights-missing` | GGUF не на устройстве (или ABI не arm64) | вариант 1/2 выше; на x86_64 эмуляторе — эвристика, это ожидаемо |
| GGUF 0 байт после обрыва установки | устаревшая сборка | в текущем коде фикс: truncated-GGUF ретраится (`3a04212`) |
| пусто на репликах 5–6 м | дальние места — известная граница | `docs/LIMITATIONS.md`; чудес не обещаем |
| приложение просит сеть | это flavor presentConnect | офлайн-режим сети не требует |
| дек показывает «Резервный режим» | телефон молчит >45 с | держать live-пуш активным; переподключиться (hello → hello.ok) |
