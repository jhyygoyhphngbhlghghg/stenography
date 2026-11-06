# Limits

Canonical copy: [LIMITATIONS.md](./LIMITATIONS.md) (hacathon_template name).

Multi-talker demo is **Path B** (≤20 separate PresentConnect streams). The 20/20, DER ≈ 0.24, main WER 0.087 figures (PR #4) are **clean per-stream** only (dry/separate tracks). **Path A** one-room cocktail mix is a measured hard fail (1/20, DER 0.95) — do not claim it.

Worst-case edge suite (echo, overlap, white noise / room noise, bad mics): **unmeasured until edge suite**. No scores here. Expect **FAIL / hard degradation**. **Do not claim in slides.** Banned: "should be fine", "robust", "handles noisy rooms", "graceful under echo". Full write-up: LIMITATIONS and `TEST_RESULTS.md`.
