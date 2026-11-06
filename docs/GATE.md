# Path B regression gate (FROZEN)

H1 accepted the WORST_CASE table at `9908353`. These six Path B rows are the **frozen** demo-stress gate.

| Frozen `test_id` | Role |
|---|---|
| `mix_20talker_per_stream` | CLEAN Path B baseline |
| `EDGE_reverb` | isolated-mic echo ablation |
| `EDGE_overlap` | true overlap + bleed ablation |
| `EDGE_noise` | room-noise ablation |
| `EDGE_badmic` | phone-mic ablation |
| `WORST_CASE` | all degradations stacked |

**Lock claim:** 20/20 stream tracking holds under edge; do NOT claim clean WER/DER on WORST_CASE.

## Rules

- Fixtures (wav recipe, refs, DSP knobs, VAD params) change **only with Lead sign-off**.
- Do **not** retune fixtures, VAD, or refs to make numbers pretty.
- Do **not** invent metrics. Re-score committed hyps anytime; **re-run host inference only** when Implementer/Platform claim a harden fix against this gate.
- Keep all six rows in `artifacts/eval/demo-stress/metrics.csv`, `metrics.json`, and `docs/TEST_RESULTS.md`.
- Regression smoke: `python3 scripts/test_path_b_regression.py` (schema + 20/20 speakers on every frozen row; does **not** require low WER/DER).

Accepted measured snapshot: `docs/TEST_RESULTS.md` (Johann hard-requirement table). Hold merge; overnight #2 still first.

Harden-claim remeasures use **new** test_ids on the **same** frozen wavs. They do not replace the six frozen rows.

- `*_gated` @ `3b31e24` — StreamPcmGate whole-stream wipe (9 EDGE_noise LOW_ENERGY drops)
- `*_gated_b142a4d` — no padded-file hush wipe; same thresholds; crop inspect before ASR
- `*_gated_v2` — StreamPcmGate v2 @ `b12a2bb` (BAND_LIMITED + boundSeat SOFT_LOW). Headline ingest; supersedes v1 gate for new runs. Does not replace frozen baseline or prior gated history.
