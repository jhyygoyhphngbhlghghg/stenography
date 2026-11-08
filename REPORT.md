# Agent3 Full Report — presentation connectivity (inherited-session rollup)

- Branch: `agent3-presentation-conn` (worktree `E:/Projects/neuro-stenographer/neuro-stenographer-agent3`)
- Base: `1906eaa` (2026-11-08 00:24 +0300). Code rollup commit: `a9497e9` "present-connect: finish client wiring (inherited session rollup)"
- Emulator: LDPlayer `emulator-5554` (agent3-owned, SOV44). Hosted deck: `https://present.144.31.215.37.sslip.io/` (read-only use; server never modified)
- This report supersedes/completes `artifacts/agent3/REPORT.md` (written mid-session, contains one overclaim — see §5).

## 1. Scope of work (session phases)

1. **Server phase (msgs 1–73)**: endpoint/checkpoint/streaming/metrics testing of the web backend, local and remote (details in `artifacts/agent3/REPORT.md` §1–2: all HTTP endpoints, WS hello/partial/final relay, reconnect + mid-stream-drop recovery, no checkpoint persistence across restart, no LLM streaming endpoint, no CPU/RAM metrics, 5-min load survived).
2. **Android phase (msgs 74–224)**: token-based connectivity from a SEPARATE presentation build (`presentConnect` flavor, applicationId `team.mikcoders.neurosteno.present`) to the hosted deck; live transcription + diarization push; 6-speaker test; GIF of the live deck.
3. **Report phase (msg 225+)**: session died (upstream 502) while finalizing; this rollup finished it.

## 2. Server contract (as verified against the running server)

- Protocol `present-connect/1`, phone role.
- WS: `wss://present.144.31.215.37.sslip.io/v1/present-connect?session=demo&role=phone&device=phone_1`, header `Authorization: Bearer <PHONE_TOKEN>` on the upgrade request; then `hello` → `hello.ok`, `ping`/`pong` (~15 s), `transcript.final` relayed/broadcast to screens.
- HTTP ingest: `POST /v1/transcript`, `Authorization: Bearer <PHONE_TOKEN>`, body `{"engine":"zipformer-ru-int8","transcript":[{"speaker_id","start_ms","end_ms","text"}], optional "reset":true}` → `{"ok":true,"accepted":N,"demo_phase":"live",...,"phone":{"rows_pushed":N}}`.
- Deck hydration: `GET /v1/frontend/checkpoint` (transcript + banner + scroll_locked). Export: `GET /v1/sessions/x/transcript` (4-field brief). Health: `GET /v1/health`.
- Presenter admin: `POST /api/command` with `Bearer <ADMIN_TOKEN>` (`checkpoint.set` used once to pin slide index 4 "demo").
- Tokens (from `/etc/neurosteno-web.env`, read-only): PHONE_TOKEN `ccd535bd…`, ADMIN_TOKEN `06e01325…` (truncated here on purpose; full values in the session log).
- **Stall rule**: if the phone stops pushing for ~45 s the server flips `demo_phase` to `fallback/phone_stall` and the deck shows «Резервный режим». During the demo the app must keep WS alive or push periodically.

## 3. APK provenance (CRITICAL — demo-recording candidates)

Two different `presentConnect` APKs were installed on LDPlayer `emulator-5554` during the session:

| # | Path | Bytes | sha256 | mtime | versionCode | Built from | Embedded models | Emulator fate |
|---|---|---|---|---|---|---|---|---|
| A ("wired") | `E:\Projects\neuro-stenographer\neuro-stenographer-agent3\src\android\app\build\outputs\apk\presentConnect\debug\app-presentConnect-debug.apk` | 73,527,938 | `90e03b7079510dde5baf2db88a027067a521cf199c26b8b2176216f59f1446b2` | 2026-11-08 06:39 | 2 (versionName 0.2.0) | `1906eaa` + the then-uncommitted wiring (now committed as `a9497e9`); `assemblePresentConnectDebug -x prepareEmbeddedModels` | **NO** (task skipped) → fresh install runs stub ASR | installed OK (session msg153), then uninstalled (msg160); never launched/verified |
| B ("on device") | `E:\Projects\neuro-stenographer\neuro-stenographer\apk\app-presentConnect-debug.apk` | 220,401,103 | `b9afd39f4ee9c7bd80b282e882df792fa911c0e935605285ee57e3490f980d83` | 2026-11-07 09:26 | 1 | main-repo state **predating** `bd7b5dd`/`1906eaa` — i.e. WITHOUT the token wiring and WITHOUT the latest diarization/native-session fixes | YES (~220 MB; JNI loaded, models unpacked to `files/models`, non-stub confirmed via logcat, msgs 161–165) | final APK installed (msg160) and launched; **still on the emulator** |

**Neither APK alone is demo-ready**: A has the wiring but no models; B has models but no wiring.
**Recommended demo-recording APK**: rebuild from `a9497e9` with models: `gradlew :app:assemblePresentConnectDebug` (do NOT pass `-x prepareEmbeddedModels`). That produces A+B combined; expect ~220 MB.

### What was proven live (and with what)

Proven with **curl from the LDPlayer network namespace (`adb shell curl`) and from the host** — i.e. the emulator's connectivity + the server contract, NOT the app's UI:

- `POST /v1/transcript` with Bearer PHONE_TOKEN from LDPlayer: `speaker_1` "Test from LDPlayer token" → `{"ok":true,"accepted":1,"demo_phase":"live"}`; `speaker_2` "LDPlayer pushed live" → `rows_pushed:2`; both rows visible in `GET /v1/frontend/checkpoint`.
- 6-speaker round (speaker_1..6, Russian, 800→39 800 ms, `reset:true`): `accepted:6`, `demo_phase:live`, `phone_connected:true`, `scroll_locked:true`, slide `demo` (index 4).
- Deck render verified via Playwright: `body.innerText` contains all 6 utterances, LIVE badge, HUD rows 03–06.
- GIF: `artifacts/agent3/gif/deck-live-6spk.gif` (640×601, 2 fps, 3 s, 159,748 bytes; frames `frame1..3.png`).
- App itself (APK B): install, launch, JNI load, model unpack verified. **No transcript was ever pushed through the app UI.**

## 4. What the committed diff (`a9497e9`) adds vs the live-tested state

The live-tested state = server contract + curl pushes. The app on the emulator (APK B) has none of this code. `a9497e9` adds:

- `present/PresentConnectClient.kt`: `phoneToken` field on `PresentConnectConfig` (blank → no header).
- `present/OkHttpPresentConnectClient.kt`: `Authorization: Bearer` on the worker WS upgrade; new `PhoneDemoClient` — phone-role WS (`hello`/`pong`/error events) + `publishUtterance()` sending `transcript.final` over WS with an HTTP `POST /v1/transcript` mirror fallback.
- `ui/StenoViewModel.kt`: default wsUrl → hosted deck with `role=phone`; token state; dual-client connect/disconnect; `startLiveTranscribe`/`stopLiveTranscribe`; `transcribeLiveAndPublish()` runs `DemoPcmPrep → pipeline.transcribe` on held PCM and maps `speaker_N` → stream `s0N` → `PhoneDemoClient.publishUtterance`.
- `ui/StenoApp.kt`: PHONE_TOKEN input, "Connect + Live", "⏺ Live ASR → web" / "Stop live" buttons.

Verification of `a9497e9`: `:app:testPresentConnectDebugUnitTest` **BUILD SUCCESSFUL** (26 s, all present/pipeline unit tests green); Kotlin compilation of the wiring was already proven by the msg151–153 assemble.

## 5. 100% works vs might-not-work

### 100% works (evidence-backed)

1. Server contract end-to-end over the internet: Bearer-token HTTP ingest from LDPlayer and host → rows land on the hosted deck checkpoint and render (Playwright-verified).
2. 6-speaker transcript on the live deck + GIF artifact.
3. WS protocol behaviors (hello.ok, ping/pong, partial/final relay, reconnect, mid-stream-drop recovery) — server-phase tests.
4. presentConnect flavor builds, installs, launches on LDPlayer; JNI + model unpack with embedded-models APK (B).
5. The wiring code compiles and unit tests pass (`a9497e9`).

### Might NOT work (untested or known-fragile)

1. **App-UI publish path never exercised**: `PhoneDemoClient` WS `hello`→`transcript.final` and the "Live ASR → web" loop were never run from the app against the hosted deck (only curl was). Treat as unverified until one on-device run.
2. **NetworkOnMainThread risk (fallback path only)**: `PhoneDemoClient.publishUtterance()` HTTP mirror calls `execute()` synchronously; invoked from `viewModelScope` (Main). If the WS is down and the mirror fires, expect `NetworkOnMainThreadException`. Primary WS path (`ws.send`) is unaffected. Fix for integrator: make `publishUtterance` suspend + `withContext(Dispatchers.IO)`, or enqueue async.
3. **APK A has no models** (`-x prepareEmbeddedModels`): fresh install of A = stub pipeline. Rebuild with models before recording the demo.
4. **45 s stall → fallback banner**: app must keep pushing/WS-alive throughout the demo.
5. `startLiveTranscribe` requires `heldPcm` (a completed mic recording or imported file); the 600 ms ticker loop is a keep-alive placeholder, not a streaming mic feed. Live-mic streaming (record while publishing) is not implemented.
6. No server-side checkpoint persistence (restart loses session/transcript).
7. No CPU/RAM metrics endpoint — deck HUD shows `RAM — / CPU —`; do not claim numbers on stage.
8. LDPlayer has no real mic; on-stage phone needs a real-device mic pass.
9. Cosmetic: unused import `putJsonObject` and unused `tick` var in the rollup (warnings only); `Verify_repo_GitHub_readiness_2026-11-07_22-15.md` shows a 3-line trailing-whitespace/CRLF diff that survives `git checkout HEAD --` (normalization noise, deliberately left uncommitted).

**Correction to `artifacts/agent3/REPORT.md`**: its §5 item 7 ("Live ASR pipeline path (DemoPcmPrep → SherpaStenoPipeline → PhoneDemoClient) ✅") is an overclaim — that path was code-complete and compiled but never executed end-to-end from the app. Its §3 claim "models unpacked … not stub" refers to APK B (main-repo build), not the worktree APK A.

## 6. Artifacts (binaries stay gitignored)

- `artifacts/agent3/gif/deck-live-6spk.gif` — live deck, 6 speakers (binary, gitignored via `artifacts/agent3/.gitignore`)
- `artifacts/agent3/gif/frame1..3.png`, `artifacts/agent3/gif/frames/` — GIF source frames (gitignored)
- `artifacts/agent3/present-frontend-checkpoint.json`, `transcript-export.json`, `final-checkpoint.json`, `final-export.json` — server responses captured at test time (committed)
- `artifacts/agent3/REPORT.md` — mid-session report (committed, with the §5 correction noted above)
- Copies also exist in the main checkout at `E:\Projects\neuro-stenographer\neuro-stenographer\artifacts\agent3\` (session msg231).

## 7. Integrator handoff (carry to main)

1. Commit `a9497e9` (4 files) — the complete token-connectivity wiring; unit tests green.
2. **Rebuild the demo APK from `a9497e9` WITH models** (`:app:assemblePresentConnectDebug`, no `-x prepareEmbeddedModels`) — neither existing APK is demo-ready alone (see §3 table for hashes).
3. Known bug to fix before relying on the HTTP fallback: NetworkOnMainThread in `PhoneDemoClient.publishUtterance` (§5.2).
4. Operational facts: PHONE_TOKEN/ADMIN_TOKEN in `/etc/neurosteno-web.env`; 45 s stall rule; checkpoint not persistent across server restart.
5. One on-device smoke run (Connect + Live → push → see rows on deck) before the demo recording — the app-UI path is unproven (§5.1).
