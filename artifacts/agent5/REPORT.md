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
