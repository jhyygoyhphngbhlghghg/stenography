# Установка и использование

Это инструкция для сдачи (пункт 3 отчётного материала). Проверяющему нужно
только первое: **установить APK, импортировать запись, получить JSON**.

## Что сдаётся

| Материал | Где |
|---|---|
| APK | `apk/` (в git бинарники не лежат; выкладывается как Release/LFS-артефакт — см. `apk/README.md`) |
| исходный код | `src/android/` |
| инструкция | этот файл |
| архитектура и модели | `docs/ARCHITECTURE.md`, `docs/MODELS.md` |
| ограничения | `docs/LIMITATIONS.md` |
| результаты собственных тестов | `docs/TEST_RESULTS.md`, `artifacts/test-results/results.csv`, `artifacts/eval/` |
| демонстрация (видео) | `artifacts/demo/` |

## 1. Установка

Минимум Android 8.0 (API 26). Сборка под arm64-v8a / armeabi-v7a / x86_64.
**Он-девайс прогон (sideload + живой микрофон) на момент сдачи ещё не сделан** —
для него нужен демо-телефон; APK ниже собран и проверен сборкой, не установкой.

```bash
adb install -r apk/app-stenographer-debug.apk
```

APK собран с весами внутри (Zipformer-ru int8, Silero VAD, pyannote
segmentation-3.0 + 3D-Speaker embedding, ≈114 МиБ ассетов). При первом
запуске они распаковываются в `filesDir/models` приложения — сеть для этого
не нужна. В шапке экрана видно `Офлайн: Zipformer-ru + Silero VAD + diar
(models в APK)`; строка `Сейчас pipeline = stub` означает, что весов нет, и
судьям такой кадр показывать нельзя.

### Локальная LLM

ТЗ требует локальную LLM, и она в приложении настоящая (Qwen2-0.5B-Instruct
Q4_0 через llama.cpp JNI). Два способа положить веса на устройство:

1. **APK со всеми весами** — пересобрать с флагом, тогда GGUF (353 МиБ)
   едет внутри ассетов и после установки ничего докачивать не нужно:

   ```bash
   ./scripts/download-models.sh
   cd src/android && ./gradlew :app:assembleStenographerDebug -PembedLlm=true
   ```

2. **Отдельно закачать GGUF** (APK остаётся ≈205 МиБ):

   ```bash
   ./scripts/download-models.sh
   adb push models/llm /sdcard/Android/data/team.mikcoders.neurosteno/files/models/
   ```

Строка `LLM:` в UI показывает правду: `llama.cpp GGUF qwen2-0_5b-instruct-q4_0.gguf`
(на устройстве) или `llm-weights-missing (intonation punct only)` красным —
во втором случае пунктуацию добавляет только интонационный пост-проход,
настоящей LLM нет.

## 2. Проверка офлайн-стенографии (авиарежим)

1. Убедиться, что приложение открывалось хотя бы раз после установки (идёт
   распаковка ≈114 МиБ — на это уходят секунды, шкала в UI есть).
2. Включить авиарежим.
3. **Импорт** → выбрать диалог из памяти устройства (WAV / M4A / MP3; всё
   декодируется в 16 кГц моно) — либо **Запись** прямо из приложения.
4. **Расшифровать**. Стадии: VAD → диаризация (K ≤ 6) → Zipformer-ru по
   каждомуcrop → локальная LLM (пунктуация по интонации).
5. Показать список реплик: `speaker_N`, время начала/конца, текст.
6. **Сохранить JSON** (SAF-диалог, файл `transcript.json`) — это обязательный
   экспорт в файле; **Поделиться** отдаёт тот же payload chooser'ом.
7. Проверить схему можно и без телефона:

   ```bash
   python3 scripts/verify-transcript-json.py export.json
   ```

Формат файла: `docs/JSON.md`. Ровно четыре ключа в реплике —
`speaker_id`, `start_ms`, `end_ms`, `text`.

## 3. Сборка из исходников

Нужен JDK 17+ и Android SDK 35.

```bash
cp src/android/local.properties.example src/android/local.properties   # sdk.dir=...
cd src/android
./gradlew :app:testStenographerDebugUnitTest   # 110 JVM-тестов
./gradlew :app:assembleStenographerDebug       # check-APK с весами
./gradlew :app:assembleStenographerSlim        # тот же app без весов (karma)
./gradlew :app:assemblePresentConnectDebug     # optional-режим Path B
```

Первая сборка тянет sherpa-onnx AAR (JitPack, 49 441 341 байт, sha256
`a8d09d1d…c9bf0ee7`, проверка через `scripts/inspect-sherpa-aar.sh`) и
`dev.ffmpegkit-maintained:llama-android:0.1.1`. Веса в git не кладутся —
только `./scripts/download-models.sh`.

## 4. PresentConnect (не заявка защиты)

Второй flavor — `presentConnect`. Телефон воркер на ≤20 отдельных потоков
(один `stream_id` — один буфер — один Zipformer-ru), контракт в
`docs/PRESENT_CONNECT.md`. Нужна сеть **до презентационного бэкенда**, не до
облачного ASR. В UI это отдельные чипы потоков и флаги микрофона. Это
опциональный режим: на защите показываем DEMO_STAGE6.

## 5. Если что-то не работает

| Симптом | Причина | Что делать |
|---|---|---|
| `pipeline = stub` | нет весов в `filesDir/models` | переустановить APK с ассетами либо `adb push models/ …` |
| `JNI … FAIL` | не та ABI / не тот AAR | собрать заново, проверить `scripts/inspect-sherpa-aar.sh` |
| `LLM: llm-weights-missing` | GGUF не на устройстве | способ 1 или 2 выше |
| пусто на репликах 5–6 м | дальние места — известная граница | см. `docs/LIMITATIONS.md`, не обещаем чудес |
| приложение просит сеть | это flavor `presentConnect` | офлайн-режим сети не требует |
