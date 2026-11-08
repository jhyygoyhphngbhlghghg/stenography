# REPORT — branch `agent3-product-features` (worktree wt-a5, HEAD 7912c20)

Written by the successor session after the product-feature engineer session died mid-edit
(upstream rate-limit). The dead session's uncommitted edits (a cosmetic refactor of the
live-interim placeholder text in `StenoViewModel.kt` / `LiveFeed.kt`) were lost with its
worktree; HEAD 7912c20 is intact and this audit was done against it. No code changes were
needed (see audit below), so nothing code-wise was committed by the successor.

## 1. Features per commit + verification status

### 9bc4f0c — "QOL: transcript reader pack"
- `pipeline/TranscriptView.kt`: merge adjacent same-speaker turns, transcript stats.
- `ui/StenoApp.kt` / `ui/StenoViewModel.kt`: merge toggle, show-all toggle, error hide, file size, utterance retime.
- Tests added: `TranscriptViewTest.kt` (4 @Test).
- Verification (dead session export): gradle `:app:testStenographerDebugUnitTest` BUILD SUCCESSFUL after this commit (session msg 41).

### 9c37f74 — "Product features: export pack, LLM summary/QA, diary, rename, live windows, tabs"
- `export/TranscriptExport.kt`: TXT / SRT / WebVTT / CSV / DOCX (in-memory OOXML zip) / PDF (hand-rolled, no new deps).
- `export/Diary.kt`: save/load/delete transcript+summary diary entries.
- `llm/TranscriptLlm.kt`: on-device GGUF summarize / ask-about-transcript.
- `pipeline/LiveFeed.kt`: 5 s non-overlapping interim windows for the live view.
- `ui/`: tabs, speaker rename (in-state map applied via `refreshTranscriptPreview`).
- Tests added: `ExportTest.kt` (7), `TranscriptLlmTest.kt` (5), `LiveFeedTest.kt` (3).
- Verification: one test failed mid-development (session msg 125: "7 tests completed, 1 failed"), was fixed, then gradle BUILD SUCCESSFUL (msg 149, 173).

### 1c2c384 — "UI: clear copy, numbered steps, single ready line, tab order is workflow"
- UI copy cleanup; `AssetModelUnpack.kt` adjust + `AssetModelUnpackTest.kt` (6 @Test).
- Verification: gradle test+assemble BUILD SUCCESSFUL (session msg 194).

### 7912c20 — "UI: LIVE entry point - top-bar status, primary LIVE-record button"
- `ui/StenoApp.kt` only (+10/−2): top-bar `● LIVE` / `○ готово` status, primary LIVE-record button.
- Verification: gradle test+assemble BUILD SUCCESSFUL (session msg 214), then emulator run on emulator-5554 (~45 adb interactions): record → transcribe end-to-end with `alternating_two_voices.wav`, progress and result checked via screenshots (session msgs 220–233).

Unit-test inventory at HEAD: 27 test classes, 166 @Test methods under
`src/android/app/src/test/java/team/mikcoders/neurosteno/`. Last full gradle unit-test pass at HEAD: BUILD SUCCESSFUL (dead session, msg 214). Successor session ran NO gradle (no code change made; constraint: at most one run).

## 2. Integrity audit — msg219 "test integrity, it needs to capture speech whole": CLOSED, no speech-loss bug

Verdict: the final transcript is produced from the FULL recording; the 5 s live interim feed is
display-only and can never replace or truncate it. No fix required → no code commit.

Evidence (all paths relative to `src/android/app/src/main/java/team/mikcoders/neurosteno/`):

1. Final transcript source = whole recording.
   - `ui/StenoViewModel.kt:352` — `pipeline.transcribe(prep.audio)` is called on the complete prepared PCM; the returned `result` becomes `state.transcript` at `ui/StenoViewModel.kt:367–377`. Nothing else ever writes `transcript`.
2. Interim feed is a separate, placeholder-only UI channel.
   - `ui/StenoViewModel.kt:353–364` — `liveInterim` is built from `LiveFeed.interim(emptyList(), windows.take(n))`: placeholder rows ("окно N: речь не распознана"), never real text, never merged into `transcript`.
   - `ui/StenoApp.kt:354` — interim rows render only while `state.running == true`; when transcription finishes, `running=false` and the full final transcript renders (`StenoApp.kt:353, 362`). Stale interims cannot outlive the run.
   - `pipeline/LiveFeed.kt:5–8` — doc: "interim rows are replaced by the final full-file transcript (single source of truth unchanged)". Windows are contiguous and cover `[0, durationMs)` exactly (`LiveFeed.kt:24–34`; unit-proven by `LiveFeedTest.windowsCoverDuration`).
3. Diarization runs over the FULL audio at transcribe time.
   - `pipeline/SherpaStenoPipeline.kt:152` — `diar.process(audio.samples)` on the entire sample array (not on windows/interims).
4. Prep stage is length-preserving (no trimming).
   - `audio/FarFieldGain.kt:57` — `val out = samples.copyOf()` (in-place gain on a full copy).
   - `audio/PhoneDistanceEq.kt:55` — same pattern; EQ writes slices back into a full-size copy.
   - `pipeline/DemoPcmPrep.kt:36–52` — passes `eq.samples` (same length) into `PcmAudio`; hard-skip only fires on CLIPPED/BAD_MIC/empty raw PCM (deliberate gate, reported as error, not silent loss).
5. Long recordings: ASR crop bound splits, never drops.
   - `pipeline/AsrWindowing.kt:44–58` — segments >30 s are split into contiguous windows; the last window ends exactly at `endMs` (line 49–50), cuts land on quietest frames; coverage is gapless (`start = cut` loop).
   - `pipeline/SherpaStenoPipeline.kt:261–270` (`transcribeCrop`) and `413–416` (`crop`, indices coerced into array) — every window's text is decoded and joined; whole-file fallback path also windowed (`SherpaStenoPipeline.kt:350–358`).
6. Recording capture has no tail loss; no pause/resume feature exists (start/stop only).
   - `audio/MicRecorder.kt:33–46` — capture thread appends every `rec.read` result to the buffer while running.
   - `audio/MicRecorder.kt:49–55` — `stop()` sets the flag, `join(2000)` waits for the reader thread (one read is ~100 ms at 3200-byte buffers), then snapshots the buffer: the final in-flight chunk is included.
   - `ui/StenoViewModel.kt:294–309` — `toggleRecord()` stores the complete PCM into `heldPcm`; `transcribe()` (`:318–322`) feeds exactly that `heldPcm` to the pipeline.

Caveats (cosmetic, NOT speech loss, deliberately left unfixed):
- Interim progress mapping `upto = p.fraction / 0.6f` (`StenoViewModel.kt:356`) vs actual ASR fraction range 0.3–0.9 (`SherpaStenoPipeline.kt:287`) means placeholder windows appear starting at mid-ASR. Display-only.
- `VadDiarReconcile.run` (`VadDiarReconcile.kt:55–58`) drops diar segments fully clipped inside the first (near) segment — dedupe of overlapping diar output for time already covered by the near segment; every VAD region is otherwise adopted/snapped (`VadDiarReconcile.kt:41–53`). Behavior validated by `VadDiarReconcileTest` (8 @Test) and commit 1906eaa.
- `liveInterim` is not cleared from state at completion, but is unreachable in UI due to the `running` gate (`StenoApp.kt:354`).

The dead session was mid-edit trying to rework exactly this cosmetic interim-derivation (its `edit` calls on `StenoViewModel.kt` errored out; the one applied `LiveFeed.kt` edit only changed placeholder wording). Losing those edits costs nothing functional.

## 3. OVERLAP WARNING for the integrator — github/main commit 4d1e0e4

`4d1e0e4` "feat(app): multi-format export, transcript Q&A, speaker names, presentation mode"
(Artem, Nov 7 14:27) sits in `github/main` history (merge-base with this branch: `8eb4bb5`).
It DUPLICATES parts of this branch and touches the same files (`export/TranscriptExport.kt`,
`ui/StenoViewModel.kt` +191, `ui/StenoApp.kt` +214, `pipeline/StenoPipeline.kt`) — a merge
will conflict hard. The integrator must pick ONE side for the overlapping Android features.

Recommendation: take THIS branch (`agent3-product-features`) for the Android overlap:

| Aspect | 4d1e0e4 (github/main) | agent3-product-features (this branch) |
|---|---|---|
| Export formats | SRT, TXT, CSV, DOCX (148 lines) | TXT, SRT, WebVTT, CSV, DOCX, PDF (213 lines) |
| Android unit tests for the overlap | NONE (0 test files in the commit; its only tests are web backend `web/backend/test/server.test.ts`) | ExportTest (7), TranscriptLlmTest (5), LiveFeedTest (3), TranscriptViewTest (4); full suite 166 @Test, gradle pass green at HEAD |
| Q&A | `llm/TranscriptQa.kt` (110 lines: ask/summarize/extractKeyPoints) | `llm/TranscriptLlm.kt` (75 lines: summarize/ask) + tests |
| Speaker names | `ui/SpeakerNames.kt`, persisted in SharedPreferences | in-state map + `refreshTranscriptPreview` (no persistence) |
| Extras | presentation mode, web deck (`deck.js`, `server.ts`), `llmRunner` on `StenoPipeline` | diary, live 5 s interim feed, transcript merge/stats/retime, UI workflow copy, LIVE entry point |

This branch is more complete (superset of formats incl. VTT/PDF) and the only side with Android
unit-test coverage and an emulator-verified end-to-end run for these features. Suggested
salvage from 4d1e0e4 (cherry-pick pieces, not the commit): speaker-name SharedPreferences
persistence and the web presentation/deck work, which this branch does not touch.

## 4. Constraints honored
- Only wt-a5 touched; no push; no emulator; zero gradle runs by this session (none required — no code fix).
- Pre-existing unstaged whitespace-only diff in `Verify_repo_GitHub_readiness_2026-11-07_22-15.md` (not from either product session's code work) left untouched and NOT committed.

---

# AGENT 4 — WEBSITE: SYNC + QR ENTRY — full report

Branch: `agent4-web-sync-qr` (worktree `wt-a4`, base `1906eaa`).
Scope: prove realtime sync app ↔ server ↔ site, QR entry from emulator, QR-on-first-slide fix, tokens default, redeploy.
Server SSH keys are now REVOKED — everything below marked "live-verified" was re-checked read-only over HTTPS on 2026-11-08 from this worktree; no further server changes are possible or were attempted.

## 1. What was tested (local, while keys were valid)

Environment: no physical phone. Isolated AVD `emulator-5554` (1600×900, Android 14, Chrome 124.0.6367.82) + desktop browser (Playwright). Local backend: `web/backend` (Node/TS, ws+http) started on port 18084 via `cmd start`; nothing else touched.

### Pages / endpoints (all HTTP 200 locally)
- `/` (built-in deck, 14798 B), `/presenter`, `/join`, `/stage`
- static: `/css/style.css`, `/js/deck.js`, `/vendor/anime.js`
- presenter console login via `ADMIN_TOKEN` cookie — works on emulator Chrome and desktop Playwright
- `POST /api/login {"token": ...}` → 200 with correct token, 401 with wrong token (verified on server after token change)
- `/api/invite` → join URL; health endpoint per session

### Sync (proven live, no manual refresh)
- App → site: `POST /v1/transcript` — **78 ms**. Emulator Chrome on `/join?session=...` updated HUD within seconds without reload: «телефон: на связи» → «телефон: запись» (recording flag), «демо: в эфире», session chip `f24774`. `GET /v1/sessions/<id>/transcript` returned pushed rows exactly (2× rows, `rows_pushed=2`).
- Site → app: `POST /v1/status` — **77 ms**; `phone.recording=true` reflected in deck HUD in <5 s.
- Checkpoint navigation: presenter `checkpoint.set index=5` → presentation API `slide_index:5 slide_id:live revision:3`, emulator deck counter `06 / 09`, seat ring speaker_1…6 rendered.
- Transcript, speakers, recording status: verified both directions. Summary: backend has **no summary endpoint by design** (only transcript/speakers/status/checkpoint) — N/A, not a bug.
- Cross-device: desktop + emulator simultaneously on same `/join?session=...` — both stay live, no conflict (`broadcastScreens()` fans out to all sockets).

### QR entry from emulator — 10/10 with a caveat
- QR generated in presenter console (`console.js drawQr()` + `/api/invite`). Extracted URL example: `http://192.168.31.55:18084/join?session=sess_3738f9479d58`; opened via `adb am start ... com.android.chrome` — correct session/page, no 404, no redirect, clean browser (no cookies).
- Session rotate → new QR URL (`sess_9fb7e67d8eda`) — QR re-encodes session id correctly.
- 10/10 loop: the underlying URL was reachable 10/10 (curl from emulator: 200 every time). Full UI landing in Chrome succeeded 6/10 under coordinate automation because Chrome **First-Run flow** (TabbedModeFirstRunActivity / MiniMe chooser) intercepts each clean-clear; where the dismiss tap aligned, `ChromeTabbedActivity` loaded the join page. The flakiness is Chrome first-run UX, not the site.

### Mobile layout (emulator screenshots)
Title slide text fully visible; presenter login input + «Войти» visible; checkpoint buttons 01–09 visible; QR canvas + URL visible; app buttons «Импорт/Запись/Расшифровать» visible at 1600×900. One cosmetic: live-slide seat labels overlap slightly at compact width. `?free=1` and locked-scroll both work.

### Bug fixed during testing
One desync bug in `server.test.ts`: `waitFor` matched the stale `phone` frame from `hello` (recording:false) before the `phone.status` update. Fixed the test to match `recording===true`. `npm run typecheck` exit 0, `npm run build` exit 0, `npx tsx --test` 9/9 pass. No production code changed locally at that stage.

## 2. Server-side changes made while keys were valid (all LIVE)

Production layout: `/opt/stenography/repo/src/web/backend` (`node dist/server.js`, systemd `neurosteno-web.service`, User=steno, port 8080, nginx → `present.144.31.215.37.sslip.io`). Presentation at `/` = built React (TanStack) deck from `src/web/present/dist/client`; first checkpoint is the QR slide (`qr → team → case → tester → demo → tech → solutions → end`).

1. **QR fix** — `/opt/stenography/repo/src/web/present/src/components/deck/Deck.tsx` line ~100:
   `QRCode.create(window.location.origin + window.location.pathname, …)` → `… + window.location.search`.
   Before: QR on the first slide encoded bare `/` (join params dropped). After: QR encodes the full join URL including `?session=...`.
2. **Tokens default `09879078`** — repo defaults `src/web/backend/src/config.ts`: `adminToken: str('ADMIN_TOKEN','09879078')`, `phoneToken: str('PHONE_TOKEN','09879078')` (were `''`), compiled into `dist/config.js`; **and** live env `/etc/neurosteno-web.env` set to `ADMIN_TOKEN=09879078` / `PHONE_TOKEN=09879078` (env overrides code defaults — both were needed; stale env was why defaults initially didn't take).
3. **Redeploy** — `npm run build` backend ✓, `npm run build` present (vite, new hashed routes chunk) ✓, `systemctl restart neurosteno-web.service` ✓.
4. Local-only (not uploaded, production uses the React deck): `neurosteno-web/web/public/index.html` built-in deck got a QR panel on first screen + `qrcode.js`.

## 3. Repo state after this session (branch `agent4-web-sync-qr`)

The QR fix existed only on the server — `github/main` (`cca7892`) still had `origin + pathname` without search, and local HEAD (`1906eaa`) had no `src/web` at all. Ported here:

- `fc28b9c` — port `src/web` tree (backend + present deck) from `github/main` `cca7892` verbatim.
- `4c5dd6e` — **QR fix in repo**: `src/web/present/src/components/deck/Deck.tsx` — QR payload now `origin + pathname + search`, identical to the deployed live fix. This is the only QR generation site in the repo (`deck.js`/`console.js` on `github/main` do not generate QR in the React flow; grep for `QRCode.create` finds exactly one hit).
- `d0210b3` — **token defaults in repo**: `src/web/backend/src/config.ts` — `ADMIN_TOKEN`/`PHONE_TOKEN` defaults `''` → `'09879078'`, matching the live server. Backend tests pass tokens explicitly via the hermetic harness, so defaults don't affect the suite.
- The `server.test.ts` desync fix (match `recording===true`) is already present in `github/main` — no port needed.
- No backend build was run for this port (constraint: prefer none; Deck tests do not assert the QR payload string, they assert slide ids/classes).

## 4. Current live state of https://present.144.31.215.37.sslip.io/ (re-verified read-only, 2026-11-08)

- `/` → 200 (SPA shell, assets `index-C-eOlGmu.js`, `routes-BR_HqwxI.js`, …)
- **QR fix is live**: built bundle `routes-BR_HqwxI.js` contains `…create(window.location.origin+window.location.pathname+window.location.search,{errorCorrectionLevel:"M"})…`
- `/v1/health` → 200 `{"ok":true,"session_id":"sess_ae2be978110d","demo_phase":"idle","slide_index":0,"slides":8,"screens":2}`
- `/v1/presentation` → 200, `slide_id:"qr"` at index 0 — first slide is the QR slide
- `/api/invite` → 200 `{"join_url":"https://present.144.31.215.37.sslip.io/join?session=sess_ae2be978110d","slides":["qr","team","case","tester","demo","tech","solutions","end"]}`
- `/join?session=sess_ae2be978110d` → 200; `/presenter` → 200
- Note: `/api/health` → 404 (health lives at `/v1/health`, always did)

## 5. 100% works vs might-not-work

**100% works (verified):**
- Site serves all pages locally and in production; SPA + static assets 200.
- Realtime sync app→site and site→app without refresh: transcript push 78 ms, status push 77 ms, HUD/checkpoint/speakers/recording flag propagation <5 s.
- Presenter console login via `ADMIN_TOKEN` cookie; `POST /api/login` 200/401 semantics.
- QR join URL correctness: encoded URL opens the right session, no 404/redirect, clean browser; new session → new QR.
- QR-on-first-slide now encodes the full join URL with `?session=...` — fix live on server and committed in repo (`4c5dd6e`).
- Tokens: phone + admin = `09879078` (code default AND live env).
- Cross-device parallel viewing (desktop + emulator) with no conflict.
- Backend test suite 9/9 after the `server.test.ts` desync fix.

**Might not work / risks for the stage:**
- Fresh Chrome profile on a phone can hit the First-Run/chooser flow before landing on the join page (emulator: 6/10 clean UI landings, 10/10 URL reachability). Mitigation on stage: pre-initialized Chrome or in-app WebView; QR/URL itself is correct.
- Session on server is `sess_ae2be978110d` with `phone_connected:false`, `demo_phase:idle` — the app must be connected and recording started before the demo flow shows live transcript; no summary endpoint exists (by design — don't promise a summary screen).
- Cosmetic: live-slide seat labels slightly overlap at compact width.
- Server is now immutable for us (keys revoked): any further change (e.g., session rotate, service restart) requires someone with server access. If `neurosteno-web.service` dies, nobody on this team can restart it.
- `github/main` itself still lacks the QR fix and the token defaults — integrator must merge `agent4-web-sync-qr` (or cherry-pick `4c5dd6e` + `d0210b3`) before any redeploy from the repo, or the live fixes regress (live env `/etc/neurosteno-web.env` masks the token regression until the env file is touched, but a repo-only redeploy of the deck would drop the QR fix).

---

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

---

# AGENT 1 (MOBILE APP — FRESH BUILD + EMULATOR E2E) — Reconstruction Report

Branch: `agent1-mobile-e2e` @ `1906eaa8edfd22d46a43e0029d214eb5f749c8e5`
Reconstructed: 2026-11-08, from the dead session export
`C:/Users/KMService/Downloads/new-session---2026-11-07t22-02-10-525z.json`
(294 messages; session ran 2026-11-07 22:02 UTC → 2026-11-08 ~04:28 UTC / ~07:28 +03:00, died mid-tool-call).

## 0. Did agent1 leave code changes? Where did they land?

**No code changes, no commits.** The session used only `read`, `bash`, `glob`, `todowrite` tools — zero
write/edit/patch calls, zero `git commit`/`git add`. Commits `bd7b5dd`, `3af1e5a`, `1906eaa` all
pre-date the session (`1906eaa` committed 2026-11-08 00:24 +03:00; session started 01:02 +03:00) and
are **not** agent1's fixes — they are the pre-existing baseline agent1 branched from
(`git checkout -b agent1-mobile-e2e` at `1906eaa`, session msg #15). The branch was never advanced.

The only repo writes agent1 made were test artifacts under `artifacts/eval/demo-stress/`
(`DEMO_STAGE6_agent1_e2e.json` etc. plus regenerated metrics); agent1 itself reverted them
(`git checkout -- artifacts/eval/demo-stress/`, `Remove-Item ...agent1_e2e*`, msgs #80–#83), leaving
the tree clean. **No uncommitted code edits were lost** — nothing was ever edited.

Note: this worktree currently shows a pre-existing whitespace-only (trailing-space/CRLF) local
modification in `Verify_repo_GitHub_readiness_2026-11-07_22-15.md`. It is not agent1's work and was
left unstaged.

## 1. What was tested, results, evidence

All evidence below: session log (msg indices in brackets) + surviving files in
`C:\Users\KMService\AppData\Local\Temp\opencode\` (screenshots/JSON pulled from the emulator).

1. **Clean fresh build** at `1906eaa`: `clean :app:assembleStenographerDebug` BUILD SUCCESSFUL;
   APK `app-stenographer-debug.apk` 508,675,346 B, versionName 0.2.0 / versionCode 2,
   SHA256 `5C9E9D761AA1F46564D96E03087F7E9F68291DC127C09DFBE763635794367789`, reproduced identically
   by two independent clean builds [27][28][282]. Incremental build produced a *different* hash
   (`2A584A53…`, 508,812,244 B) [281].
2. **Unit tests**: `:app:testStenographerDebugUnitTest` — 24 suites, 145 tests, 0 failures, 0 errors,
   run twice [26][280].
3. **Emulator**: isolated official AVD `emulator-5554` (x86_64) created/cold-booted; LDPlayer
   `127.0.0.1:5555` used earlier then superseded [15–17][178–183]. Install (streamed) + launch OK,
   no crash on start, `MainActivity` focused, `RECORD_AUDIO` granted via `pm grant`
   [39][100–101][283][287]. Screens: `agent1_clean.png`, `emu_t390.png` (idle UI, tabs
   Транскрипт/Резюме/Вопросы/Дневник all render).
4. **Import E2E (RU)**: WAVs pushed to device (`E2E-2SPK_RU.wav`, `E2E-3SPK_RU.wav`,
   `alternating_two_voices.wav`, `replicas_one_voice.wav`, `long_monologue_200s.wav`) [96][127];
   SAF/DocumentsUI import flow driven by taps (`emu_PR2.png`); on-device pipeline ran ASR then
   `OfflineSpeakerDiarization K=6`, progress reached the LLM post-pass stage
   (`llama.cpp GGUF qwen2-0_5b-instruct-q4_0.gguf`) — `done_s2.png`, `rep_n2.png`, `a1_f2.png`,
   `a1_f3.png`. **The final on-device transcript was never captured**: every run was still inside
   the LLM phase after 90–100 s+ waits when the session died [288–292].
5. **LIVE recording**: 3+ min run completed to `ГОТОВО` without crash; all 5 s windows reported
   «речь не распознана» because the emulator mic captures silence — `done_s2.png` [262–266].
6. **Stability**: repeated force-stop/relaunch, background/foreground (`am start` re-delivery),
   240–390 s soak screenshots `emu_t240/t330/t390.png`, `dumpsys meminfo` ~64 MB PSS [171][199–215].
7. **Host-side cross-check of the same audio** with `scripts/host_asr_diar.py`:
   `E2E-2SPK_RU_host.json` (2 RU segments, correct text), `alt_host.json` (4 segments),
   `DEMO_STAGE6_hostdefault.json` / deleted `DEMO_STAGE6_agent1_e2e.json` [46–48][75][95].

## 2. What is 100% working (proven)

- Clean reproducible Gradle build of the stenographer flavor at `1906eaa` (identical SHA256 twice).
- Full unit-test suite green (145/145).
- APK installs and cold-launches on an isolated emulator with no crash; permissions and all UI
  screens/tabs functional; SAF file import works.
- On-device offline pipeline executes end-to-end through ASR + speaker diarization into the LLM
  post-pass without crash or ANR, including 3+ min runs and relaunch/bg-fg cycles.
- RU speech-to-text content is correct (verified host-side on the same WAVs).
- LIVE record flow completes to a stable `ГОТОВО` state.

## 3. Known open issues found but NOT fixed (do-not-fix list for integrator)

1. **LLM post-pass stall on emulator**: import E2E never reaches the final transcript; UI sits at
   ~90 % progress in the `llama.cpp qwen2-0_5b` phase for many minutes, `Расшифровать`/`Сохранить
   JSON` disabled. On-device multi-speaker transcript therefore **unproven on emulator** (host-side
   proof only). Possibly emulator-CPU-bound, possibly a real hang — undiagnosed.
2. **Diarization label churn / mis-segmentation**: DEMO_STAGE6 (3 ref speakers) → 5 speaker IDs incl.
   a 202 ms empty-text segment; `alternating_two_voices` (2 speakers) → first block labelled
   `speaker_3`, rest `speaker_9`; `E2E-2SPK_RU` (2 speakers) → merged into a single `speaker_1`.
3. **Long audio diarization failure**: 200 s `bench_long.wav` → pyannote "No speakers found in the
   audio samples", 0 segments (host).
4. **EN / code-switching untestable**: the repo tracks no ASR models (`wt-a1/models` is empty,
   `apk/` holds only a README); on the host checkout the untracked `models/asr/whisper-tiny/` dir
   (the backup-ASR slot, `ModelStore.hasBackupAsr`) was **empty** while `zipformer-ru-int8` was
   populated [99] — so only RU zipformer exists and EN recognition is unavailable/unproven.
5. **Incremental APK packaging is non-deterministic** (different hash/size vs clean build); clean
   builds are reproducible.
6. Minor/test-harness: `uiautomator dump` fails on device ("UiAutomationService already registered");
   `android.vending` I/O errors reading base.apk in logcat (Play-scanning noise); emulator mic
   silence makes LIVE transcription vacuous.

## 4. What was abandoned mid-flight

Session died at ~07:28 +03:00 while polling the final fresh-APK import run (`a1_f3.png`, 07:25).
Left unfinished: capturing the completed on-device transcript/JSON export, pause/resume checklist
item, and the hand-in summary. No partial work of theirs remains in any git tree.
