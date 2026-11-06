# Ограничения решения

## Поддерживаемые условия

- Android 9.0+ (minSdk 28; brief 26–28), демо на современном устройстве.
- Вход: локальный файл (WAV/M4A/MP3 через SAF) или запись микрофоном приложения.
- Выход: JSON брифа + просмотр списка реплик.
- Офлайн-стенография: авиарежим после того, как модели лежат в filesDir/assets.
- PresentConnect: побочный сетевой codepath, до 20 потоков; **не** live-demo.
- Live demo: DEMO_STAGE6, один mic 3–6 м. Whole-file AGC не используем (cite Eval e0e64b85: 0.73→0.70, s5 missing).

## Известные ограничения

- **WER / RTF не публикуем**, пока нет прогона на тестовых «котиках». Не выдумывать цифры.
- Zipformer-ru — RU-first; английский best-effort (для EN — Whisper tiny, не default).
- Перекрывающаяся речь и сильный шум смазывают диаризацию; DEMO_STAGE6 — 6 sequential RU с лёгким end-overlap.
- Cite-only STAGE6 @ `a8b39d5a`: agg WER **0.73**; 3.0 m **0**; 3.6 m **0.55**; 4.2–6.0 m **≈1.0** (s5 missing). Whole-file AGC @ `e0e64b85`: **0.70**, s5 still missing. No invented numbers on this PR.
- LLM: llama.cpp + Qwen2-0.5B-Instruct Q4_0 GGUF. Zipformer emits no punct (cite Eval 0824df64 keep-punct 0.7321→0.7794; s1 0→0.2857) — post-pass **adds** spoken-intonation `, . ? !`. Commas are not rewritten to textbook placement. Identity stub is not the check-APK LLM.
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
