# Scripts

- `download-models.sh` — fetch Zipformer-ru int8 (exact HF names), Silero VAD, pyannote+embedding ONNX (extracts tarball), Qwen2-0.5B-Instruct Q4_0 GGUF, optional Whisper tiny / Vosk. Writes to `models/` (gitignored). Platform PR #3 packs assets — this branch does not.
- `fetch-sherpa-aar.sh` — GitHub Releases Android AAR (JitPack `v2.0.1` unpublished).
- `verify-transcript-json.py` — exact brief schema (`transcript[]` + four fields).
- `eval_harness.py` — WER+RTF skeleton for 3 fixed clips (empty table until a real run).
- `ci-check.sh` — schema + eval skeleton + optional Gradle.
- `measure-farfield-gain.py` — sequential mix loud 3 m + quiet 5 m + quiet 6 m (not Eval wavs). Per-turn `FarFieldGain.stage`; whole-file AGC is comparison-only.
- PresentConnect mock server (later): local WS hub for the Android worker.

Do not commit downloaded weights.
