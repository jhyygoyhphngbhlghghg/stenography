package team.mikcoders.neurosteno.present

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.TranscriptJson
import team.mikcoders.neurosteno.export.Utterance

/** Outbound WebSocket JSON from docs/PRESENT_CONNECT.md (Path B: always include stream_id). */
object PresentConnectFrames {
    private val json = Json { ignoreUnknownKeys = true }

    fun hello(
        maxStreams: Int = PresentConnectLimits.MAX_STREAMS,
        sampleRate: Int = PresentConnectLimits.SAMPLE_RATE,
    ): String = buildJsonObject {
        put("type", "hello")
        put("protocol", PresentConnectLimits.PROTOCOL)
        put("max_streams", maxStreams)
        put("sample_rate", sampleRate)
        put("encoding", PresentConnectLimits.ENCODING)
        put("models", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("zipformer-ru-int8")) })
        put("accepts_diarization", true)
    }.toString()

    fun pong(t: kotlinx.serialization.json.JsonElement): String =
        buildJsonObject {
            put("type", "pong")
            put("t", t)
        }.toString()

    fun streamClosed(streamId: String): String = buildJsonObject {
        put("type", "stream.closed")
        put("stream_id", streamId)
    }.toString()

    fun transcriptPartial(streamId: String, utterance: Utterance): String = buildJsonObject {
        put("type", "transcript.partial")
        put("stream_id", streamId)
        put("speaker_id", utterance.speakerId)
        put("start_ms", utterance.startMs)
        put("end_ms", utterance.endMs)
        put("text", utterance.text)
    }.toString()

    fun transcriptFinal(streamId: String, file: TranscriptFile): List<String> =
        file.transcript.map { u ->
            val utteranceEl = json.parseToJsonElement(TranscriptJson.encode(TranscriptFile(listOf(u))))
                .let { (it as JsonObject)["transcript"] as JsonArray }[0]
            buildJsonObject {
                put("type", "transcript.final")
                put("stream_id", streamId)
                put("utterance", utteranceEl)
            }.toString()
        }

    fun error(streamId: String?, code: String, message: String): String =
        PresentConnectLimits.errorJson(streamId, code, message)
}

internal fun JsonObject.str(key: String): String? {
    val el = this[key] ?: return null
    if (el is kotlinx.serialization.json.JsonNull) return null
    return runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()
}
