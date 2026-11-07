# APK drop

Copy release/debug APKs here when shipping to judges. Binaries are gitignored.

Expected names (when built):

- `app-stenographer-debug.apk` — **JUDGE / Hex.Team check**: Zipformer-ru int8 + Silero VAD + diar ONNX in `assets/models`. Cold install, first launch unpacks to filesDir, zero network.
- `app-stenographer-slim.apk` — karma, no weights (`assembleStenographerSlim`).
- `app-presentConnect-debug.apk` — Path B flavor (also embeds models).
