package team.mikcoders.neurosteno.present

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Path B oracle: `s{N:02d}` ↔ `speaker_{N}` for N=1..20.
 * `s01` / `speaker_1` is main (WER_main). Do not recluster across streams.
 */
data class StreamLabel(
    val streamId: String,
    val speakerId: String,
    val role: String,
    val evalOffsetMs: Long,
    val clockOffsetMs: Long = 0L,
) {
    val isMain: Boolean
        get() = role == "main" || speakerId == MAIN_SPEAKER || streamId == MAIN_STREAM

    companion object {
        const val MAIN_SPEAKER = "speaker_1"
        const val MAIN_STREAM = "s01"
        const val ROLE_MAIN = "main"
        const val ROLE_BABBLE = "babble"
    }
}

class StreamLabeler(
    private val table: Map<String, StreamLabel> = oracleTable(),
) {
    fun bind(
        streamId: String,
        speakerHint: String? = null,
        clockOffsetMs: Long? = null,
    ): StreamLabel {
        require(streamId.isNotBlank()) { "stream_id required" }
        val listed = table[streamId]
        val speaker = when {
            !speakerHint.isNullOrBlank() -> normalizeSpeaker(speakerHint)
            listed != null -> listed.speakerId
            else -> speakerFromStreamId(streamId)
        }
        val role = when {
            speaker == StreamLabel.MAIN_SPEAKER || streamId == StreamLabel.MAIN_STREAM -> StreamLabel.ROLE_MAIN
            listed != null -> listed.role
            else -> StreamLabel.ROLE_BABBLE
        }
        // Eval dry wavs already sit on the session clock. Additive shift only
        // when stream.start sends an explicit offset (PCM is per-stream zero).
        val evalOffset = listed?.evalOffsetMs ?: defaultEvalOffset(streamId)
        return StreamLabel(
            streamId = streamId,
            speakerId = speaker,
            role = role,
            evalOffsetMs = evalOffset,
            clockOffsetMs = clockOffsetMs ?: 0L,
        )
    }

    fun speakerId(streamId: String): String = bind(streamId).speakerId

    companion object {
        const val STAGGER_MS = 90L
        const val MAX_STREAMS = 20
        private val SNN = Regex("""^s0*(\d+)$""", RegexOption.IGNORE_CASE)
        private val SPEAKER_N = Regex("""^speaker[_-]?(\d+)$""", RegexOption.IGNORE_CASE)
        private val json = Json { ignoreUnknownKeys = true }

        fun oracle(): StreamLabeler = StreamLabeler(oracleTable())

        fun oracleTable(): Map<String, StreamLabel> =
            (1..MAX_STREAMS).associate { n ->
                val sid = "s%02d".format(n)
                sid to StreamLabel(
                    streamId = sid,
                    speakerId = "speaker_$n",
                    role = if (n == 1) StreamLabel.ROLE_MAIN else StreamLabel.ROLE_BABBLE,
                    evalOffsetMs = (n - 1) * STAGGER_MS,
                )
            }

        fun speakerFromStreamId(streamId: String): String {
            val m = SNN.matchEntire(streamId.trim()) ?: return "speaker_unknown"
            return "speaker_${m.groupValues[1].toInt()}"
        }

        fun normalizeSpeaker(hint: String): String {
            val t = hint.trim()
            SPEAKER_N.matchEntire(t)?.let { return "speaker_${it.groupValues[1].toInt()}" }
            t.toIntOrNull()?.let { return "speaker_$it" }
            SNN.matchEntire(t)?.let { return "speaker_${it.groupValues[1].toInt()}" }
            return t
        }

        fun defaultEvalOffset(streamId: String): Long {
            val m = SNN.matchEntire(streamId.trim()) ?: return 0L
            val n = m.groupValues[1].toInt()
            return if (n >= 1) (n - 1) * STAGGER_MS else 0L
        }

        fun fromChannelsJson(raw: String): StreamLabeler {
            val root = json.parseToJsonElement(raw).jsonObject
            val channels = root["channels"]?.jsonArray ?: return oracle()
            val map = LinkedHashMap<String, StreamLabel>()
            for (el in channels) {
                val o = el as? JsonObject ?: continue
                val sid = o["stream_id"]?.jsonPrimitive?.contentOrNull ?: continue
                val speaker = o["speaker_id"]?.jsonPrimitive?.contentOrNull
                    ?: speakerFromStreamId(sid)
                val role = o["role"]?.jsonPrimitive?.contentOrNull
                    ?: if (speaker == StreamLabel.MAIN_SPEAKER) StreamLabel.ROLE_MAIN else StreamLabel.ROLE_BABBLE
                val offset = o["offset_ms"]?.jsonPrimitive?.longOrNull ?: defaultEvalOffset(sid)
                map[sid] = StreamLabel(sid, speaker, role, offset)
            }
            return StreamLabeler(map.ifEmpty { oracleTable() })
        }
    }
}
