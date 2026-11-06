package team.mikcoders.neurosteno.present

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Path B worker brain: map server frames onto [PresentStreamRegistry].
 * PCM for stream A never enters stream B's buffer.
 */
class PresentConnectProtocol(
    val registry: PresentStreamRegistry = PresentStreamRegistry(),
    private val sessionIdFallback: String = "",
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun onText(text: String): List<Outbound> {
        val obj = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
            ?: return emptyList()
        return when (obj.str("type")) {
            "hello.ok" -> listOf(
                Outbound.Raise(
                    PresentConnectEvent.HelloOk(
                        obj.str("session_id") ?: sessionIdFallback,
                        obj.str("worker_id") ?: "",
                    ),
                ),
            )
            "ping" -> {
                val t = obj["t"] ?: JsonPrimitive(0)
                listOf(Outbound.Send(PresentConnectFrames.pong(t)))
            }
            "stream.start" -> onStart(obj)
            "audio.pcm16" -> onPcm(obj)
            "stream.end" -> onEnd(obj.str("stream_id"))
            "stream.cancel" -> onCancel(obj.str("stream_id"))
            else -> emptyList()
        }
    }

    fun disconnect() {
        registry.clear()
    }

    private fun onStart(obj: JsonObject): List<Outbound> {
        val id = obj.str("stream_id") ?: return emptyList()
        val encoding = obj.str("encoding")
        val sr = obj["sample_rate"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val hint = obj.speakerHint()
        return when (val r = registry.tryStart(id, encoding, sr, hint)) {
            is PresentStreamRegistry.StartResult.Accepted -> {
                if (r.alreadyOpen) {
                    listOf(Outbound.Raise(PresentConnectEvent.StreamStarted(id)))
                } else {
                    listOf(Outbound.Raise(PresentConnectEvent.StreamStarted(id)))
                }
            }
            is PresentStreamRegistry.StartResult.OverCapacity -> listOf(
                Outbound.Send(
                    PresentConnectFrames.error(id, PresentConnectCodes.OVER_CAPACITY, "max ${registry.maxStreams} streams"),
                ),
                Outbound.Raise(PresentConnectEvent.OverCapacity(id)),
            )
            is PresentStreamRegistry.StartResult.Rejected -> listOf(
                Outbound.Send(PresentConnectFrames.error(id, r.code, r.message)),
                Outbound.Raise(PresentConnectEvent.Error(id, r.code, r.message)),
            )
        }
    }

    private fun onPcm(obj: JsonObject): List<Outbound> {
        val id = obj.str("stream_id") ?: return emptyList()
        val b64 = obj.str("pcm16_b64") ?: return emptyList()
        val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return emptyList()
        return when (val r = registry.appendPcm(id, bytes)) {
            is PresentStreamRegistry.AppendResult.UnknownStream -> listOf(
                Outbound.Raise(
                    PresentConnectEvent.Error(id, PresentConnectCodes.UNKNOWN_STREAM, "no start"),
                ),
            )
            is PresentStreamRegistry.AppendResult.Ok -> {
                val out = ArrayList<Outbound>(1)
                val snap = r.partialSnapshot
                if (snap != null) {
                    out += Outbound.Raise(
                        PresentConnectEvent.PartialDue(
                            streamId = id,
                            pcmS16le = snap,
                            speakerHint = r.session.speakerHint,
                        ),
                    )
                }
                out
            }
        }
    }

    private fun onEnd(id: String?): List<Outbound> {
        if (id == null) return emptyList()
        val session = registry.takeForEnd(id) ?: return emptyList()
        val pcm = session.snapshot()
        return listOf(
            Outbound.Send(PresentConnectFrames.streamClosed(id)),
            Outbound.Raise(PresentConnectEvent.StreamEnded(id, pcm, session.speakerHint)),
        )
    }

    private fun onCancel(id: String?): List<Outbound> {
        if (id == null) return emptyList()
        registry.cancel(id)
        return listOf(Outbound.Raise(PresentConnectEvent.StreamEnded(id, ByteArray(0), null)))
    }
}

sealed class Outbound {
    data class Send(val json: String) : Outbound()
    data class Raise(val event: PresentConnectEvent) : Outbound()
}

private fun JsonObject.speakerHint(): String? {
    val el = this["speaker_hint"] ?: return null
    if (el is JsonNull) return null
    return el.jsonPrimitive.contentOrNull
}
