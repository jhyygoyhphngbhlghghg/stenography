# Ограничения решения

## Поддерживаемые условия

- Android 9.0+ (minSdk 28; brief 26–28), демо на современном устройстве.
- Вход: локальный файл (WAV/M4A/MP3 через SAF) или запись микрофоном приложения.
- Выход: JSON брифа + просмотр списка реплик.
- Офлайн-стенография: авиарежим после того, как модели лежат в filesDir/assets.
- PresentConnect (**Path B**): отдельный сетевой режим, до **20 отдельных** browser/mic потоков в локальный Zipformer-ru + диаризацию. Поток 21 → `OVER_CAPACITY`. PCM потоков **не смешиваются** до ASR/diar. Path A (один mixed room mic / cocktail) для демо **отклонён** (H1: mix DER 0.95, 1/20 FAIL vs Path B 20/20, DER 0.235, main WER 0.087 — цифры цитируются, не пересчитывались).
- Worst-case Eval bar (cite only, not recomputed): EDGE_overlap WER 0.74 DER 0.99; bad mic WER 1.0; noise false alarms DER 1.70. Gates may drop very quiet true speech rather than decode bleed/hiss. Reverb was fine — not a focus.

## Известные ограничения

- **WER / RTF не публикуем**, пока нет прогона на тестовых «котиках». Не выдумывать цифры.
- Zipformer-ru — RU-first; английский best-effort (для EN — Whisper tiny, не default).
- Перекрывающаяся речь и сильный шум смазывают диаризацию; демо — с явной сменой реплик.
- LLM post-pass: модуль есть, **веса не приложены** (TODO Gemma/Qwen/Phi via llama.cpp или MediaPipe). Сейчас identity / лёгкая пунктуация, честно в UI.
- Модели не в git (~74 MB+ ASR, плюс VAD/diar). Без `download-models.sh` / adb push приложение соберётся, но pipeline уйдёт в stub.
- NNAPI на OEM плавает; default provider = CPU.
- Длинное аудио без VAD-chunking деградирует (особенно если позже включим GigaAM). VAD обязателен.
- PresentConnect не заменяет офлайн-проверку судей. Судьи ставят APK, импорт трёх клипов, экспорт JSON.
- Path A mixdown (сложить 20 микрофонов в один буфер) не используется: это ломает диаризацию на демо.

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
