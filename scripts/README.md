# Scripts

- `download-models.sh` — fetch Zipformer-ru int8 (`encoder.int8.onnx` / `decoder.onnx` / `joiner.int8.onnx` / `tokens.txt`), Silero VAD, diarization ONNX, optional Whisper tiny / Vosk. Writes to `models/` (gitignored). Set `DOWNLOAD_LOG` to a CSV path to record HTTP code, bytes, wall_ms, sha256.
- `inspect-sherpa-aar.sh` — resolve `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1` from JitPack and list packaged JNI `.so` files (no Android SDK required).
- `test_present_connect_capacity.py` — JVM-free PresentConnect cap test: streams 1..20 accepted, stream 21 `OVER_CAPACITY`. `--record` writes `artifacts/test-results/present-connect-capacity.json`.

Do not commit downloaded weights.
