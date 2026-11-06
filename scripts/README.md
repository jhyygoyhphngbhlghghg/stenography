# Scripts

- `download-models.sh` — fetch Zipformer-ru int8 (`encoder.int8.onnx` / `decoder.onnx` / `joiner.int8.onnx` / `tokens.txt`), Silero VAD, diarization ONNX, optional Whisper tiny / Vosk. Writes to `models/` (gitignored). Set `DOWNLOAD_LOG` to a CSV path to record HTTP code, bytes, wall_ms, sha256.
- `inspect-sherpa-aar.sh` — resolve `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1` from JitPack and list packaged JNI `.so` files (no Android SDK required).
- `test_present_connect_capacity.py` — JVM-free PresentConnect cap test: streams 1..20 accepted, stream 21 `OVER_CAPACITY`. `--record` writes `artifacts/test-results/present-connect-capacity.json`.
- `measure-stream-pcm-gate.py` — RMS/clip/DC of **synthetic** Path B gate fixtures (silence, tiny noise, clipped square, tone). `--record` writes `artifacts/test-results/stream-pcm-gate.json`. Does **not** touch Eval wavs.
- `measure-farfield-gain.py` — synthetic 3–6 m isolated scenes **and** a sequential mix (loud ~3 m + quiet 5–6 m in one file): per-turn RMS before/after, whether quiet turns get +gain while loud turns stay ≤0 dB, hard-skip rate. `--record` writes `artifacts/test-results/farfield-gain.json`. Not Eval DEMO_STAGE6 wavs. **Gain frozen** — do not retune targets.
- `measure-phone-distance-eq.py` — synthetic phone-mic + distance scenes at 3/4/5/6 m plus a sequential mix: Goertzel speech (300–3400 Hz) and presence (2000–3400 Hz) before/after EQ, near-seat identity, hard-skip rate. `--record` writes `artifacts/test-results/phone-distance-eq.json`. Not Eval wavs.
- `device-sideload-probe.sh` — johann's machine: `adb install -r` stenographer debug APK, launch app, capture `SherpaJni` / `UnsatisfiedLinkError` logcat. Not runnable on the cloud VM (no device).
- `host-jni-smoke.sh` — Linux x86_64 inspect of `libsherpa-onnx-jni.so` inside the JitPack v2.0.1 AAR (`file` / `readelf` / `nm` / `dlopen`). Does **not** replace the on-device probe. After Origin PR #2, override with `SHERPA_AAR_URL` to GitHub Releases v1.13.5.

Do not commit downloaded weights.
