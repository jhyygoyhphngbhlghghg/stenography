package team.mikcoders.neurosteno.present

import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Path B: one isolated PCM buffer per [streamId]. Never mix or sum across streams.
 *
 * Hard cap [PresentConnectLimits.MAX_STREAMS]. Stream 21 → [StartResult.OverCapacity].
 */
class PresentStreamRegistry(
    val maxStreams: Int = PresentConnectLimits.MAX_STREAMS,
) {
    private val lock = Any()
    private val sessions = ConcurrentHashMap<String, StreamSession>()

    fun activeCount(): Int = sessions.size

    fun activeStreamIds(): Set<String> = sessions.keys.toSet()

    fun get(streamId: String): StreamSession? = sessions[streamId]

    /**
     * Independent copies of each open stream's PCM. There is no mixdown API.
     * Tests use this to prove N streams == N buffers.
     */
    fun isolatedSnapshots(): Map<String, ByteArray> =
        sessions.mapValues { it.value.snapshot() }

    fun tryStart(
        streamId: String,
        encoding: String?,
        sampleRate: Int?,
        speakerHint: String?,
    ): StartResult {
        if (encoding != null && encoding != PresentConnectLimits.ENCODING) {
            return StartResult.Rejected(streamId, PresentConnectCodes.BAD_ENCODING, "need pcm_s16le")
        }
        if (sampleRate != null && sampleRate != PresentConnectLimits.SAMPLE_RATE) {
            return StartResult.Rejected(streamId, PresentConnectCodes.BAD_ENCODING, "need 16 kHz")
        }
        synchronized(lock) {
            val existing = sessions[streamId]
            if (existing != null) {
                existing.resetPcm()
                existing.speakerHint = speakerHint
                return StartResult.Accepted(existing, alreadyOpen = true)
            }
            if (!PresentConnectLimits.canAccept(sessions.size, maxStreams)) {
                return StartResult.OverCapacity(streamId)
            }
            val session = StreamSession(
                streamId = streamId,
                speakerHint = speakerHint,
                sampleRate = sampleRate ?: PresentConnectLimits.SAMPLE_RATE,
            )
            sessions[streamId] = session
            return StartResult.Accepted(session, alreadyOpen = false)
        }
    }

    fun appendPcm(streamId: String, pcmS16le: ByteArray): AppendResult {
        val session = sessions[streamId]
            ?: return AppendResult.UnknownStream(streamId)
        val duePartial: ByteArray?
        synchronized(session) {
            session.append(pcmS16le)
            duePartial = if (session.bytesSincePartial >= PresentConnectLimits.PARTIAL_PERIOD_BYTES) {
                session.bytesSincePartial = 0
                session.snapshotLocked()
            } else {
                null
            }
        }
        return AppendResult.Ok(session, duePartial)
    }

    /** Remove the stream and return only that stream's PCM (never a mix). */
    fun takeForEnd(streamId: String): StreamSession? {
        val session = synchronized(lock) { sessions.remove(streamId) } ?: return null
        return session
    }

    fun cancel(streamId: String): StreamSession? = takeForEnd(streamId)

    fun clear() {
        synchronized(lock) { sessions.clear() }
    }

    sealed class StartResult {
        data class Accepted(val session: StreamSession, val alreadyOpen: Boolean) : StartResult()
        data class OverCapacity(val streamId: String) : StartResult()
        data class Rejected(val streamId: String, val code: String, val message: String) : StartResult()
    }

    sealed class AppendResult {
        data class Ok(val session: StreamSession, val partialSnapshot: ByteArray?) : AppendResult()
        data class UnknownStream(val streamId: String) : AppendResult()
    }
}

/**
 * One browser/mic. PCM lives here only. [speakerHint] is optional BE metadata;
 * [speaker_id] in transcripts remains diarization inside this stream.
 */
class StreamSession(
    val streamId: String,
    var speakerHint: String?,
    val sampleRate: Int,
) {
    private val pcm = ByteArrayOutputStream()
    var bytesSincePartial: Int = 0
        internal set

    @Synchronized
    fun append(bytes: ByteArray) {
        pcm.write(bytes)
        bytesSincePartial += bytes.size
    }

    @Synchronized
    fun snapshot(): ByteArray = snapshotLocked()

    /** Caller must hold this session's monitor. */
    internal fun snapshotLocked(): ByteArray = pcm.toByteArray()

    @Synchronized
    fun size(): Int = pcm.size()

    @Synchronized
    fun resetPcm() {
        pcm.reset()
        bytesSincePartial = 0
    }
}
