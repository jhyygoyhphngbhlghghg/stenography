# JSON export schema

Mandatory output of the Android app (Hex.Team brief). Field names and types are fixed. Extra fields must not replace these.

## Schema

```json
{
  "transcript": [
    {
      "speaker_id": "speaker_1",
      "start_ms": 1250,
      "end_ms": 4380,
      "text": "Добрый день!"
    }
  ]
}
```

| Field | Type | Meaning |
|---|---|---|
| `transcript` | array | Ordered list of utterances |
| `speaker_id` | string | Speaker label, e.g. `speaker_1` (0-based cluster + 1) |
| `start_ms` | integer | Utterance start, milliseconds from audio origin |
| `end_ms` | integer | Utterance end, milliseconds from audio origin |
| `text` | string | ASR (+ optional LLM cleanup) text |

## Rules

- `start_ms` ≥ 0, `end_ms` ≥ `start_ms`.
- Sort `transcript` by `start_ms` ascending (tie-break `speaker_id`).
- Each item has **only** `speaker_id`, `start_ms`, `end_ms`, `text`. Eval rejects extras. **No `stream_id` in the brief** (`stream_id` exists on the PresentConnect wire `transcript.final` only).
- Path B: `speaker_1` is main (`s01`). Oracle `s{N:02d}` ↔ `speaker_{N}`.
- Do not send audio off-device to produce this file (offline path).
- UTF-8, no BOM. Share sheet + save via SAF.

Kotlin mapping: `TranscriptFile` / `Utterance`. Path B golden: `artifacts/test-results/golden_path_b_brief.json`.
