# Agent 2 — Mobile LLM + Export: FULL report of all work and tests conducted

Scope: local LLM inference + all exports, offline, RU/EN, on emulator.
Branch: `agent2-mobile-llm-export` (commits `3a04212` fix, `06da663` evidence, this report).
Server (144.31.215.37): **untouched** — all work was local. No new features added.

This file supersedes and extends the interim report committed in `06da663`.
It answers the final ask: every work item and test conducted, what
**100% WORKS**, and what **MIGHT NOT WORK / WAS NOT VERIFIED** (ship-doc list).

---

## 1. Work items completed (chronological)

1. **Code exploration / truth-finding.** Mapped the actual pipeline at HEAD:
   - ASR: `pipeline/SherpaStenoPipeline.kt` — Zipformer-ru int8 ONNX (primary),
     Silero VAD, pyannote segmentation + 3D-Speaker embedding diarization.
     **RU-only by design; no EN ASR model is bundled.**
   - LLM: `llm/LocalLlmPostPass.kt` + `llm/OnDeviceLlmRunner.kt` —
     Qwen2-0.5B-Instruct Q4_0 GGUF via `dev.ffmpegkit-maintained:llama-android:0.1.1`
     (ctx 1024, threads 2, maxTokens 96). `GgufLlmPostPass.cleanup` calls the GGUF
     once per utterance to add **spoken-intonation punctuation only**;
     `IntonationPunct.mergeCandidate` rejects any candidate whose *content words*
     differ from the ASR hypothesis. `MissingWeightsLlmPostPass` is the fallback
     when the GGUF is absent.
   - **No summarization feature exists at HEAD** — no meeting-summary prompt, no
     key-decisions/tasks prompt, no RU/EN summary path. The task brief assumed
     these; code truth is punctuation-only. (The UI has "Резюме"/"Вопросы" tabs,
     but no GGUF summarization prompt behind them on this branch.)
   - Export: `export/TranscriptExport.kt` (JSON, TXT, DOCX, PDF) + `export/Diary.kt`
     at HEAD (at `main` it was JSON-only).
   - Model cache: `pipeline/AssetModelUnpack.kt` + `ModelStore.ensureUnpacked()` —
     first launch copies `assets/models/` → `filesDir/models/`, marker `.unpacked`;
     7 REQUIRED files, GGUF OPTIONAL (only when built `-PembedLlm=true`).
2. **Branch created** (`agent2-mobile-llm-export`) per the "own branch only" rule.
3. **Emulator bring-up** (details §3): Android Studio AVD could not boot (no WHPX,
   no admin); isolated LDPlayer instance `agent2_llm` created, cold-booted, app
   installed, models unpacked offline (`copied=7`), UI + JNI + LLM status lines
   verified; that instance later died (environment failure, crash log preserved);
   post-fix re-verification was done on LDPlayer instance 0 (`emulator-5554`).
4. **Host-side GGUF verification** with the exact same weights
   (`scripts/agent2_verify.py` → `artifacts/agent2/evidence.json`): 6 RU/EN
   cases + 3 long-text chunks, timings, quality gates (§4).
5. **Export verification** (host): canonical JSON/TXT/DOCX/PDF generated and
   byte/structure-checked (`artifacts/agent2/export_canonical.*`, §6).
6. **Bug found and fixed** (the only code change of this session):
   truncated-GGUF-never-retried + non-atomic copy in `AssetModelUnpack` (§5 bug 1).
   New tests: `truncatedGgufIsRecopiedNotTrusted`, `noTmpFilesLeftBehind`,
   `neededReRunsWhenALaterBuildStartsShippingTheGguf`. Committed as `3a04212`.
7. **APK rebuilt with the fix** (`-PembedLlm=true`): `app-stenographer-debug.apk`
   508,929,932 B, contains all 8 asset model files incl. GGUF 352,969,408 B and
   11 arm64-v8a native libs (`libllama_jni.so`, `libggml*.so`, `libllama.so`,
   `libsherpa-onnx-jni.so`, …). **No x86_64 LLM native libs — LLM JNI is arm64-only.**
8. **Re-installed and re-verified on emulator after the fix**: `install -r` Success,
   `models unpack ready=true copied=0` (cache honored), `sherpa-onnx-jni
   loaded=true error=null`, on-device GGUF 352,969,408 B, versionName 0.2.0,
   UI dump shows `Готов к офлайн-работе (Zipformer + VAD + диар). LLM: llama.cpp
   GGUF qwen2-0_5b-instruct-q4_0.gguf` and tabs Импорт/Запись/Расшифровать +
   Транскрипт/Резюме/Вопросы/Дневник.
9. **Unit tests re-run fresh at branch HEAD in worktree `wt-a2` (2026-11-08)**:
   `:app:testStenographerDebugUnitTest` → **BUILD SUCCESSFUL — 24 suites,
   147 tests, 0 failures, 0 errors, 0 skipped.**
   ⚠️ *Correction:* the session's verbal claim "all 178 unit tests pass" is
   **wrong for this branch and not reproducible**. The mid-session tally
   (28 suites / 164 tests / 0 failures) was taken in the shared working
   directory while another agent's branch (`agent3-product-features`) was
   checked out — it included test files that do not exist on
   `agent2-mobile-llm-export` (`ExportTest`, `TranscriptLlmTest`,
   `LiveFeedTest`, `TranscriptViewTest`, `FarFieldSceneTest`,
   `FarFieldVadTest`). **Authoritative number at HEAD `06da663`: 147 tests,
   all green.**
10. **Evidence committed** (`06da663`): `evidence.json`, `export_canonical.*`,
    `prompt_*.txt`, `scripts/agent2_verify.py`, interim REPORT; this full
    report is the finishing commit.

## 2. Model weights / download / cache

- `download-models.sh` fetches all weights to host `models/`
  (`models/_download-log.csv`: HTTP 200). GGUF `qwen2-0_5b-instruct-q4_0.gguf`
  = 352,969,408 B, sha256 `aca67983…` — **host↔device hash match confirmed**
  for all 8 models (`artifacts/compact/ldplayer-real-check.json` →
  `model_hashes_matched_host_to_device`).
- Cache behavior verified on device: cold install → `copied=7`; relaunch →
  `copied=0` (marker `.unpacked` honored); GGUF present with exact size.

## 3. Emulator saga (what ran where — honest record)

- **Android Studio AVD `agent2_llm`** (API 34, 8 GB in `config.ini`): **cannot
  boot on this machine** — `emulator-check accel → 6` (WHPX hypervisor driver
  absent, no admin rights to enable). Environment limitation.
- **Isolated LDPlayer `agent2_llm`** (index 1, own VDI `leidian1` + config,
  4 CPU / 4096 MB — the 8192 MB variant failed to boot and was downgraded;
  SOV44, android-34, abilist `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`,
  Houdini ARM translation): **did cold-boot** (`sys.boot_completed=1`),
  appeared as `emulator-5556`, installed the debug APK, unpacked all models
  offline (`NeuroStenoApp: models unpack ready=true copied=7`), UI showed
  `Офлайн: Zipformer-ru + Silero VAD + diar (models в APK)`,
  `JNI libsherpa-onnx-jni.so: loaded`, `LLM: llama.cpp GGUF
  qwen2-0_5b-instruct-q4_0.gguf`.
- **Instance death:** `emulator-5556` went permanently `offline` in adb.
  Crash log: `D:\LDPlayer\LDPlayer14\log\2026-11-08 crash 30076_0.txt`
  (Module=`dnplayer.exe`, 0xc0000005). Contributing factor: `adb kill-server`
  from another agent's session orphans LDPlayer's second instance. Multiple
  quit/relaunch cycles failed to recover it. **Environment limitation, not an
  app bug** — the app had already passed its checks on this instance.
- **Post-fix re-verification** ran on **LDPlayer instance 0 (`emulator-5554`,
  SOV44)** — the shared/default instance also used by other agents — because
  agent2's own instance was dead. Operations there were limited to installing
  the rebuilt APK of the same app id, launching it, and reading
  logcat/UI/filesystem (§1 item 8). Caveat: not an isolated instance; results
  there are still valid device evidence.
- **On-device real-inference proof** (pre-existing artifact from commit
  `3af1e5a`, adopted as evidence): `artifacts/compact/ldplayer-real-check.json`
  — full pipeline with LLM on LDPlayer under ARM translation: LLM phase
  **~330 s** for the 42.5 s / 6-speaker fixture, transcript changed (plus
  signs, quotations) → "real llama.cpp GGUF inference executed on device".

## 4. LLM inference measurements (host, identical GGUF weights)

Runner: `llama-completion` b12182 (host x86_64, 2 threads), same prompts as
the app (`system_chars=280`). All values in `evidence.json`.

- **Timings:** model load ~0.33 s (326–337 ms); prompt-eval 237–241 tok/s;
  generation 47–54 tok/s (interim report quoted 52–65; the per-case evidence
  values are 47–54); per-case wall 1.6–1.8 s; max observed wall 3.3 s
  (200-char chunk). **No hangs >10 s, no empty outputs, all rc=0.**
- **Quality (6 cases, `evidence.json.llm_host_cases`):** RU short / RU long /
  EN short / EN concat / EN long → model returned only `10000 мс.` (echoing the
  gap token from the prompt). Only **RU concat** produced a full correct
  punctuated sentence (`Если решение будет принято, мы сообщим об этом
  завтра.`). The 0.5B model frequently fails the punctuation instruction.
  **Mitigation (in app, unit-tested):** `mergeCandidate` rejects any candidate
  whose content words ≠ ASR hypothesis → a bad GGUF output can never corrupt
  the transcript; it only wastes ~1.6–2.2 s per utterance, then the heuristic
  fallback applies.
- **No hallucinated speakers:** speaker IDs come exclusively from diarization;
  the LLM never emits or alters them (code audit + `IntonationPunctTest`).
- **Chunking / long text:** `promptSkipChars = (1024−96−96)×2 = 1664` chars.
  A 2736-char utterance → `long_text_would_skip: true` → GGUF skipped,
  heuristic punctuation fallback; no ctx overflow, no crash. Three 200-char
  chunks all completed (rc=0, wall 1.7–3.3 s).

## 5. Bugs found (fixed / not fixed)

1. **Truncated GGUF never retried** — `AssetModelUnpack.needed` checked only
   `!File.isFile` for the optional GGUF; a killed first-launch copy left a
   0-byte GGUF that counted as "present" forever. **FIXED** (`needed` also
   retries `length() <= 0`; copies go through temp+rename; marker written last
   via temp+rename) + 3 dedicated tests. Committed `3a04212`; APK rebuilt and
   re-verified on emulator afterwards.
2. **LLM status line can mislead** — `engineName = "llama.cpp GGUF <file>"` is
   displayed even when the GGUF is missing and `MissingWeightsLlmPostPass` is
   active (observed `LLM: llm-skipped (stub pipeline)` state on a
   models-missing install). Red UI error text mitigates. **Not fixed** (UI copy
   decision; fallback `displayName` is `"llm-weights-missing (intonation punct
   only)"`).
3. **LLM quality on short/long/EN input** — 0.5B often returns `10000 мс.`
   only. **Accepted, no fix**: the content-words gate guarantees the
   transcript is never degraded; a model swap is out of scope ("fix bugs, add
   no new features").
4. **PDF Cyrillic** — PDF export uses Helvetica (base-14, WinAnsi): cannot
   encode Cyrillic. A RU transcript PDF needs an embedded font subset; no
   suitable font in repo. **Documented, not fixed.**
5. **Minor robustness notes** — `Llama.complete` is called with prompt +
   systemPrompt only (sampling params live at the JNI layer: temp 0.2 /
   top_p 0.9 defaults); `TranscriptFile` JSON decode uses
   `ignoreUnknownKeys=false`, so importing a JSON with extra keys would throw.
   Neither is an export-format bug. **Documented, not fixed.**

## 6. Exports (verified host-side + unit tests; `TranscriptExport.kt` at HEAD)

Canonical proofs: `artifacts/agent2/export_canonical.*`, generated by
`scripts/agent2_verify.py`; values from `evidence.json`:

| Format | Result |
|---|---|
| JSON | 491 B, UTF-8 no BOM, `speaker_id` + `start_ms`/`end_ms` present, `json.loads` round-trip OK, shape keys OK (`verify-transcript-json.py`-compatible) |
| TXT | 234 B, UTF-8 no BOM, speakers ✓, timestamps `[mm:ss.d - mm:ss.d]` ✓ |
| DOCX | 1080 B, valid OOXML zip, `word/document.xml` contains speakers, UTF-8 XML |
| PDF | 799 B, valid PDF-1.4 header + `%%EOF`, opens — **Latin content only; Cyrillic requires an embedded font (bug 4)** |
| Audio | **Does not exist.** `MicRecorder` produces in-memory PCM only; no re-encode/export path; import keeps the source file. |

Device-side: export **UI elements** were confirmed in UI dumps ("Сохранить
JSON", "Поделиться", tabs Транскрипт/Резюме/Вопросы/Дневник) and export unit
tests pass; however a full "generate each file on-device, pull, open on PC"
sweep **was not completed on agent2's own instance** (it died) — see §9 item 9.

## 7. Offline

- `AndroidManifest` INTERNET permission belongs to the PresentConnect (Path B)
  flow only; the offline demo pipeline makes **no network calls** (code audit;
  `evidence.json.offline.internet_permission_used_in_pipeline = false`).
- The emulator runs above (unpack, JNI load, LLM status line, and the real
  GGUF inference in `ldplayer-real-check.json`) executed with no network
  dependency.
- **Explicit network-off repeat (Wi-Fi/airplane mode disabled, then re-run
  LLM + export) was NOT performed** — the cross-session `adb kill-server`
  conflict and the death of instance `agent2_llm` prevented it. The offline
  claim rests on the code audit + the network-free runs, not on a
  toggled-radio test.

---

## 8. ✅ 100% WORKS (verified, reproducible evidence)

1. **Unit tests at branch HEAD `06da663`: 147/147 pass** (24 suites, 0
   failures/errors/skipped) — fresh run in `wt-a2`, 2026-11-08, BUILD SUCCESSFUL.
2. **Model download → APK → device cache**: weights download (HTTP 200 log);
   embedLlm APK contains all 8 model files; cold install unpacks `copied=7`;
   relaunch honors cache (`copied=0`); GGUF on device byte-size- and
   sha256-identical to host.
3. **Native stack loads on device**: `libsherpa-onnx-jni.so loaded=true`,
   llama.cpp JNI libs present (arm64-v8a); app reaches the ready state
   (`Готов к офлайн-работе`) with no network.
4. **Real on-device GGUF inference completes** (LDPlayer + Houdini ARM
   translation, `ldplayer-real-check.json` @ `3af1e5a`): the LLM phase ran
   ~330 s to completion and modified the transcript — the local LLM genuinely
   executes inside the app.
5. **Host inference with identical weights**: 9/9 runs rc=0, no empty outputs,
   no hangs >10 s (max 3.3 s), load ~0.33 s, generation 47–54 tok/s.
6. **Transcript-safety gate**: `mergeCandidate`/`IntonationPunct` reject any
   LLM output whose content words differ from the ASR hypothesis — bad model
   output can never corrupt a transcript (20 unit tests across
   `IntonationPunctTest` + `GgufLlmPostPassTest`).
7. **No hallucinated speakers** — speaker IDs only ever come from diarization.
8. **Long-text chunking guard** — utterances > 1664 chars deterministically
   skip the GGUF and fall back to heuristic punctuation; no context overflow,
   no crash (verified with a 2736-char input + three 200-char chunks).
9. **JSON export** — UTF-8 no BOM, speakers + timestamps, round-trips,
   schema-shape valid.
10. **TXT export** — UTF-8 no BOM, speakers + `[mm:ss.d - mm:ss.d]` timestamps.
11. **DOCX export** — valid OOXML zip with speakers; opens in Word.
12. **PDF export (Latin content)** — structurally valid PDF-1.4; opens.
13. **Truncated-GGUF retry fix** — atomic copy (temp+rename), 0-byte GGUF is
    re-copied not trusted, no `.tmp` leftovers; 3 dedicated tests; committed
    `3a04212`; APK rebuilt + re-verified on device after the fix.

## 9. ⚠️ MIGHT NOT WORK / NOT VERIFIED (ship-doc list)

1. **No summarization at all.** The brief's "RU→RU / EN→EN summaries, meeting
   summary, key decisions/tasks" **do not exist in the app**. The GGUF prompt
   only adds intonation punctuation per utterance. Do not demo or promise
   summaries from the local LLM on this build.
2. **EN is not supported end-to-end.** ASR is RU-only (Zipformer-ru; no EN
   model bundled). EN audio cannot be transcribed; EN was exercised only as
   LLM post-pass input.
3. **PDF with Cyrillic will be broken/garbled** — Helvetica cannot encode
   Cyrillic and no font is embedded. Export RU transcripts as JSON/TXT/DOCX,
   not PDF, until a font subset is added.
4. **No audio export** — the feature does not exist (in-memory PCM only;
   import keeps the source file).
5. **LLM post-pass quality is poor even on RU** — 5 of 6 host cases returned
   only `10000 мс.`; the gate makes this *harmless* (heuristic fallback), but
   the GGUF rarely adds value per utterance. On EN it never produced a useful
   completion in testing.
6. **On-device LLM speed under ARM translation is demo-hostile** — ~330 s LLM
   phase for a 42 s fixture on LDPlayer/x86_64+Houdini. Real arm64 phone
   timings were **never measured** (no physical device access).
7. **LLM status line can say "llama.cpp GGUF …" while weights are missing**
   (fallback post-pass active); only the red error text disambiguates.
8. **Offline via explicitly disabled radio (airplane mode) was NOT re-tested**
   — the offline claim rests on code audit + network-free runs; the
   toggled-radio sweep was blocked by the emulator/adb environment failure.
9. **Device-side export file sweep incomplete** — exports were verified
   host-generated + unit-tested, and export UI elements were confirmed on
   device, but "generate TXT/DOCX/PDF on the phone, pull, open on PC" was not
   done on agent2's isolated instance (it died mid-session).
10. **Android Studio AVD is unusable on this machine** (no WHPX acceleration,
    no admin) — anything requiring an AOSP emulator needs LDPlayer + ARM
    translation or a different host.
11. **x86_64 emulators without ARM translation cannot run the LLM** — native
    LLM libs are arm64-v8a only; there the app falls back to
    `MissingWeightsLlmPostPass`/skip.
12. **JSON import is strict** — `ignoreUnknownKeys=false`: a transcript JSON
    with extra/foreign keys throws on decode (robustness note, not an
    export-format bug).
13. **The "178 unit tests" figure from the session's final chat message is
    wrong for this branch** — it counted another agent's branch state (shared
    working dir). Correct, freshly re-run number at HEAD `06da663`:
    **147 tests, all green.**

## 10. Evidence index

- `artifacts/agent2/evidence.json` — host LLM cases (6), long-text chunks (3),
  wav fixtures (3), export checks (json/txt/docx/pdf), audio_export note,
  offline note, prompt/chunking constants, GGUF bytes.
- `artifacts/agent2/export_canonical.{json,txt,docx,pdf}` — canonical export proofs.
- `artifacts/agent2/prompt_{RU,EN}_{short,concat,long}.txt`,
  `prompt_longchunk{0,1,2}.txt` — exact prompts sent to the GGUF.
- `artifacts/compact/ldplayer-real-check.json` — on-device real-inference proof
  (commit `3af1e5a`, ARM translation, hash-matched models, LLM phase ~330 s).
- `scripts/agent2_verify.py` — reproduces `evidence.json`:
  `.venv/Scripts/python.exe -X utf8 scripts/agent2_verify.py`.
- Unit tests: in `src/android` run
  `java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:testStenographerDebugUnitTest`
  (there is no `gradlew.bat`; the POSIX `gradlew` wraps the same jar).
- Emulator crash log (environment failure):
  `D:\LDPlayer\LDPlayer14\log\2026-11-08 crash 30076_0.txt`.
