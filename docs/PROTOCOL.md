# Протокол обработки (офлайн) и PresentConnect

Акустический «protocol over speaker» из шаблона хакатона **не** используется. Этот файл — указатель:

- Пайплайн аудио→JSON: `docs/ARCHITECTURE.md`
- Схема файла: `docs/JSON.md`
- Сетевой контракт с презентацией (WebSocket/HTTP, ≤20 потоков): `docs/PRESENT_CONNECT.md`. Демо PresentConnect = **Path B**: каждый `stream_id` отдельно (PCM → VAD → ASR, `speaker_id` ← `stream_id`). Цифры 20/20, DER ≈ 0.24, main WER 0.087 — только **clean per-stream** (PR #4). Echo, overlap, white/room noise, bad mics: **unmeasured until edge suite**, **expect FAIL / hard degradation**, **do not claim in slides**. Path A (один cocktail-mix комнаты) — measured FAIL, не демо.
