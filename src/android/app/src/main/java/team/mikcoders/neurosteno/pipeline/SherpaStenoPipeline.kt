package team.mikcoders.neurosteno.pipeline

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import team.mikcoders.neurosteno.audio.FarFieldGain
import team.mikcoders.neurosteno.audio.FarFieldVad
import team.mikcoders.neurosteno.audio.Pcm
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.Utterance
import team.mikcoders.neurosteno.llm.HeuristicPunctuationPostPass
import team.mikcoders.neurosteno.llm.LocalLlmPostPass

/**
 * Frozen FarFieldGain → OfflineSpeakerDiarization + VAD hole-fill →
 * Zipformer-ru per crop → llama.cpp GGUF intonation punct.
 *
 * Gain and EQ are frozen (Hex WER still 0.7794, s5 missing). PresentConnect
 * scene keeps overnight clustering. Native load failure → stub.
 */
class SherpaStenoPipeline(
    private val paths: ModelPaths,
    private val llm: LocalLlmPostPass = HeuristicPunctuationPostPass(),
    private val scene: FarFieldScene = FarFieldScene.stage6,
) : StenoPipeline {
    override val displayName: String =
        when {
            paths.hasPrimaryAsr && scene.expectedSpeakers > 0 ->
                "sherpa zipformer-ru-int8 far-field ${scene.expectedSpeakers}spk"
            paths.hasPrimaryAsr -> "sherpa zipformer-ru-int8"
            paths.hasBackupAsr -> "sherpa whisper-tiny"
            else -> "sherpa (incomplete models)"
        }
    override val llmDisplayName: String = llm.displayName
    override val llmIsOnDevice: Boolean = llm.isOnDeviceLlm

    private val inference = InferenceSession(
        create = { createRecognizer() },
        destroy = { it.release() },
    )

    override suspend fun trimMemory() {
        inference.evict { llm.trimMemory() }
    }

    override suspend fun close() {
        try {
            inference.close()
        } finally {
            llm.close()
        }
    }

    override suspend fun transcribe(
        audio: PcmAudio,
        onProgress: (PipelineProgress) -> Unit,
    ): TranscriptFile {
        val context = currentCoroutineContext()
        fun checkpoint() = context.ensureActive()
        val cleaned = runExclusivePhases(
            asrPhase = { inference.run { getRecognizer -> transcribeAsr(audio, getRecognizer, onProgress, ::checkpoint) } },
            // Peak native RAM is max(ASR, LLM), not the sum: free the recognizer
            // (weights + ORT arenas) before the GGUF loads.
            releaseAsr = { inference.evict() },
            llmPhase = { raw ->
                checkpoint()
                onProgress(PipelineProgress(PipelineStage.Llm, llm.displayName, 0.92f))
                llm.cleanup(raw)
            },
        )
        checkpoint()
        onProgress(PipelineProgress(PipelineStage.Done, "${cleaned.size} utterances", 1f))
        return TranscriptFile(transcript = cleaned.sortedBy { it.startMs })
    }

    private suspend fun transcribeAsr(
        audio: PcmAudio,
        getRecognizer: suspend () -> OfflineRecognizer,
        onProgress: (PipelineProgress) -> Unit,
        checkpoint: () -> Unit,
    ): List<Utterance> {
        require(audio.sampleRate == Pcm.TARGET_HZ) { "need 16 kHz PCM" }
        onProgress(PipelineProgress(PipelineStage.Decode, "PCM ${audio.samples.size} samples", 0.05f))

        val hz = audio.sampleRate
        val staged = if (scene.expectedSpeakers > 0) {
            FarFieldGain.stage(audio.samples)
        } else {
            null
        }
        val work = if (staged != null) audio.copy(samples = staged.samples) else audio
        val energy = if (staged != null) {
            staged.segments.map { SpeechRegion(it.start, it.endInclusive + 1) }
        } else {
            FarFieldVad.speechRegions(work.samples, hz)
        }
        onProgress(
            PipelineProgress(
                PipelineStage.Vad,
                "energy windows ${energy.size} (gain/EQ frozen)",
                0.18f,
            ),
        )
        checkpoint()
        val silero = if (paths.hasVad) collectSilero(work, checkpoint) else emptyList()
        val regions = FarFieldVad.union(energy, silero)

        return if (paths.hasDiar) {
            onProgress(PipelineProgress(PipelineStage.Diarize, "OfflineSpeakerDiarization K=${scene.expectedSpeakers}", 0.25f))
            // Diarization sessions are released before the ASR model loads;
            // the ASR session is released after this phase, before the LLM.
            val segments = diarize(work, regions)
            checkpoint()
            if (segments.isEmpty()) emptyList() else {
                transcribeSegments(work, segments, getRecognizer(), onProgress, checkpoint)
            }
        } else {
            onProgress(PipelineProgress(PipelineStage.Vad, "Silero VAD chunks", 0.25f))
            vadThenAsr(work, getRecognizer(), onProgress, checkpoint)
        }
    }

    private fun diarize(
        audio: PcmAudio,
        vadRegions: List<SpeechRegion>,
    ): List<DiarSeg> {
        val reconciled = runDiarizerAndReconcile(audio, vadRegions)
        return consolidateLabelsByVoice(audio, reconciled)
    }

    private fun runDiarizerAndReconcile(
        audio: PcmAudio,
        vadRegions: List<SpeechRegion>,
    ): List<DiarSeg> {
        val diar = createDiarizer()
        try {
            val segs = diar.process(audio.samples)
            val mapped = segs.map { s ->
                DiarSeg(
                    speakerIndex = s.speaker,
                    startMs = (s.start * 1000).toLong(),
                    endMs = (s.end * 1000).toLong(),
                )
            }
            return VadDiarReconcile.run(
                vadRegions,
                mapped,
                audio.sampleRate,
                scene,
                samples = audio.samples,
            )
        } finally {
            diar.release()
        }
    }

    /**
     * Re-label reconciled segments by voice embedding. Without this, the
     * stage6 reconcile (time + energy) will put sequential replicas from one
     * person on different speaker labels — including absorbing them via the
     * adopt/seat machinery when pyannote finds nothing (see SpeakerIdentityMerge).
     * Wraps in try/catch: a failed embedding model must not kill the
     * transcription path; labels then stay as reconciled.
     */
    private fun consolidateLabelsByVoice(
        audio: PcmAudio,
        segs: List<DiarSeg>,
    ): List<DiarSeg> {
        if (segs.size <= 1) return segs
        val extractor = try {
            createEmbeddingExtractor()
        } catch (t: Throwable) {
            return segs
        }
        return try {
            val embeddings = segs.map { seg ->
                embedSegment(extractor, audio.samples, audio.sampleRate, seg)
            }
            SpeakerIdentityMerge.merge(segs, embeddings)
        } catch (t: Throwable) {
            segs
        } finally {
            extractor.release()
        }
    }

    private fun createEmbeddingExtractor(): SpeakerEmbeddingExtractor {
        val cfg = SpeakerEmbeddingExtractorConfig(
            model = paths.diarEmbedding!!.absolutePath,
            numThreads = 2,
            debug = false,
            provider = "cpu",
        )
        return SpeakerEmbeddingExtractor(assetManager = null, config = cfg)
    }

    private fun embedSegment(
        extractor: SpeakerEmbeddingExtractor,
        samples: FloatArray,
        hz: Int,
        seg: DiarSeg,
    ): FloatArray? {
        val windows = SpeakerIdentityMerge.embeddingWindows(seg.startMs, seg.endMs)
        if (windows.isEmpty()) return null
        val embeddings = windows.mapNotNull { win ->
            val a = ((win.first * hz) / 1000).toInt().coerceIn(0, samples.size)
            val b = ((win.last * hz) / 1000).toInt().coerceIn(a, samples.size)
            if (b - a < hz / 10) return@mapNotNull null
            embedAudio(extractor, samples.copyOfRange(a, b), hz)
        }
        return SpeakerIdentityMerge.averageEmbeddings(embeddings)
    }

    private fun embedAudio(
        extractor: SpeakerEmbeddingExtractor,
        samples: FloatArray,
        sampleRate: Int,
    ): FloatArray? {
        if (samples.isEmpty()) return null
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            stream.inputFinished()
            if (extractor.isReady(stream)) extractor.compute(stream) else null
        } catch (t: Throwable) {
            null
        } finally {
            stream.release()
        }
    }

    /**
     * Decode one diar segment's text. Crops longer than ASR_MAX_CROP_MS are
     * split at energy dips (see AsrWindowing) — Zipformer's native memory
     * grows with crop length and a minutes-long voice line otherwise gets
     * the process killed during transcription.
     */
    private fun transcribeCrop(
        audio: PcmAudio,
        segStartMs: Long,
        segEndMs: Long,
        recognizer: OfflineRecognizer,
        checkpoint: () -> Unit,
    ): String {
        val pad = scene.asrCropPadMs
        val ranges = AsrWindowing.windows(audio.samples, audio.sampleRate, segStartMs - pad, segEndMs + pad)
        val parts = ArrayList<String>()
        for ((a, b) in ranges) {
            checkpoint()
            val crop = crop(audio.samples, audio.sampleRate, a, b)
            if (crop.isEmpty()) continue
            val text = asr(recognizer, FarFieldGain.stage(crop).samples, audio.sampleRate)
            if (text.isNotBlank()) parts += text
        }
        return parts.joinToString(" ")
    }

    private fun transcribeSegments(
        audio: PcmAudio,
        reconciled: List<DiarSeg>,
        recognizer: OfflineRecognizer,
        onProgress: (PipelineProgress) -> Unit,
        checkpoint: () -> Unit,
    ): List<Utterance> {
        val out = ArrayList<Utterance>(reconciled.size)
        reconciled.forEachIndexed { i, seg ->
            checkpoint()
            onProgress(
                PipelineProgress(
                    PipelineStage.Asr,
                    "ASR ${i + 1}/${reconciled.size} ${seg.speakerId}",
                    0.3f + 0.6f * (i + 1) / reconciled.size.coerceAtLeast(1),
                ),
            )
            val text = transcribeCrop(audio, seg.startMs, seg.endMs, recognizer, checkpoint)
            if (text.isNotBlank()) {
                out += Utterance(
                    speakerId = seg.speakerId,
                    startMs = seg.startMs,
                    endMs = seg.endMs,
                    text = text,
                )
            }
        }
        return out
    }

    private fun collectSilero(audio: PcmAudio, checkpoint: () -> Unit): List<SpeechRegion> {
        val vad = createVad()
        try {
            val window = 512
            var offset = 0
            val samples = audio.samples
            val out = ArrayList<SpeechRegion>()
            while (offset + window <= samples.size) {
                checkpoint()
                vad.acceptWaveform(samples.copyOfRange(offset, offset + window))
                offset += window
                drainSilero(vad, out)
            }
            vad.flush()
            drainSilero(vad, out)
            return out
        } finally {
            vad.release()
        }
    }

    private fun drainSilero(
        vad: Vad,
        out: ArrayList<SpeechRegion>,
    ) {
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            // SpeechSegment.start is Int on sherpa-onnx ≤1.13.5 and Long on
            // v2.0.1; toInt() keeps this compiling against both AARs.
            val start = seg.start.toInt()
            val end = start + seg.samples.size
            if (end > start) out += SpeechRegion(start, end)
        }
    }

    private fun vadThenAsr(
        audio: PcmAudio,
        recognizer: OfflineRecognizer,
        onProgress: (PipelineProgress) -> Unit,
        checkpoint: () -> Unit,
    ): List<Utterance> {
        if (!paths.hasVad) {
            // Whole-file ASR also respects the crop bound (see AsrWindowing):
            // without VAD the file length is unbounded.
            onProgress(PipelineProgress(PipelineStage.Asr, "ASR whole file (no VAD)", 0.5f))
            val parts = ArrayList<String>()
            for ((a, b) in AsrWindowing.windows(audio.samples, audio.sampleRate, 0, audio.durationMs)) {
                checkpoint()
                val chunk = crop(audio.samples, audio.sampleRate, a, b)
                if (chunk.isEmpty()) continue
                val text = asr(recognizer, FarFieldGain.stage(chunk).samples, audio.sampleRate)
                if (text.isNotBlank()) parts += text
            }
            val end = audio.durationMs
            return listOf(Utterance("speaker_1", 0, end, parts.joinToString(" ")))
        }
        val vad = createVad()
        try {
            val window = 512
            var offset = 0
            val samples = audio.samples
            val out = ArrayList<Utterance>()
            var idx = 0
            while (offset + window <= samples.size) {
                checkpoint()
                vad.acceptWaveform(samples.copyOfRange(offset, offset + window))
                offset += window
                while (!vad.empty()) {
                    val seg = vad.front()
                    vad.pop()
                    val startMs = (seg.start.toDouble() / audio.sampleRate * 1000).toLong()
                    val durMs = (seg.samples.size.toLong() * 1000) / audio.sampleRate
                    onProgress(PipelineProgress(PipelineStage.Asr, "VAD chunk ${++idx}", 0.4f))
                    val text = asr(recognizer, FarFieldGain.stage(seg.samples).samples, audio.sampleRate)
                    if (text.isNotBlank()) {
                        out += Utterance("speaker_1", startMs, startMs + durMs, text)
                    }
                }
            }
            vad.flush()
            while (!vad.empty()) {
                checkpoint()
                val seg = vad.front()
                vad.pop()
                val startMs = (seg.start.toDouble() / audio.sampleRate * 1000).toLong()
                val durMs = (seg.samples.size.toLong() * 1000) / audio.sampleRate
                val text = asr(recognizer, FarFieldGain.stage(seg.samples).samples, audio.sampleRate)
                if (text.isNotBlank()) {
                    out += Utterance("speaker_1", startMs, startMs + durMs, text)
                }
            }
            return out
        } finally {
            vad.release()
        }
    }

    private fun asr(recognizer: OfflineRecognizer, samples: FloatArray, sampleRate: Int): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun crop(samples: FloatArray, hz: Int, startMs: Long, endMs: Long): FloatArray {
        val a = ((startMs * hz) / 1000).toInt().coerceIn(0, samples.size)
        val b = ((endMs * hz) / 1000).toInt().coerceIn(a, samples.size)
        return samples.copyOfRange(a, b)
    }

    private fun createRecognizer(): OfflineRecognizer {
        val modelConfig: OfflineModelConfig
        if (paths.hasPrimaryAsr) {
            val dir = paths.zipformerDir!!
            val tokens = ModelStore.zipformerFile(dir, "tokens.txt")!!.absolutePath
            val enc = ModelStore.zipformerFile(dir, "encoder")!!.absolutePath
            val dec = ModelStore.zipformerFile(dir, "decoder")!!.absolutePath
            val join = ModelStore.zipformerFile(dir, "joiner")!!.absolutePath
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = enc,
                    decoder = dec,
                    joiner = join,
                ),
                tokens = tokens,
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "transducer",
            )
        } else {
            val dir = paths.whisperTinyDir ?: error("no ASR model")
            val tokens = ModelStore.whisperFile(dir, "tokens")!!.absolutePath
            val enc = ModelStore.whisperFile(dir, "encoder")!!.absolutePath
            val dec = ModelStore.whisperFile(dir, "decoder")!!.absolutePath
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = enc,
                    decoder = dec,
                    language = "ru",
                    task = "transcribe",
                ),
                tokens = tokens,
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "whisper",
            )
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(assetManager = null, config = config)
    }

    private fun createDiarizer(): OfflineSpeakerDiarization {
        val cfg = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = paths.diarSegmentation!!.absolutePath,
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = paths.diarEmbedding!!.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            clustering = FastClusteringConfig(
                numClusters = scene.expectedSpeakers,
                threshold = scene.diarClusterThreshold,
            ),
            minDurationOn = scene.diarMinDurationOnSec,
            minDurationOff = scene.diarMinDurationOffSec,
        )
        return OfflineSpeakerDiarization(assetManager = null, config = cfg)
    }

    private fun createVad(): Vad {
        val cfg = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = paths.sileroVad!!.absolutePath,
                threshold = scene.vadThreshold,
                minSilenceDuration = scene.vadMinSilenceSec,
                minSpeechDuration = scene.vadMinSpeechSec,
                windowSize = 512,
                maxSpeechDuration = scene.vadMaxSpeechSec,
            ),
            sampleRate = 16_000,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
        return Vad(assetManager = null, config = cfg)
    }
}
