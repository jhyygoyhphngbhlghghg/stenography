# A-12 gate

**Status: harness exists and runs; the on-device rows are still `pending`.** `artifacts/test-results/results.csv` carries three `measured-host-synthetic` rows (Common Voice 17 RU mix through the host ingest, commit `ef70323`) and three `pending` `check_apk_*` rows, which are the ones the judges' score comes from. Gold files in git are empty transcript arrays. They are not the hidden judge «котики» set.

Official weights, for later scoring notes only. This file does not compute them and does not claim points: ASR WER 40 · diarization 25 · timestamps 10 · speed 10 · UX 10 · defense 5.

## Must pass later

- [ ] All three slots (`clip_01`, `clip_02`, `clip_03`) are runnable: local audio plus a reference transcript the operator is allowed to use. Judge clips and judge gold are not committed in this repo.
- [ ] WER and RTF for each clip are filled from a real device or host run. Copy the CSV row the metrics CLI prints. Do not type a guessed number.
- [ ] `duration_ms` and `wall_ms` in that row are the measured inputs. RTF is `wall_ms / duration_ms` from those inputs.
- [ ] When the diarization pipeline exists: the hypothesis has at least two distinct `speaker_id` values on each multi-speaker clip.

## What exists now

- Layout, manifest, and empty gold stubs (`eval/README.md`).
- Stdlib CLI `python3 -m eval.metrics.wer_rtf` (same flags via `./scripts/eval-wer.sh`).
- Unit tests of the WER function on hand-checked toy strings (`python3 -m unittest eval.metrics.test_wer`).

A green dry-run on `eval/fixtures/` only shows that the function runs. It does not satisfy this gate.
