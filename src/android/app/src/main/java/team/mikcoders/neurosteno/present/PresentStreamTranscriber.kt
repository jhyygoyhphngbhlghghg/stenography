package team.mikcoders.neurosteno.present

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import team.mikcoders.neurosteno.audio.Pcm
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.Utterance
import team.mikcoders.neurosteno.pipeline.PipelineProgress
import team.mikcoders.neurosteno.pipeline.PcmAudio
import team.mikcoders.neurosteno.pipeline.StenoPipeline
import team.mikcoders.neurosteno.pipeline.StubStenoPipeline

/**
 * Path B: feed **one** stream's PCM into Zipformer-ru (+ diar inside that stream).
 *
 * Never concatenates or sums PCM from other streams. [speakerHint] is seat metadata
 * from the BE; [Utterance.speakerId] stays diarization inside the stream
 * (`docs/PRESENT_CONNECT.md`). Web FE maps `stream_id` → QR/seat.
 */
class PresentStreamTranscriber(
    private val pipeline: StenoPipeline,
    val modelsMissing: Boolean = pipeline is StubStenoPipeline,
) {
    private val asrLock = Mutex()

    suspend fun transcribeIsolated(
        streamId: String,
        pcmS16le: ByteArray,
        speakerHint: String? = null,
        onProgress: (PipelineProgress) -> Unit = {},
    ): IsolatedResult {
        if (modelsMissing) {
            return IsolatedResult.ModelsMissing(streamId)
        }
        // Platform StreamPcmGate on this stream_id only (never mixed), before Silero/diar/ASR.
        // Known seats s01..s20: near/below RMS floor is SOFT_LOW (still ASR), not LOW_ENERGY.
        val quality = StreamPcmGate.inspect(
            pcmS16le,
            boundSeat = StreamLabeler.isKnownSeat(streamId),
        )
        when (val verdict = quality.verdict) {
            is StreamGateVerdict.Reject -> {
                return IsolatedResult.Rejected(streamId, verdict.code, verdict.message)
            }
            StreamGateVerdict.Pass -> Unit
        }
        val samples = Pcm.s16leToFloat(pcmS16le)
        val audio = PcmAudio(
            samples = samples,
            sampleRate = Pcm.TARGET_HZ,
            sourceName = "present:$streamId",
        )
        val file = asrLock.withLock {
            pipeline.transcribe(audio, onProgress)
        }
        return IsolatedResult.Ok(
            streamId = streamId,
            speakerHint = speakerHint,
            file = file,
            softFlags = quality.softFlags,
        )
    }
}

sealed class IsolatedResult {
    abstract val streamId: String

    data class Ok(
        override val streamId: String,
        val speakerHint: String?,
        val file: TranscriptFile,
        val softFlags: List<String> = emptyList(),
    ) : IsolatedResult()

    data class ModelsMissing(override val streamId: String) : IsolatedResult()

    data class Empty(override val streamId: String) : IsolatedResult()

    /** Bad mic / clip — fail loud, do not emit a hallucinated transcript. */
    data class Rejected(
        override val streamId: String,
        val code: String,
        val message: String,
    ) : IsolatedResult()

    val utterances: List<Utterance>
        get() = (this as? Ok)?.file?.transcript.orEmpty()
}
