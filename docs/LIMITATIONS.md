# Ограничения решения

## Поддерживаемые условия

- Android 8.0+ (minSdk 26), демо на современном устройстве.
- Вход: локальный файл (WAV/M4A/MP3 через SAF) или запись микрофоном приложения.
- Выход: JSON брифа + просмотр списка реплик.
- Офлайн-стенография: авиарежим на **judge APK** (models already in assets; first launch unpacks to filesDir).
- PresentConnect: отдельный сетевой режим, до 20 одновременных потоков.

## Известные ограничения

- **WER / RTF не публикуем**, пока нет прогона на тестовых «котиках». Не выдумывать цифры.
- Zipformer-ru — RU-first; английский best-effort (для EN — Whisper tiny, не default).
- Перекрывающаяся речь и сильный шум смазывают диаризацию; демо — с явной сменой реплик.
- LLM post-pass: модуль есть, **веса не приложены** (TODO Gemma/Qwen/Phi via llama.cpp или MediaPipe). Сейчас identity / лёгкая пунктуация, честно в UI.
- Модели не в git (~74 MB+ ASR, плюс VAD/diar). **Judge APK embeds them.** Slim / assemble without `prepareEmbeddedModels` still stubs. `download-models.sh` + adb push is not the Hex.Team check path.
- NNAPI на OEM плавает; default provider = CPU.
- **JNI / emulator (platform gap):** Cursor cloud agent cannot run an on-device `UnsatisfiedLinkError` probe (no AVD, no `adb` device, self-hosted workers empty). Need johann's Nothing Phone (3a) via `scripts/device-sideload-probe.sh`, or a worker/runner with a device or emulator. Host `scripts/host-jni-smoke.sh` is not a substitute. Details: `docs/TEST_RESULTS.md` § Platform gap.
- Длинное аудио без VAD-chunking деградирует (особенно если позже включим GigaAM). VAD обязателен.
- PresentConnect не заменяет офлайн-проверку судей. Судьи ставят APK, импорт трёх клипов, экспорт JSON.

## Что не входит в текущую версию

- Web FE презентации и checkpoint-polling BE (другой трек).
- Обучение/дообучение ASR.
- pyannote Python на телефоне.
- Word-level timestamps, merge/rename спикеров в UI (stretch).
- whisper.cpp, Picovoice, облачный fallback.

## Риски демонстрации

- Забыть скачать модели до авиарежима → stub на защите (дисквалификация по смыслу «подмена расшифровкой»). Чеклист: `docs/INSTALL.md`.
- OOM на mid-range, если подложить GigaAM (~216 MB) вместо Zipformer-ru.
- Gated pyannote segmentation: нужна атрибуция / принятие terms до редистрибуции.

## Дальнейшее развитие

См. kickoff: T1 ASR → T2 diar+align → T3 export/polish → freeze 2026-11-07 21:00 MSK.
