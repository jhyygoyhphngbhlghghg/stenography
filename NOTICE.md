# NOTICE

- App code: MIK Coders, Nuclear IT Hack 2026.
- Repo layout: [hacathon_template](https://github.com/grigorevmp/hacathon_template).
- sherpa-onnx: Apache-2.0 (Xiaomi / k2-fsa), Android AAR from GitHub Releases. Model licenses vary (Zipformer-ru lineage, Silero, pyannote segmentation terms — gated HF repo, 3D-Speaker / NeMo embeddings).
- Whisper weights: OpenAI Whisper terms as redistributed by sherpa packs.
- Vosk models: Apache-2.0 (emergency path).
- Demo-stress **headline** audio is **Mozilla Common Voice 17.0 Russian** (test split, **CC0-1.0**), fetched via `scripts/fetch_cv17_ru.py` from the `fsicoli/common_voice_17_0` packaging of https://commonvoice.mozilla.org/. Gold transcripts are the corpus `sentence` field. Large clips stay gitignored.
- Historical eSpeak NG SYNTHETIC baseline (GPLv3 renderer, not vendored) is superseded; measured files live under `artifacts/eval/demo-stress/espeak-baseline/`.
- Do not add Picovoice or other AccessKey SDKs.
