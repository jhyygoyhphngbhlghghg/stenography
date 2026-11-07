package team.mikcoders.neurosteno.pipeline

import team.mikcoders.neurosteno.export.TranscriptFile

enum class PipelineStage {
    Idle,
    Decode,
    Vad,
    Diarize,
    Asr,
    Llm,
    Done,
}

data class PipelineProgress(
    val stage: PipelineStage,
    val message: String,
    val fraction: Float,
)

data class PcmAudio(
    val samples: FloatArray,
    val sampleRate: Int = 16_000,
    val sourceName: String,
) {
    val durationMs: Long
        get() = if (sampleRate <= 0) 0L else (samples.size * 1000L) / sampleRate
}

interface StenoPipeline {
    val displayName: String
    val llmDisplayName: String
    val llmIsOnDevice: Boolean

    /** Evict warm native weights without changing the selected models. */
    suspend fun trimMemory() {}

    /** Wait for in-flight inference before releasing native state. */
    suspend fun close() {}

    suspend fun transcribe(
        audio: PcmAudio,
        onProgress: (PipelineProgress) -> Unit,
    ): TranscriptFile
}
