# Source

Две платформы, одна кодовая база.

| Платформа | Каталог | Что это |
|---|---|---|
| **mobile (android)** | `android/` | Kotlin + Jetpack Compose, приложение-стенографист. Модуль `:app`, два flavor |
| **web** | `web/` | Экраны показа: Node-бэкенд, React-дек, пульт ведущего |

Путей `src/linux/`, `src/windows/`, `src/macos/` нет — эти платформы не
реализуются (см. `docs/LIMITATIONS.md`).

## mobile/android

```
android/app/src/main/java/team/mikcoders/neurosteno/
  ui/          # Compose: импорт, запись, прогресс, список реплик, экспорт в файл
  audio/       # decode → 16 kHz mono, MicRecorder, gain/EQ, energy/endpointing
  pipeline/    # prep(gain+EQ, заморожено) → VAD → diar K≤6 → ASR → LLM post-pass
  llm/         # локальная LLM: llama.cpp GGUF + IntonationPunct (только добавляет знаки)
  export/      # TranscriptJson — ровно 4 ключа брифа, ничего лишнего
  present/     # Path B: stream table + labeler + PathBSession + StreamPcmGate (≤20)
```

Нужны JDK 17+ и Android SDK 35; `android/local.properties` с `sdk.dir`
создаётся из `local.properties.example` и в git не попадает.

```bash
cd src/android
./gradlew :app:testStenographerDebugUnitTest   # 110 JVM-тестов, 20 классов
./gradlew :app:assembleStenographerDebug       # check-APK: ASR+VAD+diar внутри
./gradlew :app:assembleStenographerDebug -PembedLlm=true   # тот же APK + GGUF
./gradlew :app:assembleStenographerSlim        # приложение без весов
./gradlew :app:assemblePresentConnectDebug     # сетевой worker (Path B)
```

Веса в git нет: `./scripts/download-models.sh` (~470 МБ, один раз по Wi-Fi).
Подробно — `docs/INSTALL.md`, размеры и sha256 — `docs/MODELS.md`.

## web

Три части, запускаются одной командой после сборки деки:

```bash
cd src/web/backend
npm install
ADMIN_TOKEN=<токен> npm run build:all   # tsc + vite build для React-дека
ADMIN_TOKEN=<токен> npm start           # http://localhost:8080
```

| Каталог | Роль |
|---|---|
| `web/backend/` | Node: WS/HTTP контракта, чекпоинты, раздача статики, экспорт JSON |
| `web/present/` | React-дек (TanStack Start) — основные экраны, восемь чекпоинтов |
| `web/public/` | пульт ведущего + встроенный (ванильный) дек как запасной вариант |

`/` — React-дек, `/presenter` — пульт, `/deck-legacy` — встроенный дек.
Телефон подключается к `ws://…/v1/present-connect?…&role=phone`.
Архитектура, API и режимы демо — `web/README.md`, контракт —
`docs/PRESENT_CONNECT.md`.

Тесты: `cd src/web/backend && npm test` (19), `cd src/web/present && npm test`
(14). Схема экспорта проверяется отдельно: `python3
scripts/verify-transcript-json.py <export.json>`.

Модули и что из какой ветки приехало — `docs/ARCHITECTURE.md`.
