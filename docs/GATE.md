# Demo fixtures

**Primary presentation target:** `DEMO_STAGE6` — 6 RU talkers, **one mic** (Path A room mix), turn-taking with slight end overlap. Recipe: `samples/demo-stress/stage6/`. Soft-bound 20-stream / `*_gated_v3` is **secondary regression only**, not the demo.

# Path B regression gate (FROZEN, secondary)

H1 accepted the WORST_CASE table at `9908353`. These six Path B rows are the **frozen** 20-stream regression gate. They are **not** the DEMO_STAGE6 presentation fixture.

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
- `*_gated_v2` — StreamPcmGate v2 Implementer `e3ad908` (sha256 == Platform `b12a2bb`; hard `BAND_LIMITED` even on bound seats). History @ `bed868b`.
- `*_gated_v3` — StreamPcmGate v3 Implementer `a5b7aed` (sha256 == Platform `686ac4a`). Bound `s01`–`s20`: `BAND_LIMITED` is a **soft** flag + still ASR. CLIPPED/BAD_MIC stay hard. Headline ingest. Does not replace frozen baseline or prior gated history.

`*_gated_v3` @ `a5b7aed` / `686ac4a`: bound `BAND_LIMITED` is a soft flag. **Secondary regression.** DEMO_STAGE6 is the primary demo fixture. 20-stream EDGE_* stay green; do not retune those frozen wavs.

DEMO_STAGE6 fixture (wav/gold/recipe) is **frozen** @ `a8b39d5a`. Harden-claim remeasures use **new** test_ids on the same wavs:

- `DEMO_STAGE6_ffgain` @ `e0e64b85` — whole-file AGC (`b66581c`); mix already 0.119 FS so −3.46 dB
- `DEMO_STAGE6_perturn` @ `db3afe4` — per-turn FarFieldGain (`ae86903`); near 0 dB, quiet islands → 0.08 FS, no crush
- `DEMO_STAGE6_phone_eq` — Platform PhoneDistanceEq @ `af71d91` (`040274d` 250–4500 Hz; near seat identity; gain frozen). Hex keep-punct vs `0824df64`. Do not re-enable whole-file crush.
- `DEMO_STAGE6_impl_second_pass` — Impl VadDiarReconcile + IntonationPunct @ `552b142` (PR #7). Over-split **12/6** @ `9b29b9c1`. Hex vs `0824df64` / phone_eq @ `5ffae114`.
- `DEMO_STAGE6_impl_hardcap6` — Impl HARD CAP ≤6 + heuristic ADD commas @ `3c3f9ce` (PR #7). Merge orphans into K=6; never speaker_7..12; near 3 m protected. GGUF-absent. Hex vs `9b29b9c1` / Hex baseline.

Do not replace prior rows. Do not retune gold.

## Hex.Team WER lock (Mikhail Grigorev / Q&A 2026-11-06)

Applies to all eval WER (`scripts/eval_metrics.py` tokenize):

1. Punctuation **is** in WER. Do **not** strip `,` `.` `?` `!` before scoring (they are their own tokens).
2. Comma placement in gold = **spoken intonation** (where the talker paused), not a prettier orthographic edit. Do not retune wording to fake low WER.
3. **е vs ё does not count** — fold `ё→е` on hyp and ref.

STAGE6 wav/recipe frozen @ `a8b39d5a`. Gold wording unchanged after intonation audit (dry-CV pauses match existing commas on s1/s3/s6; s2/s4/s5 stay period-only).
