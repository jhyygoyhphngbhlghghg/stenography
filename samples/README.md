# Sample recordings

Optional 2–3 speaker RU clips for our own tests. Large audio is gitignored. Judges use a hidden «котики» set; do not treat samples here as the eval set.

Fixed harness slots (empty metrics until a real run):

| test_id | file | reference |
|---|---|---|
| clip_01 | `samples/clip_01.wav` | `samples/ref/clip_01.json` |
| clip_02 | `samples/clip_02.wav` | `samples/ref/clip_02.json` |
| clip_03 | `samples/clip_03.wav` | `samples/ref/clip_03.json` |

```bash
python3 scripts/eval_harness.py
```

Do not invent WER/RTF. The CSV stays pending while wav/ref/hyp are missing.

## Demo-stress (20 talkers)

Separate suite, does not replace the three clip slots: `samples/demo-stress/` (SYNTHETIC eSpeak mix, host Linux only). See that README for SNR / stagger / licenses.

```bash
python3 scripts/generate_demo_stress_mix.py
python3 scripts/eval_harness.py --suite demo-stress --run-host
```
