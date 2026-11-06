package team.mikcoders.neurosteno.present

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import team.mikcoders.neurosteno.audio.Pcm
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.TranscriptJson
import team.mikcoders.neurosteno.export.Utterance
import team.mikcoders.neurosteno.pipeline.PcmAudio
import team.mikcoders.neurosteno.pipeline.StenoPipeline

/**
 * Path B ingest: one PCM buffer per stream_id, oracle speaker_id, merge by time.
 * Never mix PCM across streams.
 */
fun interface PathBAsr {
    suspend fun transcribe(streamId: String, audio: PcmAudio): List<Utterance>
}

class PathBSession(
    private val labeler: StreamLabeler = StreamLabeler.oracle(),
    private val asr: PathBAsr,
    private val sampleRate: Int = Pcm.TARGET_HZ,
) {
    private val lock = Any()
    private val labels = LinkedHashMap<String, StreamLabel>()
    private val absorbed = ArrayList<Utterance>()
    private val wireFinals = ArrayList<WireFinal>()
    private val flags = LinkedHashMap<String, StreamMicFlag>()

    fun bindStart(streamId: String, speakerHint: String? = null, clockOffsetMs: Long? = null): StreamLabel {
        val label = labeler.bind(streamId, speakerHint, clockOffsetMs)
        synchronized(lock) { labels[streamId] = label }
        return label
    }

    fun unbind(streamId: String) {
        synchronized(lock) { labels.remove(streamId) }
    }

    fun labelOf(streamId: String): StreamLabel? = synchronized(lock) { labels[streamId] }

    /**
     * Gate this stream's PCM, then optionally ASR. Oracle speaker_id, optional clock shift.
     * Bound `s01`..`s20`: near-silence is SOFT_LOW (still ASR). Empty buffer still skips.
     * Cancel path must not call this. Hard rejects do not absorb into the brief.
     */
    suspend fun finish(streamId: String, pcmS16le: ByteArray): PathBFinish {
        val boundKnown = synchronized(lock) {
            labels.containsKey(streamId) && StreamLabeler.isKnownSeat(streamId)
        }
        val label = synchronized(lock) { labels[streamId] } ?: labeler.bind(streamId)
        val quality = StreamPcmGate.inspect(pcmS16le, sampleRate, boundSeat = boundKnown)
        when (val verdict = quality.verdict) {
            is StreamGateVerdict.Reject -> {
                val flag = StreamMicFlag(streamId, label.speakerId, verdict.code, hardReject = true)
                synchronized(lock) { flags[streamId] = flag }
                return PathBFinish(
                    label = label,
                    utterances = emptyList(),
                    wireJson = listOf(PresentConnectWire.error(streamId, verdict.code, verdict.message)),
                    quality = quality,
                    asrInvoked = false,
                )
            }
            StreamGateVerdict.Pass -> {
                if (quality.softFlags.isNotEmpty()) {
                    synchronized(lock) {
                        flags[streamId] = StreamMicFlag(
                            streamId = streamId,
                            speakerId = label.speakerId,
                            code = quality.softFlags.first(),
                            hardReject = false,
                            flags = quality.softFlags,
                        )
                    }
                }
                val samples = Pcm.s16leToFloat(pcmS16le)
                val audio = PcmAudio(samples = samples, sampleRate = sampleRate, sourceName = streamId)
                val raw = asr.transcribe(streamId, audio)
                val labeled = relabel(label, raw)
                synchronized(lock) {
                    absorbed.addAll(labeled)
                    for (u in labeled) {
                        wireFinals += WireFinal(streamId, u)
                    }
                }
                return PathBFinish(
                    label = label,
                    utterances = labeled,
                    wireJson = labeled.map { PresentConnectWire.transcriptFinal(streamId, it) },
                    quality = quality,
                    asrInvoked = true,
                )
            }
        }
    }

    fun brief(): TranscriptFile = synchronized(lock) { PresentConnectWire.brief(absorbed) }

    fun wireFinals(): List<WireFinal> = synchronized(lock) { wireFinals.toList() }

    fun micFlags(): List<StreamMicFlag> = synchronized(lock) { flags.values.toList() }

    companion object {
        fun relabel(label: StreamLabel, utterances: List<Utterance>): List<Utterance> {
            val shift = label.clockOffsetMs
            return utterances.map { u ->
                u.copy(
                    speakerId = label.speakerId,
                    startMs = (u.startMs + shift).coerceAtLeast(0L),
                    endMs = (u.endMs + shift).coerceAtLeast(0L),
                )
            }
        }

        fun fromPipeline(pipeline: StenoPipeline, labeler: StreamLabeler = StreamLabeler.oracle()): PathBSession =
            PathBSession(
                labeler = labeler,
                asr = PathBAsr { _, audio ->
                    pipeline.transcribe(audio) { }.transcript
                },
            )
    }
}

data class PathBFinish(
    val label: StreamLabel,
    val utterances: List<Utterance>,
    val wireJson: List<String>,
    val quality: StreamPcmStats,
    val asrInvoked: Boolean,
) {
    val rejectCode: String?
        get() = quality.rejectCode
}

data class WireFinal(
    val streamId: String,
    val utterance: Utterance,
)

object PresentConnectWire {
    fun transcriptFinal(streamId: String, u: Utterance): String =
        buildJsonObject {
            put("type", "transcript.final")
            put("stream_id", streamId)
            putJsonObject("utterance") {
                put("speaker_id", u.speakerId)
                put("start_ms", u.startMs)
                put("end_ms", u.endMs)
                put("text", u.text)
            }
        }.toString()

    fun transcriptPartial(streamId: String, u: Utterance): String =
        buildJsonObject {
            put("type", "transcript.partial")
            put("stream_id", streamId)
            put("speaker_id", u.speakerId)
            put("start_ms", u.startMs)
            put("end_ms", u.endMs)
            put("text", u.text)
        }.toString()

    /** Session export / GET transcript: four brief keys only, sorted. */
    fun brief(utterances: List<Utterance>): TranscriptFile =
        TranscriptFile(
            transcript = utterances.sortedWith(compareBy({ it.startMs }, { it.speakerId })),
        )

    fun briefJson(utterances: List<Utterance>): String = TranscriptJson.encode(brief(utterances))

    fun error(streamId: String, code: String, message: String): String =
        buildJsonObject {
            put("type", "error")
            put("stream_id", streamId)
            put("code", code)
            put("message", message)
        }.toString()
}
