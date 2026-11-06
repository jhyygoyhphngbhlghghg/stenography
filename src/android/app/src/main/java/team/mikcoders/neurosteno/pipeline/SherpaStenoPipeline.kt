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
import team.mikcoders.neurosteno.audio.Pcm
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.Utterance
import team.mikcoders.neurosteno.llm.IdentityLlmPostPass
import team.mikcoders.neurosteno.llm.LocalLlmPostPass
import java.io.File

/**
 * VAD → OfflineSpeakerDiarization → Zipformer-ru (or Whisper tiny) per crop → LLM stub.
 *
 * Requires sherpa-onnx AAR + models on disk. If native load fails, caller should
 * fall back to [StubStenoPipeline].
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

        val recognizer = createRecognizer()
        try {
            val raw = if (paths.hasDiar) {
                onProgress(PipelineProgress(PipelineStage.Diarize, "OfflineSpeakerDiarization", 0.2f))
                diarizeThenAsr(audio, recognizer, onProgress)
            } else {
                onProgress(PipelineProgress(PipelineStage.Vad, "Silero VAD chunks", 0.2f))
                vadThenAsr(audio, recognizer, onProgress)
            }
            onProgress(PipelineProgress(PipelineStage.Llm, if (llm.weightsPresent) "LLM cleanup" else "LLM identity stub", 0.92f))
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
                val text = asr(recognizer, crop, audio.sampleRate)
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
            val text = asr(recognizer, audio.samples, audio.sampleRate)
            val end = audio.durationMs
            return listOf(Utterance("speaker_1", 0, end, text))
        }
        val vad = createVad()
        try {
            val window = 512
            var offset = 0
            val samples = audio.samples
            val out = ArrayList<Utterance>()
            var idx = 0
            while (offset + window <= samples.size) {
                vad.acceptWaveform(samples.copyOfRange(offset, offset + window))
                offset += window
                while (!vad.empty()) {
                    val seg = vad.front()
                    vad.pop()
                    val startMs = (seg.start.toDouble() / audio.sampleRate * 1000).toLong()
                    val durMs = (seg.samples.size.toLong() * 1000) / audio.sampleRate
                    onProgress(PipelineProgress(PipelineStage.Asr, "VAD chunk ${++idx}", 0.4f))
                    val text = asr(recognizer, seg.samples, audio.sampleRate)
                    if (text.isNotBlank()) {
                        out += Utterance("speaker_1", startMs, startMs + durMs, text)
                    }
                }
            }
            vad.flush()
            while (!vad.empty()) {
                val seg = vad.front()
                vad.pop()
                val startMs = (seg.start.toDouble() / audio.sampleRate * 1000).toLong()
                val durMs = (seg.samples.size.toLong() * 1000) / audio.sampleRate
                val text = asr(recognizer, seg.samples, audio.sampleRate)
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
        val tokens: String
        val modelConfig: OfflineModelConfig
        if (paths.hasPrimaryAsr) {
            val dir = paths.zipformerDir!!
            tokens = ModelStore.zipformerFile(dir, "tokens.txt")!!.absolutePath
            val enc = ModelStore.zipformerFile(dir, "encoder")!!.absolutePath
            val dec = ModelStore.zipformerFile(dir, "decoder")!!.absolutePath
            val join = ModelStore.zipformerFile(dir, "joiner")!!.absolutePath
            val transducer = OfflineTransducerModelConfig.builder()
                .setEncoder(enc)
                .setDecoder(dec)
                .setJoiner(join)
                .build()
            modelConfig = OfflineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(tokens)
                .setNumThreads(2)
                .setDebug(false)
                .build()
        } else {
            val dir = paths.whisperTinyDir ?: error("no ASR model")
            tokens = File(dir, "tiny-tokens.txt").takeIf { it.isFile }?.absolutePath
                ?: File(dir, "tokens.txt").absolutePath
            val enc = dir.listFiles()?.first { it.name.contains("encoder") }!!.absolutePath
            val dec = dir.listFiles()?.first { it.name.contains("decoder") }!!.absolutePath
            val whisper = OfflineWhisperModelConfig.builder()
                .setEncoder(enc)
                .setDecoder(dec)
                .build()
            modelConfig = OfflineModelConfig.builder()
                .setWhisper(whisper)
                .setTokens(tokens)
                .setNumThreads(2)
                .build()
        }
        val config = OfflineRecognizerConfig.builder()
            .setFeatConfig(FeatureConfig.builder().setSampleRate(16_000).setFeatureDim(80).build())
            .setOfflineModelConfig(modelConfig)
            .setDecodingMethod("greedy_search")
            .build()
        return OfflineRecognizer(config)
    }

    private fun createDiarizer(): OfflineSpeakerDiarization {
        val pyannote = OfflineSpeakerSegmentationPyannoteModelConfig.builder()
            .setModel(paths.diarSegmentation!!.absolutePath)
            .build()
        val seg = OfflineSpeakerSegmentationModelConfig.builder()
            .setPyannote(pyannote)
            .setNumThreads(2)
            .setProvider("cpu")
            .build()
        val emb = SpeakerEmbeddingExtractorConfig.builder()
            .setModel(paths.diarEmbedding!!.absolutePath)
            .setNumThreads(2)
            .setProvider("cpu")
            .build()
        val clustering = FastClusteringConfig.builder()
            .setNumClusters(-1)
            .setThreshold(0.5f)
            .build()
        val cfg = OfflineSpeakerDiarizationConfig.builder()
            .setSegmentation(seg)
            .setEmbedding(emb)
            .setClustering(clustering)
            .setMinDurationOn(0.2f)
            .setMinDurationOff(0.5f)
            .build()
        return OfflineSpeakerDiarization(cfg)
    }

    private fun createVad(): Vad {
        val silero = SileroVadModelConfig.builder()
            .setModel(paths.sileroVad!!.absolutePath)
            .setMinSilenceDuration(0.25f)
            .setMinSpeechDuration(0.25f)
            .setWindowSize(512)
            .setMaxSpeechDuration(20f)
            .build()
        val cfg = VadModelConfig.builder()
            .setSileroVadModelConfig(silero)
            .setSampleRate(16_000)
            .setNumThreads(1)
            .setProvider("cpu")
            .build()
        return Vad(cfg)
    }
}
