# Методика и результаты тестирования

Собственные прогоны команды (не судейский набор «котики»).

**Запрещено выдумывать WER/RTF.** Пока нет замера на реальном клипе — в таблице `pending`.

Шаблон строк: `artifacts/test-results/results.csv` (колонки включают `clip_id`, `wer`, `speakers_ref`; строк с замерами нет).

## Harness A-12

Скелет: `eval/README.md`. Ровно три слота — `clip_01`, `clip_02`, `clip_03`. Как положить аудио и эталон: `eval/clips/README.md`. Судейские клипы «котики» в репозиторий не коммитим. Свои RU-диалоги для сухого прогона лежат в `samples/` и судейским набором не являются. Закоммиченные `eval/gold/clip_XX.gold.json` — пустые `transcript`, не эталон.

Один клип, когда есть экспорт JSON и измеренные длительность и wall time:

```bash
python3 -m eval.metrics.wer_rtf \
  --hyp path/to/hypothesis.json \
  --gold eval/gold/clip_01.gold.json \
  --duration-ms <measured> \
  --wall-ms <measured> \
  --clip-id clip_01
```

То же самое: `./scripts/eval-wer.sh` с теми же флагами.

Если `duration_ms` или `wall_ms` не переданы, RTF остаётся `pending`. Пустой эталон даёт WER `pending`, не ноль. Не вписывать WER/RTF в `results.csv`, пока нет реального прогона на устройстве или хосте. Вывод сухого прогона на `eval/fixtures/` — проверка функции на игрушечных строках, не метрика речи, в таблицу не копировать.

```bash
python3 -m eval.metrics.wer_rtf \
  --hyp eval/fixtures/toy_hyp.json \
  --gold eval/fixtures/toy_gold.json \
  --clip-id toy \
  --notes "toy fixture; not a measured clip"

python3 -m unittest eval.metrics.test_wer
```

Что должно стать зелёным позже: `eval/GATE.md`. Сейчас статус — skeleton only.

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
| Eval harness, 3 clips, WER+RTF | skeleton only; no measured row |
