# PresentConnect API contract

**Audience:** web FE (designer) + web BE (Артём + Пётр).  
**Owner of this doc / Android worker:** overnight Android track (this repo).  
**Flavor:** `presentConnect` (`BuildConfig.PRESENT_CONNECT = true`).  
**Not this:** checkpoint polling so slide-followers stay in sync — that is **web BE only**. Documented at the bottom so we do not collide.

**Not the live demo claim.** Path B ≤20 browser streams is a secondary codepath (PR #6). Live demo is DEMO_STAGE6: one phone mic, 6 RU speakers at 3–6 m.

The phone is an **on-device ASR+diarization worker**, not a browser. Optional Path B loop:

```
QR on slide → audience browser mic → web BE relays PCM → Android app
        → transcript JSON snippets → BE → projector
```

Hard cap: **≤ 20 concurrent audio streams** per phone worker. Stream 21 is rejected.

**Path B (locked):** each browser/mic is its own `stream_id` with its own PCM buffer into local Zipformer-ru + diarization. The worker **must not** mix or sum PCM across streams before ASR/diar (that was Path A; rejected for the demo). Web FE maps `stream_id` → seat/QR; `speaker_id` is diarization **inside** that stream.

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
| `LOW_ENERGY` | **unbound / anonymous** below RMS floor, or **empty** (`n==0`); **ASR skipped**. Bound `s01`..`s20` near-silence is `SOFT_LOW` (ASR still runs). Bound main `s01` is never hard-dropped for energy |
| `CLIPPED` | clip ratio too high (hard-limited mic); **ASR skipped** (bound and unbound) |
| `BAD_MIC` | stuck mic / extreme DC; **ASR skipped** (bound and unbound) |
| `BAND_LIMITED` | phone-EQ / crush (HF/speech occupancy) without hard clip. **Hard skip only if unbound/anonymous.** Bound `s01`..`s20` (incl. main): **soft flag**, ASR still runs |

### 4.1 Path B pre-ASR energy / mic-quality gate

Byte-identical to platform PR #3 (`platform/derisk-sherpa-present` @ `686ac4a`). H1: soft `BAND_LIMITED` on bound seats. Runs on the **accumulated pcm_s16le for that `stream_id` only** (never mixed), **before** Silero / diar / Zipformer.

Measure command (synthetic fixtures only; **Eval WORST_CASE wavs untouched**):

```bash
python3 scripts/measure-stream-pcm-gate.py --record
```

Units: full-scale (FS) = 32768 for s16. `dBFS = 20*log10(rms_fs)`. Evidence: `artifacts/test-results/stream-pcm-gate.json`.

| Constant | Value | Code |
|---|---|---|
| `RMS_REJECT_FS` | 0.008 (≈ −41.94 dBFS) | `LOW_ENERGY` (unbound only) |
| `PEAK_REJECT_FS` | 0.012 | `LOW_ENERGY` (unbound only) |
| `CLIP_REJECT` | 0.02 of samples with \|s16\| ≥ 32760 | `CLIPPED` |
| `DC_REJECT_FS` | 0.20 (+ `STUCK_VARIANCE_FS` 1e-7) | `BAD_MIC` |
| `MIN_SAMPLES` | 800 (50 ms @ 16 kHz) | `LOW_ENERGY` (unbound / short / empty) |
| `BAND_LIMITED_HF_RATIO` | 0.08 | `BAND_LIMITED` (hard unbound; soft bound) |
| `BAND_LIMITED_SPEECH_OCC` | ≥2 Goertzel bins (250–3500 Hz) | `BAND_LIMITED` |
| `BAND_LIMITED_HF_OCC` | 0 bins in 4–7 kHz | `BAND_LIMITED` |

**Soft vs hard (bound `s01`..`s20` vs unbound):**

| Condition | Bound known seat (`s01`..`s20`) | Unbound / anonymous |
|---|---|---|
| Empty `n==0` | hard `LOW_ENERGY`, skip ASR | hard `LOW_ENERGY`, skip ASR |
| Near / below RMS 0.008 FS (non-empty) | **`SOFT_LOW`**, still ASR | hard `LOW_ENERGY`, skip ASR |
| Phone crush / band-limit (no clip/DC) | **soft `BAND_LIMITED`**, still ASR | hard `BAND_LIMITED`, skip ASR |
| `CLIPPED` / stuck / extreme DC | hard skip | hard skip |

Bound **main `s01` is never hard-dropped for energy or band-limit** (H1: Eval gated_v2 hard `BAND_LIMITED` dropped main; WORST_CASE 0/20 — cite only). Empty `n==0` and `CLIPPED`/`BAD_MIC` may still skip s01.

Pre-diar stream wipe/zeroing stays **removed** (H1: that path cost 9 speakers). Overlap/bleed endpointing is unchanged.

Hard reject: no ASR, no brief row, wire `error` with `LOW_ENERGY|CLIPPED|BAD_MIC|BAND_LIMITED`, then `stream.closed`. Soft flags (`SOFT_LOW` / `BAND_LIMITED` / `SOFT_CLIP` / `SOFT_DC`) still ASR (`hardReject=false`); scored brief stays four fields only.

---

## 5. Checkpoint polling (web BE — not Android)

Clients follow the presenter:

`GET /v1/presentation` → `{session_id, slide_index, slide_id, slide_label, t_ms, follow_token, revision, demo_phase, scroll_locked, slides[], phone{}}`

`scroll_locked` — снимал ли пульт замок прокрутки (`screen.scroll`, кнопка на
`/presenter`). Экраны при `true` держатся чекпоинта пульта; при `false` зал
листает сам, а позиция пульта возвращается первым же переводом строки. Смена
замка поднимает `revision`, иначе экраны, дедуплицирующие чекпоинты по
`revision`, потеряли бы её как повтор.

`GET /v1/frontend/checkpoint` — the same checkpoint plus the dialogue rows in
the brief's four-field shape; this is what the React deck reads on boot before
it upgrades to the WebSocket.

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

Implementation talks OkHttp WebSocket. **Per stream**, PCM → VAD → diarize → ASR → LLM stub → `transcript.partial` / `transcript.final` with that `stream_id`. Same Zipformer-ru engine as file import, but **N open streams are N isolated buffers**, never one mixed buffer.

---

## 7. Out of scope for web team tonight (do not block on)

- Shipping ONNX models
- WER
- Airplane-mode file path

Need from web team: reachable `wss://` on the LAN/demo Wi-Fi, QR → join page with mic permission, display of `transcript.final`.

---

## 8. Tonight for Артём / Петя (web BE)

Android worker is OkHttp WebSocket. Do this on the BE, nothing else:

1. **Listen** `wss://{host}/v1/present-connect?session={session_id}&role=worker&device={device_id}`.
2. **Parse** worker `hello` (`protocol: present-connect/1`, `max_streams: 20`, `encoding: pcm_s16le`, `models: ["zipformer-ru-int8"]`). Reply `hello.ok` with `session_id` + `worker_id`.
3. **Relay** browser mics as `stream.start` → `audio.pcm16` (base64 s16le 16 kHz mono, 100–200 ms) → `stream.end`. Do **not** send AAC/Opus.
4. **Cap**: if the phone already has 20 open streams it replies `error` / `OVER_CAPACITY` and drops that `stream_id`. Do not retry-storm; show the seat as rejected.
5. **Display** `transcript.final.utterance` using the **same four fields** as the brief JSON (`speaker_id`, `start_ms`, `end_ms`, `text`). Map `stream_id` → QR/seat on your side.
6. **Ping** every ~15 s; worker answers `pong` with the same `t`.
7. Checkpoint polling (`GET /v1/presentation/{id}/checkpoint`) stays **web-only**. Android will not implement it.

HTTP `POST /v1/workers/register` is optional bring-up. The 20-stream demo **must** be WebSocket.

If the worker is in stub mode (no ONNX on the phone) it may emit `MODELS_MISSING` — do not use that APK on stage.
