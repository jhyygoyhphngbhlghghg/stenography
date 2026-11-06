# Adversarial Path B (worst-case demo room)

Johann / H1 hard requirement: **do not** treat clean Common Voice Path B as the demo-room edge. This suite is **hostile on purpose**. Expect FAIL numbers. Fixtures were **not** tuned to look good.

Path B still means: ≤20 PresentConnect dry streams, per-stream RMS VAD + Zipformer, oracle `s0N`↔`speaker_N`, merge by time, JSON `{speaker_id,start_ms,end_ms,text}` only. Main is only slightly louder than interferers on `s01` (~+3 dB EDGE_overlap, ~+1.5 dB WORST_CASE).

CLEAN baseline stays `mix_20talker_per_stream` (not rewritten here).

## Conditions

| test_id | What is stacked | Timeline |
|---|---|---|
| `EDGE_reverb` | SYNTHETIC echo-y room (taps + RT60≈0.82 s tail) | CLEAN (90 ms stagger, no bleed) |
| `EDGE_overlap` | True overlap (two concurrent clusters, not 90 ms stagger) + geometric mic bleed | OVERLAP |
| `EDGE_noise` | SYNTHETIC white + HVAC + chair scrapes + door slam + phone dual-tone | CLEAN |
| `EDGE_badmic` | 300–3400 Hz phone band, EQ bump, compressor, clip, μ-law crush, hiss | CLEAN |
| `WORST_CASE` | **All of the above** (overlap+bleed at ~+1.5 dB on s01) | OVERLAP |
| `smoke_worst.wav` | 4-talker cropped WORST_CASE (committed) | not headline |

Speech: Mozilla Common Voice 17.0 RU test, **CC0-1.0**. All IR / HVAC / chair / door / phone / white are **SYNTHETIC** (generated in `scripts/generate_adversarial_path_b.py`). No third-party noise libraries.

Large wavs are **gitignored**. Refs + `recipe.json` + `smoke_worst.wav` are committed.

```bash
python3 scripts/generate_adversarial_path_b.py
python3 scripts/eval_demo_stress.py --run-host --only EDGE_reverb --only EDGE_overlap --only EDGE_noise --only EDGE_badmic --only WORST_CASE
python3 scripts/verify-transcript-json.py samples/demo-stress/adversarial/ref/*.json
```
