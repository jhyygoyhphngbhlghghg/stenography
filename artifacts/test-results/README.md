# Артефакты тестирования

Таблица: `results.csv`. Не заполнять WER/RTF выдуманными числами.

Колонки:

`test_id,clip_id,timestamp_utc,commit,file_name,duration_ms,wall_ms,rtf,wer,speakers_hyp,speakers_ref,notes`

Пустая ячейка или слово `pending` в `wer` / `rtf` значит «ещё не измерено». Строка с `#` — комментарий, не данные. Строк с замерами в файле нет.

Как посчитать одну строку из реального hyp/gold и измеренных `duration_ms` / `wall_ms`: `eval/README.md`. Пока замера нет — файл не пополнять.
