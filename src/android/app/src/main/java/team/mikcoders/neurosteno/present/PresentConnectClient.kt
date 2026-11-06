package team.mikcoders.neurosteno.present

import kotlinx.coroutines.flow.Flow
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.Utterance

/**
 * Phone-side worker for the presentation website.
 *
 * Contract: docs/PRESENT_CONNECT.md
 * Path B: each stream_id is its own PCM path into Zipformer-ru + diar (no mixdown).
 * Cap: [PresentConnectLimits.MAX_STREAMS] streams (brief/demo: 20). Stream 21 → OVER_CAPACITY.
 *
 * Network mode only. Offline stenography must not call this.
 */
interface PresentConnectClient {
    suspend fun connect(config: PresentConnectConfig)

    suspend fun disconnect()

    val events: Flow<PresentConnectEvent>

    /** Currently accepted streams, 0..maxStreams. */
    fun activeStreamCount(): Int

    /** Push brief-shaped utterances after local ASR of **this** stream only. */
    fun sendTranscriptFinal(streamId: String, file: TranscriptFile)

    fun sendTranscriptPartial(streamId: String, utterance: Utterance)

    fun sendError(streamId: String?, code: String, message: String)

    fun sendStreamClosed(streamId: String)
}

data class PresentConnectConfig(
    val wsUrl: String,
    val httpBaseUrl: String,
    val sessionId: String,
    val deviceId: String,
    val maxStreams: Int = PresentConnectLimits.MAX_STREAMS,
    val sampleRate: Int = PresentConnectLimits.SAMPLE_RATE,
)

sealed class PresentConnectEvent {
    data class HelloOk(val sessionId: String, val workerId: String) : PresentConnectEvent()

    data class StreamStarted(val streamId: String) : PresentConnectEvent()

    data class StreamEnded(
        val streamId: String,
        val pcmS16le: ByteArray,
        val speakerHint: String? = null,
    ) : PresentConnectEvent()

    /**
     * Optional live ASR on **this** stream's isolated snapshot (Path B: not a mix).
     * [speakerHint] is BE metadata; [speaker_id] still comes from diarization inside the stream.
     */
    data class PartialDue(
        val streamId: String,
        val pcmS16le: ByteArray,
        val speakerHint: String? = null,
    ) : PresentConnectEvent()

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

    data object Disconnected : PresentConnectEvent()
}

/** Error codes from docs/PRESENT_CONNECT.md */
object PresentConnectCodes {
    const val OVER_CAPACITY = "OVER_CAPACITY"
    const val BAD_ENCODING = "BAD_ENCODING"
    const val UNKNOWN_STREAM = "UNKNOWN_STREAM"
    const val PIPELINE = "PIPELINE"
    const val MODELS_MISSING = "MODELS_MISSING"
    const val BAD_AUDIO = "BAD_AUDIO"
    const val LOW_ENERGY = "LOW_ENERGY"
    const val CLIPPED = "CLIPPED"
    const val BAD_MIC = "BAD_MIC"
}
