# Demo-stress fixture: 20 talkers, cocktail party

Host-Linux eval for Nuclear IT Hack «Нейро-стенографист». **Not** Gradle assemble, **not** PresentConnect unit tests, **not** the overnight `clip_01..03` skeleton.

Cloud agents have **no KVM / ADB**. This path is **sherpa-onnx Python on Linux** (Zipformer-ru + `OfflineSpeakerDiarization`).

## What was mixed

| Item | Value |
|---|---|
| Talkers | **20** (1 main + 19 babble) |
| Main | `speaker_1` / PresentConnect `s01` |
| SNR | main ≈ **+3 dB** vs the **sum** of the other 19, measured on frames where main is active |
| Overlap | 19 babble voices looped for the whole mix |
| Stagger | stream `s{N}` starts at `(N-1) * 90 ms` (smoke: 40 ms) — ≤20 channels landing late |
| Main turns | three gaps (start ~0.4 s, 5.4 s, 10.2 s) so the same id must survive turns |
| Rate | 16 kHz mono PCM16 |
| Label | **SYNTHETIC** |

Renderer: **eSpeak NG** Russian (`espeak-ng -v ru`) when installed. Fallback: formant buzz in `scripts/generate_demo_stress_mix.py` (also synthetic; Zipformer will not decode it as speech). A generate on this branch measured **SNR 3.00 dB** (`mix_build.json`). Zipformer-ru does not recognize eSpeak (dry-main WER 1.0 in the host run); the mix is a **diarization geometry** stress, not an in-domain ASR corpus.

**Not used:** Common Voice, GOLOS, or any recorded corpus. No extra license beyond eSpeak NG as a local renderer (GPLv3; we do not vendor the binary). Measured SNR is written to `mix_build.json` after each generate.

Large wavs (`mix_20talker.wav`, `dry/*.wav`) are **gitignored**. `smoke.wav` is a ~1 s committed sample so CI can see a file without regenerating the full mix.

## How to run (Linux VM)

```bash
# 1) fixture (needs python3 + numpy; espeak-ng recommended; ffmpeg unused)
sudo apt-get install -y espeak-ng espeak-ng-data   # once
python3 scripts/generate_demo_stress_mix.py

# 2) models (same names as docs/MODELS.md)
./scripts/download-models.sh

# 3) optional: pip install sherpa-onnx   # Python wheel, not the Android AAR

# 4) host inference + DER/JER/WER/RTF (no Android)
python3 scripts/eval_harness.py --suite demo-stress --run-host

# 5) schema
python3 scripts/verify-transcript-json.py artifacts/eval/demo-stress/hyp.json
```

Score an existing hyp without inference:

```bash
python3 scripts/eval_harness.py --suite demo-stress
```

Overnight clips are unchanged:

```bash
python3 scripts/eval_harness.py          # clip_01..03 pending skeleton
```

## Metrics

Implemented in `scripts/eval_metrics.py` (no `pyannote.metrics`):

- **DER** — NIST-style (miss + FA + speaker error) / reference speaker-time; 250 ms collar **and** uncollared
- **JER** — mean Jaccard error after Hungarian mapping (unmapped hyp clusters count as 1)
- **Speaker purity** — duration-weighted; fallback if you only want a clustering number
- **WER** — on the **main speaker only**, after mapping hyp ids → `speaker_1`
- **RTF** — `wall_ms / duration_ms` of the mix

Rows stay `pending` / `blocked` with the real reason when models, wavs, or `sherpa-onnx` are missing. **No invented WER/DER/RTF.**

## Layout

```
samples/demo-stress/
  README.md              this file
  manifest.json
  mix_build.json         engine + measured SNR (after generate)
  channels.json          PresentConnect stream offsets
  smoke.wav              committed tiny mix
  mix_20talker.wav       gitignored full mix
  dry/spk_XX.wav         gitignored per-talker tracks
  ref/smoke.json
  ref/mix_20talker.json
artifacts/eval/demo-stress/
  hyp.json               copy of mix_20talker.json after a run
  mix_20talker.json
  metrics.csv / metrics.json
  run.log
```
