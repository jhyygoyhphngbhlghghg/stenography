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
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import team.mikcoders.neurosteno.audio.AudioQualityGate
import team.mikcoders.neurosteno.audio.Pcm
import team.mikcoders.neurosteno.audio.SpeechEndpointing
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.Utterance
import team.mikcoders.neurosteno.llm.IdentityLlmPostPass
import team.mikcoders.neurosteno.llm.LocalLlmPostPass

/**
 * Endpoint trim → VAD → OfflineSpeakerDiarization → Zipformer-ru (or Whisper tiny) per crop → LLM stub.
 *
 * Overlap/bleed: tighter Silero params + drop crops that fail [SpeechEndpointing.isMainSpeech]
 * (cite-only bar: EDGE_overlap WER 0.74 DER 0.99).
 *
 * Wired to the sherpa-onnx **Kotlin** Android AAR (data classes, not Java builders).
 * Models live on disk (filesDir), so AssetManager is null → newFromFile.
 * If native load fails, caller should fall back to [StubStenoPipeline].
 */
class SherpaStenoPipeline(
    private val paths: ModelPaths,
    private val llm: LocalLlmPostPass = IdentityLlmPostPass(),
) : StenoPipeline {
    override val displayName: String =
        when {
            paths.hasPrimaryAsr -> "sherpa zipformer-ru-int8"
            paths.hasBackupAsr -> "sherpa whisper-tiny"
            else -> "sherpa (incomplete models)"
        }

    override suspend fun transcribe(
        audio: PcmAudio,
        onProgress: (PipelineProgress) -> Unit,
    ): TranscriptFile {
        require(audio.sampleRate == Pcm.TARGET_HZ) { "need 16 kHz PCM" }
        onProgress(PipelineProgress(PipelineStage.Decode, "PCM ${audio.samples.size} samples", 0.05f))
        when (val q = AudioQualityGate.inspect(audio.samples)) {
            is AudioQualityGate.Quality.Fail -> {
                onProgress(PipelineProgress(PipelineStage.Decode, q.message, 0.05f))
                throw AudioQualityGate.Rejected(q.code, q.message)
            }
            AudioQualityGate.Quality.Ok -> Unit
        }
        val gated = audio.copy(samples = AudioQualityGate.dcBlock(audio.samples))

        val recognizer = createRecognizer()
        try {
            val raw = if (paths.hasDiar) {
                onProgress(PipelineProgress(PipelineStage.Diarize, "OfflineSpeakerDiarization", 0.2f))
                diarizeThenAsr(gated, recognizer, onProgress)
            } else {
                onProgress(PipelineProgress(PipelineStage.Vad, "Silero VAD chunks", 0.2f))
                vadThenAsr(gated, recognizer, onProgress)
            }
            onProgress(
                PipelineProgress(
                    PipelineStage.Llm,
                    llm.displayName,
                    0.92f,
                ),
            )
            val cleaned = llm.cleanup(raw)
            onProgress(PipelineProgress(PipelineStage.Done, "${cleaned.size} utterances", 1f))
            return TranscriptFile(transcript = cleaned.sortedBy { it.startMs })
        } finally {
            recognizer.release()
        }
    }

    private fun diarizeThenAsr(
        audio: PcmAudio,
        recognizer: OfflineRecognizer,
        onProgress: (PipelineProgress) -> Unit,
    ): List<Utterance> {
        val diar = createDiarizer()
        try {
            val contextP80 = SpeechEndpointing.streamP80Rms(audio.samples, audio.sampleRate)
            val segs = diar.process(audio.samples)
            val out = ArrayList<Utterance>(segs.size)
            segs.forEachIndexed { i, seg ->
                onProgress(
                    PipelineProgress(
                        PipelineStage.Asr,
                        "ASR ${i + 1}/${segs.size} speaker_${seg.speaker + 1}",
                        0.3f + 0.6f * (i + 1) / segs.size.coerceAtLeast(1),
                    ),
                )
                val startMs = (seg.start * 1000).toLong()
                val endMs = (seg.end * 1000).toLong()
                val crop = crop(audio.samples, audio.sampleRate, startMs, endMs)
                val voiced = SpeechEndpointing.trimToSpeech(crop, audio.sampleRate)
                if (!SpeechEndpointing.isMainSpeech(voiced, audio.sampleRate, contextP80)) continue
                val text = asr(recognizer, voiced, audio.sampleRate)
                if (text.isNotBlank()) {
                    out += Utterance(
                        speakerId = "speaker_${seg.speaker + 1}",
                        startMs = startMs,
                        endMs = endMs,
                        text = text,
                    )
                }
            }
            return out
        } finally {
            diar.release()
        }
    }

    private fun vadThenAsr(
        audio: PcmAudio,
        recognizer: OfflineRecognizer,
        onProgress: (PipelineProgress) -> Unit,
    ): List<Utterance> {
        if (!paths.hasVad) {
            onProgress(PipelineProgress(PipelineStage.Asr, "ASR whole file (no VAD)", 0.5f))
            val voiced = SpeechEndpointing.trimToSpeech(audio.samples, audio.sampleRate)
            if (!SpeechEndpointing.isMainSpeech(voiced, audio.sampleRate)) return emptyList()
            val text = asr(recognizer, voiced, audio.sampleRate)
            val end = (voiced.size.toLong() * 1000) / audio.sampleRate
            return listOf(Utterance("speaker_1", 0, end, text))
        }
        val vad = createVad()
        try {
            val window = 512
            var offset = 0
            val samples = audio.samples
            val contextP80 = SpeechEndpointing.streamP80Rms(samples, audio.sampleRate)
            val out = ArrayList<Utterance>()
            var idx = 0
            while (offset + window <= samples.size) {
                vad.acceptWaveform(samples.copyOfRange(offset, offset + window))
                offset += window
                while (!vad.empty()) {
                    val seg = vad.front()
                    vad.pop()
                    val startMs = (seg.start.toDouble() / audio.sampleRate * 1000).toLong()
                    emitVadUtterance(recognizer, seg.samples, audio.sampleRate, startMs, contextP80, out) {
                        onProgress(PipelineProgress(PipelineStage.Asr, "VAD chunk ${++idx}", 0.4f))
                    }
                }
            }
            vad.flush()
            while (!vad.empty()) {
                val seg = vad.front()
                vad.pop()
                val startMs = (seg.start.toDouble() / audio.sampleRate * 1000).toLong()
                emitVadUtterance(recognizer, seg.samples, audio.sampleRate, startMs, contextP80, out)
            }
            return out
        } finally {
            vad.release()
        }
    }

    private fun emitVadUtterance(
        recognizer: OfflineRecognizer,
        raw: FloatArray,
        sampleRate: Int,
        startMs: Long,
        contextP80: Float,
        out: MutableList<Utterance>,
        onChunk: () -> Unit = {},
    ) {
        val voiced = SpeechEndpointing.trimToSpeech(raw, sampleRate)
        if (!SpeechEndpointing.isMainSpeech(voiced, sampleRate, contextP80)) return
        onChunk()
        val text = asr(recognizer, voiced, sampleRate)
        if (text.isNotBlank()) {
            val durMs = (voiced.size.toLong() * 1000) / sampleRate
            out += Utterance("speaker_1", startMs, startMs + durMs, text)
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
            clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
            minDurationOn = 0.2f,
            minDurationOff = 0.5f,
        )
        return OfflineSpeakerDiarization(assetManager = null, config = cfg)
    }

    private fun createVad(): Vad {
        val cfg = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = paths.sileroVad!!.absolutePath,
                threshold = SpeechEndpointing.VAD_THRESHOLD,
                minSilenceDuration = SpeechEndpointing.MIN_SILENCE_SEC,
                minSpeechDuration = SpeechEndpointing.MIN_SPEECH_SEC,
                windowSize = 512,
                maxSpeechDuration = SpeechEndpointing.MAX_SPEECH_SEC,
            ),
            sampleRate = 16_000,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
        return Vad(assetManager = null, config = cfg)
    }
}
