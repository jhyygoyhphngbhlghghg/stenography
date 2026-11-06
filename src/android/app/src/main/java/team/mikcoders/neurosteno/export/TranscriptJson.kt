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
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(file: TranscriptFile): String = json.encodeToString(file)

    fun decode(raw: String): TranscriptFile = json.decodeFromString(raw)
}
