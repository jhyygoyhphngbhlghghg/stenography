# Source

Платформа: **Android**.

Gradle-проект: `src/android/` (module `:app`).

```
src/android/
  app/src/main/java/team/mikcoders/neurosteno/
    ui/          # Compose: import, progress, transcript list, export
    audio/       # decode, record, PCM 16 kHz mono
    pipeline/    # VAD → diarize → ASR → LLM post-pass
    llm/         # heuristic punctuation + GGUF TODO weights
    export/      # JSON (brief schema) + TXT
    present/     # Path B: PresentConnectClient, per-stream isolated PCM → Zipformer-ru
```

Сборка: см. корневой `README.md` и `docs/INSTALL.md`.
