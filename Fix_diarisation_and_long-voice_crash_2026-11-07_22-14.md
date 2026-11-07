# Fix diarisation and long-voice crash

**Exported:** 2026-11-07T19:14:59.850Z

**Work Directory:** `E:\Projects\neuro-stenographer\neuro-stenographer`

**Branch:** `main`

---

### **You**

diarisation locks sequential replicas from the same person as if theyre a new speaker every time. app crashes after расшифровка if voiceline was too long

### **Assistant**

[hook: SessionStart] succeeded (exit 0)

[hook: SessionStart] succeeded (exit 0)

[hook: UserPromptSubmit] succeeded (exit 0)

#### Thinking

Let me parse this user request. They're saying:

1. "diarisation locks sequential replicas from the same person as if theyre a new speaker every time" — speaker diarization is failing to recognize sequential utterances ("replicas") from the same speaker, assigning a new speaker ID each time.
2. "app crashes after расшифровка if voiceline was too long" — the app crashes after transcription (расшифровка = Russian for "transcription") if the audio recording was too long.

"расшифровка" is Russian, but the message is mostly English. The memory mentions "Neuro-stenographer hand-in" — GitHub `stenography` mirror, APK release assets, `-PembedLlm` packaging, stale-weights / segmentation model traps. This is highly relevant! The user has a project called "stenography" — a neuro-stenographer app (likely Android APK) that does transcription and diarization.

Recent commits in the current workspace:
- 3af1e5a Add compact APK build with validated prepared encoder and lossless packaging
- bd7b5dd Reuse native inference sessions with serialized lifecycle management
- 8eb4bb5 Fix the last stale code comment and point test artifacts at today's evidence
- f90b0be Record the re-score reproducibility check
- 97331cd Link the published hand-in artifacts; two claim-wording corrections

So the current workspace appears to be the stenography project itself. The user reports two bugs:
1. Diarization assigns new speaker IDs to sequential replicas from the same person (i.e., same speaker across consecutive segments gets different speaker labels — clustering/merging issue).
2. App crashes after transcription when the voice recording is too long (likely OOM or buffer issue in long audio processing).

I need to investigate the codebase. Let me start by exploring the workspace structure. This is a real debugging task in the user's project. Per memory:
- "Verify, don't guess" — reproduce and verify against the actual artifact before saying "fixed"
- "Debug ladders, not feature lists" — ordered cheapest-first stages + symptom→cause table
- "Fix root cause in the toolchain"
- "Commit and push" — commit/push to GitHub and verify HEAD equals remote (but only when asked... memory says user wants commit/push — but the memory note says commit/push to GitHub and verify HEAD equals remote; hold commit if another session's work is in tree)
- "Terse multi-part batches" — execute the whole batch; ask only for real decisions
- "Experimental work stays isolated" — never write another session's active workspace

Let me first look at the workspace to understand what we're working with. The memory reference "reference-neuro-stenographer-handin.md" would have details. Let me read that memory file first, plus explore the current directory.

Language: The user wrote mostly English with one Russian word. I should respond in English (the dominant language), possibly they're Russian-speaking. The instruction says respond in the same language as user's input — the input is predominantly English, so English is fine.

Let me start investigating. First steps:
1. Read the memory file about the neuro-stenographer hand-in.
2. Look at the current workspace structure.
3. Find diarization code and transcription pipeline code.

Let me do these in parallel where possible.

I'll dig into both issues — the speaker-ID churn in diarization and the crash on long recordings after transcription. Let me first check my notes on this project and look at the workspace.

#### Tool: Read

**Input:**

```json
{
  "file_path": "C:\\Users\\KMService\\.qoder\\memory\\reference-neuro-stenographer-handin.md"
}
```

**Response:**

1	---
2	name: reference-neuro-stenographer-handin
3	description: Neuro-stenographer (Hex.Team «Нейро-стенографист») repo layout, hand-in target, and the two build traps that changed measured artifacts
4	metadata:
5	  type: reference
6	---
7	
8	Workspace `E:\Projects\neuro-stenographer\neuro-stenographer`. Case «Нейро-стенографист», team MIK Coders, Nuclear IT Hack Nov 2026.
9	
10	- Hand-in mirror: `github.com/jhyygoyhphngbhlghghg/stenography` (public), branch `main`. The Cursor origin (`origin.cursor.com/johann-schilling/neuro-stenographer`) holds the PR branches and needs `origin auth login`, which is not usable from this box.
11	- APKs (210 MiB check / 547 MiB with GGUF / 93 MiB slim) cannot be git commits — they are Release assets on tag `v0.1.0-stage6`, hashes listed in `apk/README.md`.
12	- Brief requires the local LLM weights on device at check time: build with `-PembedLlm=true` to pack the 353 MB Qwen2 GGUF into `assets/models`.
13	- Trap 1: `:app:prepareEmbeddedModels` originally declared no inputs, so Gradle kept it up-to-date and the APK shipped stale weights — any weight change needs the declared `inputs.file` set to fire.
14	- Trap 2: `download-models.sh` used `find | head -n 1` for pyannote segmentation and picked the int8 export (1 540 506 B, sha `d582f4b4…`) instead of the float `model.onnx` (5 992 913 B, sha `220ad67c…`) that every measured STAGE6 row used. It now picks the float model explicitly.
15	- `scripts/ci-check.sh` re-scores `artifacts/eval/demo-stress/metrics.csv` from committed hyp JSONs and rewrites only the timestamp/commit columns (0 metric differences) — do not read that restamp as a retune.
16

#### Tool: Bash

**Input:**

```json
{
  "command": "ls -la",
  "description": "List files in current workspace"
}
```

**Response:**

total 56
drwxr-xr-x 1 l1ff0 197121     0 Nov  7 10:16 .
drwxr-xr-x 1 l1ff0 197121     0 Nov  7 13:20 ..
drwxr-xr-x 1 l1ff0 197121     0 Nov  7 21:05 .git
-rw-r--r-- 1 l1ff0 197121   740 Nov  7 09:36 .gitattributes
-rw-r--r-- 1 l1ff0 197121  2189 Nov  7 09:05 .gitignore
drwxr-xr-x 1 l1ff0 197121     0 Nov  7 09:55 .venv
-rw-r--r-- 1 l1ff0 197121  4457 Nov  7 09:32 NOTICE.md
-rw-r--r-- 1 l1ff0 197121 10689 Nov  7 10:16 README.md
drwxr-xr-x 1 l1ff0 197121     0 Nov  7 21:04 apk
drwxr-xr-x 1 l1ff0 197121     0 Nov  7 10:24 artifacts
drwxr-xr-x 1 l1ff0 197121     0 Nov  7 08:21 config
drwxr-xr-x 1 l1ff0 197121     0 Nov  7 20:05 docs
drwxr-xr-x 1 l1ff0 197121     0 Nov  7 09:10 eval
drwxr-xr-x 1 l1ff0 197121     0 Nov  7 10:24 models
drwxr-xr-x 1 l1ff0 197121     0 Nov  7 09:34 samples
drwxr-xr-x 1 l1ff0 197121     0 Nov  7 20:05 scripts
drwxr-xr-x 1 l1ff0 197121     0 Nov  7 10:16 src

#### Thinking

This is the neuro-stenographer workspace. Let me check git status and the structure. The memory says the workspace is `E:\Projects\neuro-stenographer\neuro-stenographer`. Let me confirm current directory and explore the source layout — particularly diarization code and the app code (Android app, since APK).

Let me check where I am and get the repo layout.

#### Tool: Bash

**Input:**

```json
{
  "command": "pwd && git status --short && git log --oneline -3",
  "description": "Show working directory, git status, recent commits"
}
```

**Response:**

/e/Projects/neuro-stenographer/neuro-stenographer
 M src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/SherpaStenoPipeline.kt
?? artifacts/compact/exclusive-phases-experiment.json
?? src/android/app/src/main/java/team/mikcoders/neurosteno/EmulatorBench.kt
?? src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/ExclusivePhases.kt
?? src/android/app/src/test/java/team/mikcoders/neurosteno/pipeline/ExclusivePhasesTest.kt
3af1e5a Add compact APK build with validated prepared encoder and lossless packaging
bd7b5dd Reuse native inference sessions with serialized lifecycle management
8eb4bb5 Fix the last stale code comment and point test artifacts at today's evidence

#### Tool: Bash

**Input:**

```json
{
  "command": "find src -maxdepth 3 -type d | head -50",
  "description": "Show source directory tree"
}
```

**Response:**

src
src/android
src/android/.gradle
src/android/.gradle/8.11.1
src/android/.gradle/buildOutputCleanup
src/android/.gradle/vcs-1
src/android/.kotlin
src/android/.kotlin/sessions
src/android/app
src/android/app/build
src/android/app/libs
src/android/app/src
src/android/build
src/android/build/reports
src/android/gradle
src/android/gradle/wrapper

#### Thinking

There are uncommitted changes in the tree:
- Modified: `SherpaStenoPipeline.kt`
- Untracked: `ExclusivePhases.kt`, `ExclusivePhasesTest.kt`, `EmulatorBench.kt`, `artifacts/compact/exclusive-phases-experiment.json`

Per my memory: "hold the commit if another session's work is in the tree" and "Experimental work stays isolated — never write another session's active workspace". These uncommitted changes might be from another session's active work (exclusive phases experiment). I need to be careful: the user is asking me to fix two bugs. The tree has uncommitted work — possibly from another session working on "exclusive phases" (memory-related? phase separation for memory reduction?).

Let me look at what these changes are, and understand the pipeline. The two bugs:
1. Diarization assigns a new speaker to sequential replicas from the same person — likely a clustering/threshold issue in speaker embedding comparison, or the speaker registry not matching across chunks.
2. Crash after transcription ("расшифровка") on long recordings — likely OOM (native memory), or accumulation of segments, or the LLM summarization step on huge transcripts.

Let me explore the pipeline code first. Let me look at the directory structure of the app and the diarization code.

#### Tool: Bash

**Input:**

```json
{
  "command": "find src/android/app/src -type f -name \"*.kt\" | sort",
  "description": "List all Kotlin source files"
}
```

**Response:**

src/android/app/src/main/java/team/mikcoders/neurosteno/EmulatorBench.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/MainActivity.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/NeuroStenoApp.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/audio/AudioDecoder.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/audio/AudioQualityGate.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/audio/FarFieldGain.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/audio/FarFieldVad.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/audio/MicRecorder.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/audio/Pcm.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/audio/PhoneDistanceEq.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/audio/SpeechEndpointing.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/audio/SpeechEnergy.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/audio/WavPcmReader.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/export/TranscriptJson.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/llm/IntonationPunct.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/llm/LocalLlmPostPass.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/llm/OnDeviceLlmRunner.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/AssetModelUnpack.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/DemoPcmPrep.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/ExclusivePhases.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/FarFieldScene.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/InferenceSession.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/ModelStore.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/PipelineFactory.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/SherpaJni.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/SherpaStenoPipeline.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/StenoPipeline.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/StubStenoPipeline.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/VadDiarReconcile.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/present/OkHttpPresentConnectClient.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/present/PathBSession.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/present/PresentConnectClient.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/present/PresentConnectLimits.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/present/PresentConnectStreamTable.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/present/StreamLabeler.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/present/StreamPcmGate.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/present/StubPresentConnectClient.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/ui/StenoApp.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/ui/StenoViewModel.kt
src/android/app/src/main/java/team/mikcoders/neurosteno/ui/theme/Theme.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/audio/AudioQualityGateTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/audio/FarFieldGainTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/audio/PcmTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/audio/PerTurnGainTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/audio/PhoneDistanceEqTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/audio/SpeechEndpointingTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/audio/SpeechEnergyTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/export/TranscriptJsonTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/llm/GgufLlmPostPassTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/llm/HeuristicPunctuationPostPassTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/llm/IntonationPunctTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/pipeline/AssetModelUnpackTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/pipeline/ExclusivePhasesTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/pipeline/InferenceSessionTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/pipeline/VadDiarReconcileTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/present/PathBGoldenBriefTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/present/PathBSessionTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/present/PresentConnectLimitsTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/present/PresentConnectStreamTableTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/present/StreamLabelerTest.kt
src/android/app/src/test/java/team/mikcoders/neurosteno/present/StreamPcmGateTest.kt

#### Tool: Bash

**Input:**

```json
{
  "command": "git diff --stat && ls artifacts/compact/",
  "description": "Show diff stats and compact artifacts"
}
```

**Response:**

warning: in the working copy of 'src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/SherpaStenoPipeline.kt', CRLF will be replaced by LF the next time Git touches it
 .../neurosteno/pipeline/SherpaStenoPipeline.kt     | 42 +++++++++++++++-------
 1 file changed, 29 insertions(+), 13 deletions(-)
build
encoder-validation.json
exclusive-phases-experiment.json
llm-baseline-bench.json
llm-embedding-q4-screen.json
verification.json

#### Thinking

There's in-flight work on `ExclusivePhases.kt` (untracked) plus a modified `SherpaStenoPipeline.kt`. This is likely another session's work on memory phases (probably related to the long-recording crash, actually — "exclusive phases" might be about running ASR/diarization/LLM phases exclusively to save memory).

The user now reports two bugs:
1. Diarization: sequential replicas from same person locked as new speaker every time.
2. Crash after расшифровка (transcription) if voiceline too long.

Let me look at the key files. First the diarization-related: `VadDiarReconcile.kt`, `SherpaStenoPipeline.kt`, `StreamLabeler.kt` (present connect), and the pipeline interface. Let me read the main pipeline first.

#### Tool: Read

**Input:**

```json
{
  "file_path": "E:\\Projects\\neuro-stenographer\\neuro-stenographer\\src\\android\\app\\src\\main\\java\\team\\mikcoders\\neurosteno\\pipeline\\SherpaStenoPipeline.kt"
}
```

**Response:**

1	package team.mikcoders.neurosteno.pipeline
2	
3	import com.k2fsa.sherpa.onnx.FastClusteringConfig
4	import com.k2fsa.sherpa.onnx.FeatureConfig
5	import com.k2fsa.sherpa.onnx.OfflineModelConfig
6	import com.k2fsa.sherpa.onnx.OfflineRecognizer
7	import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
8	import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
9	import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
10	import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
11	import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
12	import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
13	import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
14	import com.k2fsa.sherpa.onnx.SileroVadModelConfig
15	import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
16	import com.k2fsa.sherpa.onnx.Vad
17	import com.k2fsa.sherpa.onnx.VadModelConfig
18	import kotlinx.coroutines.currentCoroutineContext
19	import kotlinx.coroutines.ensureActive
20	import team.mikcoders.neurosteno.audio.FarFieldGain
21	import team.mikcoders.neurosteno.audio.FarFieldVad
22	import team.mikcoders.neurosteno.audio.Pcm
23	import team.mikcoders.neurosteno.export.TranscriptFile
24	import team.mikcoders.neurosteno.export.Utterance
25	import team.mikcoders.neurosteno.llm.HeuristicPunctuationPostPass
26	import team.mikcoders.neurosteno.llm.LocalLlmPostPass
27	
28	/**
29	 * Frozen FarFieldGain → OfflineSpeakerDiarization + VAD hole-fill →
30	 * Zipformer-ru per crop → llama.cpp GGUF intonation punct.
31	 *
32	 * Gain and EQ are frozen (Hex WER still 0.7794, s5 missing). PresentConnect
33	 * scene keeps overnight clustering. Native load failure → stub.
34	 */
35	class SherpaStenoPipeline(
36	    private val paths: ModelPaths,
37	    private val llm: LocalLlmPostPass = HeuristicPunctuationPostPass(),
38	    private val scene: FarFieldScene = FarFieldScene.stage6,
39	) : StenoPipeline {
40	    override val displayName: String =
41	        when {
42	            paths.hasPrimaryAsr && scene.expectedSpeakers > 0 ->
43	                "sherpa zipformer-ru-int8 far-field ${scene.expectedSpeakers}spk"
44	            paths.hasPrimaryAsr -> "sherpa zipformer-ru-int8"
45	            paths.hasBackupAsr -> "sherpa whisper-tiny"
46	            else -> "sherpa (incomplete models)"
47	        }
48	    override val llmDisplayName: String = llm.displayName
49	    override val llmIsOnDevice: Boolean = llm.isOnDeviceLlm
50	
51	    private val inference = InferenceSession(
52	        create = { createRecognizer() },
53	        destroy = { it.release() },
54	    )
55	
56	    override suspend fun trimMemory() {
57	        inference.evict { llm.trimMemory() }
58	    }
59	
60	    override suspend fun close() {
61	        try {
62	            inference.close()
63	        } finally {
64	            llm.close()
65	        }
66	    }
67	
68	    override suspend fun transcribe(
69	        audio: PcmAudio,
70	        onProgress: (PipelineProgress) -> Unit,
71	    ): TranscriptFile {
72	        val context = currentCoroutineContext()
73	        fun checkpoint() = context.ensureActive()
74	        val cleaned = runExclusivePhases(
75	            asrPhase = { inference.run { getRecognizer -> transcribeAsr(audio, getRecognizer, onProgress, ::checkpoint) } },
76	            // Peak native RAM is max(ASR, LLM), not the sum: free the recognizer
77	            // (weights + ORT arenas) before the GGUF loads.
78	            releaseAsr = { inference.evict() },
79	            llmPhase = { raw ->
80	                checkpoint()
81	                onProgress(PipelineProgress(PipelineStage.Llm, llm.displayName, 0.92f))
82	                llm.cleanup(raw)
83	            },
84	        )
85	        checkpoint()
86	        onProgress(PipelineProgress(PipelineStage.Done, "${cleaned.size} utterances", 1f))
87	        return TranscriptFile(transcript = cleaned.sortedBy { it.startMs })
88	    }
89	
90	    private suspend fun transcribeAsr(
91	        audio: PcmAudio,
92	        getRecognizer: suspend () -> OfflineRecognizer,
93	        onProgress: (PipelineProgress) -> Unit,
94	        checkpoint: () -> Unit,
95	    ): List<Utterance> {
96	        require(audio.sampleRate == Pcm.TARGET_HZ) { "need 16 kHz PCM" }
97	        onProgress(PipelineProgress(PipelineStage.Decode, "PCM ${audio.samples.size} samples", 0.05f))
98	
99	        val hz = audio.sampleRate
100	        val staged = if (scene.expectedSpeakers > 0) {
101	            FarFieldGain.stage(audio.samples)
102	        } else {
103	            null
104	        }
105	        val work = if (staged != null) audio.copy(samples = staged.samples) else audio
106	        val energy = if (staged != null) {
107	            staged.segments.map { SpeechRegion(it.start, it.endInclusive + 1) }
108	        } else {
109	            FarFieldVad.speechRegions(work.samples, hz)
110	        }
111	        onProgress(
112	            PipelineProgress(
113	                PipelineStage.Vad,
114	                "energy windows ${energy.size} (gain/EQ frozen)",
115	                0.18f,
116	            ),
117	        )
118	        checkpoint()
119	        val silero = if (paths.hasVad) collectSilero(work, checkpoint) else emptyList()
120	        val regions = FarFieldVad.union(energy, silero)
121	
122	        return if (paths.hasDiar) {
123	            onProgress(PipelineProgress(PipelineStage.Diarize, "OfflineSpeakerDiarization K=${scene.expectedSpeakers}", 0.25f))
124	            // Diarization sessions are released before the ASR model loads;
125	            // the ASR session is released after this phase, before the LLM.
126	            val segments = diarize(work, regions)
127	            checkpoint()
128	            if (segments.isEmpty()) emptyList() else {
129	                transcribeSegments(work, segments, getRecognizer(), onProgress, checkpoint)
130	            }
131	        } else {
132	            onProgress(PipelineProgress(PipelineStage.Vad, "Silero VAD chunks", 0.25f))
133	            vadThenAsr(work, getRecognizer(), onProgress, checkpoint)
134	        }
135	    }
136	
137	    private fun diarize(
138	        audio: PcmAudio,
139	        vadRegions: List<SpeechRegion>,
140	    ): List<DiarSeg> {
141	        val diar = createDiarizer()
142	        try {
143	            val segs = diar.process(audio.samples)
144	            val mapped = segs.map { s ->
145	                DiarSeg(
146	                    speakerIndex = s.speaker,
147	                    startMs = (s.start * 1000).toLong(),
148	                    endMs = (s.end * 1000).toLong(),
149	                )
150	            }
151	            return VadDiarReconcile.run(
152	                vadRegions,
153	                mapped,
154	                audio.sampleRate,
155	                scene,
156	                samples = audio.samples,
157	            )
158	        } finally {
159	            diar.release()
160	        }
161	    }
162	
163	    private fun transcribeSegments(
164	        audio: PcmAudio,
165	        reconciled: List<DiarSeg>,
166	        recognizer: OfflineRecognizer,
167	        onProgress: (PipelineProgress) -> Unit,
168	        checkpoint: () -> Unit,
169	    ): List<Utterance> {
170	        val out = ArrayList<Utterance>(reconciled.size)
171	        reconciled.forEachIndexed { i, seg ->
172	            checkpoint()
173	            onProgress(
174	                PipelineProgress(
175	                    PipelineStage.Asr,
176	                    "ASR ${i + 1}/${reconciled.size} ${seg.speakerId}",
177	                    0.3f + 0.6f * (i + 1) / reconciled.size.coerceAtLeast(1),
178	                ),
179	            )
180	            val pad = scene.asrCropPadMs
181	            val crop = crop(audio.samples, audio.sampleRate, seg.startMs - pad, seg.endMs + pad)
182	            val stagedCrop = FarFieldGain.stage(crop)
183	            val text = asr(recognizer, stagedCrop.samples, audio.sampleRate)
184	            if (text.isNotBlank()) {
185	                out += Utterance(
186	                    speakerId = seg.speakerId,
187	                    startMs = seg.startMs,
188	                    endMs = seg.endMs,
189	                    text = text,
190	                )
191	            }
192	        }
193	        return out
194	    }
195	
196	    private fun collectSilero(audio: PcmAudio, checkpoint: () -> Unit): List<SpeechRegion> {
197	        val vad = createVad()
198	        try {
199	            val window = 512
200	            var offset = 0
201	            val samples = audio.samples
202	            val out = ArrayList<SpeechRegion>()
203	            while (offset + window <= samples.size) {
204	                checkpoint()
205	                vad.acceptWaveform(samples.copyOfRange(offset, offset + window))
206	                offset += window
207	                drainSilero(vad, out)
208	            }
209	            vad.flush()
210	            drainSilero(vad, out)
211	            return out
212	        } finally {
213	            vad.release()
214	        }
215	    }
216	
217	    private fun drainSilero(
218	        vad: Vad,
219	        out: ArrayList<SpeechRegion>,
220	    ) {
221	        while (!vad.empty()) {
222	            val seg = vad.front()
223	            vad.pop()
224	            // SpeechSegment.start is Int on sherpa-onnx ≤1.13.5 and Long on
225	            // v2.0.1; toInt() keeps this compiling against both AARs.
226	            val start = seg.start.toInt()
227	            val end = start + seg.samples.size
228	            if (end > start) out += SpeechRegion(start, end)
229	        }
230	    }
231	
232	    private fun vadThenAsr(
233	        audio: PcmAudio,
234	        recognizer: OfflineRecognizer,
235	        onProgress: (PipelineProgress) -> Unit,
236	        checkpoint: () -> Unit,
237	    ): List<Utterance> {
238	        if (!paths.hasVad) {
239	            onProgress(PipelineProgress(PipelineStage.Asr, "ASR whole file (no VAD)", 0.5f))
240	            val text = asr(recognizer, audio.samples, audio.sampleRate)
241	            val end = audio.durationMs
242	            return listOf(Utterance("speaker_1", 0, end, text))
243	        }
244	        val vad = createVad()
245	        try {
246	            val window = 512
247	            var offset = 0
248	            val samples = audio.samples
249	            val out = ArrayList<Utterance>()
250	            var idx = 0
251	            while (offset + window <= samples.size) {
252	                checkpoint()
253	                vad.acceptWaveform(samples.copyOfRange(offset, offset + window))
254	                offset += window
255	                while (!vad.empty()) {
256	                    val seg = vad.front()
257	                    vad.pop()
258	                    val startMs = (seg.start.toDouble() / audio.sampleRate * 1000).toLong()
259	                    val durMs = (seg.samples.size.toLong() * 1000) / audio.sampleRate
260	                    onProgress(PipelineProgress(PipelineStage.Asr, "VAD chunk ${++idx}", 0.4f))
261	                    val text = asr(recognizer, FarFieldGain.stage(seg.samples).samples, audio.sampleRate)
262	                    if (text.isNotBlank()) {
263	                        out += Utterance("speaker_1", startMs, startMs + durMs, text)
264	                    }
265	                }
266	            }
267	            vad.flush()
268	            while (!vad.empty()) {
269	                checkpoint()
270	                val seg = vad.front()
271	                vad.pop()
272	                val startMs = (seg.start.toDouble() / audio.sampleRate * 1000).toLong()
273	                val durMs = (seg.samples.size.toLong() * 1000) / audio.sampleRate
274	                val text = asr(recognizer, FarFieldGain.stage(seg.samples).samples, audio.sampleRate)
275	                if (text.isNotBlank()) {
276	                    out += Utterance("speaker_1", startMs, startMs + durMs, text)
277	                }
278	            }
279	            return out
280	        } finally {
281	            vad.release()
282	        }
283	    }
284	
285	    private fun asr(recognizer: OfflineRecognizer, samples: FloatArray, sampleRate: Int): String {
286	        if (samples.isEmpty()) return ""
287	        val stream = recognizer.createStream()
288	        return try {
289	            stream.acceptWaveform(samples, sampleRate)
290	            recognizer.decode(stream)
291	            recognizer.getResult(stream).text.trim()
292	        } finally {
293	            stream.release()
294	        }
295	    }
296	
297	    private fun crop(samples: FloatArray, hz: Int, startMs: Long, endMs: Long): FloatArray {
298	        val a = ((startMs * hz) / 1000).toInt().coerceIn(0, samples.size)
299	        val b = ((endMs * hz) / 1000).toInt().coerceIn(a, samples.size)
300	        return samples.copyOfRange(a, b)
301	    }
302	
303	    private fun createRecognizer(): OfflineRecognizer {
304	        val modelConfig: OfflineModelConfig
305	        if (paths.hasPrimaryAsr) {
306	            val dir = paths.zipformerDir!!
307	            val tokens = ModelStore.zipformerFile(dir, "tokens.txt")!!.absolutePath
308	            val enc = ModelStore.zipformerFile(dir, "encoder")!!.absolutePath
309	            val dec = ModelStore.zipformerFile(dir, "decoder")!!.absolutePath
310	            val join = ModelStore.zipformerFile(dir, "joiner")!!.absolutePath
311	            modelConfig = OfflineModelConfig(
312	                transducer = OfflineTransducerModelConfig(
313	                    encoder = enc,
314	                    decoder = dec,
315	                    joiner = join,
316	                ),
317	                tokens = tokens,
318	                numThreads = 2,
319	                debug = false,
320	                provider = "cpu",
321	                modelType = "transducer",
322	            )
323	        } else {
324	            val dir = paths.whisperTinyDir ?: error("no ASR model")
325	            val tokens = ModelStore.whisperFile(dir, "tokens")!!.absolutePath
326	            val enc = ModelStore.whisperFile(dir, "encoder")!!.absolutePath
327	            val dec = ModelStore.whisperFile(dir, "decoder")!!.absolutePath
328	            modelConfig = OfflineModelConfig(
329	                whisper = OfflineWhisperModelConfig(
330	                    encoder = enc,
331	                    decoder = dec,
332	                    language = "ru",
333	                    task = "transcribe",
334	                ),
335	                tokens = tokens,
336	                numThreads = 2,
337	                debug = false,
338	                provider = "cpu",
339	                modelType = "whisper",
340	            )
341	        }
342	        val config = OfflineRecognizerConfig(
343	            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
344	            modelConfig = modelConfig,
345	            decodingMethod = "greedy_search",
346	        )
347	        return OfflineRecognizer(assetManager = null, config = config)
348	    }
349	
350	    private fun createDiarizer(): OfflineSpeakerDiarization {
351	        val cfg = OfflineSpeakerDiarizationConfig(
352	            segmentation = OfflineSpeakerSegmentationModelConfig(
353	                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
354	                    model = paths.diarSegmentation!!.absolutePath,
355	                ),
356	                numThreads = 2,
357	                debug = false,
358	                provider = "cpu",
359	            ),
360	            embedding = SpeakerEmbeddingExtractorConfig(
361	                model = paths.diarEmbedding!!.absolutePath,
362	                numThreads = 2,
363	                debug = false,
364	                provider = "cpu",
365	            ),
366	            clustering = FastClusteringConfig(
367	                numClusters = scene.expectedSpeakers,
368	                threshold = scene.diarClusterThreshold,
369	            ),
370	            minDurationOn = scene.diarMinDurationOnSec,
371	            minDurationOff = scene.diarMinDurationOffSec,
372	        )
373	        return OfflineSpeakerDiarization(assetManager = null, config = cfg)
374	    }
375	
376	    private fun createVad(): Vad {
377	        val cfg = VadModelConfig(
378	            sileroVadModelConfig = SileroVadModelConfig(
379	                model = paths.sileroVad!!.absolutePath,
380	                threshold = scene.vadThreshold,
381	                minSilenceDuration = scene.vadMinSilenceSec,
382	                minSpeechDuration = scene.vadMinSpeechSec,
383	                windowSize = 512,
384	                maxSpeechDuration = scene.vadMaxSpeechSec,
385	            ),
386	            sampleRate = 16_000,
387	            numThreads = 1,
388	            provider = "cpu",
389	            debug = false,
390	        )
391	        return Vad(assetManager = null, config = cfg)
392	    }
393	}
394

#### Thinking

Now let me look at the key files for the two bugs:

1. Diarization bug: `VadDiarReconcile.kt` — this reconciles VAD regions with diarization segments and assigns speaker IDs. Also `FarFieldScene.kt` for the scene config (expectedSpeakers, threshold). The user says "sequential replicas from the same person [are locked] as if they're a new speaker every time". This suggests the reconcile logic assigns a new speaker ID per segment rather than matching embeddings, OR the diarization clustering isn't consistent.

2. Crash after transcription on long recordings: could be in `LocalLlmPostPass` / `OnDeviceLlmRunner` (LLM on long transcript → context overflow / OOM), or in `StenoViewModel`. Let me read `VadDiarReconcile.kt`, `FarFieldScene.kt`, and the LLM post-pass files.

#### Tool: Read

**Input:**

```json
{
  "file_path": "E:\\Projects\\neuro-stenographer\\neuro-stenographer\\src\\android\\app\\src\\main\\java\\team\\mikcoders\\neurosteno\\pipeline\\VadDiarReconcile.kt"
}
```

**Response:**

1	package team.mikcoders.neurosteno.pipeline
2	
3	import team.mikcoders.neurosteno.audio.FarFieldGain
4	import kotlin.math.sqrt
5	
6	/**
7	 * Recover quiet distant seats pyannote dropped (STAGE6 s5 @ 5.4 m) **without**
8	 * inventing speaker_7..12 **and without** gluing distinct near-field turns.
9	 *
10	 * Cite-only Eval `a2219ef` (hardcap @ 3c3f9ce): hyp 6/6 fixed, but merge
11	 * smeared s2+s3 into one 7456–19066 island (s3 WER 1.0) and left s5 unmatched
12	 * (hyp speaker_2 stub «да.»). HARD CAP stays 6. Orphans attach only to
13	 * time-adjacent **same-energy** hosts; two substantial near-field islands
14	 * never share a cluster (protect s2 @ 3.6 m from s3). Quiet far maps to its
15	 * own seat (Hungarian-style), not onto s2. Near 3 m is not expanded.
16	 * PresentConnect skips this.
17	 */
18	object VadDiarReconcile {
19	    /** Turns longer than this are distinct seats, not mergeable stubs. */
20	    internal const val SUBSTANTIAL_MS = 1_200L
21	    private const val STUB_EXTEND_MS = 800L
22	    private const val SAME_ENERGY_RATIO = 2.5
23	    private const val NEAR_FIELD_RATIO = 0.42
24	    /** Quiet far VAD/energy islands (s5 ~4.7 s) may snap; near-field may not. */
25	    private const val FAR_EXPAND_MAX_MS = 6_000L
26	
27	    fun run(
28	        vad: List<SpeechRegion>,
29	        diar: List<DiarSeg>,
30	        hz: Int,
31	        scene: FarFieldScene,
32	        samples: FloatArray? = null,
33	    ): List<DiarSeg> {
34	        if (scene.expectedSpeakers <= 0) {
35	            return diar.sortedBy { it.startMs }
36	        }
37	        val k = scene.expectedSpeakers
38	        val near = diar.minByOrNull { it.startMs }
39	        val energy = EnergyIndex(samples, hz, near)
40	        val segs = ArrayList(diar)
41	        for (region in vad) {
42	            val r0 = region.startMs(hz)
43	            val r1 = region.endMs(hz)
44	            val pieces = subtract(r0, r1, segs)
45	            for (p in pieces) {
46	                adopt(segs, p.first, p.second, scene, energy, k)
47	            }
48	            snapEndpoint(segs, r0, r1, scene, energy)
49	        }
50	        if (samples != null && samples.isNotEmpty()) {
51	            fillEnergyHoles(segs, samples, hz, scene, energy, k)
52	            splitLongByEnergyDip(segs, samples, hz, scene, energy, k)
53	        }
54	        splitNonNearByVad(segs, vad, hz, scene, energy, k)
55	        val clipped = segs.mapNotNull { s ->
56	            val c = clipAwayFromNear(s, near)
57	            if (c.endMs > c.startMs) c else null
58	        }
59	        val capped = capToExpected(clipped, k, energy)
60	        val merged = mergeSameSpeaker(capped, scene.mergeGapMs)
61	        return if (samples != null && samples.isNotEmpty()) {
62	            SpeakerSeatAssign.assign(merged, energy, k)
63	        } else {
64	            relabelByAppearance(merged)
65	        }
66	    }
67	
68	    /**
69	     * New cluster while unique < K. At cap, attach only to a time-adjacent
70	     * same-energy host — never glue two substantial near-field turns.
71	     */
72	    internal fun adopt(
73	        segs: MutableList<DiarSeg>,
74	        startMs: Long,
75	        endMs: Long,
76	        scene: FarFieldScene,
77	        energy: EnergyIndex,
78	        k: Int,
79	    ) {
80	        if (endMs - startMs < scene.orphanMinMs) return
81	        if (overlapsNear(startMs, endMs, energy.near)) return
82	        val orphan = DiarSeg(speakerIndex = -1, startMs = startMs, endMs = endMs)
83	        if (uniqueSpeakers(segs) < k) {
84	            segs += DiarSeg(speakerIndex = nextId(segs), startMs = startMs, endMs = endMs)
85	            return
86	        }
87	        if (energy.substantial(orphan)) {
88	            stealStub(segs, energy)
89	            segs += DiarSeg(speakerIndex = nextId(segs), startMs = startMs, endMs = endMs)
90	            return
91	        }
92	        val host = nearestCompatibleHost(startMs, endMs, segs, energy) ?: return
93	        val neighbor = segs.filter { it.speakerIndex == host }
94	            .minByOrNull { gap(it, startMs, endMs) } ?: return
95	        if (!energy.mayShare(neighbor, orphan)) return
96	        if (energy.canExtendStub(neighbor, orphan)) {
97	            val ix = segs.indexOf(neighbor)
98	            if (ix >= 0) {
99	                segs[ix] = neighbor.copy(
100	                    startMs = minOf(neighbor.startMs, startMs),
101	                    endMs = maxOf(neighbor.endMs, endMs),
102	                )
103	                return
104	            }
105	        }
106	        segs += DiarSeg(speakerIndex = host, startMs = startMs, endMs = endMs)
107	    }
108	
109	    private fun snapEndpoint(
110	        segs: MutableList<DiarSeg>,
111	        r0: Long,
112	        r1: Long,
113	        scene: FarFieldScene,
114	        energy: EnergyIndex,
115	    ) {
116	        val dur = r1 - r0
117	        if (dur <= 0L) return
118	        val hits = segs.withIndex().filter { (_, s) -> overlaps(s.startMs, s.endMs, r0, r1) }
119	        if (hits.size != 1) return
120	        val (ix, s) = hits[0]
121	        if (energy.isNearSeat(s) || energy.isNearField(s)) {
122	            if (dur > scene.expandMaxRegionMs) return
123	        } else if (dur > FAR_EXPAND_MAX_MS) {
124	            return
125	        }
126	        val cov = overlapMs(s.startMs, s.endMs, r0, r1).toFloat() / dur.toFloat()
127	        if (cov < scene.expandIfCoverageBelow) {
128	            val next = s.copy(startMs = minOf(s.startMs, r0), endMs = maxOf(s.endMs, r1))
129	            if (overlapsNear(next.startMs, next.endMs, energy.near) && !energy.isNearSeat(s)) {
130	                return
131	            }
132	            segs[ix] = next
133	        }
134	    }
135	
136	    private fun fillEnergyHoles(
137	        segs: MutableList<DiarSeg>,
138	        samples: FloatArray,
139	        hz: Int,
140	        scene: FarFieldScene,
141	        energy: EnergyIndex,
142	        k: Int,
143	    ) {
144	        val durationMs = if (hz <= 0) 0L else samples.size * 1000L / hz
145	        val holes = gaps(segs, 0L, durationMs)
146	        for (hole in holes) {
147	            if (hole.second - hole.first < scene.orphanMinMs) continue
148	            val a = ((hole.first * hz) / 1000L).toInt().coerceIn(0, samples.size)
149	            val b = ((hole.second * hz) / 1000L).toInt().coerceIn(a, samples.size)
150	            if (b - a < 2) continue
151	            val crop = samples.copyOfRange(a, b)
152	            val found = FarFieldGain.findSegments(crop, hz)
153	            for (r in found) {
154	                val s0 = hole.first + r.first * 1000L / hz
155	                val s1 = hole.first + (r.last + 1) * 1000L / hz
156	                adopt(segs, s0, s1, scene, energy, k)
157	            }
158	        }
159	    }
160	
161	    /**
162	     * pyannote sometimes emits one span over two near-field turns. Split on
163	     * energy dips so s2 @ 3.6 m is not glued to s3.
164	     */
165	    private fun splitLongByEnergyDip(
166	        segs: MutableList<DiarSeg>,
167	        samples: FloatArray,
168	        hz: Int,
169	        scene: FarFieldScene,
170	        energy: EnergyIndex,
171	        k: Int,
172	    ) {
173	        val keep = ArrayList<DiarSeg>(segs.size)
174	        val extra = ArrayList<Pair<Long, Long>>()
175	        for (s in segs) {
176	            if (energy.isNearSeat(s) || s.endMs - s.startMs < 2 * SUBSTANTIAL_MS) {
177	                keep += s
178	                continue
179	            }
180	            val a = ((s.startMs * hz) / 1000L).toInt().coerceIn(0, samples.size)
181	            val b = ((s.endMs * hz) / 1000L).toInt().coerceIn(a, samples.size)
182	            if (b - a < 2) {
183	                keep += s
184	                continue
185	            }
186	            val found = FarFieldGain.findSegments(samples.copyOfRange(a, b), hz)
187	            if (found.size < 2) {
188	                keep += s
189	                continue
190	            }
191	            val first = found[0]
192	            keep += DiarSeg(
193	                s.speakerIndex,
194	                s.startMs + first.first * 1000L / hz,
195	                s.startMs + (first.last + 1) * 1000L / hz,
196	            )
197	            for (r in found.drop(1)) {
198	                extra += (s.startMs + r.first * 1000L / hz) to
199	                    (s.startMs + (r.last + 1) * 1000L / hz)
200	            }
201	        }
202	        segs.clear()
203	        segs.addAll(keep)
204	        for ((t0, t1) in extra) {
205	            adopt(segs, t0, t1, scene, energy, k)
206	        }
207	    }
208	
209	    /**
210	     * If a non-near diar span covers two VAD islands, keep the first island
211	     * on the original speaker; later islands go through [adopt] (new speaker
212	     * when they are distinct substantial turns).
213	     */
214	    private fun splitNonNearByVad(
215	        segs: MutableList<DiarSeg>,
216	        vad: List<SpeechRegion>,
217	        hz: Int,
218	        scene: FarFieldScene,
219	        energy: EnergyIndex,
220	        k: Int,
221	    ) {
222	        val extra = ArrayList<Triple<Long, Long, Int>>()
223	        val remove = ArrayList<DiarSeg>()
224	        for (s in segs) {
225	            if (energy.isNearSeat(s)) continue
226	            val hits = vad.filter { r ->
227	                overlaps(s.startMs, s.endMs, r.startMs(hz), r.endMs(hz))
228	            }.sortedBy { it.startSample }
229	            if (hits.size < 2) continue
230	            remove += s
231	            val first = hits[0]
232	            extra += Triple(
233	                maxOf(s.startMs, first.startMs(hz)),
234	                minOf(s.endMs, first.endMs(hz)),
235	                s.speakerIndex,
236	            )
237	            for (h in hits.drop(1)) {
238	                extra += Triple(
239	                    maxOf(s.startMs, h.startMs(hz)),
240	                    minOf(s.endMs, h.endMs(hz)),
241	                    -1,
242	                )
243	            }
244	        }
245	        segs.removeAll(remove.toSet())
246	        for ((a, b, id) in extra) {
247	            if (id >= 0 && b > a) {
248	                segs += DiarSeg(id, a, b)
249	            } else {
250	                adopt(segs, a, b, scene, energy, k)
251	            }
252	        }
253	    }
254	
255	    internal fun capToExpected(segs: List<DiarSeg>, k: Int, energy: EnergyIndex): List<DiarSeg> {
256	        if (k <= 0 || segs.isEmpty()) return segs
257	        val work = segs.toMutableList()
258	        var guard = 0
259	        while (uniqueSpeakers(work) > k && guard++ < 32) {
260	            if (!stealStub(work, energy)) {
261	                val victim = work
262	                    .filter { !energy.isNearSeat(it) && it.speakerIndex != energy.near?.speakerIndex }
263	                    .groupBy { it.speakerIndex }
264	                    .minByOrNull { (_, xs) -> xs.sumOf { it.endMs - it.startMs } }
265	                    ?.key ?: break
266	                reassignSpeaker(work, victim, energy)
267	            }
268	        }
269	        return mergeSameSpeaker(work, 0)
270	    }
271	
272	    /** Reassign the shortest non-near stub onto a same-energy neighbor. */
273	    internal fun stealStub(segs: MutableList<DiarSeg>, energy: EnergyIndex): Boolean {
274	        val groups = segs.groupBy { it.speakerIndex }
275	        val victim = groups
276	            .filter { (id, xs) ->
277	                val dur = xs.sumOf { it.endMs - it.startMs }
278	                val dummy = xs.minBy { it.startMs }
279	                id != energy.near?.speakerIndex &&
280	                    !energy.isNearSeat(dummy) &&
281	                    dur < SUBSTANTIAL_MS
282	            }
283	            .minByOrNull { (_, xs) -> xs.sumOf { it.endMs - it.startMs } }
284	            ?.key ?: return false
285	        reassignSpeaker(segs, victim, energy)
286	        return true
287	    }
288	
289	    private fun reassignSpeaker(work: MutableList<DiarSeg>, victim: Int, energy: EnergyIndex) {
290	        val rest = work.filter { it.speakerIndex != victim }
291	        if (rest.isEmpty()) return
292	        val moved = ArrayList<DiarSeg>(work.size)
293	        for (s in work) {
294	            if (s.speakerIndex != victim) {
295	                moved += s
296	                continue
297	            }
298	            val host = nearestCompatibleHost(s.startMs, s.endMs, rest, energy)
299	                ?: rest.filter { !energy.isNearSeat(it) }.minByOrNull { gap(it, s.startMs, s.endMs) }?.speakerIndex
300	                ?: rest.first().speakerIndex
301	            moved += s.copy(speakerIndex = host)
302	        }
303	        work.clear()
304	        work.addAll(moved)
305	    }
306	
307	    fun mergeSameSpeaker(segs: List<DiarSeg>, gapMs: Long): List<DiarSeg> {
308	        if (segs.size <= 1) return segs.sortedBy { it.startMs }
309	        val sorted = segs.sortedWith(compareBy<DiarSeg> { it.startMs }.thenBy { it.speakerIndex })
310	        if (gapMs < 0) return sorted
311	        val out = ArrayList<DiarSeg>(sorted.size)
312	        for (s in sorted) {
313	            val last = out.lastOrNull()
314	            if (
315	                last != null &&
316	                last.speakerIndex == s.speakerIndex &&
317	                s.startMs <= last.endMs + gapMs
318	            ) {
319	                out[out.lastIndex] = last.copy(endMs = maxOf(last.endMs, s.endMs))
320	            } else {
321	                out += s
322	            }
323	        }
324	        return out
325	    }
326	
327	    fun relabelByAppearance(segs: List<DiarSeg>): List<DiarSeg> {
328	        val order = segs.sortedBy { it.startMs }.map { it.speakerIndex }.distinct()
329	        val map = order.withIndex().associate { it.value to it.index }
330	        return segs.map { s -> s.copy(speakerIndex = map.getValue(s.speakerIndex)) }
331	    }
332	
333	    internal fun subtract(r0: Long, r1: Long, occupied: List<DiarSeg>): List<Pair<Long, Long>> {
334	        var cur = r0
335	        val out = ArrayList<Pair<Long, Long>>()
336	        val hits = occupied.filter { overlaps(it.startMs, it.endMs, r0, r1) }.sortedBy { it.startMs }
337	        for (s in hits) {
338	            val a = maxOf(s.startMs, r0)
339	            val b = minOf(s.endMs, r1)
340	            if (a > cur) out += cur to a
341	            cur = maxOf(cur, b)
342	        }
343	        if (r1 > cur) out += cur to r1
344	        return out
345	    }
346	
347	    private fun gaps(segs: List<DiarSeg>, t0: Long, t1: Long): List<Pair<Long, Long>> =
348	        subtract(t0, t1, segs)
349	
350	    internal fun uniqueSpeakers(segs: List<DiarSeg>): Int =
351	        segs.map { it.speakerIndex }.distinct().size
352	
353	    private fun nextId(segs: List<DiarSeg>): Int =
354	        (segs.maxOfOrNull { it.speakerIndex } ?: -1) + 1
355	
356	    private fun nearestCompatibleHost(
357	        startMs: Long,
358	        endMs: Long,
359	        segs: List<DiarSeg>,
360	        energy: EnergyIndex,
361	    ): Int? {
362	        val orphan = DiarSeg(-1, startMs, endMs)
363	        val cand = segs.filter {
364	            !energy.isNearSeat(it) &&
365	                it.speakerIndex != energy.near?.speakerIndex &&
366	                energy.mayShare(it, orphan)
367	        }
368	        if (cand.isEmpty()) return null
369	        val left = cand.filter { it.endMs <= startMs }.maxByOrNull { it.endMs }
370	        val right = cand.filter { it.startMs >= endMs }.minByOrNull { it.startMs }
371	        return when {
372	            left != null && right != null ->
373	                if (startMs - left.endMs <= right.startMs - endMs) left.speakerIndex else right.speakerIndex
374	            left != null -> left.speakerIndex
375	            right != null -> right.speakerIndex
376	            else -> cand.minByOrNull { gap(it, startMs, endMs) }?.speakerIndex
377	        }
378	    }
379	
380	    internal fun gap(s: DiarSeg, startMs: Long, endMs: Long): Long =
381	        when {
382	            s.endMs <= startMs -> startMs - s.endMs
383	            endMs <= s.startMs -> s.startMs - endMs
384	            else -> 0L
385	        }
386	
387	    private fun overlapsNear(a0: Long, a1: Long, near: DiarSeg?): Boolean =
388	        near != null && overlaps(a0, a1, near.startMs, near.endMs)
389	
390	    internal fun clipAwayFromNear(s: DiarSeg, near: DiarSeg?): DiarSeg {
391	        if (near == null || (s.speakerIndex == near.speakerIndex && s.startMs == near.startMs && s.endMs == near.endMs)) {
392	            return s
393	        }
394	        if (!overlaps(s.startMs, s.endMs, near.startMs, near.endMs)) return s
395	        if (s.startMs >= near.startMs && s.endMs <= near.endMs) {
396	            return s.copy(endMs = s.startMs)
397	        }
398	        var start = s.startMs
399	        var end = s.endMs
400	        if (start < near.startMs && end > near.endMs) {
401	            start = near.endMs
402	        } else if (start < near.endMs && end > near.endMs) {
403	            start = near.endMs
404	        } else if (end > near.startMs && start < near.startMs) {
405	            end = near.startMs
406	        }
407	        return s.copy(startMs = start, endMs = end)
408	    }
409	
410	    internal fun overlaps(a0: Long, a1: Long, b0: Long, b1: Long): Boolean =
411	        a0 < b1 && b0 < a1
412	
413	    private fun overlapMs(a0: Long, a1: Long, b0: Long, b1: Long): Long =
414	        (minOf(a1, b1) - maxOf(a0, b0)).coerceAtLeast(0L)
415	}
416	
417	/**
418	 * RMS / near-field view used to decide who may share a cluster.
419	 * Missing samples → energy unknown: substantial turns still never glue.
420	 */
421	internal class EnergyIndex(
422	    val samples: FloatArray?,
423	    val hz: Int,
424	    val near: DiarSeg?,
425	) {
426	    fun isNearSeat(s: DiarSeg): Boolean =
427	        near != null &&
428	            s.speakerIndex == near.speakerIndex &&
429	            s.startMs == near.startMs &&
430	            s.endMs == near.endMs
431	
432	    fun rms(startMs: Long, endMs: Long): Double {
433	        val pcm = samples ?: return -1.0
434	        if (hz <= 0 || pcm.isEmpty()) return -1.0
435	        val a = ((startMs * hz) / 1000L).toInt().coerceIn(0, pcm.size)
436	        val b = ((endMs * hz) / 1000L).toInt().coerceIn(a, pcm.size)
437	        val n = b - a
438	        if (n <= 0) return 0.0
439	        var acc = 0.0
440	        var i = a
441	        while (i < b) {
442	            val v = pcm[i].toDouble()
443	            acc += v * v
444	            i++
445	        }
446	        return sqrt(acc / n)
447	    }
448	
449	    fun rms(s: DiarSeg): Double = rms(s.startMs, s.endMs)
450	
451	    fun nearRms(): Double = near?.let { rms(it) } ?: -1.0
452	
453	    fun substantial(s: DiarSeg): Boolean = s.endMs - s.startMs >= VadDiarReconcile.SUBSTANTIAL_MS
454	
455	    fun isNearField(s: DiarSeg): Boolean {
456	        val r = rms(s)
457	        val n = nearRms()
458	        if (r < 0.0 || n < 0.0) return isNearSeat(s)
459	        if (n < 1e-8) return false
460	        return r >= n * 0.42
461	    }
462	
463	    fun sameEnergy(a: DiarSeg, b: DiarSeg): Boolean {
464	        val ra = rms(a)
465	        val rb = rms(b)
466	        if (ra < 0.0 || rb < 0.0) return !substantial(a) || !substantial(b)
467	        val lo = minOf(ra, rb).coerceAtLeast(1e-12)
468	        val hi = maxOf(ra, rb)
469	        return hi / lo <= 2.5
470	    }
471	
472	    /**
473	     * Distinct substantial near-field turns (s2 vs s3) never share a cluster.
474	     * Two substantial seats never share. Stubs may join a time-adjacent
475	     * same-energy host that is not the 3 m seat.
476	     */
477	    fun mayShare(host: DiarSeg, orphan: DiarSeg): Boolean {
478	        if (isNearSeat(host) || host.speakerIndex == near?.speakerIndex) return false
479	        val bridged = minOf(host.startMs, orphan.startMs) to maxOf(host.endMs, orphan.endMs)
480	        if (near != null && VadDiarReconcile.overlaps(bridged.first, bridged.second, near.startMs, near.endMs) &&
481	            !isNearSeat(host)
482	        ) {
483	            return false
484	        }
485	        if (substantial(host) && substantial(orphan)) return false
486	        if (isNearField(host) && isNearField(orphan) && substantial(host) && substantial(orphan)) {
487	            return false
488	        }
489	        if (isNearField(host) && isNearField(orphan) && VadDiarReconcile.gap(host, orphan.startMs, orphan.endMs) > 0L) {
490	            return false
491	        }
492	        return sameEnergy(host, orphan)
493	    }
494	
495	    fun canExtendStub(host: DiarSeg, orphan: DiarSeg): Boolean {
496	        if (!mayShare(host, orphan)) return false
497	        if (substantial(orphan)) return false
498	        return VadDiarReconcile.gap(host, orphan.startMs, orphan.endMs) <= 800L
499	    }
500	}
501	
502	/**
503	 * Map fragments onto ≤K time-ordered seats so Eval Hungarian can match
504	 * quiet far hyp to ref s5 instead of leaving a speaker_2 stub unmapped.
505	 */
506	internal object SpeakerSeatAssign {
507	    fun assign(segs: List<DiarSeg>, energy: EnergyIndex, k: Int): List<DiarSeg> {
508	        if (segs.isEmpty() || k <= 0) return segs
509	        val anchors = pickAnchors(segs, energy, k)
510	        if (anchors.isEmpty()) {
511	            return VadDiarReconcile.relabelByAppearance(segs)
512	        }
513	        val ordered = anchors.sortedBy { it.startMs }
514	        val mapped = segs.map { s ->
515	            val host = closestAnchor(s, ordered, energy)
516	            s.copy(speakerIndex = host)
517	        }
518	        val kept = mapped.filter { s ->
519	            val host = ordered.getOrNull(s.speakerIndex)
520	            energy.substantial(s) ||
521	                energy.isNearSeat(s) ||
522	                (host != null && VadDiarReconcile.gap(host, s.startMs, s.endMs) <= 1_500L)
523	        }
524	        val out = kept.ifEmpty { mapped }
525	        return VadDiarReconcile.relabelByAppearance(out)
526	    }
527	
528	    internal fun pickAnchors(segs: List<DiarSeg>, energy: EnergyIndex, k: Int): List<DiarSeg> {
529	        val near = energy.near
530	        val picked = ArrayList<DiarSeg>()
531	        val nearSeg = segs.filter { energy.isNearSeat(it) }.minByOrNull { it.startMs }
532	            ?: segs.minByOrNull { it.startMs }
533	        if (nearSeg != null) picked += nearSeg
534	        val rest = segs
535	            .filter { s -> picked.none { it.startMs == s.startMs && it.endMs == s.endMs } }
536	            .sortedWith(
537	                compareByDescending<DiarSeg> { it.endMs - it.startMs }
538	                    .thenByDescending { energy.rms(it) },
539	            )
540	        for (s in rest) {
541	            if (picked.size >= k) break
542	            if (!energy.substantial(s) && energy.isNearField(s).not() && picked.size >= 1) {
543	                continue
544	            }
545	            val overlapsExisting = picked.any { h ->
546	                VadDiarReconcile.overlaps(h.startMs, h.endMs, s.startMs, s.endMs) &&
547	                    overlapRatio(h, s) > 0.5
548	            }
549	            if (overlapsExisting) continue
550	            picked += s
551	        }
552	        if (picked.size < k) {
553	            for (s in rest) {
554	                if (picked.size >= k) break
555	                if (picked.any { it.startMs == s.startMs && it.endMs == s.endMs }) continue
556	                if (energy.rms(s) >= 0.0 && energy.nearRms() > 0.0 &&
557	                    energy.rms(s) < energy.nearRms() * 0.42
558	                ) {
559	                    picked += s
560	                }
561	            }
562	        }
563	        if (near != null) {
564	            val hasFarQuiet = picked.any { !energy.isNearField(it) && !energy.isNearSeat(it) }
565	            if (!hasFarQuiet) {
566	                val quiet = rest
567	                    .filter { !energy.isNearField(it) && !energy.isNearSeat(it) }
568	                    .maxByOrNull { it.endMs - it.startMs }
569	                if (quiet != null && picked.size < k) picked += quiet
570	                else if (quiet != null && picked.size >= k) {
571	                    val victimIx = picked.indices
572	                        .filter { i -> !energy.isNearSeat(picked[i]) && !energy.substantial(picked[i]) }
573	                        .minByOrNull { i -> picked[i].endMs - picked[i].startMs }
574	                    if (victimIx != null) picked[victimIx] = quiet
575	                }
576	            }
577	        }
578	        return picked.take(k)
579	    }
580	
581	    /**
582	     * Min-cost (Hungarian-style) assignment of one fragment onto K anchors.
583	     * Quiet far never maps onto a near-field seat when a quiet anchor exists.
584	     */
585	    internal fun closestAnchor(s: DiarSeg, anchors: List<DiarSeg>, energy: EnergyIndex): Int {
586	        var best = 0
587	        var bestCost = Double.POSITIVE_INFINITY
588	        for ((i, a) in anchors.withIndex()) {
589	            val c = cost(s, a, energy, anchors)
590	            if (c < bestCost) {
591	                bestCost = c
592	                best = i
593	            }
594	        }
595	        return best
596	    }
597	
598	    internal fun cost(s: DiarSeg, a: DiarSeg, energy: EnergyIndex, anchors: List<DiarSeg>): Double {
599	        val gap = VadDiarReconcile.gap(a, s.startMs, s.endMs).toDouble()
600	        val ov = overlapMs(s, a).toDouble()
601	        var c = gap - 4.0 * ov
602	        if (energy.isNearSeat(a) && !energy.isNearSeat(s) && !energy.isNearField(s)) {
603	            c += 1_000_000.0
604	        }
605	        if (energy.isNearField(a) && !energy.isNearField(s) && anchors.any { !energy.isNearField(it) }) {
606	            c += 200_000.0
607	        }
608	        if (energy.isNearField(s) && energy.isNearField(a) && s.startMs != a.startMs &&
609	            energy.substantial(s) && energy.substantial(a)
610	        ) {
611	            c += 500_000.0
612	        }
613	        if (!energy.sameEnergy(s, a)) c += 50_000.0
614	        return c
615	    }
616	
617	    private fun overlapMs(a: DiarSeg, b: DiarSeg): Long =
618	        (minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)).coerceAtLeast(0L)
619	
620	    private fun overlapRatio(a: DiarSeg, b: DiarSeg): Double {
621	        val ov = overlapMs(a, b).toDouble()
622	        val den = minOf(a.endMs - a.startMs, b.endMs - b.startMs).toDouble().coerceAtLeast(1.0)
623	        return ov / den
624	    }
625	}
626

#### Tool: Read

**Input:**

```json
{
  "file_path": "E:\\Projects\\neuro-stenographer\\neuro-stenographer\\src\\android\\app\\src\\main\\java\\team\\mikcoders\\neurosteno\\pipeline\\FarFieldScene.kt"
}
```

**Response:**

1	package team.mikcoders.neurosteno.pipeline
2	
3	/**
4	 * DEMO_STAGE6 single-mic scene vs PresentConnect (Path B, not the live claim).
5	 *
6	 * FarFieldGain.stage and EQ are **frozen**. Live work: diar/VAD recover s5
7	 * with a **hard cap of 6 speakers** (Eval 9b29b9c1 over-split 12/6;
8	 * Eval a2219ef glued s2+s3 — do not merge distinct near-field turns).
9	 * Path B ingest is unchanged.
10	 */
11	data class FarFieldScene(
12	    val name: String,
13	    /** 6 for DEMO_STAGE6; -1 = overnight auto-cluster (PresentConnect). */
14	    val expectedSpeakers: Int,
15	    val vadThreshold: Float,
16	    val vadMinSilenceSec: Float,
17	    val vadMinSpeechSec: Float,
18	    val vadMaxSpeechSec: Float,
19	    val diarMinDurationOnSec: Float,
20	    val diarMinDurationOffSec: Float,
21	    val diarClusterThreshold: Float,
22	    val asrCropPadMs: Long,
23	    val orphanMinMs: Long,
24	    val expandIfCoverageBelow: Float,
25	    val expandMaxRegionMs: Long,
26	    val mergeGapMs: Long,
27	) {
28	    companion object {
29	        val stage6: FarFieldScene = FarFieldScene(
30	            name = "DEMO_STAGE6",
31	            expectedSpeakers = 6,
32	            // High-recall Silero after FarFieldGain; do not hard-drop 4–6 m.
33	            vadThreshold = 0.32f,
34	            vadMinSilenceSec = 0.18f,
35	            vadMinSpeechSec = 0.20f,
36	            vadMaxSpeechSec = 30f,
37	            // Keep short far fragments (s5 was 4.7 s at 5.4 m, missing @ a8b39d5a).
38	            // Clustering: K=6 (threshold unused when numClusters>0). Soft min-on
39	            // so embedding path does not drop a quiet 5.4 m island.
40	            diarMinDurationOnSec = 0.08f,
41	            diarMinDurationOffSec = 0.18f,
42	            diarClusterThreshold = 0.5f,
43	            asrCropPadMs = 200L,
44	            orphanMinMs = 400L,
45	            expandIfCoverageBelow = 0.5f,
46	            // Endpoint snap only. A 12 s expand would swallow the 3 m seat.
47	            expandMaxRegionMs = 1_500L,
48	            mergeGapMs = 280L,
49	        )
50	
51	        /** Overnight diar/VAD; do not retune Path B. */
52	        val presentConnect: FarFieldScene = FarFieldScene(
53	            name = "presentConnect",
54	            expectedSpeakers = -1,
55	            vadThreshold = 0.5f,
56	            vadMinSilenceSec = 0.25f,
57	            vadMinSpeechSec = 0.25f,
58	            vadMaxSpeechSec = 20f,
59	            diarMinDurationOnSec = 0.2f,
60	            diarMinDurationOffSec = 0.5f,
61	            diarClusterThreshold = 0.5f,
62	            asrCropPadMs = 0L,
63	            orphanMinMs = Long.MAX_VALUE,
64	            expandIfCoverageBelow = 0f,
65	            expandMaxRegionMs = 0L,
66	            mergeGapMs = 0L,
67	        )
68	    }
69	}
70	
71	data class SpeechRegion(
72	    val startSample: Int,
73	    val endSample: Int,
74	) {
75	    fun startMs(hz: Int): Long = if (hz <= 0) 0L else startSample * 1000L / hz
76	    fun endMs(hz: Int): Long = if (hz <= 0) 0L else endSample * 1000L / hz
77	    fun durationMs(hz: Int): Long = (endMs(hz) - startMs(hz)).coerceAtLeast(0L)
78	}
79	
80	data class DiarSeg(
81	    val speakerIndex: Int,
82	    val startMs: Long,
83	    val endMs: Long,
84	) {
85	    val speakerId: String
86	        get() = "speaker_${speakerIndex + 1}"
87	}
88

#### Thinking

Now I understand the diarization reconcile logic. It's heavily tuned for the DEMO_STAGE6 scene (6 speakers at fixed distances). The user's complaint: "diarisation locks sequential replicas from the same person as if they're a new speaker every time."

Wait — where does the user-facing diarization happen? There are two paths:
1. `SherpaStenoPipeline` with `FarFieldScene.stage6` (K=6) or `presentConnect` (auto).
2. `PathBSession` / `StreamLabeler` for PresentConnect live streaming.

Let me check which scene the app uses by default — `PipelineFactory.kt`, `StenoViewModel.kt`. Also, the user says "sequential replicas from the same person as if they're a new speaker every time". 

Looking at `SpeakerSeatAssign.assign` — it maps each fragment to the closest anchor by *time gap* primarily (`c = gap - 4*ov`). Anchors are picked as the K longest segments. So a sequential replica from the same person that occurs later in time, far from the anchor, might map to a *different* anchor that is closer in time. Actually wait — the cost is gap-based, not speaker-embedding-based. So if speaker A talks at t=0 (anchor for seat 0), then speaker B at t=10 (anchor seat 1), then speaker A again at t=20, the fragment at t=20 is closer in time to... hmm, gap to anchor 0 (t=0) is 20s minus duration, gap to anchor 1 (t=10) is ~10s. So it maps to seat 1 (speaker B)! That's exactly "sequential replicas from the same person locked as a new speaker."

Hmm wait, but actually more precisely: the anchors are individual *segments*, and cost uses time gap to that one anchor segment. A same-person replica that is separated in time from its anchor by another speaker's turn will be assigned to whichever anchor is nearest in time — not to the anchor with the same voice. The `sameEnergy` penalty is only 50,000 vs gap measured in ms — a 60-second gap = 60,000 cost which outweighs the energy penalty. So yes, for longer recordings, time-gap dominates and the same physical speaker gets split across seats depending on where their turns land.

But wait — is this the actual path the user is hitting? The user says "diarisation locks sequential replicas from the same person as if theyre a new speaker every time". "Replicas" is Russian-influenced English ("реплики" = lines/turns in a dialogue). So: consecutive turns by the same speaker get labeled as different speakers.

Also `mergeSameSpeaker` only merges adjacent segments with the *same speakerIndex* — but if the upstream clustering/reconcile assigns different indices per turn, each turn becomes its own speaker.

Hold on. Let me think about what happens with the actual diarization model: `diar.process(audio.samples)` returns segments with `s.speaker` from FastClustering with numClusters=K=6 (stage6) or threshold-based (presentConnect, numClusters=-1... wait, `numClusters = scene.expectedSpeakers` = -1 for presentConnect).

Hmm, for presentConnect: expectedSpeakers=-1 → in `transcribeAsr`, `staged = null` (no FarFieldGain), energy VAD only, and `diarize` uses FastClusteringConfig(numClusters=-1, threshold=0.5). Then `VadDiarReconcile.run` with scene.expectedSpeakers <= 0 returns `diar.sortedBy { it.startMs }` — no reconcile. So labels come straight from sherpa's clustering.

Which scene does the actual app use? Let me check PipelineFactory and StenoViewModel. Also the crash: "app crashes after расшифровка if voiceline was too long". "расшифровка" = the transcription stage in the UI. Let me look at the UI strings and StenoViewModel to see what happens after transcribe — probably the LLM post-pass (cleanup) or displaying. The crash could be:

1. LLM context overflow: long transcript → GGUF context exceeded → native crash (llama.cpp abort).
2. OOM: the whole audio FloatArray + crops + LLM.
3. The `OnDeviceLlmRunner` may have a fixed context and tokenizes the entire transcript; if too long → crash.

Let me read the LLM files and the ViewModel. Also check PipelineFactory for which scene is used in the app.

#### Tool: Read

**Input:**

```json
{
  "file_path": "E:\\Projects\\neuro-stenographer\\neuro-stenographer\\src\\android\\app\\src\\main\\java\\team\\mikcoders\\neurosteno\\llm\\LocalLlmPostPass.kt"
}
```

**Response:**

1	package team.mikcoders.neurosteno.llm
2	
3	import kotlinx.coroutines.CancellationException
4	import kotlinx.coroutines.currentCoroutineContext
5	import kotlinx.coroutines.ensureActive
6	import team.mikcoders.neurosteno.export.Utterance
7	import java.io.File
8	
9	/**
10	 * On-device LLM post-pass (Hex.Team TZ «локальная LLM»).
11	 *
12	 * Identity / heuristic-only **fails** the brief. Tiny GGUF
13	 * (Qwen2-0.5B-Instruct Q4_0) is loaded from filesDir/models/llm/. That tree is
14	 * filled either by `AssetModelUnpack` when the APK was built with
15	 * `-PembedLlm=true` (the hand-in check APK) or by
16	 * `scripts/download-models.sh` + adb push during development. llama.cpp only
17	 * adds spoken-intonation punct ([IntonationPunct]): Zipformer emits NONE.
18	 */
19	interface LocalLlmPostPass {
20	    val weightsPresent: Boolean
21	    val isOnDeviceLlm: Boolean
22	    val displayName: String
23	
24	    suspend fun cleanup(utterances: List<Utterance>): List<Utterance>
25	    suspend fun trimMemory() {}
26	    suspend fun close() {}
27	}
28	
29	class IdentityLlmPostPass : LocalLlmPostPass {
30	    override val weightsPresent: Boolean = false
31	    override val isOnDeviceLlm: Boolean = false
32	    override val displayName: String = "llm-identity"
33	
34	    override suspend fun cleanup(utterances: List<Utterance>): List<Utterance> = utterances
35	}
36	
37	/**
38	 * Intonation-safe punct when GGUF is absent. **Not** the check-APK LLM.
39	 * Must not strip hyp punctuation or rewrite commas.
40	 */
41	class HeuristicPunctuationPostPass : LocalLlmPostPass {
42	    override val weightsPresent: Boolean = false
43	    override val isOnDeviceLlm: Boolean = false
44	    override val displayName: String = "llm-weights-missing (intonation punct only)"
45	
46	    override suspend fun cleanup(utterances: List<Utterance>): List<Utterance> =
47	        applyIntonation(utterances)
48	
49	    companion object {
50	        fun punctuate(raw: String, nextGapMs: Long = Long.MAX_VALUE): String =
51	            IntonationPunct.apply(raw, candidate = null, nextGapMs = nextGapMs)
52	
53	        fun applyIntonation(utterances: List<Utterance>): List<Utterance> =
54	            utterances.mapIndexed { i, u ->
55	                val nextGap = if (i + 1 < utterances.size) {
56	                    (utterances[i + 1].startMs - u.endMs).coerceAtLeast(0L)
57	                } else {
58	                    Long.MAX_VALUE
59	                }
60	                u.copy(text = IntonationPunct.apply(u.text, nextGapMs = nextGap))
61	            }
62	    }
63	}
64	
65	class MissingWeightsLlmPostPass : LocalLlmPostPass by HeuristicPunctuationPostPass()
66	
67	/**
68	 * Real tiny GGUF via llama.cpp. Prompt asks only for spoken-intonation punct.
69	 * Output is merged with [IntonationPunct] so the model cannot strip marks or
70	 * rewrite commas to textbook placement.
71	 */
72	class GgufLlmPostPass(
73	    private val gguf: File,
74	    private val runner: OnDeviceLlmRunner,
75	) : LocalLlmPostPass {
76	    override val weightsPresent: Boolean = gguf.isFile && gguf.length() > 0L
77	    override val isOnDeviceLlm: Boolean = true
78	    override val displayName: String = runner.engineName
79	
80	    override suspend fun trimMemory() = runner.trimMemory()
81	    override suspend fun close() = runner.close()
82	
83	    override suspend fun cleanup(utterances: List<Utterance>): List<Utterance> {
84	        if (!weightsPresent) {
85	            return HeuristicPunctuationPostPass.applyIntonation(utterances)
86	        }
87	        return utterances.mapIndexed { i, u ->
88	            currentCoroutineContext().ensureActive()
89	            val nextGap = if (i + 1 < utterances.size) {
90	                (utterances[i + 1].startMs - u.endMs).coerceAtLeast(0L)
91	            } else {
92	                Long.MAX_VALUE
93	            }
94	            val candidate = try {
95	                runner.complete(SYSTEM, userPrompt(u.text, nextGap))
96	            } catch (cancelled: CancellationException) {
97	                throw cancelled
98	            } catch (_: Throwable) {
99	                null
100	            }
101	            u.copy(text = IntonationPunct.apply(u.text, candidate, nextGap))
102	        }
103	    }
104	
105	    companion object {
106	        const val SYSTEM =
107	            "ASR (Zipformer) не ставит знаки. ДОБАВЬ пунктуацию устной речи: запятые, точки, ? !. " +
108	                "Запятая = пауза/интонация говорящего, не школьное правило. " +
109	                "НЕ удаляй уже стоящие знаки. НЕ переставляй чужие запятые. " +
110	                "Не меняй слова. ё и е — один звук, пиши е. Верни только исправленную реплику."
111	
112	        fun userPrompt(text: String, nextGapMs: Long): String =
113	            "Пауза после реплики: ${nextGapMs.coerceAtMost(10_000L)} мс.\nРеплика: $text"
114	    }
115	}
116	
117	object LlmPostPassFactory {
118	    fun create(gguf: File?, runner: OnDeviceLlmRunner? = null): LocalLlmPostPass {
119	        if (gguf != null && gguf.isFile && gguf.length() > 0L) {
120	            return GgufLlmPostPass(gguf, runner ?: LlamaCppGgufRunner(gguf))
121	        }
122	        return MissingWeightsLlmPostPass()
123	    }
124	}
125

#### Tool: Read

**Input:**

```json
{
  "file_path": "E:\\Projects\\neuro-stenographer\\neuro-stenographer\\src\\android\\app\\src\\main\\java\\team\\mikcoders\\neurosteno\\llm\\OnDeviceLlmRunner.kt"
}
```

**Response:**

1	package team.mikcoders.neurosteno.llm
2	
3	import dev.ffmpegkit.llama.Llama
4	import dev.ffmpegkit.llama.LlamaConfig
5	import java.io.File
6	import team.mikcoders.neurosteno.pipeline.InferenceSession
7	
8	/** Tiny on-device GGUF completion. No cloud. */
9	interface OnDeviceLlmRunner {
10	    val engineName: String
11	    val ready: Boolean
12	    suspend fun complete(system: String, user: String): String
13	    suspend fun trimMemory() {}
14	    suspend fun close() {}
15	}
16	
17	/**
18	 * llama.cpp JNI via `dev.ffmpegkit-maintained:llama-android` (GGUF, arm64).
19	 * Loads [gguf] from filesDir/models/llm (Platform unpack; we do not pack assets).
20	 */
21	class LlamaCppGgufRunner(
22	    private val gguf: File,
23	    private val contextSize: Int = 1_024,
24	    private val threads: Int = 2,
25	    private val maxTokens: Int = 96,
26	) : OnDeviceLlmRunner {
27	    override val engineName: String = "llama.cpp GGUF ${gguf.name}"
28	
29	    @Volatile override var ready: Boolean = false
30	        private set
31	
32	    private val session = InferenceSession(
33	        create = {
34	            require(gguf.isFile && gguf.length() > 0L) { "GGUF missing: ${gguf.path}" }
35	            Llama.loadModel(
36	                modelPath = gguf.absolutePath,
37	                config = LlamaConfig(contextSize = contextSize, threads = threads),
38	            ).also { ready = true }
39	        },
40	        destroy = { model ->
41	            ready = false
42	            Llama.releaseModel(model)
43	        },
44	    )
45	
46	    override suspend fun complete(system: String, user: String): String =
47	        session.run { getModel ->
48	            Llama.complete(
49	                getModel(),
50	                prompt = user,
51	                systemPrompt = system,
52	                maxTokens = maxTokens,
53	            ).text.trim()
54	        }
55	
56	    override suspend fun trimMemory() = session.evict()
57	
58	    override suspend fun close() = session.close()
59	}
60	
61	/** Test double — still the GGUF post-pass path, not identity. */
62	class ScriptedLlmRunner(
63	    private val replies: (String) -> String,
64	    override val engineName: String = "llama.cpp GGUF scripted",
65	) : OnDeviceLlmRunner {
66	    override val ready: Boolean = true
67	    override suspend fun complete(system: String, user: String): String = replies(user)
68	}
69

#### Thinking

The LLM post-pass works per utterance, so a long *voiceline* (one long segment) would produce a very long `u.text`, and `runner.complete(SYSTEM, userPrompt(u.text, nextGap))` with `contextSize = 1024` — if the prompt exceeds the context, llama.cpp typically... depends on the ffmpegkit llama wrapper. It might crash natively (llama_decode returns error, or the wrapper might not handle it). Actually in llama.cpp, if the prompt is longer than n_ctx, `llama_decode` fails and the wrapper may loop infinitely or abort. But `GgufLlmPostPass.cleanup` catches Throwable → candidate = null. A *native* crash (SIGSEGV/abort) can't be caught though.

Wait, the user says "app crashes after расшифровка if voiceline was too long". Let me check the UI flow — StenoViewModel — what happens after transcribe. Also check MainActivity. Let me also check where "расшифровка" appears in strings.

Also for the diarization complaint, I need to see which pipeline/scene the main app uses: PipelineFactory.

The LLM pass works per-utterance with a 1024-token context — a single very long voice line could overflow that. Let me check the ViewModel flow and the pipeline factory to see which scene the app actually uses.

#### Tool: Read

**Input:**

```json
{
  "file_path": "E:\\Projects\\neuro-stenographer\\neuro-stenographer\\src\\android\\app\\src\\main\\java\\team\\mikcoders\\neurosteno\\ui\\StenoViewModel.kt"
}
```

**Response:**

1	package team.mikcoders.neurosteno.ui
2	
3	import android.app.Application
4	import android.content.ComponentCallbacks2
5	import android.content.Intent
6	import android.content.res.Configuration
7	import android.net.Uri
8	import android.util.Log
9	import androidx.lifecycle.AndroidViewModel
10	import androidx.lifecycle.viewModelScope
11	import kotlinx.coroutines.CancellationException
12	import kotlinx.coroutines.CoroutineScope
13	import kotlinx.coroutines.Dispatchers
14	import kotlinx.coroutines.flow.MutableStateFlow
15	import kotlinx.coroutines.flow.StateFlow
16	import kotlinx.coroutines.flow.collect
17	import kotlinx.coroutines.flow.update
18	import kotlinx.coroutines.launch
19	import kotlinx.coroutines.withContext
20	import team.mikcoders.neurosteno.BuildConfig
21	import team.mikcoders.neurosteno.audio.AudioDecoder
22	import team.mikcoders.neurosteno.audio.MicRecorder
23	import team.mikcoders.neurosteno.audio.Pcm
24	import team.mikcoders.neurosteno.export.TranscriptFile
25	import team.mikcoders.neurosteno.export.TranscriptJson
26	import team.mikcoders.neurosteno.pipeline.DemoPcmPrep
27	import team.mikcoders.neurosteno.pipeline.ModelPaths
28	import team.mikcoders.neurosteno.pipeline.PipelineFactory
29	import team.mikcoders.neurosteno.pipeline.PipelineProgress
30	import team.mikcoders.neurosteno.pipeline.PipelineStage
31	import team.mikcoders.neurosteno.pipeline.PcmAudio
32	import team.mikcoders.neurosteno.pipeline.SherpaJni
33	import team.mikcoders.neurosteno.pipeline.StenoPipeline
34	import team.mikcoders.neurosteno.pipeline.StubStenoPipeline
35	import team.mikcoders.neurosteno.present.OkHttpPresentConnectClient
36	import team.mikcoders.neurosteno.present.PathBSession
37	import team.mikcoders.neurosteno.present.PresentConnectConfig
38	import team.mikcoders.neurosteno.present.PresentConnectEvent
39	import team.mikcoders.neurosteno.present.PresentConnectLimits
40	
41	data class StenoUiState(
42	    val fileLabel: String? = null,
43	    val importedUri: String? = null,
44	    val running: Boolean = false,
45	    val progress: PipelineProgress? = null,
46	    val transcript: TranscriptFile? = null,
47	    val jsonPreview: String? = null,
48	    val error: String? = null,
49	    val stubPath: Boolean = true,
50	    val presentConnect: Boolean = BuildConfig.PRESENT_CONNECT,
51	    val engineName: String = "stub",
52	    val modelsReady: Boolean = false,
53	    // Model load (ONNX unpack out of the judge-APK assets + GGUF probe) runs off
54	    // the main thread; the UI shows "Загрузка pipeline…" while this is true.
55	    val modelsLoading: Boolean = true,
56	    val recording: Boolean = false,
57	    val heldPcm: PcmAudio? = null,
58	    val jniLoaded: Boolean = false,
59	    val jniError: String? = null,
60	    val llmName: String = "llm-unset",
61	    val llmOnDevice: Boolean = false,
62	    val activeStreams: List<ActiveStreamChip> = emptyList(),
63	    val overCapacityId: String? = null,
64	    val presentConnected: Boolean = false,
65	    val presentWorkerId: String = "",
66	    val presentStreams: Int = 0,
67	    val wsUrl: String = "wss://present.example.local/v1/present-connect?session=demo&role=worker&device=phone",
68	    val micFlags: List<MicFlagChip> = emptyList(),
69	)
70	
71	data class ActiveStreamChip(
72	    val streamId: String,
73	    val speakerId: String,
74	    val role: String,
75	    val qualityCode: String? = null,
76	)
77	
78	data class MicFlagChip(
79	    val streamId: String,
80	    val speakerId: String,
81	    val code: String,
82	    val hardReject: Boolean,
83	)
84	
85	class StenoViewModel(app: Application) : AndroidViewModel(app) {
86	    @Volatile private var pipeline: StenoPipeline = StubStenoPipeline()
87	    @Volatile private var models: ModelPaths? = null
88	    @Volatile private var pathB: PathBSession? = null
89	    @Volatile private var presentClient: OkHttpPresentConnectClient? = null
90	
91	    private val _state = MutableStateFlow(
92	        StenoUiState(
93	            jniLoaded = SherpaJni.last.loaded,
94	            jniError = SherpaJni.last.error,
95	        ),
96	    )
97	    val state: StateFlow<StenoUiState> = _state
98	    private val mic = MicRecorder()
99	    private val memoryCallbacks = object : ComponentCallbacks2 {
100	        override fun onConfigurationChanged(newConfig: Configuration) = Unit
101	        override fun onLowMemory() = trimModels()
102	        override fun onTrimMemory(level: Int) {
103	            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trimModels()
104	        }
105	    }
106	
107	    private fun trimModels() {
108	        viewModelScope.launch {
109	            try {
110	                pipeline.trimMemory()
111	            } catch (cancelled: CancellationException) {
112	                throw cancelled
113	            } catch (failure: Throwable) {
114	                Log.w("StenoViewModel", "Native model eviction failed", failure)
115	            }
116	        }
117	    }
118	
119	    override fun onCleared() {
120	        getApplication<Application>().unregisterComponentCallbacks(memoryCallbacks)
121	        val retiringPipeline = pipeline
122	        val retiringClient = presentClient
123	        // viewModelScope is cancelled at this point. Teardown must outlive it,
124	        // wait for JNI to return, and never block the main thread.
125	        CoroutineScope(Dispatchers.Default).launch {
126	            try {
127	                try {
128	                    retiringClient?.disconnect()
129	                } finally {
130	                    retiringPipeline.close()
131	                }
132	            } catch (failure: Throwable) {
133	                Log.w("StenoViewModel", "Native model teardown failed", failure)
134	            }
135	        }
136	        super.onCleared()
137	    }
138	
139	    init {
140	        app.registerComponentCallbacks(memoryCallbacks)
141	        viewModelScope.launch {
142	            val created = withContext(Dispatchers.IO) { PipelineFactory.create(getApplication()) }
143	            pipeline = created.first
144	            models = created.second
145	            _state.update {
146	                it.copy(
147	                    engineName = created.first.displayName,
148	                    stubPath = created.first is StubStenoPipeline,
149	                    modelsReady = created.second.readyForOfflineDemo,
150	                    modelsLoading = false,
151	                    llmName = created.first.llmDisplayName,
152	                    llmOnDevice = created.first.llmIsOnDevice,
153	                    jniLoaded = SherpaJni.last.loaded,
154	                    jniError = SherpaJni.last.error,
155	                )
156	            }
157	            if (BuildConfig.PRESENT_CONNECT && presentClient == null) {
158	                val session = PathBSession.fromPipeline(created.first)
159	                pathB = session
160	                val client = OkHttpPresentConnectClient(pathB = session)
161	                presentClient = client
162	                viewModelScope.launch { client.events.collect { onPresentEvent(it) } }
163	            }
164	        }
165	    }
166	
167	    private fun onPresentEvent(e: PresentConnectEvent) {
168	        when (e) {
169	            is PresentConnectEvent.HelloOk ->
170	                _state.update {
171	                    it.copy(presentConnected = true, presentWorkerId = e.workerId, error = null)
172	                }
173	            is PresentConnectEvent.StreamStarted ->
174	                _state.update { s ->
175	                    s.copy(
176	                        activeStreams = s.activeStreams.filterNot { it.streamId == e.streamId } +
177	                            ActiveStreamChip(e.streamId, e.speakerId, e.role),
178	                        micFlags = s.micFlags.filterNot { it.streamId == e.streamId },
179	                        presentStreams = (presentClient?.activeStreamCount() ?: s.presentStreams)
180	                            .coerceAtMost(PresentConnectLimits.MAX_STREAMS),
181	                    )
182	                }
183	            is PresentConnectEvent.StreamEnded ->
184	                _state.update { s ->
185	                    s.copy(
186	                        activeStreams = s.activeStreams.filterNot { it.streamId == e.streamId },
187	                        presentStreams = presentClient?.activeStreamCount() ?: s.presentStreams,
188	                    )
189	                }
190	            is PresentConnectEvent.Final -> {
191	                val brief = pathB?.brief() ?: return
192	                _state.update {
193	                    it.copy(
194	                        transcript = brief,
195	                        jsonPreview = team.mikcoders.neurosteno.present.PresentConnectWire
196	                            .briefJson(brief.transcript),
197	                    )
198	                }
199	            }
200	            is PresentConnectEvent.OverCapacity ->
201	                _state.update { it.copy(overCapacityId = e.rejectedStreamId) }
202	            is PresentConnectEvent.Error ->
203	                _state.update { it.copy(error = "${e.code}: ${e.message}") }
204	            is PresentConnectEvent.MicQuality ->
205	                _state.update { s ->
206	                    s.copy(
207	                        activeStreams = s.activeStreams.map { chip ->
208	                            if (chip.streamId == e.streamId) chip.copy(qualityCode = e.code) else chip
209	                        },
210	                        micFlags = s.micFlags.filterNot { it.streamId == e.streamId } +
211	                            MicFlagChip(e.streamId, e.speakerId, e.code, e.hardReject),
212	                    )
213	                }
214	            PresentConnectEvent.Disconnected ->
215	                _state.update {
216	                    it.copy(
217	                        presentConnected = false,
218	                        presentStreams = 0,
219	                        activeStreams = emptyList(),
220	                        micFlags = emptyList(),
221	                    )
222	                }
223	            is PresentConnectEvent.Partial -> { }
224	        }
225	    }
226	
227	    fun updateWsUrl(url: String) {
228	        _state.update { it.copy(wsUrl = url) }
229	    }
230	
231	    fun connectPresent() {
232	        val client = presentClient ?: return
233	        val url = _state.value.wsUrl
234	        viewModelScope.launch {
235	            client.connect(
236	                PresentConnectConfig(
237	                    wsUrl = url,
238	                    httpBaseUrl = url.substringBefore("/v1").ifBlank { url },
239	                    sessionId = "demo",
240	                    deviceId = "phone",
241	                ),
242	            )
243	        }
244	    }
245	
246	    fun disconnectPresent() {
247	        viewModelScope.launch { presentClient?.disconnect() }
248	    }
249	
250	    fun onFilePicked(uri: Uri, displayName: String?) {
251	        try {
252	            getApplication<Application>().contentResolver.takePersistableUriPermission(
253	                uri,
254	                Intent.FLAG_GRANT_READ_URI_PERMISSION,
255	            )
256	        } catch (_: SecurityException) {
257	            // One-shot SAF grant is enough for this session.
258	        }
259	        _state.update {
260	            it.copy(
261	                fileLabel = displayName ?: uri.lastPathSegment ?: uri.toString(),
262	                importedUri = uri.toString(),
263	                error = null,
264	                transcript = null,
265	                jsonPreview = null,
266	                heldPcm = null,
267	            )
268	        }
269	    }
270	
271	    fun toggleRecord() {
272	        if (mic.isRecording) {
273	            val audio = mic.stop()
274	            _state.update {
275	                it.copy(
276	                    recording = false,
277	                    heldPcm = audio,
278	                    fileLabel = "mic ${audio.durationMs} ms",
279	                    importedUri = null,
280	                )
281	            }
282	        } else {
283	            mic.start()
284	            _state.update { it.copy(recording = true, error = null) }
285	        }
286	    }
287	
288	    fun transcribe() {
289	        val label = _state.value.fileLabel ?: "mic-or-unknown"
290	        if (_state.value.running || _state.value.modelsLoading) return
291	        val request = _state.value
292	        viewModelScope.launch {
293	            _state.update { it.copy(running = true, error = null, progress = PipelineProgress(PipelineStage.Decode, "start", 0f)) }
294	            try {
295	                val audio = withContext(Dispatchers.Default) {
296	                    val held = request.heldPcm
297	                    val uriStr = request.importedUri
298	                    when {
299	                        held != null && uriStr == null -> held
300	                        uriStr != null -> AudioDecoder.decode(getApplication(), Uri.parse(uriStr), label)
301	                        else -> PcmAudio(
302	                            samples = FloatArray(Pcm.TARGET_HZ * 4),
303	                            sampleRate = Pcm.TARGET_HZ,
304	                            sourceName = label,
305	                        )
306	                    }
307	                }
308	                val prep = withContext(Dispatchers.Default) { DemoPcmPrep.prepare(audio) }
309	                if (prep.skipAsr) {
310	                    _state.update {
311	                        it.copy(
312	                            running = false,
313	                            error = prep.skipCode,
314	                            transcript = TranscriptFile(emptyList()),
315	                            jsonPreview = null,
316	                        )
317	                    }
318	                    return@launch
319	                }
320	                _state.update {
321	                    it.copy(
322	                        progress = PipelineProgress(
323	                            PipelineStage.Decode,
324	                            if (prep.eq.applied) "phone-distance EQ" else "per-turn far-field +${prep.gain.gainDb} dB",
325	                            0.08f,
326	                        ),
327	                    )
328	                }
329	                val result = pipeline.transcribe(prep.audio) { p ->
330	                    _state.update { it.copy(progress = p) }
331	                }
332	                _state.update {
333	                    it.copy(
334	                        running = false,
335	                        transcript = result,
336	                        jsonPreview = TranscriptJson.encode(result),
337	                        stubPath = pipeline is StubStenoPipeline,
338	                        engineName = pipeline.displayName,
339	                        llmName = pipeline.llmDisplayName,
340	                        llmOnDevice = pipeline.llmIsOnDevice,
341	                    )
342	                }
343	            } catch (cancelled: CancellationException) {
344	                _state.update { it.copy(running = false) }
345	                throw cancelled
346	            } catch (t: Throwable) {
347	                _state.update {
348	                    it.copy(running = false, error = t.message ?: t::class.java.simpleName)
349	                }
350	            }
351	        }
352	    }
353	
354	    fun clearError() {
355	        _state.update { it.copy(error = null) }
356	    }
357	}
358

#### Tool: Read

**Input:**

```json
{
  "file_path": "E:\\Projects\\neuro-stenographer\\neuro-stenographer\\src\\android\\app\\src\\main\\java\\team\\mikcoders\\neurosteno\\pipeline\\PipelineFactory.kt"
}
```

**Response:**

1	package team.mikcoders.neurosteno.pipeline
2	
3	import android.content.Context
4	import android.util.Log
5	import team.mikcoders.neurosteno.BuildConfig
6	import team.mikcoders.neurosteno.llm.LlmPostPassFactory
7	
8	object PipelineFactory {
9	    private const val TAG = "PipelineFactory"
10	
11	    fun create(context: Context): Pair<StenoPipeline, ModelPaths> {
12	        val jni = SherpaJni.probe()
13	        if (!jni.loaded) {
14	            Log.w(TAG, "sherpa JNI not loaded: ${jni.error}")
15	        }
16	        val paths = ModelStore(context).resolve()
17	        val llm = LlmPostPassFactory.create(paths.llmGguf)
18	        if (!paths.hasPrimaryAsr && !paths.hasBackupAsr) {
19	            Log.w(TAG, "No ASR models in ${ModelStore(context).modelsDir()} — stub pipeline")
20	            return StubStenoPipeline() to paths
21	        }
22	        if (!jni.loaded) {
23	            Log.e(TAG, "models present but JNI failed — stub. ${jni.error}")
24	            return StubStenoPipeline() to paths
25	        }
26	        // PresentConnect stays on overnight clustering; the offline demo claims
27	        // DEMO_STAGE6 (one mic, 6 speakers, 3–6 m).
28	        val scene = if (BuildConfig.PRESENT_CONNECT) {
29	            FarFieldScene.presentConnect
30	        } else {
31	            FarFieldScene.stage6
32	        }
33	        return try {
34	            SherpaStenoPipeline(paths, llm, scene) to paths
35	        } catch (t: Throwable) {
36	            Log.e(TAG, "sherpa init failed, stub", t)
37	            StubStenoPipeline() to paths
38	        }
39	    }
40	}
41

#### Thinking

So the default app (non-PresentConnect) uses `FarFieldScene.stage6` — K=6 hard cap. The user's scenario: they record/import a voiceline (maybe a dialogue or a single person speaking), and diarization labels sequential replicas of the same person as new speakers.

Wait — actually, let me re-read the user's complaint: "diarisation locks sequential replicas from the same person as if theyre a new speaker every time."

Given the stage6 scene with K=6: `VadDiarReconcile` + `SpeakerSeatAssign` is tuned for the specific 6-seat demo. For a general recording (e., 1-2 people), this reconcile logic forces up to 6 seats: `adopt` creates new speakers while unique < K, and `SpeakerSeatAssign.pickAnchors` picks up to K anchors and assigns fragments by time-gap cost. For a single person speaking multiple turns:

1. pyannote + FastClustering(numClusters=6) on a 1-person recording → forced 6 clusters, splitting one voice into 6 "speakers". Actually with numClusters=6, fast clustering will partition embeddings into 6 clusters even if there's only 1 speaker! That's a huge issue for general use: K is hardcoded to 6 for the demo scene. `mergeSameSpeaker` only merges same-index adjacent segments, `capToExpected` caps at 6 but never reduces below. So a 1-person recording yields up to 6 speakers. `SpeakerSeatAssign` then maps each fragment to nearest anchor by time → sequential turns of the same person land on different seats → "new speaker every time".

Hmm wait, but does the eval claim require K=6 hard cap? Yes — the brief's DEMO_STAGE6 has exactly 6 speakers. But the user is now using the app for real recordings ("voiceline too long" crash). The user wants:
1. Same person across sequential replicas → same speaker label. Long recording → crash after transcription.

Let me now check where the crash could be. "after расшифровка" — the UI button probably says "Расшифровка" (transcribe). Let me check StenoApp.kt for the button label. The crash happens *after* transcribe — so during LLM post-pass or when rendering the result, or memory.

Key suspects for the crash:
1. `MicRecorder` — accumulates samples in memory; long recording → FloatArray huge. Let me check. `TranscriptJson.encode(result)` — long transcript → huge JSON string in UI → maybe not crash but slow. `GgufLlmPostPass` per utterance — a long *single* utterance (vadMaxSpeechSec=30s → up to 30s of speech ≈ maybe 100+ words) — prompt could approach 1024 tokens? 30s of Russian speech ≈ 60-90 words ≈ 150-250 tokens. Not overflow. But wait — what if diarization is absent and VAD maxSpeechDuration forces splits at 30s... Actually `vadMaxSpeechSec = 30f` — Silero splits at 30s. Hmm.

Wait, "voiceline was too long" — maybe the whole *recording* is long (e., 10+ minutes). Then:
- `MicRecorder.stop()` returns PcmAudio with all samples (float 16kHz: 10 min = 9.6M samples = 38MB; 1 hour = 230MB). 
- `AudioDecoder.decode` for imported files — decodes whole file into memory.
- `diar.process(audio.samples)` — pyannote segmentation on the *entire* audio at once. The segmentation model processes the whole waveform — memory scales with duration. For very long audio this could OOM natively.
- ASR per segment fine.
- LLM per utterance fine.

But the user says the crash happens *after* расшифровка (transcription), i., after the transcription completes? Or "after [pressing] расшифровка"? Russian UI label probably "Расшифровать" (button). "app crashes after расшифровка if voiceline was too long" — most likely: after hitting the transcribe button, when the voice recording is long, the app crashes. So during the pipeline.

Let me check MicRecorder and AudioDecoder for memory behavior, and DemoPcmPrep. Also let me look at the in-flight diff to understand what the other session changed (exclusive phases — probably already addresses peak RAM between ASR and LLM). Let me view the diff.

#### Tool: Bash

**Input:**

```json
{
  "command": "git diff src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/SherpaStenoPipeline.kt",
  "description": "Show uncommitted pipeline changes"
}
```

**Response:**

warning: in the working copy of 'src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/SherpaStenoPipeline.kt', CRLF will be replaced by LF the next time Git touches it
diff --git a/src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/SherpaStenoPipeline.kt b/src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/SherpaStenoPipeline.kt
index 1b7cd92..9c534fb 100644
--- a/src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/SherpaStenoPipeline.kt
+++ b/src/android/app/src/main/java/team/mikcoders/neurosteno/pipeline/SherpaStenoPipeline.kt
@@ -68,9 +68,31 @@ class SherpaStenoPipeline(
     override suspend fun transcribe(
         audio: PcmAudio,
         onProgress: (PipelineProgress) -> Unit,
-    ): TranscriptFile = inference.run { getRecognizer ->
+    ): TranscriptFile {
         val context = currentCoroutineContext()
         fun checkpoint() = context.ensureActive()
+        val cleaned = runExclusivePhases(
+            asrPhase = { inference.run { getRecognizer -> transcribeAsr(audio, getRecognizer, onProgress, ::checkpoint) } },
+            // Peak native RAM is max(ASR, LLM), not the sum: free the recognizer
+            // (weights + ORT arenas) before the GGUF loads.
+            releaseAsr = { inference.evict() },
+            llmPhase = { raw ->
+                checkpoint()
+                onProgress(PipelineProgress(PipelineStage.Llm, llm.displayName, 0.92f))
+                llm.cleanup(raw)
+            },
+        )
+        checkpoint()
+        onProgress(PipelineProgress(PipelineStage.Done, "${cleaned.size} utterances", 1f))
+        return TranscriptFile(transcript = cleaned.sortedBy { it.startMs })
+    }
+
+    private suspend fun transcribeAsr(
+        audio: PcmAudio,
+        getRecognizer: suspend () -> OfflineRecognizer,
+        onProgress: (PipelineProgress) -> Unit,
+        checkpoint: () -> Unit,
+    ): List<Utterance> {
         require(audio.sampleRate == Pcm.TARGET_HZ) { "need 16 kHz PCM" }
         onProgress(PipelineProgress(PipelineStage.Decode, "PCM ${audio.samples.size} samples", 0.05f))
 
@@ -94,28 +116,22 @@ class SherpaStenoPipeline(
             ),
         )
         checkpoint()
-        val silero = if (paths.hasVad) collectSilero(work, ::checkpoint) else emptyList()
+        val silero = if (paths.hasVad) collectSilero(work, checkpoint) else emptyList()
         val regions = FarFieldVad.union(energy, silero)
 
-        val raw = if (paths.hasDiar) {
+        return if (paths.hasDiar) {
             onProgress(PipelineProgress(PipelineStage.Diarize, "OfflineSpeakerDiarization K=${scene.expectedSpeakers}", 0.25f))
-            // Release segmentation/embedding sessions before loading the cold
-            // ASR model. Subsequent calls may reuse one warm ASR model.
+            // Diarization sessions are released before the ASR model loads;
+            // the ASR session is released after this phase, before the LLM.
             val segments = diarize(work, regions)
             checkpoint()
             if (segments.isEmpty()) emptyList() else {
-                transcribeSegments(work, segments, getRecognizer(), onProgress, ::checkpoint)
+                transcribeSegments(work, segments, getRecognizer(), onProgress, checkpoint)
             }
         } else {
             onProgress(PipelineProgress(PipelineStage.Vad, "Silero VAD chunks", 0.25f))
-            vadThenAsr(work, getRecognizer(), onProgress, ::checkpoint)
+            vadThenAsr(work, getRecognizer(), onProgress, checkpoint)
         }
-        checkpoint()
-        onProgress(PipelineProgress(PipelineStage.Llm, llm.displayName, 0.92f))
-        val cleaned = llm.cleanup(raw)
-        checkpoint()
-        onProgress(PipelineProgress(PipelineStage.Done, "${cleaned.size} utterances", 1f))
-        TranscriptFile(transcript = cleaned.sortedBy { it.startMs })
     }
 
     private fun diarize(

#### Tool: Read

**Input:**

```json
{
  "file_path": "E:\\Projects\\neuro-stenographer\\neuro-stenographer\\src\\android\\app\\src\\main\\java\\team\\mikcoders\\neurosteno\\audio\\MicRecorder.kt"
}
```

**Response:**

1	package team.mikcoders.neurosteno.audio
2	
3	import android.media.AudioFormat
4	import android.media.AudioRecord
5	import android.media.MediaRecorder
6	import team.mikcoders.neurosteno.pipeline.PcmAudio
7	import java.util.concurrent.atomic.AtomicBoolean
8	
9	/** In-app record → 16 kHz mono float. Separate from PresentConnect browser mics. */
10	class MicRecorder {
11	    private val running = AtomicBoolean(false)
12	    private var thread: Thread? = null
13	    private val pcm = java.io.ByteArrayOutputStream()
14	
15	    val isRecording: Boolean get() = running.get()
16	
17	    fun start() {
18	        if (!running.compareAndSet(false, true)) return
19	        pcm.reset()
20	        val min = AudioRecord.getMinBufferSize(
21	            Pcm.TARGET_HZ,
22	            AudioFormat.CHANNEL_IN_MONO,
23	            AudioFormat.ENCODING_PCM_16BIT,
24	        )
25	        val rec = AudioRecord(
26	            MediaRecorder.AudioSource.MIC,
27	            Pcm.TARGET_HZ,
28	            AudioFormat.CHANNEL_IN_MONO,
29	            AudioFormat.ENCODING_PCM_16BIT,
30	            min.coerceAtLeast(Pcm.TARGET_HZ),
31	        )
32	        rec.startRecording()
33	        thread = Thread {
34	            val buf = ByteArray(min.coerceAtLeast(3200))
35	            try {
36	                while (running.get()) {
37	                    val n = rec.read(buf, 0, buf.size)
38	                    if (n > 0) {
39	                        synchronized(pcm) { pcm.write(buf, 0, n) }
40	                    }
41	                }
42	            } finally {
43	                rec.stop()
44	                rec.release()
45	            }
46	        }.also { it.start() }
47	    }
48	
49	    fun stop(): PcmAudio {
50	        running.set(false)
51	        thread?.join(2_000)
52	        thread = null
53	        val bytes = synchronized(pcm) { pcm.toByteArray() }
54	        val samples = Pcm.s16leToFloat(bytes)
55	        return PcmAudio(samples = samples, sampleRate = Pcm.TARGET_HZ, sourceName = "mic")
56	    }
57	}
58

#### Thinking

The in-flight work restructures transcribe into exclusive phases (ASR evicted before LLM loads) — that's the other session's memory work. I should not disturb it; my changes need to integrate.

MicRecorder: `ByteArrayOutputStream` grows unbounded — 16kHz 16-bit = 32KB/s = 1.9MB/min. 10 min = 19MB bytes, then `toByteArray()` copies (2x), then `s16leToFloat` makes float array (4 bytes/sample = 2x bytes) — total transient ~5x. Not the crash for moderate lengths, but for very long (1h: 115MB bytes → 230MB copy → 460MB floats) could OOM on the JVM heap. Android heap limit maybe 256-512MB. Hmm.

But the user says crash happens *after* расшифровка (transcription) — i., the pipeline finishes and then the app crashes? Or during? Let me look at the UI: StenoApp.kt — what happens when transcript is set. `TranscriptJson.encode(result)` — builds a JSON string. For a long recording with many utterances, the JSON preview string could be huge, and rendering it in a Text composable... Compose can handle long strings but measuring a huge text could be slow/ANR, not crash.

Let me look at StenoApp.kt and TranscriptJson. Also AudioDecoder. And DemoPcmPrep.

#### Tool: Read

**Input:**

```json
{
  "file_path": "E:\\Projects\\neuro-stenographer\\neuro-stenographer\\src\\android\\app\\src\\main\\java\\team\\mikcoders\\neurosteno\\ui\\StenoApp.kt"
}
```

**Response:**

1	package team.mikcoders.neurosteno.ui
2	
3	import android.Manifest
4	import android.content.Intent
5	import android.provider.OpenableColumns
6	import androidx.activity.compose.rememberLauncherForActivityResult
7	import androidx.activity.result.contract.ActivityResultContracts
8	import androidx.compose.foundation.layout.Arrangement
9	import androidx.compose.foundation.layout.Column
10	import androidx.compose.foundation.layout.PaddingValues
11	import androidx.compose.foundation.layout.Row
12	import androidx.compose.foundation.layout.Spacer
13	import androidx.compose.foundation.layout.fillMaxSize
14	import androidx.compose.foundation.layout.fillMaxWidth
15	import androidx.compose.foundation.layout.height
16	import androidx.compose.foundation.layout.padding
17	import androidx.compose.foundation.lazy.LazyColumn
18	import androidx.compose.foundation.lazy.items
19	import androidx.compose.material3.Button
20	import androidx.compose.material3.Card
21	import androidx.compose.material3.CircularProgressIndicator
22	import androidx.compose.material3.ExperimentalMaterial3Api
23	import androidx.compose.material3.FilterChip
24	import androidx.compose.material3.LinearProgressIndicator
25	import androidx.compose.material3.MaterialTheme
26	import androidx.compose.material3.OutlinedButton
27	import androidx.compose.material3.Scaffold
28	import androidx.compose.material3.OutlinedTextField
29	import androidx.compose.material3.Text
30	import androidx.compose.material3.TopAppBar
31	import androidx.compose.runtime.Composable
32	import androidx.compose.runtime.collectAsState
33	import androidx.compose.runtime.getValue
34	import androidx.compose.ui.Modifier
35	import androidx.compose.ui.platform.LocalContext
36	import androidx.compose.ui.unit.dp
37	import androidx.lifecycle.viewmodel.compose.viewModel
38	import team.mikcoders.neurosteno.export.Utterance
39	
40	@OptIn(ExperimentalMaterial3Api::class)
41	@Composable
42	fun StenoApp(vm: StenoViewModel = viewModel()) {
43	    val state by vm.state.collectAsState()
44	    val context = LocalContext.current
45	    val picker = rememberLauncherForActivityResult(
46	        contract = ActivityResultContracts.OpenDocument(),
47	    ) { uri ->
48	        if (uri == null) return@rememberLauncherForActivityResult
49	        var name: String? = null
50	        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
51	            ?.use { c ->
52	                if (c.moveToFirst()) name = c.getString(0)
53	            }
54	        vm.onFilePicked(uri, name)
55	    }
56	    val jsonSaver = rememberLauncherForActivityResult(
57	        contract = ActivityResultContracts.CreateDocument("application/json"),
58	    ) { uri ->
59	        if (uri == null) return@rememberLauncherForActivityResult
60	        val json = vm.state.value.jsonPreview ?: return@rememberLauncherForActivityResult
61	        context.contentResolver.openOutputStream(uri)?.use { os ->
62	            os.write(json.toByteArray(Charsets.UTF_8))
63	        }
64	    }
65	    val recPerm = rememberLauncherForActivityResult(
66	        contract = ActivityResultContracts.RequestPermission(),
67	    ) { granted ->
68	        if (granted) vm.toggleRecord()
69	    }
70	
71	    Scaffold(
72	        topBar = {
73	            TopAppBar(
74	                title = { Text("Нейро-стенографист") },
75	            )
76	        },
77	    ) { padding ->
78	        Column(
79	            modifier = Modifier
80	                .fillMaxSize()
81	                .padding(padding)
82	                .padding(horizontal = 16.dp),
83	        ) {
84	            Text(
85	                if (state.presentConnect) {
86	                    "PresentConnect Path B ≤20 — опциональный сетевой режим, не слайд. Live demo = DEMO_STAGE6: один телефонный mic."
87	                } else {
88	                    "Live demo = DEMO_STAGE6: один mic (Nothing-class), 6 говорящих по-русски, 3–6 м, большая комната, не студия. Path B ≤20 — опциональный PresentConnect."
89	                },
90	                style = MaterialTheme.typography.bodyMedium,
91	            )
92	            if (state.presentConnect) {
93	                Spacer(Modifier.height(8.dp))
94	                OutlinedTextField(
95	                    value = state.wsUrl,
96	                    onValueChange = { vm.updateWsUrl(it) },
97	                    modifier = Modifier.fillMaxWidth(),
98	                    label = { Text("PresentConnect wss") },
99	                    singleLine = true,
100	                )
101	                Spacer(Modifier.height(8.dp))
102	                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
103	                    Button(onClick = { vm.connectPresent() }, enabled = !state.presentConnected) {
104	                        Text("WS connect")
105	                    }
106	                    OutlinedButton(onClick = { vm.disconnectPresent() }, enabled = state.presentConnected) {
107	                        Text("Disconnect")
108	                    }
109	                    Text(
110	                        "потоков ${state.activeStreams.size}/20",
111	                        style = MaterialTheme.typography.bodySmall,
112	                        modifier = Modifier.padding(top = 12.dp),
113	                    )
114	                }
115	                if (state.activeStreams.isNotEmpty()) {
116	                    Spacer(Modifier.height(8.dp))
117	                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
118	                        state.activeStreams.forEach { s ->
119	                            FilterChip(
120	                                selected = s.role == "main",
121	                                onClick = {},
122	                                label = {
123	                                    Text(
124	                                        buildString {
125	                                            append(s.streamId)
126	                                            append('→')
127	                                            append(s.speakerId)
128	                                            s.qualityCode?.let { append(" $it") }
129	                                        },
130	                                    )
131	                                },
132	                            )
133	                        }
134	                    }
135	                }
136	                state.overCapacityId?.let { id ->
137	                    Text(
138	                        "OVER_CAPACITY: $id (stream 21)",
139	                        color = MaterialTheme.colorScheme.error,
140	                        style = MaterialTheme.typography.bodySmall,
141	                    )
142	                }
143	                state.micFlags.forEach { f ->
144	                    Text(
145	                        "${f.code}: ${f.streamId}→${f.speakerId}" +
146	                            if (f.hardReject) " (ASR skipped)" else " (soft, ASR ran)",
147	                        color = if (f.hardReject) {
148	                            MaterialTheme.colorScheme.error
149	                        } else {
150	                            MaterialTheme.colorScheme.onSurface
151	                        },
152	                        style = MaterialTheme.typography.bodySmall,
153	                    )
154	                }
155	            }
156	            Spacer(Modifier.height(8.dp))
157	            if (state.modelsLoading) {
158	                Text(
159	                    "Загрузка pipeline…",
160	                    style = MaterialTheme.typography.bodySmall,
161	                )
162	            } else if (state.stubPath) {
163	                Text(
164	                    "Сейчас pipeline = ${state.engineName}. Не показывать судьям как ASR.",
165	                    color = MaterialTheme.colorScheme.error,
166	                    style = MaterialTheme.typography.bodySmall,
167	                )
168	            } else if (state.modelsReady) {
169	                Text(
170	                    "Офлайн: Zipformer-ru + Silero VAD + diar (models в APK).",
171	                    style = MaterialTheme.typography.bodySmall,
172	                )
173	            }
174	            Text(
175	                if (state.jniLoaded) {
176	                    "JNI libsherpa-onnx-jni.so: loaded"
177	                } else {
178	                    "JNI libsherpa-onnx-jni.so: FAIL ${state.jniError ?: "not probed"}"
179	                },
180	                style = MaterialTheme.typography.bodySmall,
181	            )
182	            Text(
183	                "LLM: ${state.llmName}",
184	                color = if (state.llmOnDevice) {
185	                    MaterialTheme.colorScheme.onSurface
186	                } else {
187	                    MaterialTheme.colorScheme.error
188	                },
189	                style = MaterialTheme.typography.bodySmall,
190	            )
191	            Spacer(Modifier.height(12.dp))
192	            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
193	                Button(onClick = { picker.launch(arrayOf("audio/*", "application/octet-stream")) }) {
194	                    Text("Импорт")
195	                }
196	                OutlinedButton(onClick = { recPerm.launch(Manifest.permission.RECORD_AUDIO) }) {
197	                    Text(if (state.recording) "Стоп записи" else "Запись")
198	                }
199	                Button(
200	                    onClick = { vm.transcribe() },
201	                    enabled = !state.running,
202	                ) {
203	                    Text("Расшифровать")
204	                }
205	                OutlinedButton(
206	                    enabled = state.jsonPreview != null,
207	                    onClick = { jsonSaver.launch("transcript.json") },
208	                ) {
209	                    Text("Сохранить JSON")
210	                }
211	                OutlinedButton(
212	                    enabled = state.jsonPreview != null,
213	                    onClick = {
214	                        val json = state.jsonPreview ?: return@OutlinedButton
215	                        val send = Intent(Intent.ACTION_SEND).apply {
216	                            type = "application/json"
217	                            putExtra(Intent.EXTRA_TEXT, json)
218	                            putExtra(Intent.EXTRA_TITLE, "transcript.json")
219	                        }
220	                        context.startActivity(Intent.createChooser(send, "Поделиться JSON"))
221	                    },
222	                ) {
223	                    Text("Поделиться")
224	                }
225	            }
226	            Spacer(Modifier.height(8.dp))
227	            Text(
228	                state.fileLabel?.let { "Файл: $it" } ?: "Файл не выбран — можно всё равно прогнать stub.",
229	                style = MaterialTheme.typography.bodySmall,
230	            )
231	            if (state.running) {
232	                Spacer(Modifier.height(8.dp))
233	                LinearProgressIndicator(
234	                    progress = { state.progress?.fraction ?: 0f },
235	                    modifier = Modifier.fillMaxWidth(),
236	                )
237	                Text(state.progress?.message ?: "…", style = MaterialTheme.typography.bodySmall)
238	            }
239	            state.error?.let {
240	                Text(it, color = MaterialTheme.colorScheme.error)
241	            }
242	            Spacer(Modifier.height(12.dp))
243	            val rows = state.transcript?.transcript.orEmpty()
244	            if (rows.isEmpty() && !state.running) {
245	                Text(
246	                    if (state.presentConnect) {
247	                        "Path B: жду stream.start (s01→speaker_1 … s20→speaker_20). Brief без stream_id."
248	                    } else {
249	                        "Пусто. Импортируйте запись и нажмите «Расшифровать»."
250	                    },
251	                    style = MaterialTheme.typography.bodyMedium,
252	                )
253	            } else {
254	                LazyColumn(
255	                    modifier = Modifier.fillMaxSize(),
256	                    contentPadding = PaddingValues(bottom = 24.dp),
257	                    verticalArrangement = Arrangement.spacedBy(8.dp),
258	                ) {
259	                    items(rows, key = { it.startMs.toString() + it.speakerId + it.text }) { u ->
260	                        UtteranceRow(u)
261	                    }
262	                }
263	            }
264	        }
265	    }
266	}
267	
268	@Composable
269	private fun UtteranceRow(u: Utterance) {
270	    Card(modifier = Modifier.fillMaxWidth()) {
271	        Column(Modifier.padding(12.dp)) {
272	            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
273	                FilterChip(
274	                    selected = true,
275	                    onClick = {},
276	                    label = { Text(u.speakerId) },
277	                )
278	                Text(
279	                    formatMs(u.startMs) + " – " + formatMs(u.endMs),
280	                    style = MaterialTheme.typography.labelMedium,
281	                    modifier = Modifier.padding(top = 10.dp),
282	                )
283	            }
284	            Spacer(Modifier.height(4.dp))
285	            Text(u.text, style = MaterialTheme.typography.bodyLarge)
286	        }
287	    }
288	}
289	
290	private fun formatMs(ms: Long): String {
291	    val s = ms / 1000
292	    val m = s / 60
293	    val rem = s % 60
294	    val frac = (ms % 1000) / 100
295	    return "%d:%02d.%d".format(m, rem, frac)
296	}
297	
298	@Suppress("unused")
299	@Composable
300	private fun BusyIf(running: Boolean) {
301	    if (running) CircularProgressIndicator()
302	}
303

#### Thinking

UI: button "Расшифровать". After transcribe completes, the result is shown in a LazyColumn — that's fine for long lists. `jsonPreview = TranscriptJson.encode(result)` — could be big but not crash-level for typical lengths.

The crash "after расшифровка if voiceline was too long" — likely during the pipeline run triggered by the Расшифровать button. Key native phases: Decode (AudioDecoder/MicRecorder), DemoPcmPrep, FarFieldGain.stage (whole array), FarFieldVad.speechRegions (whole array), collectSilero, diarize (pyannote on the WHOLE waveform), transcribeSegments (per crop), LLM per utterance.

Big native memory suspect: `diar.process(audio.samples)` — sherpa-onnx OfflineSpeakerDiarization processes the entire audio; internally it runs the pyannote segmentation model over sliding windows and *stores all embeddings*, then clusters. Memory grows with audio length but should be manageable... Actually, there's a known issue: sherpa-onnx offline diarization with long audio can use a lot of RAM, but the more likely crash for "too long" audio:

1. `AudioDecoder.decode` — let me read it. If it uses MediaExtractor + MediaCodec decoding entire file into a growing buffer, long files → OOM (JVM heap). That's a classic crash *after* pressing transcribe with a long imported audio. `DemoPcmPrep.prepare` — let me read. May resample etc. `FarFieldGain.stage` on the whole recording — allocates multiple float arrays. JVM OOM.

Let me also check `Pcm.s16leToFloat`, `AudioDecoder`, `DemoPcmPrep`, `FarFieldGain`, `FarFieldVad`. And the `InferenceSession` + `ExclusivePhases` to understand the in-flight work. Let me read several files now.

#### Tool: Read

**Input:**

```json
{
  "file_path": "E:\\Projects\\neuro-stenographer\\neuro-stenographer\\src\\android\\app\\src\\main\\java\\team\\mikcoders\\neurosteno\\audio\\AudioDecoder.kt"
}
```

**Response:**

1	package team.mikcoders.neurosteno.audio
2	
3	import android.content.Context
4	import android.media.MediaCodec
5	import android.media.MediaExtractor
6	import android.media.MediaFormat
7	import android.net.Uri
8	import team.mikcoders.neurosteno.pipeline.PcmAudio
9	import java.nio.ByteBuffer
10	
11	/**
12	 * SAF / file URI → 16 kHz mono float PCM.
13	 * WAV 16-bit is parsed without MediaCodec; M4A/MP3 go through MediaExtractor.
14	 */
15	object AudioDecoder {
16	    fun decode(context: Context, uri: Uri, label: String): PcmAudio {
17	        context.contentResolver.openInputStream(uri)?.use { ins ->
18	            val wav = WavPcmReader.read(ins)
19	            if (wav != null) {
20	                return PcmAudio(samples = wav.samples, sampleRate = wav.sampleRate, sourceName = label)
21	            }
22	        }
23	        return decodeWithExtractor(context, uri, label)
24	    }
25	
26	    private fun decodeWithExtractor(context: Context, uri: Uri, label: String): PcmAudio {
27	        val extractor = MediaExtractor()
28	        try {
29	            extractor.setDataSource(context, uri, null)
30	            val track = (0 until extractor.trackCount).firstOrNull { i ->
31	                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
32	            } ?: error("В файле нет аудиодорожки")
33	            extractor.selectTrack(track)
34	            val format = extractor.getTrackFormat(track)
35	            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("unknown mime")
36	            val srcHz = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
37	                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
38	            } else {
39	                Pcm.TARGET_HZ
40	            }
41	            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
42	                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
43	            } else {
44	                1
45	            }
46	            val codec = MediaCodec.createDecoderByType(mime)
47	            codec.configure(format, null, null, 0)
48	            codec.start()
49	            val outChunks = ArrayList<ByteArray>()
50	            val info = MediaCodec.BufferInfo()
51	            var inputDone = false
52	            var outputDone = false
53	            while (!outputDone) {
54	                if (!inputDone) {
55	                    val inIx = codec.dequeueInputBuffer(10_000)
56	                    if (inIx >= 0) {
57	                        val buf = codec.getInputBuffer(inIx) ?: ByteBuffer.allocate(0)
58	                        buf.clear()
59	                        val n = extractor.readSampleData(buf, 0)
60	                        if (n < 0) {
61	                            codec.queueInputBuffer(
62	                                inIx, 0, 0, 0,
63	                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
64	                            )
65	                            inputDone = true
66	                        } else {
67	                            codec.queueInputBuffer(inIx, 0, n, extractor.sampleTime, 0)
68	                            extractor.advance()
69	                        }
70	                    }
71	                }
72	                val outIx = codec.dequeueOutputBuffer(info, 10_000)
73	                if (outIx >= 0) {
74	                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
75	                        outputDone = true
76	                    }
77	                    val outBuf = codec.getOutputBuffer(outIx)
78	                    if (outBuf != null && info.size > 0) {
79	                        val chunk = ByteArray(info.size)
80	                        outBuf.position(info.offset)
81	                        outBuf.get(chunk)
82	                        outChunks.add(chunk)
83	                    }
84	                    codec.releaseOutputBuffer(outIx, false)
85	                }
86	            }
87	            codec.stop()
88	            codec.release()
89	            val bytes = concat(outChunks)
90	            val floats = Pcm.s16leToFloat(bytes)
91	            val mono = Pcm.downmixToMono(floats, channels.coerceAtLeast(1))
92	            val resampled = Pcm.resampleLinear(mono, srcHz, Pcm.TARGET_HZ)
93	            return PcmAudio(samples = resampled, sampleRate = Pcm.TARGET_HZ, sourceName = label)
94	        } finally {
95	            extractor.release()
96	        }
97	    }
98	
99	    private fun concat(chunks: List<ByteArray>): ByteArray {
100	        val n = chunks.sumOf { it.size }
101	        val all = ByteArray(n)
102	        var o = 0
103	        for (c in chunks) {
104	            System.arraycopy(c, 0, all, o, c.size)
105	            o += c.size
106	        }
107	        return all
108	    }
109	}
110

#### Tool: Read

**Input:**

```json
{
  "file_path": "E:\\Projects\\neuro-stenographer\\neuro-stenographer\\src\\android\\app\\src\\main\\java\\team\\mikcoders\\neurosteno\\pipeline\\DemoPcmPrep.kt"
}
```

**Response:**

1	package team.mikcoders.neurosteno.pipeline
2	
3	import team.mikcoders.neurosteno.audio.FarFieldGain
4	import team.mikcoders.neurosteno.audio.FarFieldGainResult
5	import team.mikcoders.neurosteno.audio.Pcm
6	import team.mikcoders.neurosteno.audio.PhoneDistanceEq
7	import team.mikcoders.neurosteno.audio.PhoneDistanceEqResult
8	import team.mikcoders.neurosteno.present.PresentConnectCodes
9	import team.mikcoders.neurosteno.present.StreamPcmGate
10	import team.mikcoders.neurosteno.present.StreamPcmStats
11	
12	/**
13	 * Single-channel **DEMO_STAGE6** prep (offline ViewModel path only — not Path B).
14	 * CLIPPED/BAD_MIC/empty on **raw** PCM (hard), then frozen per-turn gain
15	 * (do not retune), then **phone-distance EQ** on far islands only (near seat
16	 * identity). Inspect as bound main: SOFT_LOW / soft BAND_LIMITED,
17	 * **never hard-drop distant talkers**.
18	 */
19	object DemoPcmPrep {
20	    fun prepare(audio: PcmAudio, boundMain: Boolean = true): PreparedDemoPcm {
21	        val s16 = Pcm.floatToS16le(audio.samples)
22	        val rawGate = StreamPcmGate.inspect(s16, audio.sampleRate, boundSeat = boundMain)
23	        val hard = hardSkipCode(rawGate)
24	        if (hard != null) {
25	            return PreparedDemoPcm(
26	                audio = audio,
27	                skipAsr = true,
28	                skipCode = hard,
29	                rawGate = rawGate,
30	                afterGate = rawGate,
31	                gain = FarFieldGain.stage(FloatArray(0)),
32	                eq = PhoneDistanceEq.apply(FloatArray(0)),
33	            )
34	        }
35	        val gain = FarFieldGain.stage(audio.samples)
36	        val eq = PhoneDistanceEq.apply(gain.samples)
37	        val afterGate = StreamPcmGate.inspect(
38	            Pcm.floatToS16le(eq.samples),
39	            audio.sampleRate,
40	            boundSeat = boundMain,
41	        )
42	        return PreparedDemoPcm(
43	            audio = PcmAudio(eq.samples, audio.sampleRate, audio.sourceName),
44	            skipAsr = false,
45	            skipCode = null,
46	            rawGate = rawGate,
47	            afterGate = afterGate,
48	            gain = gain,
49	            eq = eq,
50	        )
51	    }
52	
53	    private fun hardSkipCode(stats: StreamPcmStats): String? {
54	        val code = stats.rejectCode ?: return null
55	        return when (code) {
56	            PresentConnectCodes.CLIPPED,
57	            PresentConnectCodes.BAD_MIC,
58	            -> code
59	            PresentConnectCodes.LOW_ENERGY -> if (stats.n == 0) code else null
60	            else -> null
61	        }
62	    }
63	}
64	
65	data class PreparedDemoPcm(
66	    val audio: PcmAudio,
67	    val skipAsr: Boolean,
68	    val skipCode: String?,
69	    val rawGate: StreamPcmStats,
70	    val afterGate: StreamPcmStats,
71	    val gain: FarFieldGainResult,
72	    val eq: PhoneDistanceEqResult,
73	)
74

#### Thinking

Memory analysis for a long recording (say 30 min = 28.8M samples):

Decode path (imported m4a): outChunks (57.6MB bytes) + concat copy (57.6MB) + floats (115MB) + downmix (same or new 115MB) + resample (115MB) → transient ~460MB+ JVM heap. Android default heap 256MB (512 with largeHeap?). Let me check AndroidManifest for largeHeap.

Mic path: BAOS internal (grows to ~57.6MB, plus doubling during growth → up to 115MB transient) + toByteArray copy (57.6MB) + floats (115MB) → ~230-290MB transient.

Then DemoPcmPrep: floatToS16le (57.6MB) + FarFieldGain.stage (multiple arrays) + PhoneDistanceEq.apply + another floatToS16le... This all adds up.

Then pipeline: FarFieldGain.stage again on the whole thing (staged copy), energy VAD, Silero windows (`samples.copyOfRange` per 512 window — small), diar.process(whole) — native.

So "crashes after расшифровка if voiceline too long" is very plausibly an OutOfMemoryError during decode/prep — the ViewModel catches Throwable and shows error, so JVM OOM wouldn't "crash" the app (it'd show error text)... unless OOM happens on a thread without catch (MicRecorder thread? no, that just records), or native OOM (SIGSEGV/abort in sherpa/llama), or the OOM is thrown while rendering...

Wait — the ViewModel's transcribe catches Throwable and sets error. So a JVM OOM in decode → error shown, not crash. A *native* crash (llama.cpp abort, sherpa-onnx assert) → process dies. "App crashes" = process death. So native crash suspects:

1. llama.cpp with prompt > context (1024): `Llama.complete` — the ffmpegkit llama wrapper. If the prompt tokenizes to more than n_ctx, llama.cpp's `llama_decode` returns 1 (error) — the wrapper might not check, leading to... Actually in many wrappers, they do `llama_batch_get_one` with all prompt tokens and call llama_decode; if tokens > n_ctx, it returns error and the wrapper may loop or crash. But GgufLlmPostPass catches Throwable — native abort can't be caught. Let me check the ffmpegkit llama library. It's `dev.ffmpegkit-maintained:llama-android`. Let me find the AAR/sources in libs or gradle cache. `transcribeSegments`: each crop is up to... diar segments can be long. pyannote segments with minDurationOn=0.08 — a segment can span minutes if one person talks continuously? pyannote segmentation typically produces speaker-homogeneous segments that can be long (tens of seconds). ASR on a minutes-long crop: Zipformer transducer offline — memory scales with length (encoder attention O(n²) for some layers!). Zipformer uses attention with left/right context — actually full attention within chunks? Zipformer2 attention is O(n²) in the worst case for long sequences. A 5-minute crop → 300s * 100 frames/s = 30k frames → attention matrices huge → native OOM → crash. Hmm, but crops come from diar segments which rarely exceed ~30s... except `snapEndpoint`/`fillEnergyHoles`/`adopt` can create longer spans, and mergeSameSpeaker merges adjacent same-speaker segments with gap ≤ 280ms into potentially *very* long segments (a person talking for 10 minutes with short pauses → one 10-min DiarSeg → one ASR crop of 10 minutes → encoder OOM native crash). That's a strong candidate for "voiceline too long → crash"!

Wait, actually — let me reconsider. `vadMaxSpeechSec = 30f` only applies to the Silero VAD path (vadThenAsr), not the diar path. In the diar path, segments can be arbitrarily long after merging. And ASR `stream.acceptWaveform(samples, sampleRate)` + decode on a huge crop → sherpa-onnx offline transducer on very long audio → known to OOM (feature extraction + encoder activations). Native OOM = process crash (SIGABRT/SIGKILL by LMK). This fits "app crashes after расшифровка if voiceline was too long" — one long voice line → one long segment → crash during ASR decode.

Hmm, but the user said "crashes AFTER расшифровка" — maybe meaning after the transcription stage completes, i., during LLM post-pass? With a long voice line → long text → prompt > 1024 ctx → llama.cpp crash. Both are plausible. Let me check the ffmpegkit llama wrapper behavior on long prompts. Let me find it in the gradle cache or libs.

Also the diarization complaint. Let me think again about "sequential replicas from the same person as if they're a new speaker every time":

With stage6 K=6 and a recording of, say, 2 people having a conversation: FastClustering(numClusters=6) forces 6 clusters → each person split into ~3 clusters. Then VadDiarReconcile keeps up to 6, SpeakerSeatAssign maps by time proximity. Result: same person gets different speaker labels across turns — "new speaker every time". 

The root cause for general recordings: the pipeline hardcodes the 6-seat demo scene. For real use, K should be auto (threshold-based clustering), and the reconcile/seat-assign machinery (tuned for the fixed demo) shouldn't shred labels. But the brief requires the demo claims... The memory says "version scope by decree" and the demo is the hand-in. The user is now reporting real-use bugs. The right fix respects the frozen demo tuning while making general recordings work: 

Option A: Add a "general/auto" scene for non-demo use (e., when the user imports/records their own audio) with threshold-based clustering (numClusters=-1) and embedding-based label consistency, keeping stage6 for the demo sample. But how does the app know it's the demo? DemoPcmPrep... the demo sample is imported too. Hmm.

Wait — actually, let me re-read the user's complaint once more: "diarisation locks sequential replicas from the same person as if theyre a new speaker every time." 

"locks ... as if they're a new speaker every time" — every replica (turn) gets a NEW speaker. Even in the pure diar output? Let me think about what happens with K=6 on a 1-speaker recording:

pyannote segmentation → speaker embeddings per segment → FastClustering with numClusters=6 → forces 6 cluster labels even for one voice. So turns get arbitrary labels 0-5. Then VadDiarReconcile: capToExpected caps at 6 (no reduction), mergeSameSpeaker merges only adjacent same-label, SpeakerSeatAssign picks 6 anchors and assigns each fragment to nearest anchor in time. Result: speaker count = 6 for a single person, and consecutive turns flip labels. Exactly the complaint.

Even with 2 speakers: forced 6 clusters → same person split. The fix for real recordings: don't force K=6; use threshold clustering (numClusters=-1, threshold tuned), and skip the seat-assign machinery (or make it embedding-aware). But the frozen demo needs K=6... 

Hmm wait, actually let me check: does FastClustering with numClusters=6 really force 6 clusters on 1-speaker audio? sherpa-onnx FastClustering: if numClusters > 0, it uses agglomerative clustering with a fixed cluster count (it sets threshold via... let me check the actual implementation). In sherpa-onnx, FastClustering with numClusters > 0 → uses `fast_clustering` with `num_clusters` set → agglomerative hierarchical clustering stopping at exactly num_clusters (unless fewer embeddings than clusters). Yes, it forces K. So a 1-person recording → up to 6 "speakers". Confirmed root cause candidate.

But wait — is the user running the stage6 scene or presentConnect? Default build: PRESENT_CONNECT flag — let me check build.gradle. The user recorded a "voiceline" — probably mic record → transcribe in the default (stage6) build. So yes, K=6 forced.

Now, how to fix while preserving the demo? The memory notes: "Preserve spell semantics" (Hex, not relevant), "Fix root cause in the toolchain", "version scope by decree" (Hex). For this project: the demo claims are frozen (Hex WER etc.), but the user explicitly reports the app mislabels real recordings. The clean approach:

1. Make clustering adaptive: use threshold-based clustering (numClusters = -1) with a sensible threshold, OR keep K for the demo but add a general scene. Since `expectedSpeakers` is the scene knob, and stage6 is the demo... The user wants the *app* to not split one person into many. 

Hmm, but hold on — maybe I should check whether there's already an "auto" path: presentConnect scene has expectedSpeakers=-1 → reconcile returns diar sorted, clustering threshold=0.5 with numClusters=-1. That's the auto path. The default app build uses stage6 though.

Let me check BuildConfig.PRESENT_CONNECT default and how the user runs the app. Let me check build.gradle. Also, maybe the right fix: in the default app, use auto clustering for user recordings but keep the demo scene when the demo sample is detected? That's fragile. 

Alternative interpretation: maybe the user IS talking about the demo scenario (6 speakers) — "sequential replicas from the same person" — in the demo, speaker s2 speaks at 7.4s, then again later; the seat-assign maps the later replica to a different seat because cost is time-gap dominated. The eval hyp is frozen though ("hyp 6/6 fixed"). Hmm, but the user says "every time" — sounds like general use.

I think the honest engineering answer: the stage6 scene hardcodes K=6 and a time-based seat assignment that doesn't track voice identity across turns. For any non-demo recording this produces exactly the reported symptom. The fix: 
- Use threshold-based auto clustering when the speaker count isn't known (real recordings), i., numClusters=-1 with a tuned threshold, and
- Make label assignment embedding-consistent rather than purely time-based.

But the demo must stay frozen. So: add a `FarFieldScene.general` (auto) and let the user pick, or detect demo vs user audio? Actually — wait. Let me look at how the demo is run: `samples/` dir, `eval/`, `artifacts/eval/demo-stress/`. The app's default scene is stage6 for the hand-in. The user now uses the app for real. The minimal, scope-respecting fix: make the non-demo path available in the app (a toggle "Demo 6 seats / Auto"), defaulting to... hmm, changing the default could break the hand-in demo claims.

Let me not over-decide. Let me first verify the actual behavior with a test: run the existing unit tests, and write a reproduction. The repo has a golden VM? No — that's Hex. Here: `src/android/app/src/test/...VadDiarReconcileTest.kt`. Let me read that test and the ExclusivePhases files, InferenceSession, then check build.gradle for the flag, then look at the ffmpegkit llama wrapper for the long-prompt crash.

Also let me check `Pcm.kt` (resample/downmix allocations) and AndroidManifest (largeHeap). And StreamLabeler for the Path B diarization (maybe the user means Path B? "voiceline" suggests mic recording in the main app though).

Let me batch reads.

Let me look at the remaining pieces — the in-flight ExclusivePhases work, the test for reconcile, Pcm allocations, and the build config.

#### Tool: Read

**Input:**

```json
{
  "file_path": "E:\\Projects\\neuro-stenographer\\neuro-stenographer\\src\\android\\app\\src\\main\\java\\team\\mikcoders\\neurosteno\\pipeline\\ExclusivePhases.kt"
}
```

**Response:**

1	package team.mikcoders.neurosteno.pipeline
2	
3	/**
4	 * Run ASR and LLM as exclusive memory phases: release ASR weights/arenas
5	 * before the LLM loads, so peak native RAM is max(ASR, LLM), not the sum.
6	 * Release also runs when the ASR phase fails or is cancelled (the session
7	 * invalidates its own model on failure; eviction is idempotent and
8	 * non-cancellable), and never runs while the ASR transaction still owns the
9	 * session — callers pass an eviction that acquires the session lock.
10	 */
11	internal suspend fun <Raw, Out> runExclusivePhases(
12	    asrPhase: suspend () -> Raw,
13	    releaseAsr: suspend () -> Unit,
14	    llmPhase: suspend (Raw) -> Out,
15	): Out {
16	    val raw = try {
17	        asrPhase()
18	    } finally {
19	        releaseAsr()
20	    }
21	    return llmPhase(raw)
22	}
23

#### Tool: Read

**Input:**

```json
{
  "file_path": "E:\\Projects\\neuro-stenographer\\neuro-stenographer\\src\\android\\app\\src\\main\\java\\team\\mikcoders\\neurosteno\\pipeline\\InferenceSession.kt"
}
```

**Response:**

1	package team.mikcoders.neurosteno.pipeline
2	
3	import java.util.concurrent.atomic.AtomicBoolean
4	import kotlinx.coroutines.CoroutineDispatcher
5	import kotlinx.coroutines.Dispatchers
6	import kotlinx.coroutines.NonCancellable
7	import kotlinx.coroutines.currentCoroutineContext
8	import kotlinx.coroutines.ensureActive
9	import kotlinx.coroutines.sync.Mutex
10	import kotlinx.coroutines.sync.withLock
11	import kotlinx.coroutines.withContext
12	
13	/**
14	 * One lazy native model, serialized transactions, no cached PCM or transcripts.
15	 *
16	 * Native calls cannot be preempted safely. Cancellation is observed between
17	 * calls; eviction/close wait for the owning transaction before freeing JNI.
18	 * Creation and destruction stay on [dispatcher], including teardown after the
19	 * caller is cancelled. A failed transaction invalidates possibly poisoned state.
20	 */
21	internal class InferenceSession<M : Any>(
22	    private val create: suspend () -> M,
23	    private val destroy: (M) -> Unit,
24	    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
25	) {
26	    private val mutex = Mutex()
27	    private val closed = AtomicBoolean(false)
28	    private var model: M? = null
29	
30	    /** [getModel] is valid only inside the transaction and must not escape it. */
31	    suspend fun <T> run(operation: suspend (getModel: suspend () -> M) -> T): T =
32	        withContext(dispatcher) {
33	            mutex.withLock {
34	                check(!closed.get()) { "Inference session is closed" }
35	                try {
36	                    currentCoroutineContext().ensureActive()
37	                    val result = operation {
38	                        currentCoroutineContext().ensureActive()
39	                        // Publish ownership before observing cancellation of a
40	                        // suspending native load, so its handle is not lost.
41	                        val loaded = model ?: withContext(NonCancellable) { create() }
42	                            .also { model = it }
43	                        currentCoroutineContext().ensureActive()
44	                        loaded
45	                    }
46	                    currentCoroutineContext().ensureActive()
47	                    result
48	                } catch (failure: Throwable) {
49	                    try {
50	                        discard()
51	                    } catch (cleanup: Throwable) {
52	                        failure.addSuppressed(cleanup)
53	                    }
54	                    throw failure
55	                }
56	            }
57	        }
58	
59	    /** Drop warm weights and dependent caches between complete transactions. */
60	    suspend fun evict(onEvict: suspend () -> Unit = {}) = withContext(dispatcher + NonCancellable) {
61	        mutex.withLock {
62	            try {
63	                discard()
64	            } finally {
65	                onEvict()
66	            }
67	        }
68	    }
69	
70	    /** Reject queued/new work and release once the active transaction finishes. */
71	    suspend fun close() {
72	        closed.set(true)
73	        withContext(dispatcher + NonCancellable) {
74	            mutex.withLock { discard() }
75	        }
76	    }
77	
78	    private fun discard() {
79	        val old = model
80	        model = null
81	        if (old != null) destroy(old)
82	    }
83	}
84

#### Tool: Bash

**Input:**

```json
{
  "command": "cat src/android/app/build.gradle.kts 2>/dev/null || cat src/android/app/build.gradle",
  "description": "Show app build configuration"
}
```

**Response:**

import java.io.File
import java.security.MessageDigest

fun fileSha256(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1024 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val repoRoot: File = rootProject.projectDir.resolve("../..").normalize()
val modelsSrc: File = File(repoRoot, "models")
val embeddedModelsAssets = layout.buildDirectory.dir("generated/embeddedModels/assets")

// Self-contained by default. Slim remains model-free; -PembedLlm=false is
// available only for developer builds that install their own GGUF separately.
val embedLlm: Boolean = (findProperty("embedLlm")?.toString() ?: "true") == "true"
val compactModels: Boolean = (findProperty("compactModels")?.toString() ?: "false") == "true"
val targetAbis = (findProperty("targetAbis")?.toString() ?: "arm64-v8a")
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
require(targetAbis.isNotEmpty() && targetAbis.all { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64") }) {
    "targetAbis must list supported Android ABIs"
}
val encoderRelPath = "asr/zipformer-ru-int8/encoder.int8.onnx"
val compactValidation = File(repoRoot, "artifacts/compact/encoder-validation.json")

val judgeModelRelPaths = mutableListOf(
    "asr/zipformer-ru-int8/encoder.int8.onnx",
    "asr/zipformer-ru-int8/decoder.onnx",
    "asr/zipformer-ru-int8/joiner.int8.onnx",
    "asr/zipformer-ru-int8/tokens.txt",
    "vad/silero_vad.onnx",
    "diar/segmentation.onnx",
    "diar/embedding.onnx",
)
if (embedLlm) judgeModelRelPaths.add("llm/qwen2-0_5b-instruct-q4_0.gguf")

fun resolveJudgeModel(rel: String): File {
    if (compactModels && rel == encoderRelPath) return File(modelsSrc, "compact/$rel")
    val direct = File(modelsSrc, rel)
    if (direct.isFile) return direct
    if (rel.endsWith("embedding.onnx")) {
        val alt = File(modelsSrc, "diar/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx")
        if (alt.isFile) return alt
    }
    if (rel.endsWith("segmentation.onnx")) {
        val alt = File(modelsSrc, "diar/model.onnx")
        if (alt.isFile) return alt
        val nested = File(modelsSrc, "diar/sherpa-onnx-pyannote-segmentation-3-0/model.onnx")
        if (nested.isFile) return nested
    }
    return direct
}

val prepareEmbeddedModels by tasks.registering {
    group = "build"
    description = "Copy Zipformer-ru + Silero VAD + diar ONNX (and, with -PembedLlm, the GGUF) into judge APK assets/models"
    outputs.dir(embeddedModelsAssets)
    // Without declared inputs Gradle marks this up-to-date forever, and the
    // APK silently ships whatever weights the first build found. Measured the
    // hard way: a replaced pyannote segmentation stayed stale inside a
    // "successful" assemble.
    judgeModelRelPaths.forEach { rel ->
        val src = resolveJudgeModel(rel)
        if (src.isFile) {
            inputs.file(src)
        } else {
            inputs.property("absent:$rel", true)
        }
    }
    inputs.property("embedLlm", embedLlm)
    inputs.property("compactModels", compactModels)
    if (compactModels && compactValidation.isFile) inputs.file(compactValidation)
    doLast {
        if (compactModels) {
            require(compactValidation.isFile && resolveJudgeModel(encoderRelPath).isFile) {
                "Run scripts/prepare_compact_encoder.py before -PcompactModels=true"
            }
            val validation = groovy.json.JsonSlurper().parse(compactValidation) as Map<*, *>
            require(validation["all_text_tokens_timestamps_identical"] == true &&
                validation["metadata_identical"] == true &&
                validation["hardware_layout_optimization"] == false &&
                validation["prepared_sha256"] == fileSha256(resolveJudgeModel(encoderRelPath)) &&
                validation["source_sha256"] == fileSha256(File(modelsSrc, encoderRelPath))) {
                "Compact encoder does not match its passing validation report; rerun prepare_compact_encoder.py"
            }
        }
        var missing = judgeModelRelPaths.filter { !resolveJudgeModel(it).isFile }
        if (missing.isNotEmpty()) {
            val script = File(repoRoot, "scripts/download-models.sh")
            require(script.isFile) { "missing $script and models $missing" }
            exec {
                commandLine("bash", script.absolutePath)
                workingDir(repoRoot)
            }
            missing = judgeModelRelPaths.filter { !resolveJudgeModel(it).isFile }
        }
        require(missing.isEmpty()) { "judge models missing after download: $missing" }
        val destRoot = embeddedModelsAssets.get().asFile.resolve("models")
        destRoot.deleteRecursively()
        for (rel in judgeModelRelPaths) {
            val src = resolveJudgeModel(rel)
            val dest = File(destRoot, rel)
            dest.parentFile.mkdirs()
            src.copyTo(dest, overwrite = true)
        }
        File(destRoot, ".embedded").writeText(
            judgeModelRelPaths.joinToString("\n") { rel ->
                val f = File(destRoot, rel)
                "$rel ${f.length()} ${fileSha256(f)}"
            } + "\n",
        )
        println("embedded ${judgeModelRelPaths.size} model files into $destRoot (${destRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }} bytes)")
    }
}

android {
    namespace = "team.mikcoders.neurosteno"
    compileSdk = 35

    defaultConfig {
        applicationId = "team.mikcoders.neurosteno"
        // AAR minSdk 21; sherpa Java demo uses 28. Brief allows 26–28.
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += targetAbis
        }
        // EMBEDDED_MODELS is set per build type; slim stays model-free.
    }

    flavorDimensions += "role"
    productFlavors {
        create("stenographer") {
            dimension = "role"
            isDefault = true
            buildConfigField("Boolean", "PRESENT_CONNECT", "false")
        }
        create("presentConnect") {
            dimension = "role"
            applicationIdSuffix = ".present"
            buildConfigField("Boolean", "PRESENT_CONNECT", "true")
        }
    }

    buildTypes {
        debug {
            buildConfigField("Boolean", "EMBEDDED_MODELS", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("Boolean", "EMBEDDED_MODELS", "true")
        }
        create("compact") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            // Installable local candidate, not a production-signing credential.
            signingConfig = signingConfigs.getByName("debug")
            versionNameSuffix = "-compact"
        }
        create("slim") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            versionNameSuffix = "-slim"
            buildConfigField("Boolean", "EMBEDDED_MODELS", "false")
        }
    }

    sourceSets {
        getByName("debug") {
            assets.srcDir(embeddedModelsAssets)
        }
        getByName("release") {
            assets.srcDir(embeddedModelsAssets)
        }
        getByName("compact") {
            assets.srcDir(embeddedModelsAssets)
        }
    }

    // Let AAPT losslessly compress ONNX/GGUF assets. They are streamed to
    // filesDir once, then loaded from ordinary mmap-compatible files. Inference
    // never decompresses a model, and no tensor values are changed.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // JNI + Java API: ASR, Silero VAD, OfflineSpeakerDiarization. JitPack AAR
    // (packaging=aar), 49 441 341 bytes, sha256 a8d09d1d32f3eb8520482c6d3d43fae
    // 674681c5b050385967c70a3c2c9bf0ee7 — re-check with scripts/inspect-sherpa-aar.sh.
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1")
    // Tiny on-device GGUF (llama.cpp prebuilt JNI, MIT, arm64-v8a, API 24+).
    // Weights are NOT packed here — ASR/VAD/diar assets come from
    // prepareEmbeddedModels; the GGUF lands in filesDir/models/llm
    // (scripts/download-models.sh).
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

afterEvaluate {
    tasks.matching { t ->
        val n = t.name
        !n.contains("Slim", ignoreCase = true) &&
            (n.contains("LintVital", ignoreCase = true) ||
                (n.contains("Assets") &&
                    (n.startsWith("merge") || n.startsWith("generate") || n.startsWith("package"))))
    }.configureEach {
        dependsOn(prepareEmbeddedModels)
    }
}

tasks.register<Copy>("copyApkToDrop") {
    dependsOn("assembleStenographerDebug")
    from(layout.buildDirectory.dir("outputs/apk/stenographer/debug"))
    into(rootProject.layout.projectDirectory.dir("../../apk"))
    include("*.apk")
}

#### Thinking

Confirmed: default flavor `stenographer` → PRESENT_CONNECT=false → `FarFieldScene.stage6` with K=6 hardcoded. So any real recording through the app gets forced 6-speaker clustering + the demo seat machinery. That's the diarization bug root cause.

Now for the crash, I need to see the ffmpegkit llama wrapper to understand long-prompt behavior. Let me find the AAR in the Gradle cache and inspect. Also check `Pcm.kt` and AndroidManifest.

Let me also read the VadDiarReconcileTest to understand what's pinned, and check `StreamLabeler` (Path B) — but the user's flow is the main app.

Plan forming. Let me create the task list now, then continue investigation:

1. Reproduce/confirm diarization label churn root cause (K=6 forced + time-based seat assign). Crash root cause on long recordings (decode/prep JVM OOM vs native ASR/LLM). Fix both. Tests + build verification.

Let me check the llama wrapper first. Find in gradle cache.

#### Thinking

Let me create the remaining tasks and continue investigation.

#### Tool: Bash

**Input:**

```json
{
  "command": "find ~/.gradle/caches/modules-2/files-2.1/dev.ffmpegkit-maintained -type f 2>/dev/null | head -20; find src/android/app/libs -type f 2>/dev/null",
  "description": "Locate ffmpegkit llama AAR in gradle cache"
}
```

**Response:**

/c/Users/KMService/.gradle/caches/modules-2/files-2.1/dev.ffmpegkit-maintained/llama-android/0.1.1/1b68382aa058fb10062268f56090731a51ab6608/llama-android-0.1.1.aar
/c/Users/KMService/.gradle/caches/modules-2/files-2.1/dev.ffmpegkit-maintained/llama-android/0.1.1/34b267f671f1f68b5e3cddd9b077cb1cbe564268/llama-android-0.1.1.pom
/c/Users/KMService/.gradle/caches/modules-2/files-2.1/dev.ffmpegkit-maintained/llama-android/0.1.1/8a2028973f0c0ad316ebe46d17d35e292f887e2f/llama-android-0.1.1.module
src/android/app/libs/.gitkeep

#### Thinking

Let me extract the llama AAR and inspect the Java/Kotlin wrapper for `complete` to see how it handles prompts longer than the context. The AAR contains classes.jar. Let me unzip and decompile-ish (javap or just look at strings). Better: check if sources are available. Let me unzip classes.jar and use javap to inspect `dev.ffmpegkit.llama.Llama`.

#### Tool: Bash

**Input:**

```json
{
  "command": "mkdir -p /tmp/llama-aar && cd /tmp/llama-aar && unzip -o -q \"/c/Users/KMService/.gradle/caches/modules-2/files-2.1/dev.ffmpegkit-maintained/llama-android/0.1.1/1b68382aa058fb10062268f56090731a51ab6608/llama-android-0.1.1.aar\" && ls -la && unzip -l classes.jar | head -30",
  "description": "Extract llama-android AAR and list classes"
}
```

**Response:**

total 6801
drwxr-xr-x 1 l1ff0 197121     0 Nov  7 21:48 .
drwxr-xr-x 1 l1ff0 197121     0 Nov  7 21:48 ..
-rw-r--r-- 1 l1ff0 197121   355 Feb  1  1980 AndroidManifest.xml
drwxr-xr-x 1 l1ff0 197121     0 Nov  7 21:48 META-INF
-rw-r--r-- 1 l1ff0 197121     0 Feb  1  1980 R.txt
-rw-r--r-- 1 l1ff0 197121 19279 Feb  1  1980 classes.jar
drwxr-xr-x 1 l1ff0 197121     0 Feb  1  1980 jni
Archive:  classes.jar
  Length      Date    Time    Name
---------  ---------- -----   ----
        0  1981-01-01 01:01   META-INF/
        0  1981-01-01 01:01   dev/
        0  1981-01-01 01:01   dev/ffmpegkit/
        0  1981-01-01 01:01   dev/ffmpegkit/llama/
     4068  1981-01-01 01:01   dev/ffmpegkit/llama/Llama$complete$2.class
     3279  1981-01-01 01:01   dev/ffmpegkit/llama/Llama$embed$2.class
     3825  1981-01-01 01:01   dev/ffmpegkit/llama/Llama$loadModel$2.class
     5988  1981-01-01 01:01   dev/ffmpegkit/llama/Llama.class
     4449  1981-01-01 01:01   dev/ffmpegkit/llama/LlamaConfig.class
     1046  1981-01-01 01:01   dev/ffmpegkit/llama/LlamaException$InferenceFailed.class
     1532  1981-01-01 01:01   dev/ffmpegkit/llama/LlamaException$ModelLoadFailed.class
      813  1981-01-01 01:01   dev/ffmpegkit/llama/LlamaException$ModelReleased.class
     1660  1981-01-01 01:01   dev/ffmpegkit/llama/LlamaException.class
     2961  1981-01-01 01:01   dev/ffmpegkit/llama/LlamaJNI.class
     1851  1981-01-01 01:01   dev/ffmpegkit/llama/LlamaModel.class
     3990  1981-01-01 01:01   dev/ffmpegkit/llama/LlamaResult.class
      474  1981-01-01 01:01   dev/ffmpegkit/llama/BuildConfig.class
       24  1981-01-01 01:01   META-INF/library_release.kotlin_module
---------                     -------
    35960                     18 files

Shell cwd was reset to E:\Projects\neuro-stenographer\neuro-stenographer

#### Thinking

Let me decompile the Llama class to see the `complete` flow. `javap -c` gives bytecode; better to check if there's a Kotlin metadata / use a decompiler. Let me try javap first to see method signatures, then look at the native side. Actually the key question: does `complete` throw a catchable LlamaException on prompt overflow, or crash natively? The native lib is in jni/. Let me check what the JNI does — I can look at strings in the .so for error messages. But more efficient: javap -c the Llama$complete$2 class to see the flow.

#### Tool: Bash

**Input:**

```json
{
  "command": "cd /tmp/llama-aar && unzip -o -q classes.jar -d classes && javap -p -c classes/dev/ffmpegkit/llama/Llama.class 2>/dev/null | head -120",
  "description": "Decompile Llama wrapper class signatures and bytecode"
}
```

**Response:**

Compiled from "Llama.kt"
public final class dev.ffmpegkit.llama.Llama {
  public static final dev.ffmpegkit.llama.Llama INSTANCE;

  private dev.ffmpegkit.llama.Llama();
    Code:
       0: aload_0
       1: invokespecial #8                  // Method java/lang/Object."<init>":()V
       4: return

  public final java.lang.Object loadModel(java.lang.String, dev.ffmpegkit.llama.LlamaConfig, kotlin.coroutines.Continuation<? super dev.ffmpegkit.llama.LlamaModel>);
    Code:
       0: invokestatic  #21                 // Method kotlinx/coroutines/Dispatchers.getDefault:()Lkotlinx/coroutines/CoroutineDispatcher;
       3: checkcast     #23                 // class kotlin/coroutines/CoroutineContext
       6: new           #25                 // class dev/ffmpegkit/llama/Llama$loadModel$2
       9: dup
      10: aload_1
      11: aload_2
      12: aconst_null
      13: invokespecial #28                 // Method dev/ffmpegkit/llama/Llama$loadModel$2."<init>":(Ljava/lang/String;Ldev/ffmpegkit/llama/LlamaConfig;Lkotlin/coroutines/Continuation;)V
      16: checkcast     #30                 // class kotlin/jvm/functions/Function2
      19: aload_3
      20: invokestatic  #36                 // Method kotlinx/coroutines/BuildersKt.withContext:(Lkotlin/coroutines/CoroutineContext;Lkotlin/jvm/functions/Function2;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
      23: areturn

  public static java.lang.Object loadModel$default(dev.ffmpegkit.llama.Llama, java.lang.String, dev.ffmpegkit.llama.LlamaConfig, kotlin.coroutines.Continuation, int, java.lang.Object);
    Code:
       0: iload         4
       2: iconst_2
       3: iand
       4: ifeq          25
       7: new           #46                 // class dev/ffmpegkit/llama/LlamaConfig
      10: dup
      11: iconst_0
      12: iconst_0
      13: iconst_0
      14: fconst_0
      15: fconst_0
      16: iconst_0
      17: iconst_0
      18: bipush        127
      20: aconst_null
      21: invokespecial #49                 // Method dev/ffmpegkit/llama/LlamaConfig."<init>":(IIIFFIIILkotlin/jvm/internal/DefaultConstructorMarker;)V
      24: astore_2
      25: aload_0
      26: aload_1
      27: aload_2
      28: aload_3
      29: invokevirtual #51                 // Method loadModel:(Ljava/lang/String;Ldev/ffmpegkit/llama/LlamaConfig;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
      32: areturn

  public final java.lang.Object complete(dev.ffmpegkit.llama.LlamaModel, java.lang.String, java.lang.String, int, kotlin.coroutines.Continuation<? super dev.ffmpegkit.llama.LlamaResult>);
    Code:
       0: invokestatic  #21                 // Method kotlinx/coroutines/Dispatchers.getDefault:()Lkotlinx/coroutines/CoroutineDispatcher;
       3: checkcast     #23                 // class kotlin/coroutines/CoroutineContext
       6: new           #56                 // class dev/ffmpegkit/llama/Llama$complete$2
       9: dup
      10: aload_1
      11: aload_2
      12: aload_3
      13: iload         4
      15: aconst_null
      16: invokespecial #59                 // Method dev/ffmpegkit/llama/Llama$complete$2."<init>":(Ldev/ffmpegkit/llama/LlamaModel;Ljava/lang/String;Ljava/lang/String;ILkotlin/coroutines/Continuation;)V
      19: checkcast     #30                 // class kotlin/jvm/functions/Function2
      22: aload         5
      24: invokestatic  #36                 // Method kotlinx/coroutines/BuildersKt.withContext:(Lkotlin/coroutines/CoroutineContext;Lkotlin/jvm/functions/Function2;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
      27: areturn

  public static java.lang.Object complete$default(dev.ffmpegkit.llama.Llama, dev.ffmpegkit.llama.LlamaModel, java.lang.String, java.lang.String, int, kotlin.coroutines.Continuation, int, java.lang.Object);
    Code:
       0: iload         6
       2: iconst_4
       3: iand
       4: ifeq          10
       7: ldc           #69                 // String
       9: astore_3
      10: iload         6
      12: bipush        8
      14: iand
      15: ifeq          23
      18: sipush        512
      21: istore        4
      23: aload_0
      24: aload_1
      25: aload_2
      26: aload_3
      27: iload         4
      29: aload         5
      31: invokevirtual #71                 // Method complete:(Ldev/ffmpegkit/llama/LlamaModel;Ljava/lang/String;Ljava/lang/String;ILkotlin/coroutines/Continuation;)Ljava/lang/Object;
      34: areturn

  public final java.lang.Object embed(dev.ffmpegkit.llama.LlamaModel, java.lang.String, kotlin.coroutines.Continuation<? super float[]>);
    Code:
       0: invokestatic  #21                 // Method kotlinx/coroutines/Dispatchers.getDefault:()Lkotlinx/coroutines/CoroutineDispatcher;
       3: checkcast     #23                 // class kotlin/coroutines/CoroutineContext
       6: new           #76                 // class dev/ffmpegkit/llama/Llama$embed$2
       9: dup
      10: aload_1
      11: aload_2
      12: aconst_null
      13: invokespecial #79                 // Method dev/ffmpegkit/llama/Llama$embed$2."<init>":(Ldev/ffmpegkit/llama/LlamaModel;Ljava/lang/String;Lkotlin/coroutines/Continuation;)V
      16: checkcast     #30                 // class kotlin/jvm/functions/Function2
      19: aload_3
      20: invokestatic  #36                 // Method kotlinx/coroutines/BuildersKt.withContext:(Lkotlin/coroutines/CoroutineContext;Lkotlin/jvm/functions/Function2;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
      23: areturn

  public final void releaseModel(dev.ffmpegkit.llama.LlamaModel);
    Code:
       0: aload_1
       1: ldc           #83                 // String model
       3: invokestatic  #89                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       6: aload_1
       7: invokevirtual #95                 // Method dev/ffmpegkit/llama/LlamaModel.getHandle$library_release:()J
      10: lstore_2
      11: lload_2
      12: lconst_0
      13: lcmp
      14: ifeq          29
      17: getstatic     #101                // Field dev/ffmpegkit/llama/LlamaJNI.INSTANCE:Ldev/ffmpegkit/llama/LlamaJNI;
      20: lload_2

Shell cwd was reset to E:\Projects\neuro-stenographer\neuro-stenographer

#### Thinking

The wrapper delegates to `LlamaJNI`. Let me look at LlamaJNI and the native library. The native .so is in jni/. Let me check what native functions exist and look for how the prompt is handled — specifically whether there's a context overflow check. Let me decompile LlamaJNI and Llama$complete$2, and check strings in the .so.

#### Tool: Bash

**Input:**

```json
{
  "command": "cd /tmp/llama-aar && javap -p classes/dev/ffmpegkit/llama/LlamaJNI.class && javap -c classes/dev/ffmpegkit/llama/Llama\\$complete\\$2.class 2>/dev/null | grep -E \"invoke|Method|String\" | head -40 && ls jni/*/",
  "description": "Inspect JNI bridge methods and native libs"
}
```

**Response:**

Compiled from "LlamaJNI.kt"
public final class dev.ffmpegkit.llama.LlamaJNI {
  public static final dev.ffmpegkit.llama.LlamaJNI INSTANCE;
  private dev.ffmpegkit.llama.LlamaJNI();
  public final native long nativeLoadModel(java.lang.String, int, int, int);
  public final native java.lang.String nativeComplete(long, java.lang.String, java.lang.String, int, float, float, int, int);
  public final native float[] nativeEmbed(long, java.lang.String);
  public final native void nativeReleaseModel(long);
  public final native java.lang.String nativeGetSystemInfo();
  static {};
}
  final java.lang.String $prompt;
  final java.lang.String $systemPrompt;
  dev.ffmpegkit.llama.Llama$complete$2(dev.ffmpegkit.llama.LlamaModel, java.lang.String, java.lang.String, int, kotlin.coroutines.Continuation<? super dev.ffmpegkit.llama.Llama$complete$2>);
       7: putfield      #18                 // Field $prompt:Ljava/lang/String;
      12: putfield      #21                 // Field $systemPrompt:Ljava/lang/String;
      25: invokespecial #28                 // Method kotlin/coroutines/jvm/internal/SuspendLambda."<init>":(ILkotlin/coroutines/Continuation;)V
  public final java.lang.Object invokeSuspend(java.lang.Object);
       0: invokestatic  #50                 // Method kotlin/coroutines/intrinsics/IntrinsicsKt.getCOROUTINE_SUSPENDED:()Ljava/lang/Object;
      29: invokestatic  #59                 // Method kotlin/ResultKt.throwOnFailure:(Ljava/lang/Object;)V
      36: invokevirtual #65                 // Method dev/ffmpegkit/llama/LlamaModel.getConfig:()Ldev/ffmpegkit/llama/LlamaConfig;
      47: invokevirtual #75                 // Method dev/ffmpegkit/llama/LlamaModel.requireHandle$library_release:()J
      51: getfield      #18                 // Field $prompt:Ljava/lang/String;
      55: getfield      #21                 // Field $systemPrompt:Ljava/lang/String;
      63: invokevirtual #81                 // Method dev/ffmpegkit/llama/LlamaConfig.getTemperature:()F
      67: invokevirtual #84                 // Method dev/ffmpegkit/llama/LlamaConfig.getTopP:()F
      71: invokevirtual #88                 // Method dev/ffmpegkit/llama/LlamaConfig.getTopK:()I
      75: invokevirtual #91                 // Method dev/ffmpegkit/llama/LlamaConfig.getSeed:()I
      78: invokevirtual #95                 // Method dev/ffmpegkit/llama/LlamaJNI.nativeComplete:(JLjava/lang/String;Ljava/lang/String;IFFII)Ljava/lang/String;
      86: invokestatic  #104                // Method dev/ffmpegkit/llama/Llama.access$parseResult:(Ldev/ffmpegkit/llama/Llama;Ljava/lang/String;)Ldev/ffmpegkit/llama/LlamaResult;
      94: ldc           #108                // String call to \'resume\' before \'invoke\' with coroutine
      96: invokespecial #111                // Method java/lang/IllegalStateException."<init>":(Ljava/lang/String;)V
       9: getfield      #18                 // Field $prompt:Ljava/lang/String;
      13: getfield      #21                 // Field $systemPrompt:Ljava/lang/String;
      21: invokespecial #120                // Method "<init>":(Ldev/ffmpegkit/llama/LlamaModel;Ljava/lang/String;Ljava/lang/String;ILkotlin/coroutines/Continuation;)V
  public final java.lang.Object invoke(kotlinx.coroutines.CoroutineScope, kotlin.coroutines.Continuation<? super dev.ffmpegkit.llama.LlamaResult>);
       3: invokevirtual #128                // Method create:(Ljava/lang/Object;Lkotlin/coroutines/Continuation;)Lkotlin/coroutines/Continuation;
      12: invokevirtual #135                // Method invokeSuspend:(Ljava/lang/Object;)Ljava/lang/Object;
  public java.lang.Object invoke(java.lang.Object, java.lang.Object);
       9: invokevirtual #143                // Method invoke:(Lkotlinx/coroutines/CoroutineScope;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
libc++_shared.so
libggml-base.so
libggml-cpu.so
libggml.so
libllama.so
libllama_jni.so

Shell cwd was reset to E:\Projects\neuro-stenographer\neuro-stenographer

#### Thinking

The native `nativeComplete` is in libllama_jni.so. Let me check strings in that .so for error handling — specifically whether it checks prompt length vs n_ctx and throws, or calls llama_decode blindly. Let me look for relevant strings.

#### Tool: Bash

**Input:**

```json
{
  "command": "cd /tmp/llama-aar && strings jni/arm64-v8a/libllama_jni.so 2>/dev/null | grep -iE \"context|prompt|token|exceed|too long|overflow|decode|empty|error|failed\" | head -40",
  "description": "Search native JNI lib for prompt/context error strings"
}
```

**Response:**

llama_context_default_params
llama_decode
llama_token_to_piece
llama_tokenize
_ZTISt12length_error
_ZNSt12length_errorD1Ev
_ZNSt11logic_errorC2EPKc
_ZTVSt12length_error
,"promptTokens":
failed to load model
,"tokensPerSecond":
,"promptEvalTimeMs":
","tokensGenerated":
failed to create context
{"text":"","error":"decode failed"}

Shell cwd was reset to E:\Projects\neuro-stenographer\neuro-stenographer

#### Thinking

The JNI returns `{"text":"","error":"decode failed"}` on decode failure — so prompt overflow → llama_decode fails → JSON error → `Llama.access$parseResult` probably throws LlamaException.InferenceFailed (catchable) or returns empty. GgufLlmPostPass catches Throwable → candidate=null → falls back to IntonationPunct. So the LLM is likely NOT the crash cause (unless parseResult crashes, unlikely).

Let me check `Llama.access$parseResult` quickly... actually the string `{"text":"","error":"decode failed"}` suggests graceful handling. So the LLM long-prompt path degrades gracefully. Good — rule that out as the crash (it's a degradation, not crash).

So the crash on long recordings is most likely:
1. JVM OOM during decode/prep — but ViewModel catches Throwable → shows error, doesn't crash the process. Unless OOM occurs in a place without catch... `AudioDecoder.decode` runs inside `withContext(Dispatchers.Default)` inside the try → caught. Hmm. But wait — catching OutOfMemoryError and then continuing to render UI can leave the heap exhausted → subsequent GC thrash → ANR/LMK kill. Possible but let me look for native crash paths. Native memory: `diar.process(audio.samples)` on the whole recording. sherpa-onnx OfflineSpeakerDiarization: extracts embeddings for all segments, stores them; segmentation model slides over the whole audio. For a 30-60 min recording this is heavy but the segmentation is windowed (pyannote processes 5s/10s chunks). Memory should be bounded-ish. Hmm. ASR per segment: `mergeSameSpeaker` with gap 280ms can create very long segments. Zipformer offline encoder on a multi-minute crop → native RAM explosion → crash. This is a real known sherpa-onnx issue: offline recognition of very long audio uses lots of memory (nnet forward on the whole sequence). For a "voiceline too long" — e., user dictates for 2-3 minutes without big pauses — pyannote may emit one long segment, or merge creates one. Then ASR decode → native OOM → SIGKILL/SIGABRT → "app crashes".

Actually wait. Let me reconsider: does `transcribeSegments` bound crop length? No — `crop(audio.samples, hz, seg.startMs - pad, seg.endMs + pad)` with no max. And `vadThenAsr` path uses Silero with maxSpeechDuration=30s → bounded. But the diar path (default, since hasDiar=true) has NO bound. A long monologue → one huge crop → Zipformer encoder OOM. That's very plausibly THE crash: "if voiceline was too long" (singular voiceline!). The user says "voiceline" — one long utterance. So: long single voice line → long diar segment → huge ASR crop → native crash.

Also the JVM side: a 3-minute 16kHz float array is only ~11.5MB — fine. Even 30 min is 115MB — borderline but the crash for a "long voiceline" (minutes) is more likely native ASR.

Let me verify Zipformer memory scaling. sherpa-onnx offline transducer: features (T×80), encoder forward with T frames. Zipformer attention is chunk-limited? Zipformer2 uses `attention` with left/right context per chunk — actually in icefall zipformer, attention is within chunks of size e. 64/128/256 with left context. ONNX export: the encoder processes the whole sequence; memory ~ O(T × d × layers) for activations plus attention O(T × chunk). For T = 3min×100 = 18k frames... activations: 18k × 384 × 4 bytes × ~50 tensors ≈ 1.4GB → OOM on device. Yes.

Even simpler: `stream.acceptWaveform` + `recognizer.decode` — sherpa computes fbank for whole audio, then encoder. Known guidance: offline ASR should be fed ≤30s chunks. The VAD path already bounds at 30s (vadMaxSpeechSec). The diar path doesn't.

So the crash fix: bound ASR crop length in the diar path — split long diar segments into ≤N-second windows (at silence/energy dips where possible, or fixed windows with small overlap), transcribe each, concatenate text per segment. This matches "fix root cause": the ASR phase must never receive an unbounded crop.

But wait — I should verify against the actual artifact per my memory ("Verify, don't guess", "Verifier must reproduce the bug"). Can I reproduce on this box? There's an emulator? `EmulatorBench.kt` is untracked — the other session built an emulator bench. Let me read it and the exclusive-phases experiment JSON to see what infrastructure exists (maybe an emulator is running with the app). Also check `docs/` for bench instructions and `artifacts/compact/exclusive-phases-experiment.json`.

Also for the diarization bug, let me read VadDiarReconcileTest to see pinned behavior, and StreamLabeler (Path B) — actually the user's flow is the main app (mic record → Расшифровать). Let me confirm the reproduction path for diar: with K=6 forced on a 1-2 speaker recording. I can write a JVM unit test that runs VadDiarReconcile with synthetic diar output simulating forced 6-cluster split... but the actual clustering happens in native sherpa. Hmm — the JVM test can only test the reconcile logic, not sherpa clustering. To verify the full path I'd need the emulator or a device.

Let me check what bench infrastructure exists. Read EmulatorBench.kt and the experiment JSON.

#### Tool: Read

**Input:**

```json
{
  "file_path": "E:\\Projects\\neuro-stenographer\\neuro-stenographer\\src\\android\\app\\src\\main\\java\\team\\mikcoders\\neurosteno\\EmulatorBench.kt"
}
```

**Response:**

1	package team.mikcoders.neurosteno
2	
3	import android.os.Debug
4	import java.io.File
5	import java.io.RandomAccessFile
6	import kotlinx.coroutines.runBlocking
7	import team.mikcoders.neurosteno.audio.Pcm
8	import team.mikcoders.neurosteno.export.TranscriptJson
9	import team.mikcoders.neurosteno.llm.LlmPostPassFactory
10	import team.mikcoders.neurosteno.pipeline.DemoPcmPrep
11	import team.mikcoders.neurosteno.pipeline.FarFieldScene
12	import team.mikcoders.neurosteno.pipeline.ModelPaths
13	import team.mikcoders.neurosteno.pipeline.PcmAudio
14	import team.mikcoders.neurosteno.pipeline.SherpaStenoPipeline
15	
16	/**
17	 * EXPERIMENTAL emulator benchmark entry (uncommitted; revert before hand-in).
18	 * Drives the real DemoPcmPrep + SherpaStenoPipeline on a pushed PCM16/16k mono
19	 * wav using pushed model files. No UI, no stub. Run via app_process:
20	 *
21	 *   CLASSPATH=/data/app/.../base.apk app_process \
22	 *     -Djava.library.path=/data/app/.../lib/arm64 /system/bin \
23	 *     team.mikcoders.neurosteno.EmulatorBench <modelsDir> <wav> <outJson> [runs]
24	 *
25	 * Logs per-stage wall time, total RTF, Java heap and process PSS (Debug.getPss).
26	 */
27	object EmulatorBench {
28	    @JvmStatic
29	    fun main(args: Array<String>) {
30	        require(args.size >= 3) { "usage: <modelsDir> <wav> <outJson> [runs]" }
31	        val root = File(args[0])
32	        val wav = File(args[1])
33	        val out = File(args[2])
34	        val runs = if (args.size >= 4) args[3].toInt() else 2
35	        val samples = readWavPcm16Mono16k(wav)
36	        println("BENCH wav=${wav.name} samples=${samples.size} dur_ms=${samples.size * 1000L / Pcm.TARGET_HZ}")
37	        println("BENCH abi=${System.getProperty("os.arch")} pss_start_kb=${Debug.getPss()}")
38	
39	        val gguf = File(root, "llm").listFiles()?.firstOrNull { it.extension == "gguf" }
40	        val paths = ModelPaths(
41	            zipformerDir = File(root, "asr/zipformer-ru-int8").takeIf { it.isDirectory },
42	            whisperTinyDir = null,
43	            sileroVad = File(root, "vad/silero_vad.onnx").takeIf { it.isFile },
44	            diarSegmentation = File(root, "diar/segmentation.onnx").takeIf { it.isFile },
45	            diarEmbedding = File(root, "diar/embedding.onnx").takeIf { it.isFile },
46	            voskDir = null,
47	            llmGguf = gguf,
48	        )
49	        val llm = LlmPostPassFactory.create(gguf)
50	        val pipeline = SherpaStenoPipeline(paths, llm, FarFieldScene.stage6)
51	        println("BENCH engine=${pipeline.displayName} llm=${pipeline.llmDisplayName} llmOnDevice=${pipeline.llmIsOnDevice}")
52	        val audio = PcmAudio(samples, Pcm.TARGET_HZ, wav.name)
53	
54	        runBlocking {
55	            repeat(runs) { run ->
56	                val prep = DemoPcmPrep.prepare(audio)
57	                if (prep.skipAsr) {
58	                    println("BENCH run=$run SKIP=${prep.skipCode}")
59	                    return@repeat
60	                }
61	                val t0 = System.nanoTime()
62	                val pssBefore = Debug.getPss()
63	                var peakPss = pssBefore
64	                val result = pipeline.transcribe(prep.audio) { p ->
65	                    val ms = (System.nanoTime() - t0) / 1_000_000
66	                    val pss = Debug.getPss()
67	                    if (pss > peakPss) peakPss = pss
68	                    println("BENCH run=$run stage=${p.stage} t_ms=$ms pss_kb=$pss msg=${p.message}")
69	                }
70	                val wallMs = (System.nanoTime() - t0) / 1_000_000
71	                val durMs = samples.size * 1000L / Pcm.TARGET_HZ
72	                val json = TranscriptJson.encode(result)
73	                out.writeText(json)
74	                println("BENCH run=$run DONE wall_ms=$wallMs dur_ms=$durMs rtf=${"%.4f".format(wallMs.toDouble() / durMs)} utterances=${result.transcript.size} speakers=${result.transcript.map { it.speakerId }.distinct().size} pss_before_kb=$pssBefore pss_peak_kb=$peakPss pss_end_kb=${Debug.getPss()}")
75	                println("BENCH run=$run transcript=$json")
76	            }
77	            pipeline.close()
78	        }
79	        println("BENCH FINISHED pss_end_kb=${Debug.getPss()}")
80	    }
81	
82	    private fun readWavPcm16Mono16k(file: File): FloatArray {
83	        RandomAccessFile(file, "r").use { raf ->
84	            val riff = ByteArray(12)
85	            raf.readFully(riff)
86	            require(String(riff, 0, 4) == "RIFF" && String(riff, 8, 4) == "WAVE") { "not a WAV" }
87	            var sampleRate = 0
88	            var channels = 0
89	            var bits = 0
90	            var data = ByteArray(0)
91	            while (raf.filePointer < raf.length()) {
92	                val id = ByteArray(4)
93	                raf.readFully(id)
94	                val size = readLeInt(raf)
95	                when (String(id)) {
96	                    "fmt " -> {
97	                        val fmt = ByteArray(size)
98	                        raf.readFully(fmt)
99	                        channels = (fmt[2].toInt() and 0xff) or ((fmt[3].toInt() and 0xff) shl 8)
100	                        sampleRate = (fmt[4].toInt() and 0xff) or ((fmt[5].toInt() and 0xff) shl 8) or
101	                            ((fmt[6].toInt() and 0xff) shl 16) or ((fmt[7].toInt() and 0xff) shl 24)
102	                        bits = (fmt[14].toInt() and 0xff) or ((fmt[15].toInt() and 0xff) shl 8)
103	                    }
104	                    "data" -> {
105	                        data = ByteArray(size)
106	                        raf.readFully(data)
107	                    }
108	                    else -> raf.seek(raf.filePointer + size + (size and 1))
109	                }
110	                if (data.isNotEmpty() && sampleRate != 0) break
111	            }
112	            require(bits == 16) { "need PCM16, got $bits" }
113	            val n = data.size / 2
114	            val mono = FloatArray(if (channels > 1) n / channels else n)
115	            var i = 0
116	            var j = 0
117	            while (i + 1 < data.size) {
118	                val s = ((data[i].toInt() and 0xff) or (data[i + 1].toInt() shl 8)).toShort()
119	                if (channels > 1) {
120	                    // average channels
121	                    var acc = 0
122	                    for (c in 0 until channels) {
123	                        val k = i + c * 2
124	                        if (k + 1 < data.size) acc += ((data[k].toInt() and 0xff) or (data[k + 1].toInt() shl 8)).toShort()
125	                    }
126	                    mono[j++] = (acc.toFloat() / channels) / 32768f
127	                    i += channels * 2
128	                } else {
129	                    mono[j++] = s / 32768f
130	                    i += 2
131	                }
132	            }
133	            require(sampleRate == Pcm.TARGET_HZ) { "need 16 kHz, got $sampleRate (pre-resample on host)" }
134	            return mono.copyOf(j)
135	        }
136	    }
137	
138	    private fun readLeInt(raf: RandomAccessFile): Int {
139	        val b = ByteArray(4)
140	        raf.readFully(b)
141	        return (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) or
142	            ((b[2].toInt() and 0xff) shl 16) or ((b[3].toInt() and 0xff) shl 24)
143	    }
144	}
145

#### Tool: Bash

**Input:**

```json
{
  "command": "cat artifacts/compact/exclusive-phases-experiment.json",
  "description": "Read in-flight exclusive phases experiment results"
}
```

**Response:**

{
  "test_id": "EXCLUSIVE_PHASES_EXPERIMENT",
  "date": "2026-11-07",
  "status": "uncommitted-prototype",
  "revert_point": "3af1e5a",
  "android_device_execution": false,
  "changes": [
    "SherpaStenoPipeline.transcribe: ASR session evicted after ASR phase, before LLM loads (runExclusivePhases)",
    "ExclusivePhases.kt + ExclusivePhasesTest.kt (5 contract tests)",
    "No model, fixture, gain/EQ/VAD, or decoder changes"
  ],
  "lever_2_custom_jni": {
    "decision": "not built: null RAM result",
    "evidence": "artifacts/compact/build/ubatch-rss-b8054.json",
    "bundled_llama_build": "b8054",
    "host_peak_rss_mb_ubatch_512": 639.455232,
    "host_peak_rss_mb_ubatch_128": 630.140928,
    "host_peak_rss_mb_ubatch_64": 625.283072,
    "outputs_identical_across_ubatch": true,
    "note": "b8054 already allocates logits efficiently; batch control would save ~14 MB, not the feared ~311 MB. NDK downloaded then removed unused."
  },
  "host_component_rss": {
    "asr_only_peak_mb": 273.362944,
    "asr_note": "includes Python+numpy interpreter baseline",
    "llm_only_peak_mb": 630.140928,
    "llm_config": "ctx 1024, ubatch 128, threads 2, CPU, mmap default"
  },
  "ram_estimate_android_not_measured": {
    "before_exclusive_phases": "ASR + LLM resident simultaneously: ~0.9-1.3 GB peak (estimate)",
    "after_exclusive_phases": "max(diar, ASR, LLM) + UI: ~0.7-0.8 GB peak (estimate)",
    "target_400_800_mb": "upper band plausible; unverified without device PSS",
    "baseline_under_1500_mb": "plausible both before and after; unverified"
  },
  "latency_tradeoff": "ASR reloads each run (~590 ms prepared-encoder init, host) instead of staying warm; LLM stays warm after first run. First-run latency unchanged.",
  "verification": {
    "stenographer_jvm": "131 tests, 0 failures (126 + 5 new)",
    "present_connect_jvm": "131 tests, 0 failures",
    "compact_apk_built": true,
    "exclusive_phases_in_dex": true,
    "apk_path": "apk/app-stenographer-compact-excl-phases.apk",
    "apk_bytes": 449911919,
    "apk_sha256": "f8cebb36783210e83e63a4ab65d117cf323780f192ee0ceec5b6dceba233bafd",
    "signature_verified": true
  }
}

#### Thinking

The other session's experiment is a memory-phase prototype, not device-verified. It built `apk/app-stenographer-compact-excl-phases.apk`. The user is likely testing that APK (or an earlier one) and found:
1. Speaker churn. Crash on long voicelines.

Now — is there an emulator available on this box? Let me check `adb devices` and the docs for how they bench. Also let me read the VadDiarReconcileTest to understand pinned behavior, and check `docs/` for the current state. Let me also check `StreamLabeler` quickly since Path B has its own labeling (but user flow is main app).

Let me check adb and the test file.

#### Tool: Bash

**Input:**

```json
{
  "command": "adb devices -l 2>/dev/null; ls docs/; ls apk/",
  "description": "Check emulator availability, docs, and APK drop"
}
```

**Response:**

List of devices attached
emulator-5554          device product:SOV44 model:SOV44 device:graceltexx transport_id:3

ARCHITECTURE.md
GATE.md
INSTALL.md
JSON.md
LIMITATIONS.md
LIMITS.md
MODELS.md
PRESENT_CONNECT.md
PROTOCOL.md
TEST_RESULTS.md
TZ_CHECKLIST.md
overnight_clips_handoff.md
README.md
app-presentConnect-debug.apk
app-stenographer-compact-excl-phases.apk
app-stenographer-compact.apk
app-stenographer-debug-embedLlm.apk
app-stenographer-debug.apk
app-stenographer-runtime-embedLlm.apk
app-stenographer-slim.apk

#### Tool: Read

**Input:**

```json
{
  "file_path": "E:\\Projects\\neuro-stenographer\\neuro-stenographer\\src\\android\\app\\src\\test\\java\\team\\mikcoders\\neurosteno\\pipeline\\VadDiarReconcileTest.kt",
  "limit": 100
}
```

**Response:**

1	package team.mikcoders.neurosteno.pipeline
2	
3	import org.junit.Assert.assertEquals
4	import org.junit.Assert.assertNotEquals
5	import org.junit.Assert.assertTrue
6	import org.junit.Test
7	import team.mikcoders.neurosteno.audio.FarFieldSynth
8	import kotlin.math.sin
9	
10	class VadDiarReconcileTest {
11	    private val hz = 16_000
12	    private val scene = FarFieldScene.stage6
13	
14	    @Test
15	    fun orphanFifthTurnIsRecoveredAsSpeaker() {
16	        val vad = listOf(
17	            SpeechRegion(0, 16_000),
18	            SpeechRegion(16_000, 32_000),
19	            SpeechRegion(32_000, 48_000),
20	            SpeechRegion(48_000, 64_000),
21	            SpeechRegion(64_000, 80_000),
22	            SpeechRegion(80_000, 96_000),
23	        )
24	        val diar = listOf(
25	            DiarSeg(0, 0, 1_000),
26	            DiarSeg(1, 1_000, 2_000),
27	            DiarSeg(2, 2_000, 3_000),
28	            DiarSeg(3, 3_000, 4_000),
29	            DiarSeg(5, 5_000, 6_000),
30	        )
31	        val out = VadDiarReconcile.run(vad, diar, hz, scene)
32	        assertEquals(6, out.map { it.speakerIndex }.distinct().size)
33	        val fifth = out.single { it.startMs == 4_000L }
34	        assertTrue(fifth.endMs > fifth.startMs)
35	        assertTrue(out.none { it.speakerIndex >= 6 })
36	    }
37	
38	    @Test
39	    fun twelveVadIslandsNeverInventSpeakerSeven() {
40	        val vad = (0 until 12).map { i ->
41	            SpeechRegion(i * 16_000, (i + 1) * 16_000)
42	        }
43	        val diar = listOf(
44	            DiarSeg(0, 0, 1_000),
45	            DiarSeg(1, 1_000, 2_000),
46	            DiarSeg(2, 2_000, 3_000),
47	            DiarSeg(3, 3_000, 4_000),
48	            DiarSeg(4, 4_000, 5_000),
49	        )
50	        val out = VadDiarReconcile.run(vad, diar, hz, scene)
51	        val n = out.map { it.speakerIndex }.distinct().size
52	        assertEquals("hard cap K=6, was $n labels ${out.map { it.speakerId }}", 6, n)
53	        assertTrue(out.none { it.speakerIndex > 5 })
54	        val near = out.minBy { it.startMs }
55	        assertEquals(0L, near.startMs)
56	        assertEquals(1_000L, near.endMs)
57	    }
58	
59	    @Test
60	    fun largeVadBlobDoesNotSwallowNearSeat() {
61	        val vad = listOf(SpeechRegion(0, 16_000 * 6))
62	        val diar = listOf(
63	            DiarSeg(0, 0, 1_000),
64	            DiarSeg(1, 2_000, 2_202),
65	        )
66	        val out = VadDiarReconcile.run(vad, diar, hz, scene)
67	        val near = out.minBy { it.startMs }
68	        assertEquals(0L, near.startMs)
69	        assertEquals(1_000L, near.endMs)
70	        assertTrue(out.none { it.startMs == 0L && it.endMs == 6_000L })
71	        assertTrue(out.map { it.speakerIndex }.distinct().size <= 6)
72	    }
73	
74	    @Test
75	    fun energyHoleRecoversQuietFarTurnWithoutMovingNear() {
76	        val (pcm, turns) = FarFieldSynth.sequentialMix()
77	        val near = turns.first { it.name == "near_3m" }
78	        val t0 = near.range.first * 1000L / hz
79	        val t1 = (near.range.last + 1) * 1000L / hz
80	        val diar = listOf(DiarSeg(0, t0, t1))
81	        val vad = listOf(SpeechRegion(near.range.first, near.range.last + 1))
82	        val out = VadDiarReconcile.run(vad, diar, hz, scene, samples = pcm)
83	        val kept = out.minBy { it.startMs }
84	        assertEquals(t0, kept.startMs)
85	        assertEquals(t1, kept.endMs)
86	        assertTrue("recover s5-like far island", out.size >= 2)
87	        assertTrue(out.any { it.startMs >= t1 && it.endMs - it.startMs >= 400 })
88	        assertTrue(out.map { it.speakerIndex }.distinct().size <= 6)
89	        assertEquals(kept.speakerIndex, 0)
90	        assertTrue(out.filter { it.startMs >= t1 }.none { it.speakerIndex == kept.speakerIndex })
91	    }
92	
93	    @Test
94	    fun doesNotGlueS2AndS3NearFieldTurns() {
95	        val pcm = stage6LikePcm()
96	        val vad = listOf(
97	            SpeechRegion(ms(0), ms(7_000)),
98	            SpeechRegion(ms(7_456), ms(12_000)),
99	            SpeechRegion(ms(12_500), ms(19_066)),
100	            SpeechRegion(ms(20_000), ms(25_000)),

#### Thinking

There's an emulator running (emulator-5554, SOV44 — Samsung Galaxy Note 8 profile? "graceltexx"). The other session used it for benching. I can use it to reproduce both bugs on the actual artifact — per my memory "Verifier must reproduce the bug" and "Verify, don't guess".

Plan:
1. Reproduce the diarization churn: push a synthetic 1-2 speaker recording (or use the existing demo sample) through the pipeline on the emulator via EmulatorBench (it's in the tree but is it in the installed APK? The experiment JSON says the compact APK has exclusive phases in dex; EmulatorBench is untracked but may have been compiled into that APK). Let me check what's installed on the emulator and whether EmulatorBench is in the APK. Reproduce the crash: generate a long voiceline WAV (e., 3-5 minutes of continuous speech), push, run through pipeline → observe crash/OOM.

But wait — EmulatorBench uses `FarFieldScene.stage6` and `DemoPcmPrep`. For the crash repro, a long single-speaker WAV. I can synthesize speech-like audio? For ASR to produce a long segment, I need actual speech. There are samples in `samples/`. Let me check what samples exist — maybe I can concatenate a Russian speech sample repeatedly to make a long voiceline. Concatenating the same sample = same speaker, continuous → tests both bugs at once! 

Let me check samples/ and the emulator state. Also check docs/TEST_RESULTS.md for how they ran the bench (commands).

Actually, let me first check what's on the emulator: installed packages, pushed files.

#### Tool: Bash

**Input:**

```json
{
  "command": "adb -s emulator-5554 shell pm list packages 2>/dev/null | grep -i mik; adb -s emulator-5554 shell ls /data/local/tmp/ 2>/dev/null; ls samples/ eval/ 2>/dev/null",
  "description": "Check emulator installed app, pushed files, and local samples"
}
```

**Response:**

eval/:
GATE.md
README.md
__init__.py
__pycache__
clips
fixtures
gold
manifest.json
metrics

samples/:
README.md
clips_build.json
demo-stress
manifest.json
ref

#### Thinking

Hmm, the adb shell commands returned nothing for packages and /data/local/tmp — maybe the grep found nothing and ls failed silently. Let me run them separately to see actual output/errors.

#### Tool: Bash

**Input:**

```json
{
  "command": "adb -s emulator-5554 shell pm list packages | grep -iE \"mik|steno\"; echo \"---\"; adb -s emulator-5554 shell ls /data/local/tmp/; echo \"---\"; ls samples/demo-stress/ samples/ref/ 2>/dev/null | head -30",
  "description": "List emulator packages, tmp files, and sample audio"
}
```

**Response:**

---
ls: C:/Program: No such file or directory
ls: Files/Git/data/local/tmp/: No such file or directory
---
samples/demo-stress/:
README.md
adversarial
channels.json
golden_path_b_brief.json
manifest.json
mix_build.json
ref
smoke.wav
stage6

samples/ref/:
clip_01.json
clip_02.json
clip_03.json

#### Thinking

Git Bash is mangling the remote path `/data/local/tmp/` — it's converting to `C:/Program Files/Git/data/local/tmp/`. Classic MSYS path conversion issue. I need to escape: use `//data/local/tmp/` or set `MSYS_NO_PATHCONV=1`. Also the package grep returned nothing — maybe the app isn't installed, or pm list packages output got mangled too? Let me retry with MSYS_NO_PATHCONV.

Git Bash is mangling remote paths. Let me disable path conversion and retry.

#### Tool: Bash

**Input:**

```json
{
  "command": "export MSYS_NO_PATHCONV=1; adb -s emulator-5554 shell pm list packages | grep -iE \"mik|steno\"; echo \"---tmp---\"; adb -s emulator-5554 shell ls /data/local/tmp/; echo \"---samples---\"; ls samples/demo-stress/stage6/ samples/demo-stress/adversarial/ 2>/dev/null | head -40",
  "description": "List emulator packages and pushed files without path conversion"
}
```

**Response:**

---tmp---
---samples---
samples/demo-stress/adversarial/:
README.md
channels_overlap.json
recipe.json
ref
smoke_worst.wav

samples/demo-stress/stage6/:
README.md
recipe.json
ref
smoke.wav

#### Thinking

The emulator has no mikcoders package installed and /data/local/tmp is empty. So the other session's emulator was reset, or this is a fresh emulator. The `pm list packages | grep` returned nothing — let me double check that the emulator is responsive and see total packages. Also, the samples: `samples/demo-stress/stage6/smoke.wav` — let me check its duration and the ref. And `eval/clips`, `eval/fixtures` may have audio.

Let me check emulator responsiveness and sample durations.

#### Tool: Bash

**Input:**

```json
{
  "command": "export MSYS_NO_PATHCONV=1; adb -s emulator-5554 shell getprop ro.product.cpu.abi; adb -s emulator-5554 shell pm list packages | wc -l; adb -s emulator-5554 shell cat /proc/meminfo | head -3; ls -la samples/demo-stress/stage6/smoke.wav samples/demo-stress/smoke.wav samples/demo-stress/adversarial/smoke_worst.wav; cat samples/demo-stress/stage6/recipe.json 2>/dev/null | head -40",
  "description": "Check emulator ABI, memory, and sample audio files"
}
```

**Response:**

x86_64
100
MemTotal:        6086764 kB
MemFree:         4726184 kB
MemAvailable:    4954916 kB
-rw-r--r-- 1 l1ff0 197121 24684 Nov  7 09:03 samples/demo-stress/adversarial/smoke_worst.wav
-rw-r--r-- 1 l1ff0 197121 39724 Nov  7 09:03 samples/demo-stress/smoke.wav
-rw-r--r-- 1 l1ff0 197121 38444 Nov  7 09:03 samples/demo-stress/stage6/smoke.wav
{
  "test_id": "DEMO_STAGE6",
  "role": "primary_demo_fixture",
  "secondary": "20-stream Path B CLEAN+EDGE+WORST / gated_* is regression only",
  "label": "REAL",
  "corpus": "Mozilla Common Voice 17.0 ru test split",
  "license": "CC0-1.0",
  "seed": 20261106,
  "n_talkers": 6,
  "one_voice_channel": true,
  "duration_ms": 39718,
  "overlap": {
    "max_active_speakers": 2,
    "frac_speech_frames_2plus": 0.0256,
    "frac_speech_frames_5plus": 0.0,
    "mean_active_on_speech": 1.026
  },
  "channels": [
    {
      "speaker_id": "speaker_1",
      "client_id": "50228d02790b054af1d849979146d7aa77737c42fabbe9267b82638a67e7f0db5d0fb20bd670ebb1330f02e1de607aabe1acc15b7fb00f512e0812b780f2522a",
      "distance_m": 3.0,
      "gain_vs_3m": 1.0,
      "start_ms": 250,
      "end_ms": 7018,
      "duration_ms": 6768,
      "path": "common_voice_ru_37303728.mp3"
    },
    {
      "speaker_id": "speaker_2",
      "client_id": "562fa31f74b3ca16702456f506bfe49d6b4f55a2fa8c76f3f88d39e4f410050a23fa582682886256700ae292c0d385d9e4455c729b24983445b81f1644901b5f",
      "distance_m": 3.6,
      "gain_vs_3m": 0.8333,
      "start_ms": 6818,
      "end_ms": 15650,
      "duration_ms": 8832,
      "path": "common_voice_ru_19237378.mp3"
    },
    {
      "speaker_id": "speaker_3",

#### Thinking

The emulator is x86_64 with 6GB RAM. The APKs in apk/ — are they arm64-only? build.gradle default targetAbis = arm64-v8a. The emulator is x86_64 — arm64 APK won't install/run unless the emulator has ARM translation (Google APIs x86_64 images have libndk translation for arm64 on API 28+? Actually ARM translation on x86_64 emulators exists for API 28-30 via libndk_translation; newer images (API 31+) support arm64-v8a translation too on Google Play images). The other session's EmulatorBench comment says `lib/arm64` — so they ran arm64 on this emulator via translation. product SOV44 model... "graceltexx" — that's a Samsung Galaxy Note8 device profile, probably API 28-ish with ndk translation.

The smoke.wav files are tiny (38KB ≈ 1.2s) — those are smoke fixtures, not the full 39.7s mix. The full mix must be built by a script (mix_build.json). Let me check eval/fixtures and eval/clips for real audio, and scripts for building the demo mix. Also check docs/TEST_RESULTS.md for the bench commands used.

Actually — wait. Let me reconsider scope. The user asked to fix two bugs. The strongest verification is on-device/emulator reproduction. The infrastructure exists (EmulatorBench, emulator running). But the emulator is x86_64 and APKs are arm64 — the other session apparently ran it (the experiment JSON says android_device_execution: false though! "ram_estimate_android_not_measured"). Hmm, so the other session did NOT run on device. EmulatorBench was prepared but maybe never run. The apk/app-stenographer-compact-excl-phases.apk was built but not device-tested.

Let me check if the emulator can run arm64 (check for libndk_translation) and what API level. Also find the full demo audio. Let me look at eval/fixtures and scripts.

#### Tool: Bash

**Input:**

```json
{
  "command": "export MSYS_NO_PATHCONV=1; adb -s emulator-5554 shell getprop ro.build.version.sdk; adb -s emulator-5554 shell ls /system/lib64/ | grep -iE \"ndk|houdini|arm\" | head; echo \"---\"; ls eval/fixtures/ eval/clips/ 2>/dev/null | head -30; echo \"---\"; ls scripts/ | head -40",
  "description": "Check emulator ARM translation and locate full demo audio"
}
```

**Response:**

34
android.frameworks.cameraservice.common-V1-ndk.so
android.frameworks.cameraservice.device-V1-ndk.so
android.frameworks.cameraservice.service-V1-ndk.so
android.frameworks.sensorservice-V1-ndk.so
android.frameworks.stats-V2-ndk.so
android.hardware.audio.common-V2-ndk.so
android.hardware.audio.core-V1-ndk.so
android.hardware.audio.core.sounddose-V1-ndk.so
android.hardware.audio.effect-V1-ndk.so
android.hardware.audio.sounddose-V1-ndk.so
---
eval/clips/:
README.md

eval/fixtures/:
README.md
toy_gold.json
toy_hyp.json
---
README.md
__pycache__
benchmark_asr_runtime.py
benchmark_llm_compaction.py
ci-check.sh
demo_pcm_prep.py
device-sideload-probe.sh
diar_param_sweep.py
download-models.sh
eval-wer.sh
eval_demo_stress.py
eval_harness.py
eval_metrics.py
farfield_gain.py
fetch_cv17_ru.py
generate_adversarial_path_b.py
generate_demo_stage6.py
generate_demo_stress_mix.py
host-jni-smoke.sh
host_asr_diar.py
inspect-sherpa-aar.sh
intonation_punct.py
measure-farfield-gain.py
measure-phone-distance-eq.py
measure-stream-pcm-gate.py
phone_distance_eq.py
prepare_compact_encoder.py
requirements-eval.txt
stream_pcm_gate.py
test_eval_metrics.py
test_farfield_gain.py
test_intonation_punct.py
test_path_b_regression.py
test_phone_distance_eq.py
test_present_connect_capacity.py
test_stream_pcm_gate.py
test_vad_diar_reconcile.py
vad_diar_reconcile.py
verify-transcript-json.py

#### Thinking

API 34 emulator, x86_64. No houdini/ndk_translation visible in that grep (the grep matched "ndk" in unrelated libs). Let me check specifically for libndk_translation.so or arm64 support. API 34 Google Play x86_64 images DO support arm64 translation (since API 31, arm64-v8a is supported via ndk_translation on Google Play images; on AOSP images only armv7). Let me check /system/lib64/libndk_translation.so and /system/bin/ndk_translation_program_runner.

Also there's `scripts/host_asr_diar.py` — host-side ASR+diar! And `scripts/test_vad_diar_reconcile.py` + `vad_diar_reconcile.py` — a Python mirror of the reconcile. The measured STAGE6 rows were produced on host (Python sherpa-onnx). So I can reproduce the diarization behavior on HOST with sherpa-onnx Python — much faster than the emulator. Let me check host_asr_diar.py and the .venv.

For the crash (native ASR OOM on long crops), host reproduction with Python sherpa-onnx would also demonstrate memory scaling, but the actual crash is Android-specific (memory limits). Still, host repro of "long segment → huge RSS" plus code inspection is decent evidence; the emulator can confirm the actual crash if it can run arm64.

Let me check: .venv has sherpa_onnx? And host_asr_diar.py content. And emulator translation.

#### Tool: Bash

**Input:**

```json
{
  "command": "export MSYS_NO_PATHCONV=1; adb -s emulator-5554 shell \"ls /system/lib64/libndk_translation.so /system/bin/ndk_translation_program_runner 2>/dev/null; getprop ro.dalvik.vm.isa.arm64\"; echo \"---venv---\"; .venv/Scripts/python.exe -c \"import sherpa_onnx; print(sherpa_onnx.__version__)\" 2>&1; echo \"---\"; head -60 scripts/host_asr_diar.py",
  "description": "Check ARM translation, host sherpa-onnx, and host ASR script"
}
```

**Response:**

x86_64
---venv---
2.0.1
---
#!/usr/bin/env python3
"""Host-Linux Zipformer-ru + OfflineSpeakerDiarization (no Android / no ADB).

Cloud agents have no KVM/ADB. This is the eval path that can actually run
on a Linux VM. Models come from scripts/download-models.sh (same names as
docs/MODELS.md).

Output JSON is exactly {transcript:[{speaker_id,start_ms,end_ms,text}]}.

Does not invent metrics. On missing models / import failure / OOM it raises
HostBlocked with the real reason.

Usage:
  python3 scripts/host_asr_diar.py --wav samples/demo-stress/mix_20talker.wav \\
      --out artifacts/eval/demo-stress/hyp.json
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import demo_pcm_prep as DEMO_PREP  # noqa: E402
import farfield_gain as FF  # noqa: E402
import intonation_punct as PUNCT  # noqa: E402
import stream_pcm_gate as PCM_GATE  # noqa: E402
import vad_diar_reconcile as RECON  # noqa: E402


class HostBlocked(RuntimeError):
    """Inference did not run. Callers must record pending/blocked, not fake WER."""


def collect_silero(
    samples,
    sr: int,
    models: Path,
    *,
    threshold: float = 0.32,
    min_silence_sec: float = 0.18,
    min_speech_sec: float = 0.20,
    max_speech_sec: float = 30.0,
    window: int = 512,
) -> list[dict[str, int]]:
    """Silero VAD regions (exclusive-end samples). Empty if model/API missing."""
    import sherpa_onnx

    vad_path = Path(models) / "vad" / "silero_vad.onnx"
    if not vad_path.is_file():
        return []
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = str(vad_path)
    cfg.silero_vad.threshold = float(threshold)

#### Thinking

Host has sherpa_onnx 2.0.1 in .venv — same version as the Android AAR (v2.0.1). The emulator has NO ARM translation (getprop ro.dalvik.vm.isa.arm64 returned x86_64?? wait — the output shows "x86_64" which is the answer to `getprop ro.dalvik.vm.isa.arm64`... that means arm64 ISA maps to x86_64?? No wait — the ls found nothing (no output), and getprop returned "x86_64". Hmm, `ro.dalvik.vm.isa.arm64` = x86_64 would mean arm64 apps translate to x86_64 — that's unusual. Actually on some emulator images `ro.dalvik.vm.isa.arm64=x86_64` indicates native bridge support. Let me verify by checking ro.dalvik.vm.isa.arm and native bridge props. Actually, if ro.dalvik.vm.isa.arm64 = x86_64, then the emulator CAN run arm64 apps via native bridge! That's the "ndk translation" configured at the ISA level. Let me check `ro.enable.native.bridge.exec` and whether the arm64 APK actually installs.

Actually the simplest test: install the existing compact APK and run EmulatorBench. But wait — EmulatorBench is uncommitted; is it in the built APK `app-stenographer-compact-excl-phases.apk`? The experiment JSON says "exclusive_phases_in_dex": true but doesn't mention EmulatorBench. Let me check the APK dex for EmulatorBench.

Plan for reproduction (cheapest first):
1. HOST reproduction of diarization churn: use host_asr_diar.py path (Python sherpa-onnx 2.1, same models) on a synthetic 1-speaker and 2-speaker recording. The Python `vad_diar_reconcile.py` mirrors the Kotlin. If K=6 forced clustering splits one speaker → confirmed. This is fast and uses the actual models. CRASH: host repro of ASR on a long crop → measure RSS scaling; then emulator confirm if native bridge works.

Let me check the models are present locally (models/ dir), and read the rest of host_asr_diar.py to see how to drive it, plus vad_diar_reconcile.py to confirm it mirrors Kotlin. Also check the demo mix generation script to build a long single-speaker WAV from Common Voice clips — wait, do we have the CV17 clips locally? eval/clips is empty (just README). samples/demo-stress/stage6 has only smoke.wav (1.2s). The full mix isn't in the tree (too big?). Let me check models/ and what audio we actually have.

#### Tool: Bash

**Input:**

```json
{
  "command": "find models -type f | head -20; echo \"---audio---\"; find . -name \"*.wav\" -not -path \"./.venv/*\" -not -path \"./src/android/*\" 2>/dev/null | head; echo \"---\"; sed -n 60,200p scripts/host_asr_diar.py",
  "description": "List local models, audio files, and host ASR script body"
}
```

**Response:**

models/.gitkeep
models/asr/zipformer-ru-int8/decoder.onnx
models/asr/zipformer-ru-int8/encoder.int8.onnx
models/asr/zipformer-ru-int8/joiner.int8.onnx
models/asr/zipformer-ru-int8/tokens.txt
models/compact/asr/zipformer-ru-int8/encoder-all.onnx
models/compact/asr/zipformer-ru-int8/encoder-extended.onnx
models/compact/asr/zipformer-ru-int8/encoder.int8.onnx
models/compact/asr/zipformer-ru-int8/encoder.ort
models/diar/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx
models/diar/embedding.onnx
models/diar/segmentation.onnx
models/diar/sherpa-onnx-pyannote-segmentation-3-0/export-onnx.py
models/diar/sherpa-onnx-pyannote-segmentation-3-0/LICENSE
models/diar/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx
models/diar/sherpa-onnx-pyannote-segmentation-3-0/model.onnx
models/diar/sherpa-onnx-pyannote-segmentation-3-0/README.md
models/diar/sherpa-onnx-pyannote-segmentation-3-0/run.sh
models/diar/sherpa-onnx-pyannote-segmentation-3-0/show-onnx.py
models/diar/sherpa-onnx-pyannote-segmentation-3-0/speaker-diarization-onnx.py
---audio---
./artifacts/demo-stage6-handoff/stem_or_slice_speaker_1_3m.wav
./artifacts/demo-stage6-handoff/stem_or_slice_speaker_2_3p6m.wav
./artifacts/demo-stage6-handoff/stem_or_slice_speaker_3_4p2m.wav
./artifacts/demo-stage6-handoff/stem_or_slice_speaker_4_4p8m.wav
./artifacts/demo-stage6-handoff/stem_or_slice_speaker_5_5p4m.wav
./artifacts/demo-stage6-handoff/stem_or_slice_speaker_6_6m.wav
./artifacts/eval/demo-stress/stage6/listen/DEMO_STAGE6.wav
./artifacts/eval/demo-stress/stage6/listen/speaker_1_3.0m_worked.wav
./artifacts/eval/demo-stress/stage6/listen/speaker_2_3.6m_worked.wav
./artifacts/eval/demo-stress/stage6/listen/speaker_5_5.4m_failed.wav
---
    cfg.silero_vad.threshold = float(threshold)
    cfg.silero_vad.min_silence_duration = float(min_silence_sec)
    cfg.silero_vad.min_speech_duration = float(min_speech_sec)
    cfg.silero_vad.max_speech_duration = float(max_speech_sec)
    cfg.silero_vad.window_size = int(window)
    cfg.sample_rate = int(sr)
    cfg.num_threads = 1
    vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=60)
    x = samples.astype("float32", copy=False)
    offset = 0
    out: list[dict[str, int]] = []
    n = int(x.size)
    def _front():
        # sherpa-onnx 2.0.1 exposes front as a property, not a method.
        f = vad.front
        return f() if callable(f) else f

    while offset + window <= n:
        vad.accept_waveform(x[offset : offset + window])
        offset += window
        while not vad.empty():
            seg = _front()
            vad.pop()
            start = int(seg.start)
            end = start + int(len(seg.samples))
            if end > start:
                out.append({"start_sample": start, "end_sample": end})
    vad.flush()
    while not vad.empty():
        seg = _front()
        vad.pop()
        start = int(seg.start)
        end = start + int(len(seg.samples))
        if end > start:
            out.append({"start_sample": start, "end_sample": end})
    return out


def _asr_crop(recognizer, samples, sr: int, start_ms: int, end_ms: int, *, pad_ms: int = 0, per_crop_gain: bool = False) -> str:
    a = max(0, int(((start_ms - pad_ms) * sr) / 1000))
    b = min(int(samples.size), int(((end_ms + pad_ms) * sr) / 1000))
    if b <= a:
        return ""
    crop = samples[a:b]
    if per_crop_gain:
        crop = FF.stage(crop, per_turn=True)["samples"]
    if recognizer is None or crop.size == 0:
        return ""
    try:
        stream = recognizer.create_stream()
        stream.accept_waveform(sr, crop)
        recognizer.decode_stream(stream)
        return (stream.result.text or "").strip()
    except Exception as e:
        print(f"ASR crop {start_ms}-{end_ms}ms failed: {e}", file=sys.stderr)
        return ""


def find_zipformer(models: Path) -> dict[str, Path]:
    d = models / "asr" / "zipformer-ru-int8"
    if not d.is_dir():
        raise HostBlocked(f"missing Zipformer-ru dir {d} (run scripts/download-models.sh)")
    names = list(d.iterdir())

    def pick(kind: str) -> Path:
        if kind == "tokens":
            hits = [p for p in names if p.name.lower() == "tokens.txt"]
        else:
            hits = [p for p in names if kind in p.name.lower() and p.suffix == ".onnx"]
        if not hits:
            raise HostBlocked(f"Zipformer-ru missing {kind} under {d}")
        return hits[0]

    return {
        "encoder": pick("encoder"),
        "decoder": pick("decoder"),
        "joiner": pick("joiner"),
        "tokens": pick("tokens"),
    }


def find_diar(models: Path) -> dict[str, Path]:
    d = models / "diar"
    seg = None
    for cand in (d / "segmentation.onnx", d / "model.onnx"):
        if cand.is_file():
            seg = cand
            break
    if seg is None and d.is_dir():
        hits = [p for p in d.iterdir() if p.suffix == ".onnx" and "embed" not in p.name.lower()]
        seg = hits[0] if hits else None
    emb = None
    for cand in (
        d / "embedding.onnx",
        d / "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
    ):
        if cand.is_file():
            emb = cand
            break
    if emb is None and d.is_dir():
        hits = [p for p in d.iterdir() if p.suffix == ".onnx" and "3dspeaker" in p.name.lower()]
        emb = hits[0] if hits else None
    if seg is None or emb is None:
        raise HostBlocked(
            f"missing diarization ONNX under {d} (need pyannote segmentation + 3D-Speaker embedding; "
            "run scripts/download-models.sh)"
        )
    return {"segmentation": seg, "embedding": emb}


def load_wav_mono16k(path: Path):
    import wave

    import numpy as np

    with wave.open(str(path), "rb") as w:
        sr = w.getframerate()
        nch = w.getnchannels()
        sw = w.getsampwidth()
        raw = w.readframes(w.getnframes())
    if sw != 2:
        raise HostBlocked(f"{path} is not PCM16")
    x = np.frombuffer(raw, dtype="<i2").astype("float32") / 32768.0
    if nch > 1:
        x = x.reshape(-1, nch).mean(axis=1)
    if sr != 16000:
        n_out = max(1, int(round(x.size * 16000 / sr)))
        t_in = np.linspace(0.0, 1.0, x.size, endpoint=False)
        t_out = np.linspace(0.0, 1.0, n_out, endpoint=False)
        x = np.interp(t_out, t_in, x).astype("float32")
        sr = 16000
    return x, sr


def transcribe_asr_only(
    wav: Path,
    models: Path,
    *,
    speaker_id: str = "speaker_1",
    num_threads: int = 2,
) -> tuple[dict, dict]:

#### Thinking

All models are present locally, and there's real speech audio: `artifacts/demo-stage6-handoff/stem_or_slice_speaker_*.wav` (6 speaker stems) and `artifacts/eval/demo-stress/stage6/listen/DEMO_STAGE6.wav` (the full 39.7s mix). 

For reproduction I can:
1. **Diarization churn**: run the host diarization (sherpa-onnx OfflineSpeakerDiarization with num_clusters=6, threshold=0.5 — same as the app) on a single-speaker stem (e. speaker_1 sliced/looped into several turns with gaps). Observe: one voice → multiple speaker labels. Then run through the Python reconcile mirror → observe labels churn per turn. **Crash**: feed a long single crop (e. 3-5 min looped stem) to the host Zipformer and measure RSS/time; on Android this native allocation is what kills the process. Also check the emulator can run the arm64 APK for a true crash repro.

Let me check the stems' durations and the Python reconcile mirror's fidelity. Let me also read the rest of host_asr_diar.py (the diarize function) to see how clustering is configured there — does it use num_clusters=6? Let me grep.

#### Tool: Bash

**Input:**

```json
{
  "command": "grep -n \"num_clusters\\|cluster_threshold\\|FastClustering\\|clustering\" scripts/host_asr_diar.py scripts/diar_param_sweep.py | head -20; echo \"---\"; .venv/Scripts/python.exe -c \"\nimport wave\nfor i in [1,2,5]:\n    p=f'artifacts/demo-stage6-handoff/stem_or_slice_speaker_{i}_'+{1:'3m',2:'3p6m',5:'5p4m'}[i]+'.wav'\n    w=wave.open(p); print(p, w.getframerate(),'Hz', w.getnframes()/w.getframerate(),'s', w.getnchannels(),'ch')\nw=wave.open('artifacts/eval/demo-stress/stage6/listen/DEMO_STAGE6.wav'); print('DEMO_STAGE6.wav', w.getframerate(),'Hz', w.getnframes()/w.getframerate(),'s')\n\"",
  "description": "Check clustering config in host scripts and stem durations"
}
```

**Response:**

scripts/host_asr_diar.py:252:        "num_clusters": 1,
scripts/host_asr_diar.py:269:    num_clusters: int = 20,
scripts/host_asr_diar.py:271:    cluster_threshold: float = 0.5,
scripts/host_asr_diar.py:307:        num_clusters = int(scene["expected_speakers"])
scripts/host_asr_diar.py:308:        cluster_threshold = float(scene["diar_cluster_threshold"])
scripts/host_asr_diar.py:347:        clustering=sherpa_onnx.FastClusteringConfig(
scripts/host_asr_diar.py:348:            num_clusters=num_clusters,
scripts/host_asr_diar.py:349:            threshold=cluster_threshold,
scripts/host_asr_diar.py:476:        "num_clusters": num_clusters,
scripts/host_asr_diar.py:477:        "cluster_threshold": cluster_threshold,
scripts/host_asr_diar.py:602:    num_clusters: int = 1,
scripts/host_asr_diar.py:737:                num_clusters=num_clusters,
scripts/host_asr_diar.py:896:        doc, meta = transcribe_file(args.wav, args.models, num_clusters=args.num_clusters)
scripts/diar_param_sweep.py:2:"""Path A clustering sweep on the one-room mix (diarization only, no ASR).
scripts/diar_param_sweep.py:32:    {"num_clusters": 20, "cluster_threshold": 0.5, "min_duration_on": 0.2, "min_duration_off": 0.5},
scripts/diar_param_sweep.py:33:    {"num_clusters": 20, "cluster_threshold": 0.3, "min_duration_on": 0.2, "min_duration_off": 0.5},
scripts/diar_param_sweep.py:34:    {"num_clusters": 20, "cluster_threshold": 0.8, "min_duration_on": 0.2, "min_duration_off": 0.5},
scripts/diar_param_sweep.py:35:    {"num_clusters": -1, "cluster_threshold": 0.5, "min_duration_on": 0.2, "min_duration_off": 0.5},
scripts/diar_param_sweep.py:36:    {"num_clusters": -1, "cluster_threshold": 0.3, "min_duration_on": 0.1, "min_duration_off": 0.3},
scripts/diar_param_sweep.py:37:    {"num_clusters": 20, "cluster_threshold": 0.5, "min_duration_on": 0.1, "min_duration_off": 0.2},
---
artifacts/demo-stage6-handoff/stem_or_slice_speaker_1_3m.wav 16000 Hz 6.768 s 1 ch
artifacts/demo-stage6-handoff/stem_or_slice_speaker_2_3p6m.wav 16000 Hz 8.832 s 1 ch
artifacts/demo-stage6-handoff/stem_or_slice_speaker_5_5p4m.wav 16000 Hz 4.68 s 1 ch
DEMO_STAGE6.wav 16000 Hz 39.718 s

#### Thinking

I have everything for a host reproduction. Let me write a reproduction script that:

**Repro 1 (diarization churn):** Build a "one person, several sequential replicas" WAV: take speaker_1 stem (6.768s), repeat it 4 times with 0.5s gaps → ~29s. Run sherpa-onnx OfflineSpeakerDiarization with the app's exact stage6 config (num_clusters=6, threshold=0.5, min_on=0.08, min_off=0.18). Count distinct speakers in raw diar output. Then also with 2 speakers alternating (speaker_1, speaker_2, speaker_1, speaker_2...) to show the same person gets different labels across turns.

Also run the Kotlin-equivalent reconcile (Python mirror) to show the final labels. But the Python mirror may differ from Kotlin — the Kotlin is ground truth for the app. Better: run the actual Kotlin via unit tests. But the raw clustering behavior (forced K=6) is the first link in the chain — host sherpa-onnx 2.1 == app's sherpa 2.1, so raw diar output is representative.

**Repro 2 (long-crop ASR memory):** Loop speaker_1 stem to ~3 minutes (one continuous "voiceline"), feed the whole thing as ONE crop to OfflineRecognizer (as transcribeSegments would for one long merged segment), measure peak RSS and time. Compare with 30s crop. On the host with plenty of RAM it won't crash but RSS scaling shows the mechanism; on Android (6GB emulator, but app heap+native limits) it dies. Actually — better: run it on the EMULATOR for a true crash. First check if the emulator can install the arm64 APK.

Let me first write and run the host repro for diarization (fast), and in parallel check emulator arm64 installability.

Actually, let me be careful about time. The host diarization on ~30s audio: segmentation + embedding + clustering — a few seconds to a minute on CPU. Fine.

Let me write the repro script. I need to check how host_asr_diar.py creates the diarizer (lines ~340-360) to copy the exact config. Let me view that section.

#### Tool: Bash

**Input:**

```json
{
  "command": "sed -n 300,380p scripts/host_asr_diar.py",
  "description": "Read host diarizer creation config"
}
```

**Response:**

ff_meta = None
    scene = RECON.STAGE6 if impl_second_pass else None
    if impl_second_pass:
        # Impl @ 3c3f9ce: frozen per-turn FarFieldGain (no whole-file crush), then
        # VadDiarReconcile HARD CAP K=6 + IntonationPunct. EQ stays frozen.
        farfield_gain = True
        farfield_per_turn = True
        num_clusters = int(scene["expected_speakers"])
        cluster_threshold = float(scene["diar_cluster_threshold"])
        min_duration_on = float(scene["diar_min_duration_on_sec"])
        min_duration_off = float(scene["diar_min_duration_off_sec"])
    use_prep = bool(farfield_gain or phone_distance_eq or impl_second_pass)
    if use_prep:
        # phone_eq forces frozen per-turn gain (never whole-file crush).
        prep = DEMO_PREP.prepare(
            samples,
            sr,
            bound_main=True,
            per_turn=bool(farfield_per_turn or phone_distance_eq or impl_second_pass),
            phone_eq=bool(phone_distance_eq) and not impl_second_pass,
        )
        ff_meta = DEMO_PREP.gain_meta(prep)
        if phone_distance_eq and not impl_second_pass:
            src = "af71d91 PhoneDistanceEq + frozen per-turn FarFieldGain"
        elif farfield_per_turn or impl_second_pass:
            src = "db3afe4 per-turn"
        else:
            src = "b66581c whole-file"
        if prep["skip_asr"]:
            raise HostBlocked(
                f"DemoPcmPrep hard-skip {prep['skip_code']} "
                f"(Platform {src}; CLIPPED/BAD_MIC/empty only)"
            )
        samples = prep["samples"]

    t0 = time.perf_counter()
    diar_cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                model=str(d["segmentation"]),
            ),
        ),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(
            model=str(d["embedding"]),
            num_threads=num_threads,
            provider="cpu",
        ),
        clustering=sherpa_onnx.FastClusteringConfig(
            num_clusters=num_clusters,
            threshold=cluster_threshold,
        ),
        min_duration_on=min_duration_on,
        min_duration_off=min_duration_off,
    )
    if hasattr(diar_cfg, "validate") and not diar_cfg.validate():
        raise HostBlocked("OfflineSpeakerDiarizationConfig.validate() failed")

    try:
        sd = sherpa_onnx.OfflineSpeakerDiarization(diar_cfg)
    except Exception as e:
        raise HostBlocked(f"OfflineSpeakerDiarization init failed: {type(e).__name__}: {e}") from e

    try:
        raw = sd.process(samples)
        if hasattr(raw, "sort_by_start_time"):
            segs = list(raw.sort_by_start_time())
        else:
            segs = sorted(list(raw), key=lambda r: float(r.start))
    except MemoryError as e:
        raise HostBlocked(f"OOM during diarization: {e}") from e
    except Exception as e:
        raise HostBlocked(f"diarization process() failed: {type(e).__name__}: {e}") from e

    recognizer = None
    if not skip_asr:
        try:
            recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
                encoder=str(z["encoder"]),
                decoder=str(z["decoder"]),
                joiner=str(z["joiner"]),
                tokens=str(z["tokens"]),

#### Thinking

I have the exact config. Now let me write a focused reproduction script in a scratch location (not polluting the repo — per memory "experimental work stays isolated"; I'll put it in `artifacts/` — no wait, artifacts is committed. Let me use a temp dir outside the repo or the system temp. Actually the repo has `artifacts/compact/build/` used for experiments. To be safe and respect "never write another session's active workspace" — this IS the workspace the user pointed me at (current dir), and the user asked me to fix bugs here. The in-flight files are the other session's; I won't touch them. My repro scripts: put in `/tmp` (system temp) to avoid polluting the tree. Output JSON evidence can go to artifacts later if needed.

Let me write the repro script:

```python
# repro_diar.py
import sys, wave, json
import numpy as np
import sherpa_onnx
from pathlib import Path

ROOT = Path("E:/Projects/neuro-stenographer/neuro-stenographer")
sys.path.insert(0, str(ROOT/"scripts"))
import vad_diar_reconcile as RECON

def load(p):
    w = wave.open(str(p)); raw=w.readframes(w.getnframes())
    return np.frombuffer(raw, dtype="<i2").astype("float32")/32768.0

s1 = load(ROOT/"artifacts/demo-stage6-handoff/stem_or_slice_speaker_1_3m.wav")
s2 = load(ROOT/"artifacts/demo-stage6-handoff/stem_or_slice_speaker_2_3p6m.wav")
gap = np.zeros(int(0.6*16000), dtype="float32")

# one speaker, 4 sequential replicas
one = np.concatenate([s1, gap, s1, gap, s1, gap, s1])
# two speakers alternating: A B A B A B
two = np.concatenate([s1, gap, s2, gap, s1, gap, s2, gap, s1, gap, s2])

def diarize(x, num_clusters, threshold=0.5):
    cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                model=str(ROOT/"models/diar/segmentation.onnx")),
            num_threads=2),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(
            model=str(ROOT/"models/diar/embedding.onnx"), num_threads=2),
        clustering=sherpa_onnx.FastClusteringConfig(num_clusters=num_clusters, threshold=threshold),
        min_duration_on=0.08, min_duration_off=0.18)
    sd = sherpa_onnx.OfflineSpeakerDiarization(cfg)
    segs = sorted(list(sd.process(x)), key=lambda r: float(r.start))
    return [(int(r.speaker), round(float(r.start),2), round(float(r.end),2)) for r in segs]
```

Wait — does the app apply FarFieldGain.stage before diar? Yes: `work = staged` when expectedSpeakers>0, and diarize(work,... For the repro of raw clustering, gain staging matters less for speaker count. But to be faithful, apply FF.stage per-turn... Actually the app applies `FarFieldGain.stage(audio.samples)` (whole-file staging? let me check — `FarFieldGain.stage` — the Kotlin). Hmm, in the app transcribeAsr: `staged = FarFieldGain.stage(audio.samples)` then diarize(work=staged). And DemoPcmPrep.prepare ALSO applies gain+EQ before. Double staging? The ViewModel calls DemoPcmPrep.prepare(audio) → prep.audio (gained+EQ'd), then pipeline.transcribe(prep.audio) → transcribeAsr stages AGAIN. Whatever — for the clustering repro, the key variable is num_clusters=6 vs -1. Let me keep it simple and run on raw stems (clean speech) — forced K=6 will split even clean single-speaker audio.

Also run with num_clusters=-1, threshold=0.5 to show auto clustering keeps 1 speaker. And measure embedding similarity to prove same voice.

For the crash repro: feed a 3-min looped s1 as one crop to the recognizer, measure RSS. Let me also time it. On host it may take a while (Zipformer on 3 min CPU... RTF maybe 0.1-0.3 → 20-60s). RSS measurement via resource.getrusage or psutil. .venv has psutil? Let me check. Actually simpler: use `resource.getrusage(resource.RUSAGE_SELF).ru_maxrss` — on Windows that's not available... This is Windows (Git Bash, .venv/Scripts). ru_maxrss doesn't exist on Windows. Use psutil if available, else ctypes GetProcessMemoryInfo. Let me check psutil.

Actually — for the crash, the cleanest true reproduction is on the emulator. Let me test installing the arm64 APK on the emulator FIRST (it has ro.dalvik.vm.isa.arm64=x86_64 which suggests native bridge). If that works, I can run EmulatorBench with a long WAV and watch it crash. That's the gold-standard repro.

Let me parallelize: 
1. Write+run host diar repro. Check emulator install of existing APK.

Let me write the repro script now.

#### Tool: Write

**Input:**

```json
{
  "content": "\"\"\"Reproduce: sequential replicas of ONE speaker get split into many speakers.\n\nUses the app's exact stage6 diar config (sherpa-onnx 2.0.1 == Android AAR):\nFastClustering(num_clusters=6, threshold=0.5), min_on=0.08, min_off=0.18.\nGround truth: speaker_1 stem repeated 4x (one voice), and A/B alternation (two voices).\n\"\"\"\nimport json\nimport sys\nimport wave\nfrom pathlib import Path\n\nimport numpy as np\nimport sherpa_onnx\n\nROOT = Path(\"E:/Projects/neuro-stenographer/neuro-stenographer\")\n\n\ndef load(p):\n    w = wave.open(str(p))\n    assert w.getframerate() == 16000 and w.getnchannels() == 1\n    raw = w.readframes(w.getnframes())\n    return np.frombuffer(raw, dtype=\"<i2\").astype(\"float32\") / 32768.0\n\n\ns1 = load(ROOT / \"artifacts/demo-stage6-handoff/stem_or_slice_speaker_1_3m.wav\")\ns2 = load(ROOT / \"artifacts/demo-stage6-handoff/stem_or_slice_speaker_2_3p6m.wav\")\ngap = np.zeros(int(0.6 * 16000), dtype=\"float32\")\n\none_voice = np.concatenate([s1, gap, s1, gap, s1, gap, s1])          # ~29.5 s, 1 person, 4 replicas\ntwo_voice = np.concatenate([s1, gap, s2, gap, s1, gap, s2, gap, s1, gap, s2])  # A B A B A B\n\n\ndef diarize(x, num_clusters, threshold=0.5):\n    cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(\n        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(\n            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(\n                model=str(ROOT / \"models/diar/segmentation.onnx\"),\n            ),\n            num_threads=2,\n        ),\n        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(\n            model=str(ROOT / \"models/diar/embedding.onnx\"),\n            num_threads=2,\n        ),\n        clustering=sherpa_onnx.FastClusteringConfig(num_clusters=num_clusters, threshold=threshold),\n        min_duration_on=0.08,\n        min_duration_off=0.18,\n    )\n    sd = sherpa_onnx.OfflineSpeakerDiarization(cfg)\n    segs = sorted(list(sd.process(x)), key=lambda r: float(r.start))\n    return [{\"speaker\": int(r.speaker), \"start\": round(float(r.start), 2), \"end\": round(float(r.end), 2)} for r in segs]\n\n\nreport = {}\nfor name, x, truth in [(\"one_voice_4_replicas\", one_voice, 1), (\"two_voices_alternating_ABABAB\", two_voice, 2)]:\n    for k in (6, -1):\n        segs = diarize(x, k)\n        labels = sorted({s[\"speaker\"] for s in segs})\n        key = f\"{name}__K={k}\"\n        report[key] = {\"ground_truth_speakers\": truth, \"distinct_labels\": len(labels), \"labels\": labels, \"segments\": segs}\n        print(f\"{key}: truth={truth} -> {len(labels)} labels {labels}\")\n        for s in segs:\n            print(f\"   spk{s['speaker']}  {s['start']:7.2f} - {s['end']:7.2f}\")\n\nout = Path(__file__).parent / \"repro_diar_result.json\"\nout.write_text(json.dumps(report, indent=2))\nprint(\"written\", out)",
  "file_path": "C:\\Users\\KMService\\AppData\\Local\\Temp\\steno-repro\\repro_diar.py"
}
```

**Response:**

File created successfully at: C:\Users\KMService\AppData\Local\Temp\steno-repro\repro_diar.py The file state is current in your context; no need to read it back.

#### Tool: Bash

**Input:**

```json
{
  "command": "export MSYS_NO_PATHCONV=1; adb -s emulator-5554 install -r apk/app-stenographer-compact-excl-phases.apk 2>&1 | tail -3",
  "description": "Install existing compact APK on emulator to test ARM bridge",
  "timeout": 180000
}
```

**Response:**

Performing Streamed Install
Success

[hook: PostToolUse] succeeded (exit 0)

#### Thinking

The arm64 APK installed successfully on the x86_64 emulator — native bridge works. Now:
1. Run the host diar repro. Check if EmulatorBench is in the installed APK's dex, and run it with a long WAV to reproduce the crash.

Let me run the host repro first (background it if slow), and check the APK for EmulatorBench.

#### Tool: Bash

**Input:**

```json
{
  "command": ".venv/Scripts/python.exe /c/Users/KMService/AppData/Local/Temp/steno-repro/repro_diar.py 2>&1 | grep -vE \"^\\s*$\" | tail -50",
  "description": "Run host diarization reproduction",
  "run_in_background": true
}
```

**Response:**

Command running in background with ID: b1ixg2d80. Output is being written to: C:\Users\KMSERV~1\AppData\Local\Temp\qoder-cli\E--Projects-neuro-stenographer-neuro-stenographer\e7c0c28e-8ee5-4064-b88d-e9cebdf9407c\tasks\b1ixg2d80.output You will be notified when it completes. To check interim output, use Read on that file path.

> Run host diarization reproduction

#### Tool: Bash

**Input:**

```json
{
  "command": "export MSYS_NO_PATHCONV=1; adb -s emulator-5554 shell pm path team.mikcoders.neurosteno; unzip -l apk/app-stenographer-compact-excl-phases.apk | grep -E \"classes|\\.so\" | head -15",
  "description": "Get installed APK path and check dex contents"
}
```

**Response:**

package:/data/app/~~mjBYfOjf7mEOziO8tweUZw==/team.mikcoders.neurosteno-_qxKkHVv5Fazc7rQn-hJQw==/base.apk
  2599680  1981-01-01 01:01   classes.dex
    10096  1981-01-01 01:01   lib/arm64-v8a/libandroidx.graphics.path.so
  1292904  1981-01-01 01:01   lib/arm64-v8a/libc++_shared.so
   672816  1981-01-01 01:01   lib/arm64-v8a/libggml-base.so
   718384  1981-01-01 01:01   lib/arm64-v8a/libggml-cpu.so
    58576  1981-01-01 01:01   lib/arm64-v8a/libggml.so
  2756984  1981-01-01 01:01   lib/arm64-v8a/libllama.so
    43840  1981-01-01 01:01   lib/arm64-v8a/libllama_jni.so
 22249560  1981-01-01 01:01   lib/arm64-v8a/libonnxruntime.so
  4303136  1981-01-01 01:01   lib/arm64-v8a/libsherpa-onnx-c-api.so
   272848  1981-01-01 01:01   lib/arm64-v8a/libsherpa-onnx-cxx-api.so
  4609320  1981-01-01 01:01   lib/arm64-v8a/libsherpa-onnx-jni.so

#### Thinking

The host diar repro completed. Let me read the output.

#### Tool: Read

**Input:**

```json
{
  "file_path": "C:\\Users\\KMSERV~1\\AppData\\Local\\Temp\\qoder-cli\\E--Projects-neuro-stenographer-neuro-stenographer\\e7c0c28e-8ee5-4064-b88d-e9cebdf9407c\\tasks\\b1ixg2d80.output"
}
```

**Response:**

1	D:\a\sherpa-onnx\sherpa-onnx\sherpa-onnx/csrc/offline-speaker-diarization-pyannote-impl.h:Process:151 No speakers found in the audio samples
2	Traceback (most recent call last):
3	  File "C:\Users\KMService\AppData\Local\Temp\steno-repro\repro_diar.py", line 57, in <module>
4	    segs = diarize(x, k)
5	  File "C:\Users\KMService\AppData\Local\Temp\steno-repro\repro_diar.py", line 50, in diarize
6	    segs = sorted(list(sd.process(x)), key=lambda r: float(r.start))
7	                  ~~~~^^^^^^^^^^^^^^^
8	TypeError: 'sherpa_onnx.lib._sherpa_onnx.OfflineSpeakerDiarizationResult' object is not iterable
9

#### Thinking

Two issues:
1. "No speakers found in the audio samples" — hmm, that appeared for the first config? That's odd for clean speech. Wait — maybe the segmentation model didn't detect speech because... the stem is clean Common Voice speech, should be detected. Or maybe the message is from a different run. Actually the message order: it printed before the traceback. The traceback is `OfflineSpeakerDiarizationResult` not iterable in sherpa-onnx 2.1 Python — need `.sort_by_start_time()` (as host_asr_diar.py does: `if hasattr(raw, "sort_by_start_time"): segs = list(raw.sort_by_start_time())`).

Wait, but "No speakers found" — that may be the result of the FIRST diarize call (one_voice, K=6)?? If segmentation found no speakers... Hmm, that could happen if the audio is too quiet? The stem is "speaker_1_3m" — a worked slice, should be fine. Or maybe the pyannote model needs louder audio. Let me fix the iteration and add RMS logging. Also the result object: use sort_by_start_time().

Let me fix the script.

#### Tool: Edit

**Input:**

```json
{
  "file_path": "C:\\Users\\KMService\\AppData\\Local\\Temp\\steno-repro\\repro_diar.py",
  "new_string": "    sd = sherpa_onnx.OfflineSpeakerDiarization(cfg)\n    raw = sd.process(x)\n    segs = list(raw.sort_by_start_time()) if hasattr(raw, \"sort_by_start_time\") else sorted(list(raw), key=lambda r: float(r.start))\n    return [{\"speaker\": int(r.speaker), \"start\": round(float(r.start), 2), \"end\": round(float(r.end), 2)} for r in segs]",
  "old_string": "    sd = sherpa_onnx.OfflineSpeakerDiarization(cfg)\n    segs = sorted(list(sd.process(x)), key=lambda r: float(r.start))\n    return [{\"speaker\": int(r.speaker), \"start\": round(float(r.start), 2), \"end\": round(float(r.end), 2)} for r in segs]"
}
```

**Response:**

Successfully modified file: C:\Users\KMService\AppData\Local\Temp\steno-repro\repro_diar.py (1 replacements). The file state is current in your context; no need to read it back.

[hook: PostToolUse] succeeded (exit 0)

#### Thinking

Also add RMS debug and handle the "no speakers" case. Let me also print the RMS of the input. Let me add that quickly and rerun.

#### Tool: Edit

**Input:**

```json
{
  "file_path": "C:\\Users\\KMService\\AppData\\Local\\Temp\\steno-repro\\repro_diar.py",
  "new_string": "for name, x, truth in [(\"one_voice_4_replicas\", one_voice, 1), (\"two_voices_alternating_ABABAB\", two_voice, 2)]:\n    print(f\"{name}: dur={len(x)/16000:.1f}s rms={float(np.sqrt(np.mean(x**2))):.4f}\")\n    for k in (6, -1):",
  "old_string": "for name, x, truth in [(\"one_voice_4_replicas\", one_voice, 1), (\"two_voices_alternating_ABABAB\", two_voice, 2)]:\n    for k in (6, -1):"
}
```

**Response:**

Successfully modified file: C:\Users\KMService\AppData\Local\Temp\steno-repro\repro_diar.py (1 replacements). The file state is current in your context; no need to read it back.

[hook: PostToolUse] succeeded (exit 0)

#### Tool: Bash

**Input:**

```json
{
  "command": ".venv/Scripts/python.exe /c/Users/KMService/AppData/Local/Temp/steno-repro/repro_diar.py 2>&1 | tail -60",
  "description": "Rerun host diarization reproduction",
  "run_in_background": true
}
```

**Response:**

Command running in background with ID: b924i14cf. Output is being written to: C:\Users\KMSERV~1\AppData\Local\Temp\qoder-cli\E--Projects-neuro-stenographer-neuro-stenographer\e7c0c28e-8ee5-4064-b88d-e9cebdf9407c\tasks\b924i14cf.output You will be notified when it completes. To check interim output, use Read on that file path.

> Rerun host diarization reproduction

#### Thinking

While that runs, let me set up the emulator crash reproduction. First check if EmulatorBench is in the APK dex. Let me pull the dex and check for the class, or use `adb shell` with app_process. Actually simpler: check if the class exists via dexdump or just strings on classes.dex. Let me extract classes.dex from the APK and grep for EmulatorBench.

#### Tool: Bash

**Input:**

```json
{
  "command": "cd /tmp/llama-aar && unzip -o -q \"E:/Projects/neuro-stenographer/neuro-stenographer/apk/app-stenographer-compact-excl-phases.apk\" classes.dex -d apkcheck && strings apkcheck/classes.dex | grep -cE \"EmulatorBench\" ; strings apkcheck/classes.dex | grep -E \"EmulatorBench|ExclusivePhases\" | head -5",
  "description": "Check if EmulatorBench is compiled into the APK"
}
```

**Response:**

0
ExclusivePhases.kt
KLteam/mikcoders/neurosteno/pipeline/ExclusivePhasesKt$runExclusivePhases$1;
6Lteam/mikcoders/neurosteno/pipeline/ExclusivePhasesKt;
runExclusivePhases
4team.mikcoders.neurosteno.pipeline.ExclusivePhasesKt

Shell cwd was reset to E:\Projects\neuro-stenographer\neuro-stenographer

#### Thinking

EmulatorBench is NOT in the APK (0 matches) — the other session built the APK without it (or it was added after). ExclusivePhases IS in the dex. So to run the bench on the emulator I'd need to rebuild with EmulatorBench. That's fine — I'll need to build anyway for the fix. But first, let me read the diar repro result.

#### Tool: Read

**Input:**

```json
{
  "file_path": "C:\\Users\\KMSERV~1\\AppData\\Local\\Temp\\qoder-cli\\E--Projects-neuro-stenographer-neuro-stenographer\\e7c0c28e-8ee5-4064-b88d-e9cebdf9407c\\tasks\\b924i14cf.output"
}
```

**Response:**

1	D:\a\sherpa-onnx\sherpa-onnx\sherpa-onnx/csrc/offline-speaker-diarization-pyannote-impl.h:Process:151 No speakers found in the audio samples
2	D:\a\sherpa-onnx\sherpa-onnx\sherpa-onnx/csrc/offline-speaker-diarization-pyannote-impl.h:Process:151 No speakers found in the audio samples
3	one_voice_4_replicas: dur=28.9s rms=0.1428
4	one_voice_4_replicas__K=6: truth=1 -> 0 labels []
5	one_voice_4_replicas__K=-1: truth=1 -> 0 labels []
6	two_voices_alternating_ABABAB: dur=49.8s rms=0.1304
7	two_voices_alternating_ABABAB__K=6: truth=2 -> 3 labels [0, 1, 2]
8	   spk1     1.03 -    6.12
9	   spk2     8.10 -   15.51
10	   spk1    22.04 -   22.76
11	   spk2    24.90 -   25.50
12	   spk0    25.50 -   25.97
13	   spk2    25.97 -   32.19
14	   spk0    41.88 -   45.96
15	   spk2    45.96 -   49.83
16	two_voices_alternating_ABABAB__K=-1: truth=2 -> 3 labels [1, 2, 3]
17	   spk1     1.03 -    6.12
18	   spk3     8.10 -   15.51
19	   spk1    22.04 -   22.76
20	   spk3    24.90 -   32.19
21	   spk2    41.88 -   45.96
22	   spk3    45.96 -   49.83
23	written C:\Users\KMService\AppData\Local\Temp\steno-repro\repro_diar_result.json
24

#### Thinking

Interesting results:

1. **one_voice_4_replicas**: "No speakers found" — 0 segments! The repeated identical stem with 0.6s gaps... pyannote found NO speakers?? That's suspicious. RMS 0.14 is healthy. Why would segmentation find nothing? 

Hmm wait — maybe the issue is the repeated EXACT same audio creates a periodic signal that pyannote's segmentation model treats oddly? Or... Actually, "No speakers found" from `offline-speaker-diarization-pyannote-impl.h:Process:151` — this happens when the segmentation produces no speaker activity above threshold. Odd for clean speech.

Wait, maybe the stems are very quiet in parts or the pyannote model is the int8 one? No — models/diar/segmentation.onnx. Let me check its sha against the memory: float model sha 220ad67c…, int8 d582f4b4…. The download script now picks float. Let me verify which one is at models/diar/segmentation.onnx.

Actually hold on — maybe the problem is my concatenation: `np.concatenate([s1, gap, s1, ...])` — the stems might have leading/trailing silence and the repeat creates abrupt jumps, but that shouldn't kill detection entirely.

Hmm, "No speakers found" for BOTH K=6 and K=-1 on one_voice, but two_voice worked (3 labels). The difference: one_voice is the SAME stem repeated. Weird. Unless... the segmentation model has an issue with highly repetitive audio? Or maybe there's something about the exact periodicity.

Actually wait — let me reconsider. Maybe the first run (before my edit) ALSO printed "No speakers found" once and then crashed on iteration. In this run, two "No speakers found" messages = the two one_voice runs. So one_voice genuinely produces no diarization output. That's a real curiosity but maybe not the user's scenario.

The two_voice result is ALREADY the bug: truth=2, but K=6 gives 3 labels with turns flipping: spk1 (A), spk2 (B), spk1 (A fragment), spk2 (B), spk0 (?), spk2 (B), spk0 (A??), spk2 (B). Speaker A's replicas at 1.03-6.12 (spk1), 22.04-22.76 (spk1), 41.88-45.96 (spk0) — the SAME person A gets spk1 then spk0. And B gets spk2 mostly but the segment 24.90-32.19 contains both B's turn AND A's turn (25.5-32.19?) glued. Messy.

But wait — with K=-1 (auto threshold 0.5) it ALSO gives 3 labels [1,2,3]: A=spk1 at 1-6s, A=spk2 at 41.88-45.96 — same person split! So even auto clustering splits the same speaker here. Hmm. That suggests the embedding clustering threshold 0.5 is too tight for this embedding model (3D-Speaker eres2net), OR the repeated-stem concatenation creates artifacts.

Hold on. Something's off with using the EXACT same stem repeated — the embeddings should be nearly identical for identical audio, so clustering should group them. Unless the segmentation model's embeddings are computed over different context windows... The 3D-Speaker embedding extractor computes one embedding per segment; identical audio → identical embedding → distance 0 → same cluster at any threshold. But A's replicas got DIFFERENT labels even at threshold 0. That means the segments fed to the embedder differ (boundaries differ, e. 1.03-6.12 vs 41.88-45.96 — different crops of the same stem, so embeddings differ somewhat).

Wait, actually — the segmentation boundaries: A's first turn 1.03-6.12 (5.09s), A's third turn 41.88-45.96 (4.08s). The stem is 6.768s. So segmentation trimmed differently. Different crops → different embeddings → distance > 0.5 → split. Hmm, threshold 0.5 for eres2net cosine distance... The default sherpa threshold is 0.5? For 3D-Speaker models the recommended threshold is ~0.5-0.6 (cosine distance). Same-speaker segments typically have cosine distance 0.2-0.4; different speakers 0.6+. A threshold of 0.5 should keep same-speaker together... unless the crops are short/noisy.

But actually — the "No speakers found" on one_voice is the bigger red flag. Let me investigate: maybe the segmentation model file is the INT8 one (trap 2 from memory!). If models/diar/segmentation.onnx is actually the int8 export (1.5MB) instead of float (6MB), detection could be broken. Let me check the file size/hash. Memory says: int8 = 1,540,506 B sha d582f4b4…, float = 5,992,913 B sha 220ad67c….

Let me check. Also let me look at how the demo mix (DEMO_STAGE6.wav, 39.7s, 6 speakers) diarizes with K=6 — that's the frozen working case. If that works but my synthetic one_voice doesn't, the difference is the audio content.

Actually — wait. I bet the issue is the gaps.6s of DIGITAL ZERO silence. Pyannote segmentation might... no, that's normal.

Hmm, let me think about "No speakers found" differently: maybe the repeated identical waveform causes the pyannote model's powerset decoding to collapse? Unlikely.

Let me just check the model file identity first, then test one_voice with the DEMO mix as sanity. Also test a single stem alone (6.768s) — does diar find 1 speaker?

#### Tool: Bash

**Input:**

```json
{
  "command": "ls -la models/diar/segmentation.onnx models/diar/embedding.onnx; sha256sum models/diar/segmentation.onnx models/diar/embedding.onnx; echo \"---expected: float seg 220ad67c... / int8 d582f4b4...---\"",
  "description": "Verify segmentation model identity against known hashes"
}
```

**Response:**

-rw-r--r-- 1 l1ff0 197121 39593761 Nov  7 09:11 models/diar/embedding.onnx
-rw-r--r-- 1 l1ff0 197121  5992913 Nov  7 09:21 models/diar/segmentation.onnx
220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079 *models/diar/segmentation.onnx
1a331345f04805badbb495c775a6ddffcdd1a732567d5ec8b3d5749e3c7a5e4b *models/diar/embedding.onnx
---expected: float seg 220ad67c... / int8 d582f4b4...---

#### Thinking

The segmentation model is the correct float one (220ad67c, 5,992,913 B). So the model is right. The "No speakers found" on the repeated stem is something else.

Let me test:
1. Single stem alone (6.768s) — does diar find speech?
2. The DEMO_STAGE6.wav mix with K=6 — sanity (should find ~6). The repeated stem with different gap (e., 1.0s) or with slight noise in gaps.

Actually — hmm, one hypothesis: pyannote segmentation-3.0 powerset model has a max speaker count per frame and works on 5s windows. Repeating the EXACT same 6.768s clip 4 times... shouldn't matter.

Wait, actually — maybe the issue is that `sd.process()` on 28.9s returns "No speakers found" because of a SHERPA bug with num_clusters=6 when there are fewer embeddings than clusters? No — the message comes from segmentation finding no speech, before clustering.

Let me look at the sherpa-onnx source for that message. `offline-speaker-diarization-pyannote-impl.h:Process:151` — "No speakers found in the audio samples" is printed when `speaker_segments` is empty after segmentation+embedding. Let me test the single stem and the demo mix to isolate. Let me write a quick follow-up probe.

#### Tool: Write

**Input:**

```json
{
  "content": "import sys, wave\nfrom pathlib import Path\nimport numpy as np\nimport sherpa_onnx\n\nROOT = Path(\"E:/Projects/neuro-stenographer/neuro-stenographer\")\n\ndef load(p):\n    w = wave.open(str(p))\n    raw = w.readframes(w.getnframes())\n    return np.frombuffer(raw, dtype=\"<i2\").astype(\"float32\") / 32768.0\n\ns1 = load(ROOT / \"artifacts/demo-stage6-handoff/stem_or_slice_speaker_1_3m.wav\")\ngap = np.zeros(int(0.6 * 16000), dtype=\"float32\")\n\ndef diarize(x, num_clusters=6, threshold=0.5, tag=\"\"):\n    cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(\n        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(\n            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(\n                model=str(ROOT / \"models/diar/segmentation.onnx\")),\n            num_threads=2),\n        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(\n            model=str(ROOT / \"models/diar/embedding.onnx\"), num_threads=2),\n        clustering=sherpa_onnx.FastClusteringConfig(num_clusters=num_clusters, threshold=threshold),\n        min_duration_on=0.08, min_duration_off=0.18)\n    sd = sherpa_onnx.OfflineSpeakerDiarization(cfg)\n    raw = sd.process(x)\n    segs = list(raw.sort_by_start_time()) if hasattr(raw, \"sort_by_start_time\") else []\n    print(f\"{tag}: dur={len(x)/16000:.1f}s -> {len(segs)} segs, labels={sorted({int(r.speaker) for r in segs})}\")\n    for r in segs:\n        print(f\"   spk{int(r.speaker)} {float(r.start):7.2f}-{float(r.end):7.2f}\")\n    return segs\n\ndiarize(s1, tag=\"single stem 6.8s K=6\")\ndiarize(s1, num_clusters=-1, tag=\"single stem 6.8s K=-1\")\ndiarize(np.concatenate([s1, gap, s1]), tag=\"stem x2 K=6\")\nmix = load(ROOT / \"artifacts/eval/demo-stress/stage6/listen/DEMO_STAGE6.wav\")\ndiarize(mix, tag=\"DEMO_STAGE6 mix K=6\")\n# one voice, 4 replicas, gap with tiny noise instead of digital zero\nrng = np.random.default_rng(7)\nnoisy_gap = (rng.standard_normal(int(0.6*16000)) * 1e-4).astype(\"float32\")\ndiarize(np.concatenate([s1, noisy_gap, s1, noisy_gap, s1, noisy_gap, s1]), tag=\"stem x4 noisy-gap K=6\")",
  "file_path": "C:\\Users\\KMService\\AppData\\Local\\Temp\\steno-repro\\probe_diar.py"
}
```

**Response:**

File created successfully at: C:\Users\KMService\AppData\Local\Temp\steno-repro\probe_diar.py The file state is current in your context; no need to read it back.

[hook: PostToolUse] succeeded (exit 0)

#### Tool: Bash

**Input:**

```json
{
  "command": ".venv/Scripts/python.exe /c/Users/KMService/AppData/Local/Temp/steno-repro/probe_diar.py 2>&1 | tail -50",
  "description": "Run diarization isolation probe",
  "run_in_background": true
}
```

**Response:**

Command running in background with ID: b6pqbyirb. Output is being written to: C:\Users\KMSERV~1\AppData\Local\Temp\qoder-cli\E--Projects-neuro-stenographer-neuro-stenographer\e7c0c28e-8ee5-4064-b88d-e9cebdf9407c\tasks\b6pqbyirb.output You will be notified when it completes. To check interim output, use Read on that file path.

> Run diarization isolation probe

#### Thinking

While that runs, let me prepare the crash reproduction. I'll build a long single-speaker WAV (loop s1 to ~3 minutes with tiny gaps to keep it one "voiceline"), and set up the emulator run. Since EmulatorBench isn't in the APK, I need to rebuild. But building takes a while. Alternatively, I can reproduce the crash mechanism on host first (feed a 3-min crop to Zipformer, watch RSS), which is fast and doesn't need a build.

Actually, let me think about what the crash really is. Let me reconsider the ViewModel: it catches Throwable around the whole transcribe. A JVM OutOfMemoryError would be caught → error shown, not a crash. A NATIVE crash (SIGSEGV, SIGABRT, or LMK SIGKILL) kills the process → "app crashes". 

Native crash candidates on long audio:
1. ASR on a huge crop → ORT allocator throws std::bad_alloc → sherpa catches? ORT throws Ort::Exception (C++) → JNI translates to Java exception? sherpa-onnx JNI does catch some exceptions... Actually sherpa-onnx JNI often does NOT catch C++ exceptions → std::terminate → SIGABRT → crash. OR running out of memory → Android LMK kills → "crash". Diarization on the whole long recording → pyannote segmentation stores all windows' outputs; embedding extraction per segment; memory grows. For 30-60 min this could be large but the segmentation is processed in one go? Let me check sherpa's implementation — `OfflineSpeakerDiarizationPyannoteImpl::Process` — it runs the segmentation model over the whole audio in 5s chunks (I think it processes the entire waveform through the model at once? No — pyannote segmentation model has fixed input size, sherpa slides it). Memory is bounded by storing embeddings (small). Probably not the crash for moderate lengths. llama.cpp: graceful error on overflow (verified). Not crash.

So the ASR long-crop is the prime suspect. Let me verify on host: feed 3-min crop, measure RSS. If RSS balloons to multi-GB, that's the mechanism (Android has less headroom + LMK).

But actually — wait. Let me reconsider the segment lengths in the diar path. Does the diar path actually produce multi-minute segments? pyannote segmentation emits segments per speaker turn; a continuous 3-min monologue → pyannote emits... its segmentation is frame-level (powerset), then sherpa groups into segments. A single speaker talking continuously → ONE segment spanning the whole 3 min (min_duration_off=0.18 only splits on ≥180ms silence). Then VadDiarReconcile.mergeSameSpeaker(gap=280ms) keeps it as one. Then transcribeSegments crops 3 min + 400ms pad → ASR on 3 min → boom.

Even the VAD path (no diar): Silero maxSpeechDuration=30s bounds it. So the crash is specific to the diar path (default app). Consistent with "voiceline too long" (one person talking long = one long segment).

Let me write the host crash-mechanism probe: loop s1 to 180s (with 0.1s micro-gaps so it stays speech), run ASR on the whole thing, measure peak RSS via psutil (check availability) or ctypes. Also time it. Let me check psutil in venv.

#### Tool: Bash

**Input:**

```json
{
  "command": ".venv/Scripts/python.exe -c \"import psutil; print(psutil.__version__)\" 2>&1; grep -n \"def stage\\|def findSegments\" src/android/app/src/main/java/team/mikcoders/neurosteno/audio/FarFieldGain.kt | head",
  "description": "Check psutil availability and FarFieldGain API"
}
```

**Response:**

7.2.2

#### Thinking

psutil available. The FarFieldGain grep returned nothing — maybe the functions have different names. Let me read the probe results first.

#### Tool: Read

**Input:**

```json
{
  "file_path": "C:\\Users\\KMSERV~1\\AppData\\Local\\Temp\\qoder-cli\\E--Projects-neuro-stenographer-neuro-stenographer\\e7c0c28e-8ee5-4064-b88d-e9cebdf9407c\\tasks\\b6pqbyirb.output"
}
```

**Response:**

1	D:\a\sherpa-onnx\sherpa-onnx\sherpa-onnx/csrc/offline-speaker-diarization-pyannote-impl.h:Process:151 No speakers found in the audio samples
2	D:\a\sherpa-onnx\sherpa-onnx\sherpa-onnx/csrc/offline-speaker-diarization-pyannote-impl.h:Process:151 No speakers found in the audio samples
3	single stem 6.8s K=6: dur=6.8s -> 0 segs, labels=[]
4	single stem 6.8s K=-1: dur=6.8s -> 0 segs, labels=[]
5	stem x2 K=6: dur=14.1s -> 0 segs, labels=[]
6	DEMO_STAGE6 mix K=6: dur=39.7s -> 5 segs, labels=[0, 1, 2, 3, 5]
7	   spk0    0.67-   6.51
8	   spk1    7.46-  14.97
9	   spk2   19.07-  21.78
10	   spk3   24.77-  24.97
11	   spk5   38.22-  38.81
12	stem x4 noisy-gap K=6: dur=28.9s -> 0 segs, labels=[]
13

#### Thinking

Very interesting! The SINGLE stem (6.8s of clean speech) produces ZERO diarization segments, but the DEMO mix produces 5. So the stem itself is problematic for pyannote. Why?

The stems are "stem_or_slice_speaker_1_3m.wav" — let me check what they actually contain. Maybe they're already processed (gained/EQ'd) or very short fragments with issues. RMS was 0.14 (healthy). Hmm.

Wait — maybe the stem is mostly silence with a short speech burst? 6.768s with RMS 0.14 overall — that's substantial. Let me look at the stem's energy profile. Or maybe the pyannote model needs more context? The DEMO mix works (5 segments). 

Actually — hold on. Let me reconsider. The DEMO mix found only 5 segments covering 0.67-6.51, 7.46-14.97, 19.07-21.78, 24.77-24.97, 38.22-38.81. The recipe says 6 speakers over 39.7s. The diar found 5 segments with labels [0,1,2,3,5] — missing speaker_4's region and s5 (the quiet far one). That's the known "s5 missing" issue the reconcile fixes. OK so the mix works "as documented".

But the single stem → 0 segments. That's bizarre. Unless the stem is NOT clean speech — maybe it's a far-field processed slice that's mostly noise? Let me check the stem content: listen... can't listen, but let me check the energy envelope and maybe run ASR on it to see if it produces text.

Actually — the "listen" folder has "speaker_1_3.0m_worked.wav" — those are the WORKED slices. The handoff stems might be different (raw slices?). Let me check both. Let me run ASR on the stem to verify it contains intelligible speech, and check its envelope.

Actually, let me step back. The stems might be the ISOLATED per-speaker slices from the mix (i., already mixed with room noise at their distance gain). speaker_1 at 3m should be loud and clean. Let me just ASR it.

Let me write a combined probe: ASR the single stem (does it produce text?), and check the energy envelope. And use the "worked" listen slices instead.

#### Tool: Write

**Input:**

```json
{
  "content": "import sys, wave\nfrom pathlib import Path\nimport numpy as np\nimport sherpa_onnx\n\nROOT = Path(\"E:/Projects/neuro-stenographer/neuro-stenographer\")\n\ndef load(p):\n    w = wave.open(str(p))\n    raw = w.readframes(w.getnframes())\n    return np.frombuffer(raw, dtype=\"<i2\").astype(\"float32\") / 32768.0\n\nrec = sherpa_onnx.OfflineRecognizer.from_transducer(\n    encoder=str(ROOT/\"models/asr/zipformer-ru-int8/encoder.int8.onnx\"),\n    decoder=str(ROOT/\"models/asr/zipformer-ru-int8/decoder.onnx\"),\n    joiner=str(ROOT/\"models/asr/zipformer-ru-int8/joiner.int8.onnx\"),\n    tokens=str(ROOT/\"models/asr/zipformer-ru-int8/tokens.txt\"),\n    num_threads=2, sample_rate=16000, feature_dim=80, decoding_method=\"greedy_search\")\n\ndef asr(x):\n    s = rec.create_stream(); s.accept_waveform(16000, x); rec.decode_stream(s)\n    return s.result.text\n\nfor name in [\"artifacts/demo-stage6-handoff/stem_or_slice_speaker_1_3m.wav\",\n             \"artifacts/eval/demo-stress/stage6/listen/speaker_1_3.0m_worked.wav\"]:\n    x = load(ROOT/name)\n    env = [float(np.sqrt(np.mean(x[i:i+16000]**2))) for i in range(0, len(x), 16000)]\n    print(name)\n    print(\"  dur\", len(x)/16000, \"rms/s:\", [round(e,3) for e in env])\n    print(\"  ASR:\", asr(x)[:120])",
  "file_path": "C:\\Users\\KMService\\AppData\\Local\\Temp\\steno-repro\\probe_stem.py"
}
```

**Response:**

File created successfully at: C:\Users\KMService\AppData\Local\Temp\steno-repro\probe_stem.py The file state is current in your context; no need to read it back.

[hook: PostToolUse] succeeded (exit 0)

#### Tool: Bash

**Input:**

```json
{
  "command": ".venv/Scripts/python.exe /c/Users/KMService/AppData/Local/Temp/steno-repro/probe_stem.py 2>&1 | tail -20",
  "description": "Probe stem audio content with ASR",
  "run_in_background": true
}
```

**Response:**

Command running in background with ID: babn7tcjs. Output is being written to: C:\Users\KMSERV~1\AppData\Local\Temp\qoder-cli\E--Projects-neuro-stenographer-neuro-stenographer\e7c0c28e-8ee5-4064-b88d-e9cebdf9407c\tasks\babn7tcjs.output You will be notified when it completes. To check interim output, use Read on that file path.

> Probe stem audio content with ASR

#### Thinking

While that runs, let me prepare the crash repro WAV and script. I'll create a long voiceline by looping the stem. But if the stem doesn't diarize, I need different source audio. Let me wait for the stem probe. Meanwhile, let me look at the FarFieldGain.kt to understand staging (the grep found nothing — odd). Let me read it.

#### Tool: Read

**Input:**

```json
{
  "file_path": "E:\\Projects\\neuro-stenographer\\neuro-stenographer\\src\\android\\app\\src\\main\\java\\team\\mikcoders\\neurosteno\\audio\\FarFieldGain.kt",
  "limit": 80
}
```

**Response:**

1	package team.mikcoders.neurosteno.audio
2	
3	import kotlin.math.abs
4	import kotlin.math.exp
5	import kotlin.math.ln
6	import kotlin.math.min
7	import kotlin.math.sin
8	import kotlin.math.sqrt
9	import kotlin.math.tanh
10	
11	/**
12	 * Single-mic DEMO_STAGE6 far-field staging.
13	 *
14	 * **GAIN FROZEN** (Eval per-turn @ `54177a1e`: STAGE6 energy islands already
15	 * ≥0.08 FS → this AGC is a no-op on those wavs; do not retune targets).
16	 * Spectral / phone-distance EQ lives in [PhoneDistanceEq], not here.
17	 *
18	 * Whole-file RMS AGC is wrong: a loud near seat (Eval ffgain mix 0.119 FS)
19	 * pulls gain **down** (−3.46 dB) and 4–6 m turns stay below ASR. This path
20	 * uses **per-turn energy windows**: each quiet segment independently targets
21	 * [TARGET_RMS_FS]; loud turns are never attenuated (gain ≥ 0 dB).
22	 *
23	 * Thresholds: `python3 scripts/measure-farfield-gain.py --record`.
24	 */
25	object FarFieldGain {
26	    const val TARGET_RMS_FS = 0.08
27	    const val MAX_GAIN_DB = 24.0
28	    const val PEAK_LIMIT = 0.95
29	    const val MIN_RMS_FOR_GAIN = 1e-5
30	    const val FRAME_MS = 20
31	    const val VAD_RMS_FS = 0.008
32	    /** Below 6 m sine RMS (~0.0059), above digital silence. */
33	    const val SEGMENT_ONSET_FS = 0.0025
34	    const val HANGOVER_FRAMES = 3
35	    const val MIN_SEGMENT_FRAMES = 4
36	    const val DIP_RATIO = 0.4
37	    const val DIP_FRAMES = 3
38	    const val SAMPLE_RATE_HZ = Pcm.TARGET_HZ
39	
40	    val maxGainLinear: Double
41	        get() = dbToLinear(MAX_GAIN_DB)
42	
43	    fun stage(samples: FloatArray): FarFieldGainResult = stagePerSegment(samples)
44	
45	    /**
46	     * Per-turn gain. Never apply gain &lt; 1 (does not crush a loud near seat).
47	     * Quiet islands independently target [TARGET_RMS_FS].
48	     */
49	    fun stagePerSegment(samples: FloatArray, sampleRateHz: Int = SAMPLE_RATE_HZ): FarFieldGainResult {
50	        val n = samples.size
51	        val rmsBefore = rms(samples)
52	        val peakBefore = peak(samples)
53	        val vadBefore = energyVadFireRate(samples, sampleRateHz)
54	        if (n == 0) {
55	            return emptyResult(samples)
56	        }
57	        val out = samples.copyOf()
58	        val frame = ((sampleRateHz * FRAME_MS) / 1000).coerceAtLeast(1)
59	        val ranges = findSegments(samples, sampleRateHz)
60	        val segs = ArrayList<SegmentGain>(ranges.size)
61	        var anyBoost = false
62	        var anyClip = false
63	        var maxG = 1.0
64	        val lim = PEAK_LIMIT.toFloat()
65	        for (range in ranges) {
66	            val slice = samples.copyOfRange(range.first, range.last + 1)
67	            val r0 = activeRms(samples, range, frame)
68	            val p0 = peak(slice)
69	            val g = gainForSegment(r0, p0)
70	            if (g > maxG) maxG = g
71	            val desiredBoost = r0 >= MIN_RMS_FOR_GAIN && r0 < TARGET_RMS_FS
72	            val clipGuard = desiredBoost && g + 1e-12 < TARGET_RMS_FS / r0.coerceAtLeast(1e-12)
73	            if (clipGuard) anyClip = true
74	            if (g > 1.0 + 1e-9) anyBoost = true
75	            var i = range.first
76	            val gf = g.toFloat()
77	            while (i <= range.last) {
78	                out[i] = (samples[i] * gf).coerceIn(-lim, lim)
79	                i++
80	            }

> Failure: The request could not be completed. Please try again.
