package team.mikcoders.neurosteno.present

import kotlinx.serialization.json.Json
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
     * ASR **this stream's PCM only**, force oracle speaker_id, optional clock shift.
     * Cancel path must not call this.
     */
    suspend fun finish(streamId: String, pcmS16le: ByteArray): PathBFinish {
        val label = synchronized(lock) { labels[streamId] } ?: labeler.bind(streamId)
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
        return PathBFinish(label = label, utterances = labeled, wireJson = labeled.map { PresentConnectWire.transcriptFinal(streamId, it) })
    }

    fun brief(): TranscriptFile = synchronized(lock) { PresentConnectWire.brief(absorbed) }

    fun wireFinals(): List<WireFinal> = synchronized(lock) { wireFinals.toList() }

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
                asr = PathBAsr { streamId, audio ->
                    pipeline.transcribe(audio) { }.transcript
                },
            )
    }
}

data class PathBFinish(
    val label: StreamLabel,
    val utterances: List<Utterance>,
    val wireJson: List<String>,
)

data class WireFinal(
    val streamId: String,
    val utterance: Utterance,
)

object PresentConnectWire {
    private val json = Json { encodeDefaults = true }

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
}
