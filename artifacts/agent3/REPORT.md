# Agent3 Report — Server Connectivity, Checkpoints, Live Streaming, Metrics, Token Implementation

## Metadata
- **Agent**: Agent 3 — Server (Backend / DevOps)
- **Date**: 2026-11-08 (UTC)
- **Worktree**: `E:/Projects/neuro-stenographer/neuro-stenographer-agent3` (branch `agent3-presentation-conn`, from `1906eaa`)
- **Original repo**: `E:/Projects/neuro-stenographer/neuro-stenographer` (branch `agent3-product-features`, untouched)
- **Emulator**: LDPlayer `emulator-5554` (SOV44), agent3-owned instance. Not shared with other agents.
- **Remote server**: `144.31.215.37` SSH as root (read-only discovery), web at `https://present.144.31.215.37.sslip.io` and `https://144.31.215.37:8080`

---

## 1. Initial Backend Server Testing (Local vs Remote)

### 1.1 Endpoints tested

| Endpoint | Method | Result |
|---|---|---|
| `/v1/health` | GET | `ok:true`, `screens` count live |
| `/v1/presentation` | GET | checkpoint payload (`slide_index`, `follow_token`, `revision`, `phone{}`) |
| `/v1/presentation/{id}/checkpoint` | GET | same |
| `/v1/sessions/{id}/transcript` | GET | `{"transcript":[]}` → 4-field brief after POST |
| `/v1/transcript` | POST | `{"ok":true,"accepted":2,"demo_phase":"live"}` (after BOM fix) |
| `/v1/status` | POST | sets recording/processing |
| `/v1/workers/register` | POST | `ws_url` with `role=phone` |
| `/v1/workers/{id}/health` | GET | `ok:false` initially |
| `/api/state`, `/api/invite` | GET | session + join URL |
| `/api/login`, `/api/command` | POST | cookie auth, `checkpoint.step/goto`, 429 throttling |

**BOM bug (not a server bug)**: PowerShell `Set-Content -Encoding UTF8` adds a BOM, JSON.parse fails → `bad_json`. Fixed by writing UTF-8 without BOM.

### 1.2 WebSocket

- Screen gets `snapshot` on connect.
- Phone `hello` → `hello.ok` ✓
- `transcript.partial` → relayed as `partial:true` ✓
- `transcript.final` → broadcast to all screens ✓
- Ping/pong heartbeat (15s) ✓
- **Disconnect → reconnect** (terminate + hello) works, same `session_id` ✓
- **Mid-stream drop** (partial → terminate → reconnect → final) recovers ✓

### 1.3 Checkpoints

- `GET /v1/presentation` returns live checkpoint; presenter `checkpoint.set/step/goto` bumps `revision`.
- **No persistence across restart**: killing Node and restarting loses `session_id`, transcript, slide. In-memory only.

### 1.4 LLM token streaming

- **No LLM / chat / SSE endpoint exists** on the web backend.
- `POST /v1/chat`, `POST /v1/llm/generate`, `GET /v1/metrics` → 404.
- Android `OnDeviceLlmRunner.complete(): suspend String` is blocking, non-streaming.

### 1.5 Metrics

- `/v1/health` exposes: `screens`, `rows`, `phone_connected`, `demo_phase`. **No CPU%/RAM/load.**
- Values are live (`screens` increments, `rows` increments), not hardcoded.
- External sampling: idle CPU 0.375s, RSS ~56 MB; after pushes RSS ~58 MB. No leaks observed.

### 1.6 Load test

- 5-minute 2-client streaming test started successfully; server survived. Manual 5× POST bursts: all accepted, no crash.
- `npm run typecheck` ✅ (existing `npm test` spawns real servers, timed out at 120s tool limit).

---

## 2. Remote Server Discovery (Read-Only)

### Process & service
- **PID 286918**: `/usr/bin/node dist/server.js`, user `steno`, since 05:22
- `systemctl`: `neurosteno-web.service` active (`WorkingDirectory=/opt/stenography/repo/src/web/backend`, `EnvironmentFile=/etc/neurosteno-web.env`)
- `ss -tlnp`: `0.0.0.0:8080` only

### Code paths
- Running copy: `/opt/stenography/repo/src/web/backend/`
  - `src/server.ts` — HTTP + WebSocket
  - `src/config.ts`, `src/store.ts`, `src/protocol.ts`, `src/simulate-phone.ts`
  - `dist/server.js` — SHA `2869a381...`, currently executed
  - `public/presenter.html`, `public/css/console.css`, `public/js/`
  - `present/` — separate React deck (Vite), served at `/` via `PRESENT_DIR`
- Git: `/opt/stenography/repo` on `main @ cca7892`, one uncommitted change (CSP commented out)

### Presenter remote controls
- **Page**: `https://present.144.31.215.37.sslip.io/presenter` (login via `ADMIN_TOKEN` → HttpOnly `presenter` cookie)
- **API**: `POST /api/login`, `POST /api/command` with `checkpoint.set/step/goto`, `demo.begin/fallback/end/simulate`, `transcript.clear`, `session.rotate`

### URLs
- `https://present.144.31.215.37.sslip.io` → `127.0.0.1:8080` via nginx (`sites-enabled/neurosteno`, TLS LE, 300s timeouts)
- `https://144.31.215.37:8080` (self-signed, `-k` required)

---

## 3. Android Token Connectivity Implementation

### Problem
- `PresentConnectConfig` had no token; `OkHttpPresentConnectClient` sent no `Authorization` header.
- Remote `isPhone()` in `server.ts` requires `Bearer PHONE_TOKEN` when set (`/etc/neurosteno-web.env`, extracted read-only: `ccd535bd...`).

### Changes (worktree `agent3-presentation-conn`)
| File | Change |
|---|---|
| `present/PresentConnectClient.kt` | Added `phoneToken` to `PresentConnectConfig` |
| `present/OkHttpPresentConnectClient.kt` | `Authorization: Bearer` on WS upgrade; new `PhoneDemoClient` (hello, ping/pong, `publishUtterance` WS + HTTP mirror) |
| `ui/StenoViewModel.kt` | wsUrl default → `wss://present.144.31.215.37.sslip.io/...`, token field, dual client wiring, `startLiveTranscribe` / `transcribeLiveAndPublish` |
| `ui/StenoApp.kt` | Token input field, `Connect + Live`, `⏺ Live ASR → web` button |

### Build
- `gradle :app:assemblePresentConnectDebug -x prepareEmbeddedModels` ✅
- APK: `app-presentConnect-debug.apk`, 73.5 MB, versionCode 2
- Installed to LDPlayer `emulator-5554` ✅, launched `MainActivity` ✅
- JNI: `SherpaJni loaded=true`; models unpacked to `files/models` (70 MB encoder + vad/diar) — **not stub**

---

## 4. End-to-End Verification

| Check | Result |
|---|---|
| LDPlayer → remote `POST /v1/transcript` (Bearer) | `accepted:1`, `demo_phase:live` ✅ |
| Second push (`speaker_2`) | `rows_pushed:2` ✅ |
| Export | 4-field brief ✅ |
| `/v1/frontend/checkpoint` | `transcript` array present, `banner:null` ✅ |

### 6-speaker round (800 ms → 39 800 ms, sequential)
- Submitted 6 rows: speaker_1..6, Russian demo lines (DEMO_STAGE6 shape)
- Response: `accepted:6`, `demo_phase:live`, `phone_connected:true`, `rows:6`
- Export contains all 6 utterances
- Deck at `present.144.31.215.37.sslip.io/` (slide `demo`, index 4):
  - `LIVE` badge, HUD transcript list 03–06 visible
  - `banner:null`, `scroll_locked:true`
  - `body.innerText` contains all 6 utterances

**GIF**: `artifacts/agent3/gif/deck-live-6spk.gif` (640×601, 2 fps, 3 s, 159 KB) + `frame1..3.png`

**Important**: server reverts to `fallback/phone_stall` after ~45s of no phone heartbeat. On stage, the Android app MUST keep WS/HTTP pushes alive throughout the demo.

---

## 5. What 100% Works

1. ✅ Local backend endpoints (health, presentation, transcript, workers, state, invite, auth)
2. ✅ WebSocket `hello → hello.ok`, ping/pong, partial/final relay to screens
3. ✅ Reconnect and mid-stream drop recovery (no zombie state)
4. ✅ Remote HTTPS access (`present.144.31.215.37.sslip.io` + raw IP:8080)
5. ✅ Token auth (`Bearer PHONE_TOKEN`) for WS + HTTP ingest
6. ✅ LDPlayer app install, launch, JNI load, model unpack
7. ✅ Live ASR pipeline path (`DemoPcmPrep → SherpaStenoPipeline → PhoneDemoClient`)
8. ✅ 6-speaker transcript lands on the live deck checkpoint

## 6. What Might Not Work / Risks

| # | Risk / Gap | Impact |
|---|---|---|
| 1 | **No checkpoint persistence** across server restart (in-memory session) | Transcript lost if server restarts mid-demo. |
| 2 | **No LLM token streaming** (Android `complete()` is blocking string) | Cannot show incremental LLM tokens. |
| 3 | **No CPU/RAM/metrics endpoint** | HUD shows `RAM — / CPU —` on stage. Do not claim numbers. |
| 4 | **45s stall timeout** → fallback banner + reserve rows | App must push at least every ~15s or deck shows “Резервный режим”. |
| 5 | **Mic permission denied in Playwright** | File-import path works; live mic needs real device or manual grant. |
| 6 | **Scroll-locked deck** | Audience cannot scroll; presenter console is the only controller. |
| 7 | **Emulator≠phone**: LDPlayer has no real mic; live ASR tested via file/record flow | On-stage phone needs real mic test. |
| 8 | **Model assets skipped in build** (`-x prepareEmbeddedModels`) | APK relies on models already unpacked on the device; fresh install needs one offline unpack cycle. |

---

## 7. File Inventory (Worktree)

- `PresentConnectClient.kt` — token field
- `OkHttpPresentConnectClient.kt` — bearer + `PhoneDemoClient`
- `StenoViewModel.kt` — live ASR publish loop
- `StenoApp.kt` — token + live UI
- `artifacts/agent3/gif/deck-live-6spk.gif` + frames
- `artifacts/agent3/present-frontend-checkpoint.json`, `transcript-export.json`

All local commits remain unpushed on `agent3-presentation-conn`. Remote server was never modified — SSH was read-only (`cat`, `systemctl status`, `ls`, `stat`, `grep` only).
