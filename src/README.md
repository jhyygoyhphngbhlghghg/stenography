# Source

Платформа: **Android**.

Gradle-проект: `src/android/` (module `:app`).

```
src/android/
  app/src/main/java/team/mikcoders/neurosteno/
    ui/          # Compose: import, запись, прогресс, список реплик, экспорт в файл
    audio/       # decode→16 kHz mono, MicRecorder, gain/EQ, energy/endpointing
    pipeline/    # prep(gain+EQ, заморожено) → VAD → diar K≤6 → ASR → LLM post-pass
    llm/         # локальная LLM: llama.cpp GGUF + IntonationPunct (только добавляет знаки)
    export/      # TranscriptJson — ровно 4 ключа брифа, ничего лишнего
    present/     # Path B: stream table + labeler + PathBSession + StreamPcmGate (≤20)
```

Сборка: см. корневой `README.md` и `docs/INSTALL.md`.

Тесты: `./gradlew :app:testStenographerDebugUnitTest` (110 JVM-тестов, 20 классов).
Модули и что из какой ветки приехало — `docs/ARCHITECTURE.md`.
