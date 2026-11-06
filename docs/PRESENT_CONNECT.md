# PresentConnect API contract

**Audience:** web FE (designer) + web BE (Артём + Пётр).  
**Owner of this doc / Android worker:** overnight Android track (this repo).  
**Flavor:** `presentConnect` (`BuildConfig.PRESENT_CONNECT = true`).  
**Not this:** checkpoint polling so slide-followers stay in sync — that is **web BE only**. Documented at the bottom so we do not collide.

The phone is an **on-device ASR+diarization worker**, not a browser. Demo loop:

```
QR on slide → audience browser mic → web BE relays PCM → Android app
        → transcript JSON snippets → BE → projector
```

Hard cap: **≤ 20 concurrent audio streams** per phone worker. Stream 21 is rejected.

Offline stenography (file import / in-app record, airplane mode) is a **different mode**. Do not send present-connect audio through a cloud ASR.

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

`transcript.partial` — optional live line:

```json
{
  "type": "transcript.partial",
  "stream_id": "s01",
  "speaker_id": "speaker_1",
  "start_ms": 0,
  "end_ms": 800,
  "text": "добрый"
}
```

`transcript.final` — one utterance, **same fields as the brief JSON item**:

```json
{
  "type": "transcript.final",
  "stream_id": "s01",
  "utterance": {
    "speaker_id": "speaker_1",
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

If worker already has 20 open streams it replies `error` / `OVER_CAPACITY` and does **not** accept PCM.

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
    "speaker_id": "speaker_1",
    "start_ms": 1250,
    "end_ms": 4380,
    "text": "Добрый день!"
  }
}
```

Web FE maps `stream_id` → seat/QR label. `speaker_id` is diarization **inside** that stream (one browser mic can still be one speaker).

---

## 3. HTTP (fallback / health)

Base: `https://{host}/v1`

| Method | Path | Who | Body / result |
|---|---|---|---|
| `POST` | `/workers/register` | phone | `{device_id, max_streams:20, models:[...]}` → `{worker_id, ws_url}` |
| `GET` | `/workers/{id}/health` | phone/BE | `{ok, streams, max_streams}` |
| `POST` | `/sessions/{sid}/transcribe` | BE (debug) | `multipart/audio` → brief JSON `{transcript:[...]}` **one file, not 20 streams** |
| `GET` | `/sessions/{sid}/transcript` | display | current concatenated `{transcript:[...]}` |

HTTP `transcribe` is for bringing-up the phone without WS. The 20-stream demo **must** use WebSocket.

---

## 4. Capacity and errors

| Code | When |
|---|---|
| `OVER_CAPACITY` | 21st `stream.start` |
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

Implementation talks OkHttp WebSocket. Pipeline on the phone is the same as file import: PCM → VAD → diarize → ASR → LLM stub → `transcript.final`.

---

## 7. Out of scope for web team tonight (do not block on)

- Shipping ONNX models
- WER
- Airplane-mode file path

Need from web team: reachable `wss://` on the LAN/demo Wi-Fi, QR → join page with mic permission, display of `transcript.final`.
