# Scripts

- `download-models.sh` — fetch Zipformer-ru int8, Silero VAD, diarization ONNX, optional Whisper tiny / Vosk. Writes to `models/` (gitignored).
- `eval-wer.sh` — one-clip WER/RTF row (`python3 -m eval.metrics.wer_rtf`). Stdlib only. Does not invent WER or RTF. See `eval/README.md`.
- PresentConnect mock server (later): local WS hub for the Android worker.

Do not commit downloaded weights.
