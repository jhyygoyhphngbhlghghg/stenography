# Scripts

- `download-models.sh` — fetch Zipformer-ru int8 (exact HF names), Silero VAD, pyannote+embedding ONNX (extracts tarball), optional Whisper tiny / Vosk. Writes to `models/` (gitignored).
- `fetch-sherpa-aar.sh` — GitHub Releases Android AAR (JitPack `v2.0.1` unpublished).
- `verify-transcript-json.py` — exact brief schema (`transcript[]` + four fields).
- `eval_harness.py` — WER+RTF skeleton for 3 fixed clips (empty table until a real run). `--suite demo-stress` scores the 20-talker mix; `--run-host` runs Linux sherpa-onnx (no KVM/ADB).
- `fetch_cv17_ru.py` — download Common Voice 17 RU test clips (CC0) for 20 distinct talkers.
- `generate_demo_stress_mix.py` — REAL 20-talker cocktail mix from that corpus (+3 dB main, staggered offsets). `--engine espeak` is the superseded OOD baseline.
- `diar_param_sweep.py` — Path A clustering grid on the one-room mix (diar only).
- `test_path_b_regression.py` — frozen Path B gate (CLEAN + EDGE_* + WORST_CASE): measured 20/20 speakers, schema. Does not require low WER/DER. See docs/GATE.md.
- `generate_adversarial_path_b.py` — Path B EDGE_*/WORST_CASE demo-room degradations (reverb, true overlap+bleed, room noise, bad mic). Does not rewrite the CLEAN baseline. **Secondary regression.**
- `generate_demo_stage6.py` — **PRIMARY** demo fixture: 6 RU talkers, one-mic mix, turn-taking + 200 ms end overlap, large-room IR, medium HVAC, Nothing-class phone mic, 3–6 m.
- `farfield_gain.py` / `demo_pcm_prep.py` — Python mirror of Platform FarFieldGain + DemoPcmPrep. Headline **per-turn** @ `ae86903` / `db3afe4` (quiet islands → 0.08 FS, loud near 0 dB, no crush). `stage_whole_file` keeps historical AGC @ `b66581c`. `phone_eq=True` then applies PhoneDistanceEq @ `af71d91` (gain stays frozen). Ingest-only; does not retune STAGE6 wavs.
- `phone_distance_eq.py` — Python mirror of Platform `PhoneDistanceEq.kt` @ `af71d91` (`040274d` 250–4500 Hz). Far-island 1600 Hz high-shelf; near seat identity; no whole-file crush.
- `measure-farfield-gain.py` — synthetic 3–6 m + sequential mix table (does not touch Eval wavs).
- `measure-phone-distance-eq.py` — Platform measure script @ `af71d91` (synth only; does not touch Eval wavs).
- `test_farfield_gain.py` — synth VAD 0→1 at 5–6 m; sequential mix does not crush near seat; CLIPPED/empty still hard-skip.
- `test_phone_distance_eq.py` — near seat identity; 3–6 m speech-band lift; CLIPPED/empty still hard-skip.
- `stream_pcm_gate.py` — Python mirror of StreamPcmGate.kt v3 @ Implementer `a5b7aed` (sha256 == Platform `686ac4a`; bound BAND_LIMITED is a soft flag + still ASR). `version=2` keeps historical hard BAND_LIMITED (`e3ad908`). `version=1` keeps `*_gated` / `*_gated_b142a4d`.
- `measure-stream-pcm-gate.py` — synthetic PCM stats for those thresholds (does not touch Eval wavs). Byte-identical to Implementer `a5b7aed` / Platform `686ac4a`.
- `test_stream_pcm_gate.py` — v2 verdicts: bound silence SOFT_LOW, phone crush BAND_LIMITED, broadband Pass.
- `vad_diar_reconcile.py` — Python mirror of Impl `VadDiarReconcile.kt` @ `3c3f9ce` (PR #7): HARD CAP K=6 (never speaker_7..12); near 3 m protected. Prior over-split @ `552b142` / `9b29b9c1`.
- `intonation_punct.py` — Python mirror of Impl `IntonationPunct.kt` @ `3c3f9ce`: ADD spoken commas (greeting/vocative/discourse/contrast) + terminal; never strip; fold ё→е. GGUF candidate optional.
- `test_vad_diar_reconcile.py` — twelveVadIslandsNeverInventSpeakerSeven (unique 6); near seat not swallowed.
- `test_intonation_punct.py` — punctless Zipformer accepts added intonation punct; never strips.
- `host_asr_diar.py` — Zipformer-ru + OfflineSpeakerDiarization on Linux Python. `impl_second_pass` runs reconcile + IntonationPunct.
- `eval_demo_stress.py` — DER/JER/purity + main-speaker WER + RTF; pending/blocked if inference cannot run.
- `eval_metrics.py` — DER/JER/purity + WER. Hex.Team lock: keep `,` `.` `?` `!`; fold ё→е.
- `test_eval_metrics.py` — unit checks of the metric code (not clip scores).
- `ci-check.sh` — schema + eval skeleton + optional Gradle.
- PresentConnect mock server (later): local WS hub for the Android worker.

Do not commit downloaded weights.
