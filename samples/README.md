# Sample recordings

Our own RU clips for the three fixed harness slots. Large audio is gitignored,
so the wav files are not in this repo — the measured rows they produced are
(`artifacts/test-results/results.csv`). Judges use a hidden «котики» set; do
not treat anything here as the eval set.

| test_id | file (local) | reference (local) | status in `results.csv` |
|---|---|---|---|
| clip_01 | `samples/clip_01.wav` | `samples/ref/clip_01.json` | `measured-host-synthetic` (WER 0.0345, RTF 0.1372) |
| clip_02 | `samples/clip_02.wav` | `samples/ref/clip_02.json` | `measured-host-synthetic` (WER 0.0000, RTF 0.1219) |
| clip_03 | `samples/clip_03.wav` | `samples/ref/clip_03.json` | `measured-host-synthetic` (WER 0.0000, RTF 0.1291) |
| check_apk_clip_01..03 | same audio, app export | `eval/gold/clip_0X.gold.json` | **`pending`** — needs the installed APK on the demo phone |

`measured-host-synthetic` means: Common Voice 17 RU voice material, mixed and
scored on host Linux through the Python ingest that mirrors the Kotlin
pipeline. It is our own test result, not an on-device run and not the
DEMO_STAGE6 claim. Slots stay `pending` until a real run fills them — do not
type WER or RTF by hand (`eval/GATE.md`).

```bash
python3 scripts/eval_harness.py                    # clip slots
python3 -m eval.metrics.wer_rtf --hyp export.json \
  --gold eval/gold/clip_01.gold.json \
  --duration-ms <measured> --wall-ms <measured> \
  --clip-id clip_01 --on-device \
  --out artifacts/test-results/results.csv         # refuses if inputs are missing
```

## Demo-stress (20 talkers) — secondary

Separate suite; it does not replace the three clip slots.
`samples/demo-stress/` holds the recipe and manifests for a **Common Voice 17
RU (CC0)** cocktail mix — the earlier eSpeak NG synthetic mix is superseded and
kept only as a baseline under `artifacts/eval/demo-stress/espeak-baseline/`.
Host Linux only (no KVM/ADB required). See `samples/demo-stress/README.md` for
SNR, stagger and licenses, and `docs/TEST_RESULTS.md` for the measured rows.

```bash
python3 scripts/fetch_cv17_ru.py
python3 scripts/generate_demo_stress_mix.py
python3 scripts/eval_harness.py --suite demo-stress --run-host
```

One-room cocktail diarization of ≥20 talkers is a **measured fail** (Path A:
1/20 speakers, DER 0.95). It is regression evidence, never a demo claim.

## DEMO_STAGE6 — the primary fixture

6 RU talkers, one phone mic, 3–6 m, sequential turns with ~200 ms end
overlap, large-room IR, medium HVAC, Nothing-class phone-mic band-limit.
Frozen wav + gold at commit `a8b39d5a`; listen slices and per-seat excerpts
are committed under `artifacts/eval/demo-stress/stage6/listen/`.

```bash
python3 scripts/generate_demo_stage6.py
```
