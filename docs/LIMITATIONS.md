# Ограничения решения

## Поддерживаемые условия

- Android 8.0+ (minSdk 26), демо на современном устройстве.
- Вход: локальный файл (WAV/M4A/MP3 через SAF) или запись микрофоном приложения.
- Выход: JSON брифа + просмотр списка реплик.
- Офлайн-стенография: авиарежим после того, как модели лежат в filesDir/assets.
- PresentConnect (демо **Path B**): отдельный сетевой режим, до **20 отдельных** потоков (один mic = один `stream_id`). 21-й поток — `OVER_CAPACITY`.

## Multi-talker: что поддерживаем

Поддерживаемое multi-talker демо — **Path B**: ≤20 отдельных PresentConnect-потоков, каждый сам по себе (PCM → VAD → ASR), `speaker_id` ← `stream_id` (или метка места). Цифры — Eval-measured, не обещание он-девайс прогона и не новые выдумки (таблица в `docs/TEST_RESULTS.md`, источник [PR #4](https://cursor.com/codebase/johann-schilling/neuro-stenographer/pull/4)): **20/20** спикеров, DER **0.24** (в замере также 0.235), main WER **0.087**, RTF **2.65**.

**Path A не заявляем.** One-room cocktail (20 говорящих в один смешанный поток, потом кластеризация) — hard fail того же замера: **1/20**, DER **0.95**, main WER **1.00**, RTF **0.18**. Офлайн-файл по-прежнему одна запись (VAD → диаризация → ASR) и не является этим демо.

## Известные ограничения

- **Новые WER/RTF не выдумывать.** Eval-measured Path A/B и overnight-клипы (~3.5% / 0% / 0% WER, без перекрытия) можно цитировать только как замер H1 / PR #4. Он-девайс строки в `artifacts/test-results/results.csv` пусты, пока нет своего прогона.
- Zipformer-ru — RU-first; английский best-effort (для EN — Whisper tiny, не default).
- Перекрывающаяся речь в **одной** записи смазывает `OfflineSpeakerDiarization`. Для ≥20 одновременно говорящих в одном миксе это уже не «смазывает», а Path A FAIL (1/20). Офлайн-демо файла — с явной сменой реплик. Multi-talker демо сцены — только Path B, отдельные потоки.
- LLM post-pass: модуль есть, **веса не приложены** (TODO Gemma/Qwen/Phi via llama.cpp или MediaPipe). Сейчас identity / лёгкая пунктуация, честно в UI.
- Модели не в git (~74 MB+ ASR, плюс VAD/diar). Без `download-models.sh` / adb push приложение соберётся, но pipeline уйдёт в stub.
- NNAPI на OEM плавает; default provider = CPU.
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
