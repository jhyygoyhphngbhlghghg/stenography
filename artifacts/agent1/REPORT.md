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
