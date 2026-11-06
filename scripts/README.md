# Scripts

- `download-models.sh` — fetch Zipformer-ru int8 (exact HF names), Silero VAD, pyannote+embedding ONNX (extracts tarball), optional Whisper tiny / Vosk. Writes to `models/` (gitignored).
- `fetch-sherpa-aar.sh` — GitHub Releases Android AAR (JitPack `v2.0.1` unpublished).
- `verify-transcript-json.py` — exact brief schema (`transcript[]` + four fields).
- `eval_harness.py` — WER+RTF skeleton for 3 fixed clips (empty table until a real run).
- `ci-check.sh` — schema + eval skeleton + optional Gradle.
- `measure-stream-pcm-gate.py` — RMS/clip/DC of **synthetic** Path B gate fixtures. `--record` writes `artifacts/test-results/stream-pcm-gate.json`. Does **not** touch Eval wavs.
- PresentConnect mock server (later): local WS hub for the Android worker.

Do not commit downloaded weights.
