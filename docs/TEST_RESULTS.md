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

JSON: `artifacts/eval/hyp/clip_0{1,2,3}.json` validates. CSV: `artifacts/test-results/results.csv`. Stack: Python `sherpa-onnx==2.0.1` Zipformer-ru + OfflineSpeakerDiarization, `num_clusters` 2/2/3. Morning handoff: `docs/overnight_clips_handoff.md`.

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

### Path B regression gate — FROZEN (H1 accepted WORST_CASE @ 9908353)

These six Path B rows are **frozen**. They change only with Lead sign-off. Do **not** retune fixtures, VAD, or refs to make numbers pretty. Re-run host inference only when Implementer/Platform claim a harden fix. Policy: `docs/GATE.md`.

**Lock claim: 20/20 stream tracking holds under edge; do NOT claim clean WER/DER on WORST_CASE.**

Frozen ids: `mix_20talker_per_stream` (CLEAN), `EDGE_reverb`, `EDGE_overlap`, `EDGE_noise`, `EDGE_badmic`, `WORST_CASE`. All six stay in `artifacts/eval/demo-stress/metrics.csv` / `metrics.json` / this table.

### Johann hard requirement — adversarial Path B (worst-case demo room)

CLEAN Path B (`mix_20talker_per_stream`) is **optimistic**: isolated dry CV clips, 90 ms stagger, no bleed, no room. H1/Johann: measure the **real demo room**, expect FAIL, **do not tune fixtures to look good**. H1 accepted this WORST_CASE table at `9908353`.

Same Path B stack (20 streams, RMS VAD + Zipformer, oracle `s0N`↔`speaker_N`). Speech still CV17 CC0. All IR / HVAC / chair / door / phone / white are **SYNTHETIC** (`scripts/generate_adversarial_path_b.py`). Overlap timeline: max **12** concurrent talkers; **90.99%** of speech frames have ≥2 speakers; mean **8.28** active. Bleed on s01 measured **+3.00 dB** (EDGE_overlap) / **+1.50 dB** (WORST_CASE) vs other talkers on main-active frames. Recipe: `samples/demo-stress/adversarial/`.

DER can exceed 1.0 when false-alarm time (noise/VAD) exceeds ref speech.

| test_id | status | DER 250ms | DER uncollared | JER | purity | WER main | RTF | hyp/ref spk |
|---|---|---|---|---|---|---|---|---|
| mix_20talker_per_stream **CLEAN Path B** | measured | **0.0913** | 0.1728 | **0.1840** | 0.9983 | **0.0870** | **0.1212** | 20 / 20 |
| EDGE_reverb | measured | **0.0849** | 0.1633 | **0.1756** | 0.9984 | **0.1304** | 0.1263 | 20 / 20 |
| EDGE_overlap | measured | **0.9936** | 1.1753 | **0.5476** | 0.9807 | **0.7391** | 0.2430 | 20 / 20 |
| EDGE_noise | measured | **1.7005** | 1.2941 | **0.5675** | 0.9203 | **0.1739** | 0.2602 | 20 / 20 |
| EDGE_badmic | measured | **0.6569** | 0.5246 | **0.2863** | 0.9497 | **1.0000** | 0.1910 | 20 / 20 |
| WORST_CASE | measured | **1.2985** | 1.4881 | **0.5981** | 0.9491 | **0.9130** | 0.2907 | 20 / 20 |

Wall: EDGE_reverb 2367 ms / 18736 ms; EDGE_overlap 4392 / 18076; EDGE_noise 4875 / 18736; EDGE_badmic 3578 / 18736; WORST_CASE **5255 / 18076**.

Honest read (not spun):

- Isolated-mic **reverb alone** does not collapse Path B (WER 0.0870→0.1304). Echo is not the demo-room killer if each stream is still a clean close mic.
- **True overlap + bleed** is: DER **0.99**, WER_main **0.74**. Leaky mics + 8-way concurrent speech.
- **Room noises** blow DER to **1.70** (VAD false alarms on HVAC/chairs/phone); main WER stays 0.1739.
- **Bad phone mic** zeros ASR: WER_main **1.0000**.
- **WORST_CASE** (all stacked, main +1.5 dB on s01): DER **1.30**, JER **0.60**, WER_main **0.91**. Hyp main is one 18 s blob whose text is `хочу говорить`. This is the demo-room row.

JSON validates (four fields). No invented numbers. Fixtures were not relaxed after seeing FAIL.

### StreamPcmGate remeasure (harden claim — fixtures still FROZEN)

Implementer Path B E2E PR #6 @ `dae416f` (StreamPcmGate byte-identical to Platform `f5ff5e2`). Host ingest now runs that gate on each dry stream **before** VAD/ASR. Frozen wavs/refs/VAD knobs **unchanged**. New rows `*_gated` sit beside the frozen baseline (do not replace it). Baseline snapshot: H1-accepted table @ `a863b2a6` / measured @ `9908353`.

Hard reject: no ASR, no brief row. Codes: `LOW_ENERGY` (RMS below 0.008 FS or peak below 0.012 FS or fewer than 800 samples), `CLIPPED` (at least 2% of samples `|s16| >= 32760`), `BAD_MIC` (`|DC| >= 0.20` FS or stuck). Soft flags still ASR.

| test_id | baseline @ a863b2a6 DER / JER / WER / RTF / hyp | gated @ HEAD DER / JER / WER / RTF / hyp | gate |
|---|---|---|---|
| EDGE_noise | **1.7005** / 0.5675 / **0.1739** / 0.2602 / **20/20** | **1.2940** / 0.7312 / **0.1739** / 0.2656 / **11/20** | passed **11/20**, dropped **9** `LOW_ENERGY` |
| EDGE_badmic | **0.6569** / 0.2863 / **1.0000** / 0.1910 / **20/20** | **0.6569** / 0.2863 / **1.0000** / 0.2726 / **20/20** | passed **20/20**, dropped **0** |
| WORST_CASE | **1.2985** / 0.5981 / **0.9130** / 0.2907 / **20/20** | **1.2985** / 0.5981 / **0.9130** / 0.3491 / **20/20** | passed **20/20**, dropped **0** |

Honest: dropping 9 quiet EDGE_noise streams cut collared DER 1.7005→1.2940; JER got worse (0.5675→0.7312) because those speakers are now misses; main WER held (s01 passed). StreamPcmGate does **not** catch EDGE_badmic (clip is 0.38 FS, not |s16|≥32760) or WORST_CASE (energy well above RMS/peak floors) — WER 1.0 / 0.91 unchanged. Do not claim clean WER/DER on WORST_CASE. Gated EDGE_noise is **11/20** speakers — the frozen 20/20 lock is the ungated baseline, not this ingest.

### StreamPcmGate post-hush-fix (Implementer `b142a4d`) vs prior gated @ `3b31e24`

Implementer removed pre-diar `SpeechEnergy` stream wipe; StreamPcmGate **thresholds unchanged**. Host eval never had that wipe; the 9-speaker loss was whole-file LOW_ENERGY on padded dry RMS (speech-active RMS still ≥ 0.016). `b142a4d` ingest: do not wipe the seat on padded-file LOW_ENERGY; still inspect() each ASR crop. Frozen wavs untouched. Prior `*_gated` rows @ `3b31e24` kept.

| test_id | prior gated @ 3b31e24 DER / JER / WER / RTF / hyp | post-hush @ HEAD DER / JER / WER / RTF / hyp | gate |
|---|---|---|---|
| EDGE_noise | **1.2940** / 0.7312 / **0.1739** / 0.2656 / **11/20** | **0.7092** / 0.4395 / **0.1739** / 0.4204 / **17/20** | streams **20 passed / 0 dropped**; 62 crops skipped LOW_ENERGY |
| EDGE_badmic | **0.6569** / 0.2863 / **1.0000** / 0.2726 / **20/20** | **0.6569** / 0.2863 / **1.0000** / 0.3729 / **20/20** | **20/0** streams; 0 crops skipped |
| WORST_CASE | **1.2985** / 0.5981 / **0.9130** / 0.3491 / **20/20** | **1.2985** / 0.5981 / **0.9130** / 0.5201 / **20/20** | **20/0** streams; 0 crops skipped |

Honest: EDGE_noise hyp **11/20 → 17/20** (closer to 20/20; 3 seats still have no surviving crop). DER 1.2940→0.7092 (crop-level LOW_ENERGY dropped HVAC FA). WER_main held 0.1739. badmic/WORST_CASE content unchanged. Do not claim clean WER/DER on WORST_CASE.

### StreamPcmGate v2 (Implementer `e3ad908` == Platform `b12a2bb`) — headline ingest

Python Path B ingest mirrors StreamPcmGate.kt @ Implementer PR #6 `e3ad908` (sha256 `3caa3a6a…` == Platform `b12a2bb`, not a divergent port): Goertzel `BAND_LIMITED` (hf_ratio &lt; 0.08, occ_speech ≥ 2, occ_hf ≤ 0, only if rms ≥ 0.020) plus bound-seat `SOFT_LOW` (s01–s20 near/below RMS floor still ASR; hard `LOW_ENERGY` only if unbound or n==0). CLIPPED/BAD_MIC still hard-reject. `BAND_LIMITED_SPEECH_OCC = 2` matches Kotlin, not the measure-script JSON comment of 3. Frozen wavs/refs/VAD **unchanged**. Prior `*_gated` @ `3b31e24` and `*_gated_b142a4d` @ `a42900e` kept as history. Headline rows: `*_gated_v2`.

| test_id | baseline DER / JER / WER / RTF / hyp | gated_v2 DER / JER / WER / RTF / hyp | gate pass/drop + reasons |
|---|---|---|---|
| EDGE_noise | **1.7005** / 0.5675 / **0.1739** / 0.2602 / **20/20** | **1.6234** / 0.5851 / **0.9565** / 0.4672 / **19/20** | **19/20** pass (s02–s20 `SOFT_LOW`); drop **1** `BAND_LIMITED` **s01** (rms 0.064, hf_ratio 0.0166, occ_speech 7, occ_hf 0) |
| EDGE_badmic | **0.6569** / 0.2863 / **1.0000** / 0.1910 / **20/20** | **0.7444** / 0.7707 / **0.2609** / 0.2961 / **6/20** | **6/20** pass (s01,s05,s06,s08,s09,s17); drop **14** `BAND_LIMITED` (s02–s04,s07,s10–s16,s18–s20) |
| WORST_CASE | **1.2985** / 0.5981 / **0.9130** / 0.2907 / **20/20** | **1.0000** / 1.0000 / **1.0000** / 0.1976 / **0/20** | **0/20** pass; drop **20** `BAND_LIMITED` (empty `{transcript:[]}`) |

Honest: v2 does what v1 could not — phone-crush seats fail `BAND_LIMITED` instead of sailing through RMS/clip/DC. Bound quiet EDGE_noise seats survive (`SOFT_LOW`, 19/20 vs v1 gated 11/20). Cost: EDGE_noise **s01** is loud enough for the band check and is rejected (HVAC/phone-like whole-file spectrum), so WER_main 0.1739→**0.9565**. EDGE_badmic WER_main 1.0000→0.2609 because s01 still ASR (hf_occupancy=1, so not `BAND_LIMITED`) and 14 crushed babble seats are gone; JER got worse (0.2863→0.7707) from those misses. WORST_CASE is a **full refuse**: 20/20 `BAND_LIMITED`, hyp **0/20**, DER/JER/WER **1.0**. Do not claim clean WER/DER on WORST_CASE. Frozen 20/20 lock remains the ungated baseline.

#### Ablation (interpretive — no new run)

Read off the frozen table above, not a new measurement. Single-factor vs CLEAN Path B (DER 0.0913 / WER_main 0.0870):

- **WER** is dominated by **badmic** (1.0000). Overlap is next (0.7391). Reverb is mild (0.1304). Noise barely moves WER (0.1739, same order as dry-main 0.1739).
- **DER** is dominated by **noise** false alarms (1.7005, DER can exceed 1). Overlap collapses time alignment (0.9936). Badmic is moderate (0.6569). Reverb does not hurt DER (0.0849).
- **WORST_CASE** is the stack (DER 1.2985, WER_main 0.9130): overlap+bleed plus badmic WER, with noise FA in the mix. Do not cite CLEAN WER/DER as the demo-room result.

### Superseded eSpeak SYNTHETIC baseline (historical)

H1-ACK'd cocktail FAIL. Files: `artifacts/eval/demo-stress/espeak-baseline/`. Do not use as headline.

| test_id | DER 250ms | JER | WER main | RTF | hyp/ref |
|---|---|---|---|---|---|
| mix_20talker | 0.9500 | 0.9503 | 1.0000 | 0.1592 | 1 / 20 |
| mix_20talker_main_dry | 0.0217 | 0.0933 | **1.0000** | 0.0591 | 1 / 1 |

Dry WER=1.0 was eSpeak out-of-domain (hyp text `с`), not only overlap. REAL dry WER is 0.1739 on the same recipe.
