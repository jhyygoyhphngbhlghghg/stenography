# Протокол обработки (офлайн) и PresentConnect

Акустический «protocol over speaker» из шаблона хакатона **не** используется. Этот файл — указатель:

- Пайплайн аудио→JSON: `docs/ARCHITECTURE.md`
- Схема файла: `docs/JSON.md`
- Сетевой контракт с презентацией (WebSocket/HTTP, ≤20 потоков): `docs/PRESENT_CONNECT.md`. Демо PresentConnect = **Path B**: каждый `stream_id` отдельно (PCM → VAD → ASR, `speaker_id` ← `stream_id`). Под edge остаётся только stream tracking **20/20**. **WORST_CASE WER/DER FAIL:** WER main **0.9130**, DER **1.2985** (PR #4 @ `9908353d`). Clean WER/DER на WORST_CASE и EDGE_* не заявлять. Killers: overlap/bleed, noise false alarms, bad mic. Reverb alone does not collapse Path B. Path A (один cocktail-mix комнаты) — measured FAIL, не демо.
