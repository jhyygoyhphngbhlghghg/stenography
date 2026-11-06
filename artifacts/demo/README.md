# Материалы демонстрации

Добавить сюда или дать ссылки:

- видео: импорт → расшифровка в авиарежиме;
- исходный аудиоклип (2 спикера, RU) и экспортированный JSON;
- скрин списка реплик;
- (опционально) PresentConnect **Path B**: QR → отдельный браузерный mic на каждый `stream_id` (≤20) → текст на слайде. Не один смешанный микрофон комнаты: Path A (cocktail, 20→1) — measured FAIL. Цифры 20/20 — только clean per-stream (PR #4). Echo, overlap, white/room noise, bad mics: **unmeasured until edge suite**, **expect FAIL / hard degradation**, **do not claim in slides**. См. `docs/ARCHITECTURE.md`.

Крупные `.wav`/`.mp4` в git не кладём.
