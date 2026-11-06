# Артефакты тестирования

Таблица: `results.csv`. Не заполнять WER/RTF выдуманными числами. Строк он-девайс прогонов пока нет.

Eval-measured Path A (cocktail FAIL, 1/20) и Path B (20/20) лежат в `docs/TEST_RESULTS.md` и в [PR #4](https://cursor.com/codebase/johann-schilling/neuro-stenographer/pull/4). Под edge Path B держит stream tracking **20/20**. **WORST_CASE WER/DER FAIL:** WER main **0.9130**, DER **1.2985** (PR #4 @ `9908353d`). Clean WER/DER на WORST_CASE и EDGE_* не писать. **Do not claim in slides.** Каталога `artifacts/eval/` на `main` нет — не ссылаться на него как на файлы этой ветки.
