# PresentConnect API contract

**Audience:** web FE (designer) + web BE (Артём + Пётр).  
**Owner of this doc / Android worker:** overnight Android track (this repo).  
**Flavor:** `presentConnect` (`BuildConfig.PRESENT_CONNECT = true`).  
**Not this:** checkpoint polling so slide-followers stay in sync — that is **web BE only**. Documented at the bottom so we do not collide.

The phone is an **on-device ASR worker** (Zipformer-ru), not a browser. **Normative demo contract = Path B** (see below). Demo loop:

```
QR on slide → one browser mic per seat (own stream_id) → web BE relays PCM
        → Android worker, each stream independent → transcript JSON → BE → projector
```

Hard cap: **≤ 20 concurrent audio streams** per phone worker. Stream 21 is rejected with `OVER_CAPACITY`.

Offline stenography (file import / in-app record, airplane mode) is a **different mode** (one recording: VAD → diarize → ASR). Do not send present-connect audio through a cloud ASR. Do not run the 20-seat demo by mixing mics into that offline diarizer.

---

## 0. Demo contract — Path B (normative)

PresentConnect demo = **≤20 separate mic/audio streams**. Each `stream_id` is processed **independently**:

`PCM → VAD → ASR` (Zipformer-ru). Streams do not share a clustering pass.

**Labeling (≤20-stream demo):** default **`speaker_id` ← `stream_id`**. If `stream.start.speaker_hint` is a non-null string, use that stable seat label as `speaker_id` instead. Either way the map is stream→speaker, not a room-wide cluster id.

Optional diarization runs **inside one stream only** (`accepts_diarization: true` means that, not “mix the room”). When intra-stream diarization is off, or the stream is one seat, `speaker_id` stays the stream/seat label. Session transcript = per-stream utterances **merged by time**.

Worker pipeline is per stream. The 21st `stream.start` is `error` / `OVER_CAPACITY`; the worker does not accept its PCM.

### Out of scope / known fail (Path A)

One-room cocktail mix of 20 talkers into a **single** stream is **Path A** and **fails**. Eval-measured (H1 / Eval Owner, [PR #4](https://cursor.com/codebase/johann-schilling/neuro-stenographer/pull/4)): speakers recovered **1/20**, DER ≈ **0.95**, main WER **1.00**, RTF **0.18**. Web FE **must not** mix audience mics into one stream and present that as the multi-talker demo. Path A stays documented so we do not overclaim. Numbers are not to be reinvented; see `docs/TEST_RESULTS.md`.

---

## 1. Roles

| Role | Who | Transport |
|---|---|---|
| `presenter` | slide deck page | HTTP checkpoint (web BE) |
| `listener` | audience / demo browser with mic | WebSocket audio up |
| `worker` | this Android app | WebSocket audio down, transcript up |
| `display` | projector page | WebSocket / HTTP transcript feed |

One session id (`session_id`) groups them. Phone registers as `worker`.

---

## 2. WebSocket (primary)

**URL:** `wss://{host}/v1/present-connect?session={session_id}&role=worker&device={device_id}`

Text frames, JSON. Audio payload is **base64 PCM s16le, 16 kHz, mono**. Chunk ~100–200 ms (1600–3200 samples). Do not send AAC/Opus to the phone in v1 (decode on BE or in the browser before relay).

### 2.1 Worker → server

`hello` (first message):

```json
{
  "type": "hello",
  "protocol": "present-connect/1",
  "max_streams": 20,
  "sample_rate": 16000,
  "encoding": "pcm_s16le",
  "models": ["zipformer-ru-int8"],
  "accepts_diarization": true
}
```

`accepts_diarization: true` (default in v1 hello): worker *may* diarize **inside** a single `stream_id`. It does **not** accept a 20-talker room mix. Default labels stay `speaker_id` ← `stream_id` (or `speaker_hint`) unless that one stream actually emits more than one intra-stream speaker.

`transcript.partial` — optional live line. `speaker_id` defaults to `stream_id` (here `s01`); a seat label from `speaker_hint` is equally valid:

```json
{
  "type": "transcript.partial",
  "stream_id": "s01",
  "speaker_id": "s01",
  "start_ms": 0,
  "end_ms": 800,
  "text": "добрый"
}
```

`transcript.final` — one utterance, **same fields as the brief JSON item**. Demo default: `utterance.speaker_id` equals `stream_id` or the seat label:

```json
{
  "type": "transcript.final",
  "stream_id": "s01",
  "utterance": {
    "speaker_id": "s01",
    "start_ms": 1250,
    "end_ms": 4380,
    "text": "Добрый день!"
  }
}
```

`stream.closed`:

```json
{"type": "stream.closed", "stream_id": "s01"}
```

`error`:

```json
{"type": "error", "stream_id": "s01", "code": "OVER_CAPACITY", "message": "max 20 streams"}
```

`pong`: `{"type":"pong","t": 1730900000000}`

### 2.2 Server → worker

`hello.ok`:

```json
{"type": "hello.ok", "session_id": "sess_abc", "worker_id": "phone_1"}
```

`stream.start`:

```json
{
  "type": "stream.start",
  "stream_id": "s01",
  "sample_rate": 16000,
  "encoding": "pcm_s16le",
  "speaker_hint": null
}
```

`speaker_hint` is optional and defaults to `null` (backward compatible). `null` → worker sets `speaker_id` = `stream_id`. A string (seat / QR label) → that string is `speaker_id` for this stream. No new required field.

If the worker already has 20 open streams it replies `error` / `OVER_CAPACITY` and does **not** accept PCM. Processing of accepted streams stays independent (no cross-stream clustering).

`audio.pcm16`:

```json
{
  "type": "audio.pcm16",
  "stream_id": "s01",
  "seq": 12,
  "pcm16_b64": "<base64>"
}
```

`seq` is uint, monotonic per `stream_id`. Gaps: worker may skip; do not stall the whole session.

`stream.end` — flush VAD, emit finals, then `stream.closed`.

`stream.cancel` — drop without a final.

`ping`: `{"type":"ping","t": 1730900000000}`  (worker answers `pong`)

### 2.3 Display / listener (web BE may fan out)

Server may broadcast to `display`:

```json
{
  "type": "transcript.final",
  "stream_id": "s01",
  "utterance": {
    "speaker_id": "s01",
    "start_ms": 1250,
    "end_ms": 4380,
    "text": "Добрый день!"
  }
}
```

Web FE keeps **one mic ↔ one `stream_id`**. `speaker_id` is that stream (or `speaker_hint` / seat label), not a cluster id from a mixed room. Intra-stream diarization, if enabled, may add speaker ids **only inside that same `stream_id`**. Do not sum browser mics into one `audio.pcm16` stream for the demo claim.

---

## 3. HTTP (fallback / health)

Base: `https://{host}/v1`

| Method | Path | Who | Body / result |
|---|---|---|---|
| `POST` | `/workers/register` | phone | `{device_id, max_streams:20, models:[...]}` → `{worker_id, ws_url}` |
| `GET` | `/workers/{id}/health` | phone/BE | `{ok, streams, max_streams}` |
| `POST` | `/sessions/{sid}/transcribe` | BE (debug) | `multipart/audio` → brief JSON `{transcript:[...]}` **one file, not 20 streams** |
| `GET` | `/sessions/{sid}/transcript` | display | current concatenated `{transcript:[...]}` |

HTTP `transcribe` is one file for bringing-up the phone without WS. The ≤20-stream demo **must** use WebSocket, one `stream_id` per mic. This endpoint is not a Path A cocktail claim.

---

## 4. Capacity and errors

| Code | When |
|---|---|
| `OVER_CAPACITY` | 21st `stream.start` (hard cap 20). Reject that stream; do not mix it into an open one |
| `BAD_ENCODING` | not pcm_s16le / not 16 kHz |
| `UNKNOWN_STREAM` | PCM for a stream that was not started |
| `PIPELINE` | on-device ASR/VAD failure |
| `MODELS_MISSING` | worker in stub mode — **do not use on stage** |

---

## 5. Checkpoint polling (web BE — not Android)

Clients follow the presenter:

`GET /v1/presentation/{id}/checkpoint` → `{slide_index, t_ms, follow_token}`

Poll 1–2 Hz. Android **does not** implement this. QR should still encode `session_id` + optional `ws_url` so browsers know where to stream.

QR payload suggestion (web):

```
https://{present-host}/join?session=sess_abc
```

---

## 6. Android interface (this repo)

```kotlin
interface PresentConnectClient {
    suspend fun connect(config: PresentConnectConfig)
    suspend fun disconnect()
    val events: Flow<PresentConnectEvent>
    fun activeStreamCount(): Int  // 0..20
}
```

Implementation talks OkHttp WebSocket. **Per open `stream_id`, independently:** PCM → VAD → ASR → `transcript.final`, with `speaker_id` ← `stream_id` (or `speaker_hint`). Optional diarization is inside that stream only. This is **not** the offline file path (one recording → `OfflineSpeakerDiarization` → ASR) and **not** Path A mix-then-cluster. LLM post-pass stays the identity stub. Finals from all streams are merged by time for the session transcript.

---

## 7. Out of scope for web team tonight (do not block on)

- Shipping ONNX models
- Re-measuring WER (use the Eval numbers in `docs/TEST_RESULTS.md`; do not invent new ones)
- Airplane-mode file path
- Path A: mixing 20 talkers into one stream (known fail, §0)

Need from web team: reachable `wss://` on the LAN/demo Wi-Fi, QR → join page with mic permission, display of `transcript.final`.
