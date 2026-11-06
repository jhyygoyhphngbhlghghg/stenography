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
| Gradle `assembleDebug` | green on this machine (sherpa-onnx 1.13.5 AAR + Kotlin API) |
| Unit tests (`testStenographerDebugUnitTest`) | run via `scripts/ci-check.sh` |
| JSON schema (`verify-transcript-json.py`) | fixture OK; extra keys rejected |
| Eval WER+RTF (clip_01..03) | skeleton only — **no invented numbers**; CSV pending |
| Import → stub list → JSON (no models) | pending on-device |
| Zipformer-ru on-device | pending models (`scripts/download-models.sh`) |
| Diarization ≥2 speakers | pending on-device |
| Airplane mode | pending on-device |
| PresentConnect ≤20 streams | unit cap test; WS needs web BE |
