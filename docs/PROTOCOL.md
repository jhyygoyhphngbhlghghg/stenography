# Протокол обработки (офлайн) и PresentConnect

Акустический «protocol over speaker» из шаблона хакатона **не** используется. Этот файл — указатель:

- Пайплайн аудио→JSON: `docs/ARCHITECTURE.md`
- Схема файла: `docs/JSON.md`
- Заявка презентации **DEMO_STAGE6** (6 RU, один микрофон телефона, 3–6 м, sequential + лёгкое перекрытие в конце, большая комната, средний шум): `docs/ARCHITECTURE.md`. WER/DER pending Eval.
- Опциональный PresentConnect **Path B** (не эта заявка): `docs/PRESENT_CONNECT.md`. Каждый `stream_id` отдельно (PCM → VAD → ASR, `speaker_id` ← `stream_id`). Под edge только stream tracking **20/20**. **WORST_CASE WER/DER FAIL:** WER main **0.9130**, DER **1.2985** (PR #4 @ `9908353d`). Clean WER/DER на WORST_CASE и EDGE_* не заявлять. Path A (cocktail-mix ≥20) — measured FAIL, не демо.
