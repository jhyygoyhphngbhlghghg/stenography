# Scripts

- `download-models.sh` — fetch Zipformer-ru int8 (exact HF names), Silero VAD, pyannote+embedding ONNX (extracts tarball), optional Whisper tiny / Vosk. Writes to `models/` (gitignored).
- `fetch-sherpa-aar.sh` — GitHub Releases Android AAR (JitPack `v2.0.1` unpublished).
- `verify-transcript-json.py` — exact brief schema (`transcript[]` + four fields).
- `eval_harness.py` — WER+RTF skeleton for 3 fixed clips (empty table until a real run). `--suite demo-stress` scores the 20-talker mix; `--run-host` runs Linux sherpa-onnx (no KVM/ADB).
- `generate_demo_stress_mix.py` — SYNTHETIC 20-talker cocktail mix (espeak-ng ru, +3 dB main, staggered offsets).
- `host_asr_diar.py` — Zipformer-ru + OfflineSpeakerDiarization on Linux Python.
- `eval_demo_stress.py` — DER/JER/purity + main-speaker WER + RTF; pending/blocked if inference cannot run.
- `eval_metrics.py` — metric implementations (not pyannote.metrics).
- `test_eval_metrics.py` — unit checks of the metric code (not clip scores).
- `ci-check.sh` — schema + eval skeleton + optional Gradle.
- PresentConnect mock server (later): local WS hub for the Android worker.

Do not commit downloaded weights.
