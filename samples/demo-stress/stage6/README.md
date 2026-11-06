# DEMO_STAGE6 — primary one-mic demo fixture

**This is the presentation target.** The 20-stream Path B / `*_gated_v3` suite is **secondary regression only**.

| Item | Value |
|---|---|
| Talkers | **6** distinct Mozilla Common Voice 17 RU (`client_id`s), CC0-1.0 |
| Channel | **one mic** (Path A room mix, not PresentConnect streams) |
| Turns | one-by-one; **200 ms** interrupt overlap at replica ends only |
| Distance | 3.0 … 6.0 m (inverse-distance vs nearest seat + wetter reverb) |
| Room | SYNTHETIC large-room IR, RT60 **1.4 s** |
| Noise | medium HVAC (target ~14 dB) + quiet unused-CV babble bed (~18 dB) |
| Phone | Nothing-class band-limit 250–4500 Hz, light compression; **not** EDGE_badmic crush |
| Gold | corpus `sentence` (not eSpeak, not ASR) |

```bash
python3 scripts/generate_demo_stage6.py
python3 scripts/eval_demo_stress.py --run-host --only DEMO_STAGE6
python3 scripts/eval_demo_stress.py --run-host --only DEMO_STAGE6_ffgain
python3 scripts/eval_demo_stress.py --run-host --only DEMO_STAGE6_perturn
python3 scripts/eval_demo_stress.py --run-host --only DEMO_STAGE6_phone_eq
```

Baseline `DEMO_STAGE6` is frozen @ `a8b39d5a`. `DEMO_STAGE6_ffgain` is whole-file AGC @ `b66581c` / `e0e64b85`. `DEMO_STAGE6_perturn` is per-turn FarFieldGain @ `db3afe4`. `DEMO_STAGE6_phone_eq` is Platform PhoneDistanceEq @ `af71d91` (gain frozen). Do not retune gold.

Full `DEMO_STAGE6.wav` is gitignored. Committed: `recipe.json`, `ref/DEMO_STAGE6.json`, `smoke.wav`. Do not retune gold to fake low WER.

Hex.Team WER lock (2026-11-06): keep `,` `.` `?` `!`; fold ё→е. Gold punct audit vs dry-CV pauses: existing commas on speaker_1/3/6 match intonation; speaker_2/4/5 period-only. Wording not changed.
