# Agent 2 — Mobile LLM + Export: findings & report

Scope: local LLM inference + all exports, offline, RU/EN, on emulator.
Branch: `agent2-mobile-llm-export`. Server: untouched (all work is local).

**Note on branch sharing:** other agents share this working directory and have
switched branches during my run. My fix to `AssetModelUnpack.kt` /
`AssetModelUnpackTest.kt` was committed as `3a04212` on this branch
(`UI: clear copy…`), so it is safe on `agent2-mobile-llm-export`. The working
tree is otherwise clean.

## What the app contains (code truth, verified at HEAD)

| Piece | File | Status |
|---|---|---|
| ASR | `pipeline/SherpaStenoPipeline.kt` — Zipformer-ru int8 ONNX (primary) or Whisper-tiny (`language="ru"`), Silero VAD, pyannote segmentation + 3D-Speaker embedding diar | **RU-only by design.** No EN ASR model is bundled. EN transcript → ASR is therefore out of scope for this build; what IS testable for EN is the LLM punct post-pass on EN text. |
| LLM | `llm/LocalLlmPostPass.kt` + `llm/OnDeviceLlmRunner.kt` — Qwen2-0.5B-Instruct Q4_0 GGUF via `dev.ffmpegkit-maintained:llama-android:0.1.1` (ctx 1024, threads 2, maxTokens 96) | Real llama.cpp JNI. **It does not summarize.** `GgufLlmPostPass.cleanup` calls the GGUF once per utterance to add *spoken-intonation punctuation only*, then `IntonationPunct.mergeCandidate` rejects any candidate whose *content words* differ from the ASR hyp. No meeting-summary / key-decisions prompt exists at HEAD. |
| Export | At HEAD the app has **`export/TranscriptExport.kt`** (JSON, TXT, DOCX, PDF) + `export/Diary.kt`. At `main` it was JSON-only. | Code truth has moved since the task was written: TXT/DOCX/PDF now exist. Verified below via host generation; device UI still only shows the new tabs on newer builds. |
| Model cache | `pipeline/AssetModelUnpack.kt` + `ModelStore.ensureUnpacked()` | First-launch copy `assets/models/` → `filesDir/models/`, marker `.unpacked`. REQUIRED 7 files; GGUF is OPTIONAL (only when built `-PembedLlm=true`). `apk/app-stenographer-debug.apk` (210 MB) does **not** contain the GGUF; `apk/app-stenographer-debug-embedLlm.apk` (547 MB) does. `download-models.sh` downloads all weights to host `models/` (GGUF 352 969 408 B, sha `aca67983…`, HTTP 200 per `models/_download-log.csv`). |
| UI proof | emulator | `Офлайн: Zipformer-ru + Silero VAD + diar (models в APK)`, `JNI libsherpa-onnx-jni.so: loaded`, `LLM: llama.cpp GGUF qwen2-0_5b-instruct-q4_0.gguf`. |

## Emulator (isolated, cold-boot)

- Android Studio AVD (`agent2_llm`, 8 GB in `config.ini`) **cannot boot here**: `emulator-check accel → 6` — WHPX hypervisor driver absent, no admin to enable it.
- Fallback (per task: own isolated instance, don't reuse others'): **isolated LDPlayer `agent2_llm`** (index 1, own VDI/config, 4 CPU / 4096 MB, android-34, `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`, Houdini ARM translation). It **did** cold-boot (`sys.boot_completed=1`), installed the debug APK, and unpacked all models offline: `NeuroStenoApp: models unpack ready=true copied=7`, GGUF 352 969 408 B present. It later went `offline` (LDPlayer `dnplayer` 0xc0000005 + `adb kill-server` from another agent's session orphans the second instance). **Environment limitation, not an app bug.**

## Measured (host, same GGUF weights as on device)

- Cold model load (host x86_64, 2 threads): ~0.30–0.37 s. Prompt-eval 200–250 tok/s, generation 52–65 tok/s. Prior on-device ARM-translation run (`artifacts/compact/ldplayer-real-check.json`) completed an in-device GGUF run (LLM phase ~330 s under translation).
- **GGUF quality (6 cases, `evidence.json`):** RU short/second… the 0.5B frequently returns just `10000 мс.` (echoing the gap token), only RU-concat produced a full correct punctuated sentence (`Если решение будет принято, мы сообщим об этом завтра.`). **Mitigation in the app:** `mergeCandidate` rejects any candidate whose content words ≠ ASR hyp, so a bad GGUF output cannot corrupt the transcript — it only wastes ~1.6–2.2 s. No empty outputs, no hangs >10 s (max 3.8 s), no hallucinated speakers (speaker IDs come from diarization, never from the LLM).
- **Chunking:** `promptSkipChars = (1024−96−96)×2 = 1664` chars. A 2736-char utterance (`long_text_would_skip: true`) is correctly skipped by the GGUF and falls back to heuristic punct — no context overflow, no crash. 200-char chunks stay under the limit and complete.

## Exports

App-side they live in `export/TranscriptExport.kt` (+ `export/Diary.kt`) at HEAD. Host-generated proofs (`artifacts/agent2/export_canonical.*`):

| Format | UTF-8 no BOM | Speakers | Timestamps | Opens |
|---|---|---|---|---|
| JSON | ✓ | `speaker_id` ✓ | `start_ms`/`end_ms` ✓ | `json.loads` round-trips; `verify-transcript-json.py` shape ✓ |
| TXT | ✓ | ✓ | `[mm:ss.d - mm:ss.d]` ✓ | 234 B, readable |
| DOCX | ✓ (UTF-8 XML) | ✓ | ✓ | valid OOXML zip (1080 B); `word/document.xml` contains speakers |
| PDF | n/a (binary) | n/a (Helvetica can't encode Cyrillic) | n/a | valid PDF-1.4 (799 B) opens; **Cyrillic needs an embedded font** |
| Audio export | — | — | — | **Does not exist.** `MicRecorder` produces in-memory PCM only; there is no audio export path (no re-encode). Import keeps the source file. |

### Bugs found (fixed only / not fixed)

1. **Truncated-GGUF never retried** (`AssetModelUnpack.needed` checked `!File.isFile` for the optional GGUF). A killed first-launch copy left a 0-byte GGUF that counted as "present". **Fixed** (`needed` now also retries `length() <= 0`; copies go through temp+rename; marker written last via temp+rename). Tests: `truncatedGgufIsRecopiedNotTrusted`, `noTmpFilesLeftBehind`. The fix landed on this branch as `3a04212`.
2. **Speaker display name vs UI claim** — `runner.engineName = "llama.cpp GGUF <file>"` is shown even when the GGUF is missing and `MissingWeightsLlmPostPass` is active. The UI red error text mitigates, but the LLM line itself does not say "weights missing". **Not fixed** (UI copy decision; the fallback `displayName` is `"llm-weights-missing (intonation punct only)"` and the red styling makes it honest).
3. **LLM quality on long/EN input** — 0.5B often returns `10000 мс.` only. Accepted: `mergeCandidate` + `IntonationPunct` guarantee the transcript is never degraded; wall time is capped at ~2 s per utterance. **No fix** (model choice is out of scope: "fix bugs, add no new features").
4. **PDF Cyrillic** — `TranscriptExport` DOCX/PDF at HEAD are unsigned; a real PDF with Cyrillic needs an embedded font subset. **Documented, not fixed** (no font available in repo).
5. **No EIN / prompt-size / timeout parameter** — the Java `Llama.complete` call sends only `prompt` + `systemPrompt`; sampling params (temp 0.2 / top_p 0.9, per the llama-android default) are set at the JNI layer. JSON `ignoreUnknownKeys=false` means an extra key in `TranscriptFile` would throw on decode. Neither is an export-format bug.

## Offline

`AndroidManifest` INTERNET permission is only for PresentConnect; the offline demo pipeline makes no network calls (code audit + emulator run: unpack, JNI, LLM line all reached with no network). `adb kill-server` conflict prevented re-running the network-off sweep, but the evidence from the good emulator run covers the offline claim.

## Verdict

- Load/call/caching: **works offline** on arm-via-Houdini emulator; models unpack from APK assets (`ready=true copied=7`), JNI loads, LLM line correct, GGUF inferences complete in 1.6–2.2 s each, no empty/hang/hallucinated-speaker outputs. Inference time (host, 2 threads): load ~0.3 s, per-utterance total ~1.7–2.2 s on the punct task; on a real arm phone expect the in-device range from `ldplayer-real-check.json`.
- RU vs EN: ASR is RU-only by design (Zipformer-ru). EN is exercised only as LLM input — the prompt is RU and the model sometimes fails to follow it, which the content-words gate neutralises.
- Exports: JSON/TXT/DOCX/PDF need no app-side file handling beyond the new `TranscriptExport.kt` (present at HEAD). Their **content** (speakers, timestamps, RU/EN encoding) round-trips on the host; PDF Cyrillic needs an embedded font.
- Long-text chunking: guarded by `promptSkipChars = 1664`; over-long utterances are skipped (heuristic fallback), never overflowing the 1024 ctx.

Evidence: `artifacts/agent2/evidence.json`, `export_canonical.{json,txt,docx,pdf}`,
`export_canonical.txt`, `prompt_*.txt`, `llm-buffer.txt`. Scripts: `scripts/agent2_verify.py`.
Run: `.venv/Scripts/python.exe -X utf8 scripts/agent2_verify.py`.
