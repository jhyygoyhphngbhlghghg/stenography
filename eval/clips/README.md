# Eval clip slots

Three local slots for a later A-12 run. This directory is a drop zone, not a dataset.

| Slot | Audio (not in git) | Gold stub (in git, empty) |
|---|---|---|
| `clip_01` | `eval/clips/clip_01.wav` | `eval/gold/clip_01.gold.json` |
| `clip_02` | `eval/clips/clip_02.wav` | `eval/gold/clip_02.gold.json` |
| `clip_03` | `eval/clips/clip_03.wav` | `eval/gold/clip_03.gold.json` |

`.m4a` and `.mp3` are fine if you point the run at that path. `eval/manifest.json` lists the expected `.wav` path for each id.

## What to drop

1. Put the audio file in this directory under the slot name above. Audio here is gitignored. Do not commit it.
2. For a team dry run, edit the matching `eval/gold/clip_XX.gold.json` so `transcript` holds your own reference utterances. Keep the mandatory shape from `docs/JSON.md`:

```json
{
  "transcript": [
    {
      "speaker_id": "speaker_1",
      "start_ms": 0,
      "end_ms": 0,
      "text": ""
    }
  ]
}
```

3. Hypothesis JSON is whatever the app exported for that file. Keep it outside git (for example `eval/hyp/clip_01.json`, also gitignored) or pass any local path to the metrics CLI.

The gold files committed on this branch are **empty transcript arrays**. They validate against the schema and they are **not** references. They are not the judge set.

## Judge clips stay out of git

The official three dialogue clips («котики») are hidden. Do not download them into the repo, do not commit them, and do not paste judge gold here. Judges import those clips on their own devices.

For a dry run before that, use your own Russian multi-speaker recordings under `samples/` (see `samples/README.md`). Large audio there is local-only. `samples/` is not the eval set.

## Not measured yet

No duration, wall time, WER, or RTF is known for these slots. Leave those fields empty until a real device or host run. See `eval/README.md`.
