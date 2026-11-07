# Path B PresentConnect mapping (no cocktail wavs)

Tech demo = **Path B**: ≤20 separate streams, never a one-room mix.

Oracle (Eval `mix_20talker_per_stream`): `s{N:02d}` ↔ `speaker_{N}` ↔ dry `spk_{N:02d}.wav`. `s01` / `speaker_1` is **main** (WER_main).

- `channels.json` — mapping table (no client hashes, no wavs)
- `golden_path_b_brief.json` — Eval brief cut (8 utterances, 7 speakers). Brief keys only: `speaker_id`, `start_ms`, `end_ms`, `text`. No `stream_id`.

Eval-measured on PR #4 (not re-run here): DER 0.235, WER_main 0.087, 20/20 speakers.
