package team.mikcoders.neurosteno.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Brief JSON item: speaker_id, start_ms, end_ms, text. */
@Serializable
data class Utterance(
    @SerialName("speaker_id") val speakerId: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val text: String,
)

@Serializable
data class TranscriptFile(
    val transcript: List<Utterance>,
)

object TranscriptJson {
    val json: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun encode(file: TranscriptFile): String = json.encodeToString(file)

    fun decode(raw: String): TranscriptFile = json.decodeFromString(raw)

    /** True when the payload has only the four brief fields per utterance. */
    fun matchesBriefShape(raw: String): Boolean {
        val element = json.parseToJsonElement(raw)
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return false
        if (obj.keys != setOf("transcript")) return false
        val arr = obj["transcript"] as? kotlinx.serialization.json.JsonArray ?: return false
        val allowed = setOf("speaker_id", "start_ms", "end_ms", "text")
        return arr.all { item ->
            val o = item as? kotlinx.serialization.json.JsonObject ?: return@all false
            o.keys == allowed
        }
    }
}
