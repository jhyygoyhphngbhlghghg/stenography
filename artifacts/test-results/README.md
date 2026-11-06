# Артефакты тестирования

Таблица: `results.csv`. Не заполнять WER/RTF выдуманными числами. Строк он-девайс прогонов пока нет.

Eval-measured Path A (cocktail FAIL, 1/20) и Path B (20/20) лежат в `docs/TEST_RESULTS.md` и в [PR #4](https://cursor.com/codebase/johann-schilling/neuro-stenographer/pull/4). Path B 20/20 — **clean per-stream** only. Edge suite (echo, overlap, white/room noise, bad mics): **unmeasured until edge suite**, чисел нет, **expect FAIL / hard degradation**, **do not claim in slides**. Каталога `artifacts/eval/` на `main` нет — не ссылаться на него как на файлы этой ветки.
