# Артефакты тестирования

Таблица: `results.csv`. Не заполнять WER/RTF выдуманными числами. Строк он-девайс прогонов пока нет.

Заявка презентации — **DEMO_STAGE6** (6 RU, один микрофон, сцена 3–6 м). Первый замер: aggregate **WER 0.73 / DER 0.52**, ideal только @ 3 м. Не занижать. Gain комнату не вернул. Eval-measured Path A (cocktail FAIL, 1/20) и optional Path B (20/20 tracking; WORST_CASE WER main **0.9130**, DER **1.2985**, PR #4 @ `9908353d`) лежат в `docs/TEST_RESULTS.md` и в [PR #4](https://cursor.com/codebase/johann-schilling/neuro-stenographer/pull/4). Это не цифры DEMO_STAGE6. Каталога `artifacts/eval/` на `main` нет — не ссылаться на него как на файлы этой ветки.
