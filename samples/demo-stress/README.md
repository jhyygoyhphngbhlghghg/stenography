# Demo-stress fixture: 20 talkers, cocktail party (REAL speech)

Host-Linux eval for Nuclear IT Hack «Нейро-стенографист». **Not** Gradle assemble, **Not** PresentConnect unit tests.

Cloud agents have **no KVM / ADB**. This path is **sherpa-onnx Python on Linux** (Zipformer-ru + `OfflineSpeakerDiarization`).

## Corpus (REAL)

| Item | Value |
|---|---|
| Source | Mozilla **Common Voice 17.0** Russian, **test** split |
| License | **CC0-1.0** |
| Packaging | Hugging Face `fsicoli/common_voice_17_0` (unofficial mirror of commonvoice.mozilla.org) |
| Talkers | **20 distinct `client_id`s** (test split has 1923 speakers; we sample 20 with seed 20261106) |
| Gold text | corpus `sentence` field (not eSpeak, not ASR) |
| Label | **REAL** |

The previous **eSpeak NG SYNTHETIC** mix is superseded (Zipformer WER=1.0 even on dry main — out of domain). Historical measured files: `artifacts/eval/demo-stress/espeak-baseline/`. `--engine espeak` can still rebuild that mix; it is not the headline eval.

## Mix recipe

| Item | Value |
|---|---|
| Main | `speaker_1` / PresentConnect `s01` (3 corpus clips, 400 ms gaps) |
| SNR | main ≈ **+3 dB** vs the **sum** of the other 19, measured on main-active frames |
| Overlap | 19 babble voices looped for the whole mix |
| Stagger | stream `s{N}` starts at `(N-1) * 90 ms` (smoke: 40 ms) |
| Rate | 16 kHz mono PCM16 |

Large wavs (`mix_20talker.wav`, `dry/*.wav`, `corpus/`) are **gitignored**. `smoke.wav` is a ~1 s committed sample. Fetch + generate:

```bash
python3 scripts/fetch_cv17_ru.py          # CC0 clips; needs ffmpeg + network
python3 scripts/generate_demo_stress_mix.py
./scripts/download-models.sh
pip install sherpa-onnx                   # Linux wheel, not the Android AAR
python3 scripts/eval_harness.py --suite demo-stress --run-host
python3 scripts/diar_param_sweep.py       # Path A clustering grid (diar only)
python3 scripts/eval_demo_stress.py --run-host --only mix_20talker_per_stream
python3 scripts/test_path_b_regression.py
python3 scripts/verify-transcript-json.py artifacts/eval/demo-stress/mix_20talker_per_stream.json
```

Overnight turn-taking clips from the same talkers (no overlap):

```bash
python3 scripts/eval_harness.py --suite clips --run-host --write-csv
```

## Path A vs Path B

- **A (one-room mix):** `mix_20talker` — diarize the cocktail wav (`num_clusters=20` default). Sweep: `scripts/diar_param_sweep.py`.
- **B (PresentConnect per-stream, tech demo):** `mix_20talker_per_stream` — RMS VAD + Zipformer on each **unlooped** `dry/spk_XX.wav`, oracle `s0N`↔`speaker_N`, merge by time. Mix still loops babble (Path A). Path-B-only ref: `ref/mix_20talker_per_stream.json`. JSON: `{speaker_id,start_ms,end_ms,text}` only.
- Path B regression (no inference): `python3 scripts/test_path_b_regression.py`

## Adversarial Path B (Johann hard requirement)

Worst-case demo room, **not** clean CV. Expect FAIL. Recipe: `samples/demo-stress/adversarial/README.md`.

```bash
python3 scripts/generate_adversarial_path_b.py
python3 scripts/eval_demo_stress.py --run-host --only EDGE_reverb --only EDGE_overlap --only EDGE_noise --only EDGE_badmic --only WORST_CASE
```

CLEAN `mix_20talker_per_stream` is the optimistic baseline. `WORST_CASE` stacks echo + true overlap/bleed + room noises + bad mic.

## Metrics

`scripts/eval_metrics.py` (no `pyannote.metrics`): DER (250 ms collar + uncollared), JER, speaker purity, WER on mapped **main** speaker, RTF. **No invented numbers.**
