package team.mikcoders.neurosteno.pipeline

import kotlinx.coroutines.delay
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.Utterance

/**
 * Development fallback when ONNX weights are not on the device.
 * Must never be presented as ASR to judges (brief forbids a canned transcript).
 */
class StubStenoPipeline : StenoPipeline {
    override val displayName: String = "stub (models missing)"
    override val llmDisplayName: String = "llm-skipped (stub pipeline)"
    override val llmIsOnDevice: Boolean = false

    override suspend fun transcribe(
        audio: PcmAudio,
        onProgress: (PipelineProgress) -> Unit,
    ): TranscriptFile {
        onProgress(PipelineProgress(PipelineStage.Decode, "PCM ${audio.sourceName}", 0.1f))
        delay(80)
        onProgress(PipelineProgress(PipelineStage.Vad, "VAD skipped — no silero_vad.onnx", 0.3f))
        delay(80)
        onProgress(PipelineProgress(PipelineStage.Diarize, "Diarization skipped", 0.5f))
        delay(80)
        onProgress(PipelineProgress(PipelineStage.Asr, "ASR skipped — no Zipformer-ru", 0.7f))
        delay(80)
        onProgress(PipelineProgress(PipelineStage.Llm, llmDisplayName, 0.9f))
        delay(80)
        val end = audio.durationMs.coerceAtLeast(3_000L)
        val mid = end / 2
        onProgress(PipelineProgress(PipelineStage.Done, "Stub transcript", 1f))
        return TranscriptFile(
            transcript = listOf(
                Utterance(
                    speakerId = "speaker_1",
                    startMs = 0,
                    endMs = mid,
                    text = "[stub] Модели не на устройстве. Это не распознавание речи.",
                ),
                Utterance(
                    speakerId = "speaker_2",
                    startMs = mid,
                    endMs = end,
                    text = "[stub] Скачайте веса: ./scripts/download-models.sh — иначе защита в авиарежиме провалится.",
                ),
            ),
        )
    }
}
