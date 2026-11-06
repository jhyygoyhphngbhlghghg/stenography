package team.mikcoders.neurosteno.present

import kotlinx.coroutines.flow.Flow

/**
 * Phone-side worker for the presentation website.
 *
 * Contract: docs/PRESENT_CONNECT.md
 * Cap: [PresentConnectConfig.maxStreams] streams (brief/demo: 20).
 *
 * Network mode only. Offline stenography must not call this.
 */
interface PresentConnectClient {
    suspend fun connect(config: PresentConnectConfig)

    suspend fun disconnect()

    val events: Flow<PresentConnectEvent>

    /** Currently accepted streams, 0..maxStreams. */
    fun activeStreamCount(): Int
}

data class PresentConnectConfig(
    val wsUrl: String,
    val httpBaseUrl: String,
    val sessionId: String,
    val deviceId: String,
    val maxStreams: Int = 20,
    val sampleRate: Int = 16_000,
)

sealed class PresentConnectEvent {
    data class HelloOk(val sessionId: String, val workerId: String) : PresentConnectEvent()

    data class StreamStarted(
        val streamId: String,
        val speakerId: String = "",
        val role: String = "",
    ) : PresentConnectEvent()

    data class StreamEnded(val streamId: String, val speakerId: String = "") : PresentConnectEvent()

    data class Partial(
        val streamId: String,
        val speakerId: String,
        val startMs: Long,
        val endMs: Long,
        val text: String,
    ) : PresentConnectEvent()

    data class Final(
        val streamId: String,
        val speakerId: String,
        val startMs: Long,
        val endMs: Long,
        val text: String,
    ) : PresentConnectEvent()

    data class OverCapacity(val rejectedStreamId: String) : PresentConnectEvent()

    data class Error(val streamId: String?, val code: String, val message: String) :
        PresentConnectEvent()

    /** Pre-ASR mic/energy gate. Hard reject skips ASR; soft flag still ASR (brief unchanged). */
    data class MicQuality(
        val streamId: String,
        val speakerId: String,
        val code: String,
        val hardReject: Boolean,
        val flags: List<String> = emptyList(),
    ) : PresentConnectEvent()

    data object Disconnected : PresentConnectEvent()
}

/** Error codes from docs/PRESENT_CONNECT.md */
object PresentConnectCodes {
    const val OVER_CAPACITY = "OVER_CAPACITY"
    const val BAD_ENCODING = "BAD_ENCODING"
    const val UNKNOWN_STREAM = "UNKNOWN_STREAM"
    const val PIPELINE = "PIPELINE"
    const val MODELS_MISSING = "MODELS_MISSING"
    const val LOW_ENERGY = "LOW_ENERGY"
    const val CLIPPED = "CLIPPED"
    const val BAD_MIC = "BAD_MIC"
}
