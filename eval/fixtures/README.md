# Toy fixtures

Not audio, not gold, not a measured clip.

`toy_gold.json` and `toy_hyp.json` are schema-shaped strings for the WER unit test. `start_ms` and `end_ms` are `0` so the files parse. They are not timings.

After normalization the reference is `кот сидит на окне да сидит` (6 words) and the hypothesis replaces `сидит` with `лежит` (1 substitution). The harness prints WER `0.166667` for that pair. That number checks the function. It is not a speech score and must not be copied into `artifacts/test-results/results.csv`.
