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
