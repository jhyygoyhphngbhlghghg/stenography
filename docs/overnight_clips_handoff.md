# Morning handoff — overnight clip_01..03

Clean turn-taking clips for the overnight harness. **Not** the Path B EDGE / WORST_CASE gate (`docs/GATE.md`). Hold merge; overnight **#2** still first. Path B gate already **FROZEN** @ `a863b2a6`.

Do **not** re-run ASR unless a wav/ref/hyp row is missing. Do **not** invent WER/RTF.

## Corpus

Mozilla **Common Voice 17.0 Russian, test split, CC0-1.0** (`samples/clips_build.json`: label REAL, gold = corpus `sentence`). Same talkers as demo-stress. **No overlap** — sequential turns with gaps (clip_01: 2 speakers / 3 utt; clip_02: 2 speakers / 2 utt; clip_03: 3 speakers / 3 utt). Speaker-blind WER on concatenated text + RTF = wall / duration.

Stack (already measured): host Linux `sherpa-onnx==2.0.1` Zipformer-ru + OfflineSpeakerDiarization, `num_clusters` 2/2/3. No KVM/ADB.

## Paths

| test_id | wav (gitignored, regenerate locally) | gold | hyp | wall |
|---|---|---|---|---|
| clip_01 | `samples/clip_01.wav` | `samples/ref/clip_01.json` | `artifacts/eval/hyp/clip_01.json` | `artifacts/eval/hyp/clip_01.wall_ms` |
| clip_02 | `samples/clip_02.wav` | `samples/ref/clip_02.json` | `artifacts/eval/hyp/clip_02.json` | `artifacts/eval/hyp/clip_02.wall_ms` |
| clip_03 | `samples/clip_03.wav` | `samples/ref/clip_03.json` | `artifacts/eval/hyp/clip_03.json` | `artifacts/eval/hyp/clip_03.wall_ms` |

Manifest: `samples/manifest.json`. Recipe: `samples/clips_build.json`. CSV: `artifacts/test-results/results.csv`. Rebuild wavs (does not re-ASR): `python3 scripts/generate_demo_stress_mix.py`. Score without inference: `python3 scripts/eval_harness.py`.

## Measured (quoted from committed `artifacts/test-results/results.csv`)

Commit on those rows: `ef70323`. Status: **measured**.

| test_id | WER | RTF | duration_ms | wall_ms |
|---|---|---|---|---|
| clip_01 | 0.0345 | 0.1372 | 22908 | 3142 |
| clip_02 | 0.0000 | 0.1219 | 18336 | 2236 |
| clip_03 | 0.0000 | 0.1291 | 23904 | 3086 |

Same figures in `docs/TEST_RESULTS.md`. JSON hyps validate as `transcript[].{speaker_id,start_ms,end_ms,text}`.
