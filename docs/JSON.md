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
| `speaker_id` | string | Speaker label. DEMO_STAGE6 / offline file/mic: diarization cluster, e.g. `speaker_1` (0-based cluster + 1). Optional PresentConnect (Path B, not the presentation): default is `stream_id` or the seat label — not a room-mix cluster. See `docs/PRESENT_CONNECT.md`. |
| `start_ms` | integer | Utterance start, milliseconds from audio origin |
| `end_ms` | integer | Utterance end, milliseconds from audio origin |
| `text` | string | ASR (+ optional LLM cleanup) text |

## Rules

- `start_ms` ≥ 0, `end_ms` ≥ `start_ms`.
- Sort `transcript` by `start_ms` ascending.
- Do not send audio off-device to produce this file (offline path).
- UTF-8, no BOM. Share sheet + save via SAF.

Kotlin mapping lives in `src/android/app/.../export/` once the shell lands (`TranscriptFile` / `Utterance`).
