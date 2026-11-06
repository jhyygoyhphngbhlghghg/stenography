# Limits

Canonical copy: [LIMITATIONS.md](./LIMITATIONS.md) (hacathon_template name).

Multi-talker demo is **Path B** (≤20 separate PresentConnect streams). **Path A** one-room cocktail mix is a measured hard fail (1/20, DER 0.95) — do not claim it.

**Claim lock:** under edge, Path B stream tracking is still **20/20**. That is the only multi-talker claim that survives. **WORST_CASE WER/DER are FAIL** (Eval PR #4 @ `9908353d`): WER main **0.9130** (slides ≈0.91), DER 250ms **1.2985** (slides ≈1.30). Do **not** claim clean WER/DER on WORST_CASE or EDGE_*. **Killers:** overlap/bleed, noise false alarms, bad mic. Reverb alone does **not** collapse Path B. Banned: "should be fine", "robust", "handles noisy rooms", "graceful under echo". Full table: LIMITATIONS and `TEST_RESULTS.md`.
