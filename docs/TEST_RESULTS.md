# Методика и результаты тестирования

Собственные прогоны команды (не судейский набор «котики»).

**Запрещено выдумывать WER/RTF.** Пока нет замера на реальном клипе — в таблице `pending`.

Шаблон строк: `artifacts/test-results/results.csv`.

## Методика (черновик)

1. Собрать debug APK flavor `stenographer`.
2. Положить модели (`scripts/download-models.sh` + push).
3. Авиарежим.
4. Импорт 2–3 своих диалогов (RU, ≥2 спикера, без перекрытия).
5. Экспорт JSON, сверка `speaker_id` / `start_ms` / `end_ms` / `text` с ручной расшифровкой.
6. Зафиксировать wall time и длительность аудио (RTF = wall / duration) только после реального замера.

## Статус overnight

| Проверка | Статус |
|---|---|
| Gradle assemble (shell) | pending first green build |
| Import → stub list → JSON (no models) | pending |
| Zipformer-ru on-device | pending models |
| Diarization ≥2 speakers | pending |
| Airplane mode | pending |
| PresentConnect ≤20 streams (mock) | pending |
