# PresentConnect API contract

**Audience:** web FE (designer) + web BE (Артём + Пётр).  
**Owner of this doc / Android worker:** overnight Android track (this repo).  
**Flavor:** `presentConnect` (`BuildConfig.PRESENT_CONNECT = true`).  
**Not this:** checkpoint polling so slide-followers stay in sync — that is **web BE only**. Documented at the bottom so we do not collide.

The phone is an **on-device ASR+diarization worker**, not a browser.

**Live demo claim = DEMO_STAGE6 (Eval PR #4 / stacked):** one Nothing-class phone mic, 6 RU speakers, each 3–6 m away, sequential turn-taking with light overlap only, large-room IR + medium babble/HVAC — **not** studio. This repo does **not** retune those wavs and does **not** invent DER/WER.

**PresentConnect Path B ≤20** is an **optional** network mode (code kept). It is **not** the slide/presentation target.

```
QR on slide → audience browser mic → web BE relays PCM → Android app
        → transcript JSON snippets → BE → projector
```

Hard cap: **≤ 20 concurrent audio streams** per phone worker. Stream 21 is rejected.

Path B (optional): each `stream_id` is its own PCM buffer → ASR. Oracle map: `s{N:02d}` ↔ `speaker_{N}` for N=1..20. `s01` / `speaker_1` is **main**. Bind on `stream.start`; do **not** recluster across streams. Never mix open streams into one buffer.

Eval (PR #4, cite only) scored Path B `mix_20talker_per_stream`: DER 0.235, WER_main 0.087, 20/20 speakers.

Offline stenography (file import / in-app record, airplane mode) is the **DEMO_STAGE6 path**. Per-turn gain is frozen; phone-distance EQ restores the Nothing-class 250–4500 Hz band on far islands without crushing the near seat. The **judge APK** embeds Zipformer-ru + Silero VAD + diar and unpacks to filesDir on first launch (zero network). Do not send that audio through a cloud ASR.

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
  "speaker_hint": null,
  "offset_ms": 0
}
```

`speaker_hint` optional; default oracle is `s01`→`speaker_1` … `s20`→`speaker_20`. `offset_ms` is an **additive** clock shift only when the PCM for that stream starts at zero. Eval dry wavs already sit on the shared session clock — default `0`.

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

`stream.end` — **gate this stream’s PCM** (energy / mic-quality, below) → skip ASR on reject (`error` + `LOW_ENERGY` / `CLIPPED` / `BAD_MIC`) or ASR **this stream’s PCM only** → `transcript.final` (oracle `speaker_id`) → `stream.closed`.

`stream.cancel` — drop the buffer without a final (do not absorb into the session brief).

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

Web FE may map `stream_id` → seat/QR label. `stream_id` is **on the wire only**. Session export / `GET …/transcript` is the Eval brief: `{ "transcript": [ {speaker_id, start_ms, end_ms, text} ] }` — extras rejected; **no `stream_id` in the brief**; sorted by `start_ms`. Worker sets `speaker_id` from the Path B oracle (`s01`→`speaker_1`), not from reclustering across streams.

---

## 3. HTTP (fallback / health)

Base: `https://{host}/v1`

| Method | Path | Who | Body / result |
|---|---|---|---|
| `POST` | `/workers/register` | phone | `{device_id, max_streams:20, models:[...]}` → `{worker_id, ws_url}` |
| `GET` | `/workers/{id}/health` | phone/BE | `{ok, streams, max_streams}` |
| `POST` | `/sessions/{sid}/transcribe` | BE (debug) | `multipart/audio` → brief JSON `{transcript:[...]}` **one file, not 20 streams** |
| `GET` | `/sessions/{sid}/transcript` | display | concat of `transcript.final` utterances, **brief keys only** (strip `stream_id` / `type`), sorted by `start_ms` |

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

Runs on the **accumulated pcm_s16le for that `stream_id` only** (never mixed). Pure Kotlin RMS/energy + heuristics — Silero VAD stays inside `SherpaStenoPipeline` (JNI + `silero_vad.onnx`) *after* this gate has already allowed ASR. JVM tests and stub workers can reject without JNI.

Measure command (synthetic fixtures only; **Eval WORST_CASE / demo-stress wavs untouched**):

```bash
python3 scripts/measure-stream-pcm-gate.py --record
```

Units: full-scale (FS) = 32768 for s16. `dBFS = 20*log10(rms_fs)`. Evidence: `artifacts/test-results/stream-pcm-gate.json`.

| Constant | Value | Why |
|---|---|---|
| `RMS_REJECT_FS` | 0.008 (≈ −41.94 dBFS) | silence RMS 0; tiny_noise 6.11e-5 (−84.3 dBFS); low-peak tone 0.00283 (−51.0 dBFS); clean 1 kHz −20 dBpeak RMS 0.0707 (−23.0 dBFS) |
| `PEAK_REJECT_FS` | 0.012 (≈ −38.4 dBFS) | clean peak 0.10; tiny_noise peak 9.2e-5 |
| `CLIP_REJECT` | 0.02 of samples with \|s16\| ≥ 32760 | clipped square measures clip_ratio **1.0** |
| `DC_REJECT_FS` | 0.20 | stuck 8000/32768 = 0.244; extreme DC 20000/32768 = 0.610 |
| `STUCK_VARIANCE_FS` | 1e-7 | stuck/extreme variance 0; tiny_noise variance 3.7e-9 but peak ≪ reject so `LOW_ENERGY` |
| `MIN_SAMPLES` | 800 (50 ms @ 16 kHz) | spectral inspect needs this much; unbound shorter buffers are `LOW_ENERGY` |
| `BAND_LIMITED_HF_RATIO` | 0.08 | broadband_stack hf_ratio **0.400**; phone_crush_stack **0**; phone_crush_lpf **0.008** (4× one-pole LP @ 2400 Hz) |
| `BAND_LIMITED_SPEECH_OCC` | ≥2 Goertzel bins (250–3500 Hz, occupancy >5% of peak bin) | phone stack occ_speech 3; LPF crush 2; clean 1 kHz occ 1 (not crush) |
| `BAND_LIMITED_HF_OCC` | 0 bins in 4–7 kHz | broadband occ_hf 2 |

**Soft vs hard (bound `s01`..`s20` vs unbound):**

| Condition | Bound known seat (`bindStart` + `s01`..`s20`) | Unbound / anonymous |
|---|---|---|
| Empty `n==0` | hard `LOW_ENERGY`, skip ASR | hard `LOW_ENERGY`, skip ASR |
| Near / below RMS 0.008 FS (non-empty) | **`SOFT_LOW`**, still ASR | hard `LOW_ENERGY`, skip ASR |
| Phone crush / band-limit (no clip/DC) | **soft `BAND_LIMITED`**, still ASR | hard `BAND_LIMITED`, skip ASR |
| `CLIPPED` / stuck / extreme DC | hard skip | hard skip |

Bound **main `s01` is sacred**: never hard-dropped for energy or band-limit (Eval gated_v2 @ `bed868b` dropped s01 on EDGE_noise and emptied WORST_CASE — cite only). Empty `n==0` and `CLIPPED`/`BAD_MIC` may still skip s01.

Hard reject: no ASR, no brief row, wire `{"type":"error","stream_id":"…","code":"LOW_ENERGY|CLIPPED|BAD_MIC|BAND_LIMITED","message":"..."}`, then `stream.closed`.

Soft flags (`SOFT_LOW` / `BAND_LIMITED` / `SOFT_CLIP` / `SOFT_DC`): still ASR; log + `MicQuality` (`hardReject=false`) only. **Scored brief stays four fields** — no quality extras.

Eval gated_v2 @ `bed868b` (cite only, not re-run): EDGE_noise dropped s01 main; WORST_CASE 0/20 empty transcript; badmic 6/20. This push does not claim new DER/WER.

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

Implementation: `OkHttpPresentConnectClient` + `PathBSession` + `StreamLabeler`. Per-stream PCM → ASR → relabel `speaker_id` from `s0N` → `transcript.final`. Mapping table: `samples/demo-stress/channels.json`. Golden brief (8 utts / 7 speakers): `artifacts/test-results/golden_path_b_brief.json`.

---

## 7. Out of scope for web team tonight (do not block on)

- Shipping ONNX models
- WER
- Airplane-mode file path

Need from web team: reachable `wss://` on the LAN/demo Wi-Fi, QR → join page with mic permission, display of `transcript.final`.
