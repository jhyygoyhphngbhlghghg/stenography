# Материалы демонстрации

Добавить сюда или дать ссылки:

- видео: импорт → расшифровка в авиарежиме;
- исходный аудиоклип (2 спикера, RU) и экспортированный JSON;
- скрин списка реплик;
- (опционально) PresentConnect **Path B**: QR → отдельный браузерный mic на каждый `stream_id` (≤20) → текст на слайде. Не один смешанный микрофон комнаты: Path A (cocktail, 20→1) — measured FAIL. Под edge только **20/20** stream tracking. **WORST_CASE FAIL:** WER main **0.9130**, DER **1.2985** (PR #4 @ `9908353d`). Clean WER/DER на edge не заявлять. **Do not claim in slides.** См. `docs/ARCHITECTURE.md`.

Крупные `.wav`/`.mp4` в git не кладём.
