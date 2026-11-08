# SMOKE TEST — presentConnect APK → hosted deck (app-UI push)

- Date: 2026-11-08, 07:56–08:25 local. Tester: smoke-test agent. Emulator: LDPlayer `emulator-5554` (only device attached).
- Server: `https://present.144.31.215.37.sslip.io` (session `sess_ae2be978110d`, immutable — no SSH).

## VERDICT: FAIL (deterministic protocol bug in the shipped APK; app cannot register as phone, publish button stays disabled)

Root cause (no code was modified, per constraints): the app's WS `hello` sends `"models":"zipformer-ru-int8"` (JSON **string**), but the running server validates with zod `models: z.array(z.string()).optional()` (`src/web/backend/src/protocol.ts` L18). Server replies `{"type":"error","code":"BAD_MESSAGE","message":"Invalid input: expected array, received string"}` and never registers the phone. Consequences:
1. `/v1/health` `phone_connected` stays `false` (server sets it only inside the `hello` case, `server.ts` L258).
2. App never receives `hello.ok` → `presentConnected=false` (`StenoViewModel.kt` L201–219) → "⏺ Live ASR → web" button is `enabled = state.presentConnected && ...` (`StenoApp.kt` L148) → **disabled**; publish path unreachable from UI.

Exact bad lines: `src/android/app/src/main/java/team/mikcoders/neurosteno/present/OkHttpPresentConnectClient.kt` — `put("models", JsonPrimitive("zipformer-ru-int8"))` in `OkHttpPresentConnectClient.Listener.onOpen` (L87) and in `PhoneDemoClient.onOpen` (L285). Fix for integrator (one line each): send a JSON array, e.g. `putJsonArray("models") { add("zipformer-ru-int8") }` (non-empty, else server marks `phone_stub` and enters fallback). Then rebuild presentConnect APK. Everything else in the chain is verified working (below).

## APK under test

- Path: `E:/Projects/neuro-stenographer/neuro-stenographer/src/android/app/build/outputs/apk/presentConnect/debug/app-presentConnect-debug.apk`
- sha256: `b6afa2838e80cf0cd6787b0ffbccf7f53aaa21aef960ae01d39bda7d92270c6d`
- Size: 508,839,870 bytes (~508 MB, NOT the expected ~220 MB — it embeds models + Qwen2 GGUF; consistent with the 508 MB demo build size in APK_PROVENANCE memory)
- mtime 2026-11-08 07:56:28 (newer than 07:50 gate; size stable across a 60 s poll). Metadata: `presentConnectDebug`, applicationId `team.mikcoders.neurosteno.present`, versionCode 2, versionName 0.2.0. Built from branch `ship` (HEAD `0bb8fab`, clean worktree; wiring commit `a9497e9` included).

## Steps executed & results

1. sha256/size recorded (above). `adb -s emulator-5554 install -r` → **Success** (streamed install).
2. Package confirmed: `pm list packages` → `team.mikcoders.neurosteno.present` (and legacy `.neurosteno`). `pm grant RECORD_AUDIO` + `appops set ... allow` OK. `am start -n team.mikcoders.neurosteno.present/team.mikcoders.neurosteno.MainActivity` → displayed +4 s, focused. UI shows `JNI libsherpa-onnx-jni.so: loaded`, `Офлайн: Zipformer-ru + Silero VAD + diar (models в APK)` → models embedded, not stub.
3. WS url field pre-filled EXACTLY to contract: `wss://present.144.31.215.37.sslip.io/v1/present-connect?session=demo&role=phone&device=phone_1`. PHONE_TOKEN field empty → typed `09879078` via adb (verified in UI dump). Tapped "Connect + Live" (twice: initial + one retry after Disconnect, per step-6 rule). Result both times: app UI shows `BAD_MESSAGE: Invalid input: expected array, received string`; `/v1/health` polled 15×2 s + 8×3 s → `phone_connected:false` throughout. Token itself is VALID: from host, `POST /v1/transcript` with `Bearer 09879078` → 400 schema-only error, with `Bearer WRONGTOKEN123` → 401 (zero-row probe, no rows added). WS upgrade with token also succeeded (error came AFTER upgrade, from hello validation). Screenshot: `artifacts/smoke_presentconnect/app_bad_message.png`; logcat: `artifacts/smoke_presentconnect/logcat_connect_attempt.txt`; health: `artifacts/smoke_presentconnect/health_after_connect.json`.
4. Audio (acoustic path per expert checklist): tapped LIVE-запись, played `samples/demo-stress/stage6/DEMO_STAGE6.wav` (39.7 s Russian dialogue) OUT LOUD on host via `ffplay -volume 100`, stopped → app holds `Файл: mic 43000 ms` (AudioRecord worked on LDPlayer). Publish step ("⏺ Live ASR → web") impossible — button disabled (root cause above). As a substitute, "Расшифровать" (offline ASR on the held mic PCM) was started at 08:15 to test whether the mic actually captured the dialogue — result appended below if it completed before the 08:50 hard stop.
5. Deck rows FROM THE APP: before = 0 (`rows_pushed:0`, `transcript:[]`), after = 0. `GET /v1/sessions/demo/transcript` → `{"transcript":[]}`. Headless Chromium (playwright) deck screenshot: `artifacts/smoke_presentconnect/deck_state.png` — deck renders (LIVE badge, slide 01/08 QR, «пока тишина» = zero rows). No row ever landed from the app.
6. Logcat captured (path above). No OkHttp/TLS/DNS failures — network path is fine; failure is purely the hello schema. Note: `PhoneDemoClient` swallows server `error` frames into the UI error field (TAG "PhoneDemo" logs only onFailure, which never fired).

## Server-side proof the rest of the chain would work (read-only code inspection, NOT executed claims)

- `WorkerTranscriptFinalSchema` (`protocol.ts` L30–34) exactly matches what `PresentConnectWire.transcriptFinal` emits (`PathBSession.kt` L147–157): `{type, stream_id?, utterance:{speaker_id,start_ms,end_ms,text}}` → once hello is fixed, `publishUtterance` WS sends will be ingested (`server.ts` case `transcript.final` → `ingestUtterances` → broadcast).
- WS send succeeds while socket open → HTTP mirror (NetworkOnMainThread risk, REPORT §5.2) does not fire on the happy path.

## Secondary note (clearly labeled — NOT part of the verdict)

Token/contract sanity checks were done from the host with curl (`POST /v1/transcript` empty-array probe: 400 with `09879078` vs 401 with wrong token; zero rows pushed). No curl transcript rows were pushed at any point; the app-row claim is not substituted by curl.

## Offline mic-ASR probe (Расшифровать on the 43 s held recording)

- Run 2 (first run's heldPcm was lost when a BACK keypress finished the activity — ViewModel cleared): LIVE-запись 42 s while `ffplay -volume 100 DEMO_STAGE6.wav` played on host → `Файл: mic 43000 ms` held → Расшифровать tapped 08:19.
- Observed: pipeline ran hot for ~19 min wall (2 cores pinned, app CPU-time 3:33→19:16+, RES 358 MB), UI entered the ASR interim view («Live (окна по 5 с):» label appeared); no interim/final text was ever captured in UI dumps; run ended (CPU idle) by 08:38 with no visible utterance count.
- Result lost before reading: a BACK keypress (to dismiss a stale export dropdown) finished the activity again → ViewModel cleared → Транскрипт tab unrecoverable at 08:39 (deadline guard). **Probe INCONCLUSIVE**: mic AudioRecord works (43 s held both times) and the ASR pipeline runs on this APK, but whether the LDPlayer mic actually captured the host speaker audio could not be confirmed (consistent with REPORT.md §5.8 "LDPlayer has no real mic"). On a real phone (expert-checklist stage) this is expected to work; on this emulator it is unproven.
- Timing note for the demo: even offline, a 43 s recording did not finish the full pipeline (ASR + GGUF punct) within ~19 min on LDPlayer — the on-stage phone must be a real device, and the punct LLM pass is far too slow under emulator ARM translation (matches the ~330 s GGUF note, worse here).

## Evidence files (all under E:/Projects/neuro-stenographer/artifacts/smoke_presentconnect/)

- `app_connected_state.png` — app after token entry + Connect tap (08:11)
- `app_bad_message.png` — app showing `BAD_MESSAGE: Invalid input: expected array, received string` (08:19)
- `deck_state.png` — hosted deck, zero rows («пока тишина»)
- `logcat_connect_attempt.txt` — full logcat of the retried connect
- `health_after_connect.json` — `phone_connected:false, rows:0` after connect attempts
- `checkpoint_after_test.json`, `transcript_export.json` — before/after deck state (both 0 rows)
- `app_final_state.png` — app after the offline-ASR probe finished (08:38, export dropdown open, status «○ готово»)
- `app_transcript_tab.png` — LDPlayer launcher captured at 08:39 after the accidental activity finish (documents the state loss; app not in foreground)

## Bottom line

APK installs, launches, has embedded models + working JNI, WS+token transport is fine, mic recording works — but the app→deck push is DEAD ON ARRIVAL due to the hardcoded `models` string vs server-expected array in `hello`. One-line fix in two places (`OkHttpPresentConnectClient.kt` L87, L285) + rebuild is required before any demo claim. Verdict: **FAIL — cause: hello schema mismatch (BAD_MESSAGE), phone never registers, publish button never enables.**
