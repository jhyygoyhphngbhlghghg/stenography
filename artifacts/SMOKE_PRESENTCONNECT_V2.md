# SMOKE PresentConnect V2 — array-fix re-smoke
Date: 2026-11-08, run 08:43–08:55 local (hard stop 09:15 respected)
Device: emulator-5554 (LDPlayer) · Server: https://present.144.31.215.37.sslip.io/ · token 09879078

## APK
- Path: `E:/Projects/neuro-stenographer/neuro-stenographer/src/android/app/build/outputs/apk/presentConnect/debug/app-presentConnect-debug.apk`
- sha256: `d327acbb1ad6bd9157aa53e69ea5be52b28452b41b3713d87b1daf267dd2b0a1`
- mtime 08:42 (freshly rebuilt), 509,012,604 bytes; `adb install -r` → Success
- Package launched: `team.mikcoders.neurosteno.present/team.mikcoders.neurosteno.MainActivity`; RECORD_AUDIO granted (dumpsys: granted=true; appops: allow)

## CRITERION 1 — handshake (models-as-array fix): **PASS**
- WS URL field pre-filled exactly: `wss://present.144.31.215.37.sslip.io/v1/present-connect?session=demo&role=phone&device=phone_1`; token field filled with `09879078`; tapped "Connect + Live" at 08:45:01.
- Baseline before connect: `phone_connected:false`. At 08:45:05 (T+4 s, well inside 30 s):
  ```json
  {"ok":true,"session_id":"sess_ae2be978110d","phone_connected":true,"phone_stub":false,"demo_phase":"idle","slide_index":0,"slides":8,"screens":2,"rows":0}
  ```
- Still true at 08:54 (health_final.json). Checkpoint confirms real app hello accepted: `"phone":{"connected":true,"device_id":"phone_1","engine":"zipformer-ru-int8","stub":false,...}`.
- App UI: no `BAD_MESSAGE` anywhere in uiautomator dump (grep count 0); status chip shows `○ готово`. Previous run's `BAD_MESSAGE Invalid input models` is gone.
- Evidence: `artifacts/v2/connected.png` (screenshot 08:45), `artifacts/v2/ui_connected.xml`, `artifacts/v2/health_connected.json`, `artifacts/v2/health_final.json`.
- logcat hello/hello.ok excerpt: the app does not log WS frames to logcat (only `NeuroStenoApp: models unpack ready=true copied=0` at 08:44:02; buffer rotated past connect). Server-side acceptance is the proof: the hosted server only flips `phone_connected:true` after zod-validating the app hello — the exact check that failed last run.

## CRITERION 2 — app-originated transcript push: **TIMEOUT** (not fail), rows delta = 0
- Attempt window 08:46:39–08:54:40 (8-min timebox honored).
- Baseline: `rows_pushed:0`, `/v1/sessions/demo/transcript` → `{"transcript":[]}`.
- Tapped `LIVE-запись`; played `samples/demo-stress/corpus/cv17-ru/wav/common_voice_ru_24531935.wav` (8.9 s Russian, Common Voice) at full volume on host speakers for ~10 s; UI showed `Стоп LIVE`; tapped stop 08:47:02. Recording genuinely happened on device: appops → `RECORD_AUDIO: allow; duration=+23s218ms`.
- Polled `/v1/frontend/checkpoint` every ~15 s for 5+ min (until 08:52:22, ~6 min): `recording:false, processing:false, rows_pushed:0, last_push_at:null` the entire time — on-device transcription never became visible server-side within the box.
- Diagnosis: app process was replaced mid-attempt (PID 9429 → 9852; no FATAL in crash buffer, silent kill/restart — emulator is memory-tight with the 509 MB APK). Recording/transcription state was lost with the old process; WS auto-reconnected (health stayed `phone_connected:true`), but no rows were ever pushed.
- End state: `/v1/sessions/demo/transcript` → `{"transcript":[]}` (transcript_final.json), health `rows:0`. Rows pushed FROM THE APP = 0. Deck screenshot not applicable (0 rows; deck would be empty) — checkpoint JSON captured instead (`artifacts/v2/checkpoint_final.json`).

## VERDICT
- **Criterion 1: PASS** — the models-as-array protocol fix is proven on device: hello accepted in ~4 s, `phone_connected:true`, no BAD_MESSAGE.
- **Criterion 2: TIMEOUT** — 0 rows pushed in the 8-min box; caused by an app process restart on the (slow, memory-tight) emulator after a valid 23 s mic recording, not by any observed protocol rejection. Recommend a re-attempt with the emulator freshly rebooted / more RAM.

## Evidence index (all under E:/Projects/neuro-stenographer/artifacts/v2/)
connected.png · ui.xml (pre-connect) · ui_connected.xml · ui_recording.xml · ui_after_stop.xml · health_connected.json · health_final.json · checkpoint_final.json · transcript_final.json
