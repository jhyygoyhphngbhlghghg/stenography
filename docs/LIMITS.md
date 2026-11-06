# Limits

Canonical copy: [LIMITATIONS.md](./LIMITATIONS.md) (hacathon_template name).

**Presentation claim = DEMO_STAGE6:** 6 on-stage RU speakers, one phone mic, scene 3–6 m, sequential turns with light end-of-replica overlap only, large room, medium babble/HVAC, phone-mic band-limit / compression / distance attenuation + reverb. First measure ([PR #4](https://cursor.com/codebase/johann-schilling/neuro-stenographer/pull/4)): aggregate **WER 0.73 / DER 0.52**. **Ideal recognition only at 3 m.** Gain agg WER **0.73 / 0.70 / 0.73** did not recover the room. Do not cite a lower aggregate. Not a studio. Not Path B ≤20. Local LLM is required for hand-in; assets-bundled weights are primary.

**Path A** one-room cocktail ≥20 is a measured hard fail (1/20, DER 0.95) — do not claim it.

**Optional PresentConnect (Path B), not the presentation:** ungated stream tracking **20/20** (frozen @ `9908353d`). **WORST_CASE WER/DER are FAIL:** WER main **0.9130**, DER 250ms **1.2985**. Hard `BAND_LIMITED` on bound seats `s01`..`s20` is **REJECTED** (Eval PR #4 @ `bed868b3`, note `ffe53ba4`): soft-flag only, still ASR. Do not claim gated_v2 hyps or clean WER/DER on WORST_CASE. Killers: overlap/bleed, noise false alarms, bad mic. Reverb alone does not collapse this optional mode. Banned: "should be fine", "robust", "handles noisy rooms", "graceful under echo". Full table: LIMITATIONS and `TEST_RESULTS.md`.
