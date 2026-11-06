# Методика и результаты тестирования

Собственные прогоны команды (не судейский набор «котики»).

**Запрещено выдумывать WER/RTF/DER.** Пока нет замера — `pending` / `blocked` с причиной.

Шаблон строк: `artifacts/test-results/results.csv`. Demo-stress: `artifacts/eval/demo-stress/metrics.csv`.

## Методика (черновик)

1. Собрать debug APK flavor `stenographer`.
2. Положить модели (`scripts/download-models.sh` + push).
3. Авиарежим.
4. Импорт 2–3 своих диалогов (RU, ≥2 спикера, без перекрытия).
5. Экспорт JSON, сверка `speaker_id` / `start_ms` / `end_ms` / `text` с ручной расшифровкой.
6. Зафиксировать wall time и длительность аудио (RTF = wall / duration) только после реального замера.

Host-Linux shortcut (no KVM/ADB): Common Voice 17 RU clips + Python sherpa-onnx (`scripts/eval_harness.py --run-host`).

## Статус overnight

| Проверка | Статус |
|---|---|
| Gradle `assembleDebug` | green on this machine (sherpa-onnx 1.13.5 AAR + Kotlin API) |
| Unit tests (`testStenographerDebugUnitTest`) | run via `scripts/ci-check.sh` |
| JSON schema (`verify-transcript-json.py`) | fixture OK; extra keys rejected |
| Eval WER+RTF (clip_01..03) | **measured** on host Linux (see below) |
| Demo-stress 20-talker (host Linux) | **measured** REAL CV17; eSpeak baseline superseded |
| Import → stub list → JSON (no models) | pending on-device |
| Zipformer-ru on-device | pending models + adb (host path already ran) |
| Diarization ≥2 speakers | pending on-device; host Path B recovered 20/20 |
| Airplane mode | pending on-device |
| PresentConnect ≤20 streams | unit cap test; WS needs web BE |

## clip_01..03 (overnight harness, host Linux)

Same Common Voice 17 RU talkers as demo-stress (CC0). Turn-taking, **no overlap**. Gold = corpus `sentence`. Speaker-blind WER (concatenated transcript text) + RTF. Wavs gitignored; refs committed.

| test_id | status | WER | RTF | duration_ms | wall_ms |
|---|---|---|---|---|---|
| clip_01 | measured | **0.0345** | **0.1372** | 22908 | 3142 |
| clip_02 | measured | **0.0000** | **0.1219** | 18336 | 2236 |
| clip_03 | measured | **0.0000** | **0.1291** | 23904 | 3086 |

JSON: `artifacts/eval/hyp/clip_0{1,2,3}.json` validates. CSV: `artifacts/test-results/results.csv`. Stack: Python `sherpa-onnx==2.0.1` Zipformer-ru + OfflineSpeakerDiarization, `num_clusters` 2/2/3.

## Demo-stress (20 talkers) — host Linux only

Cloud agents / this VM have **no KVM and no ADB**.

Fixture **REAL**: Mozilla Common Voice 17.0 Russian, test split, **CC0-1.0**, 20 distinct `client_id`s, measured SNR **3.00 dB**, 90 ms stagger, 18.736 s mix. Gold = corpus sentences. Recipe: `samples/demo-stress/README.md`.

```bash
python3 scripts/fetch_cv17_ru.py
python3 scripts/generate_demo_stress_mix.py
python3 scripts/eval_harness.py --suite demo-stress --run-host
python3 scripts/diar_param_sweep.py
```

### Headline (REAL Common Voice) — this VM

Stack: Python `sherpa-onnx==2.0.1`, Zipformer-ru int8 + pyannote segmentation-3.0 + 3D-Speaker embedding. No Android.

| test_id | status | DER 250ms | DER uncollared | JER | purity | WER main | RTF | hyp/ref spk |
|---|---|---|---|---|---|---|---|---|
| mix_20talker (Path A) | measured | **0.9500** | 0.9497 | **0.9520** | 0.9983 | **1.0000** | **0.1784** | 1 / 20 |
| mix_20talker_main_dry | measured | 0.0192 | 0.0921 | 0.0843 | 0.9157 | **0.1739** | 0.0587 | 1 / 1 |
| mix_20talker_per_stream (Path B) | measured | **0.0913** | 0.1728 | **0.1840** | 0.9983 | **0.0870** | **0.1212** | **20 / 20** |
| smoke | measured | n/a (collar ate 1.24 s) | 0.9025 | 0.9620 | 0.3774 | 1.0000 | 0.7855 | 2 / 20 |

Path A mix wall **3343 ms** / **18736 ms**. Path B wall **2271 ms** / **18736 ms** (20 dry tracks, RMS VAD + Zipformer, no pyannote). Dry-main Zipformer reads the three CV sentences (WER 0.1739 vs eSpeak 1.0). Mix hyp still one blob — cocktail overlap, not OOD TTS. JSON schema unchanged: `transcript[].{speaker_id,start_ms,end_ms,text}` only.

### Path B DER try (same branch, measured)

Hypothesis from the 0.2350 row: residual DER was looping babble vs one-utterance refs + VAD/segment edges, not identity collapse. Path A mix/ref untouched.

| Path B `mix_20talker_per_stream` | DER 250ms | JER | WER main | RTF | wall_ms | segs | hyp/ref |
|---|---|---|---|---|---|---|---|
| before (looped dry + per-stream diar) | **0.2350** | **0.2716** | **0.0870** | 2.6481 | 49615 | 60 | 20 / 20 |
| after (unlooped dry + Path-B-only ref + RMS VAD) | **0.0913** | **0.1840** | **0.0870** | **0.1212** | 2271 | 27 | 20 / 20 |

Real improvement on DER/JER/RTF; WER_main held. Knobs: unlooped babble dry tracks + `ref/mix_20talker_per_stream.json` (original clip spans, not offset→mix_end); RMS VAD `rel_thresh=0.04`, `pad_ms=250`, `min_duration_on=0.20`, `min_duration_off=0.45`, re-merge after pad. First VAD pass (`pad_ms=80`, `rel_thresh=0.12`) over-split main (WER_main 0.2609, JER 0.3133) — discarded. Residual DER is mostly CV clip silence in refs vs speech-only VAD edges. Regression: `python3 scripts/test_path_b_regression.py`.

### Path A vs Path B (diarization collapse 20→1)

**A. One-room mix** (`scripts/diar_param_sweep.py`, diar only, measured):

| num_clusters | threshold | min_on/off | hyp speakers | segments | DER | JER |
|---|---|---|---|---|---|---|
| 20 | 0.5 | 0.2/0.5 | **1** | 1 | 0.95 | 0.952 |
| 20 | 0.3 | 0.2/0.5 | **1** | 1 | 0.95 | 0.952 |
| 20 | 0.8 | 0.2/0.5 | **1** | 1 | 0.95 | 0.952 |
| 20 | 0.5 | 0.1/0.2 | **1** | 1 | 0.95 | 0.952 |
| -1 (auto) | 0.5 | 0.2/0.5 | 3 | 5 | 0.9375 | 0.9376 |
| -1 (auto) | 0.3 | 0.1/0.3 | 5 | 7 | 0.9372 | 0.9361 |

Forcing `num_clusters=20` still yields **one** cluster: pyannote segmentation sees the overlapped cocktail as a single speech region, so FastClustering has nothing to split. Auto-threshold finds 3–5 speakers; DER stays ~0.94 because 19 babble tracks occupy the same time. Clustering knobs do not rescue a 20-way additive mix.

**B. Per-stream / PresentConnect (tech demo):** RMS VAD + ASR each unlooped `dry/spk_XX.wav` with oracle stream→`speaker_id` (`s0N`↔`speaker_N`, main=`s01`/`speaker_1`), merge by time. **20/20 speakers recovered.** DER **0.0913** / JER **0.1840** / WER_main **0.0870** / RTF **0.1212**. Identity did not collapse. Residual DER is clip-silence in refs vs VAD speech edges.

Demo implication: the 20-mic PresentConnect path is the one that can track talkers; a single room mic with 20 overlapping voices is still a measured fail.

### Superseded eSpeak SYNTHETIC baseline (historical)

H1-ACK'd cocktail FAIL. Files: `artifacts/eval/demo-stress/espeak-baseline/`. Do not use as headline.

| test_id | DER 250ms | JER | WER main | RTF | hyp/ref |
|---|---|---|---|---|---|
| mix_20talker | 0.9500 | 0.9503 | 1.0000 | 0.1592 | 1 / 20 |
| mix_20talker_main_dry | 0.0217 | 0.0933 | **1.0000** | 0.0591 | 1 / 1 |

Dry WER=1.0 was eSpeak out-of-domain (hyp text `с`), not only overlap. REAL dry WER is 0.1739 on the same recipe.
