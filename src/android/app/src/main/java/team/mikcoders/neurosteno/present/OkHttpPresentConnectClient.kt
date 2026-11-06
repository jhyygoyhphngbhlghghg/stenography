package team.mikcoders.neurosteno.present

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.TranscriptJson
import team.mikcoders.neurosteno.export.Utterance
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket worker from docs/PRESENT_CONNECT.md.
 * Hard cap [PresentConnectLimits.MAX_STREAMS] (20). Stream 21 → OVER_CAPACITY.
 */
class OkHttpPresentConnectClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) : PresentConnectClient {
    private val _events = MutableSharedFlow<PresentConnectEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<PresentConnectEvent> = _events

    private val json = Json { ignoreUnknownKeys = true }
    private val openStreams = ConcurrentHashMap<String, StreamBuf>()
    private val count = AtomicInteger(0)
    private var socket: WebSocket? = null
    private var maxStreams: Int = PresentConnectLimits.MAX_STREAMS

    override suspend fun connect(config: PresentConnectConfig) {
        disconnect()
        maxStreams = config.maxStreams.coerceAtMost(PresentConnectLimits.MAX_STREAMS)
        val req = Request.Builder()
            .url(config.wsUrl)
            .build()
        socket = http.newWebSocket(req, Listener(config))
    }

    override suspend fun disconnect() {
        socket?.close(1000, "bye")
        socket = null
        openStreams.clear()
        count.set(0)
        _events.tryEmit(PresentConnectEvent.Disconnected)
    }

    override fun activeStreamCount(): Int = count.get()

    override fun sendTranscriptFinal(streamId: String, file: TranscriptFile) {
        val ws = socket ?: return
        file.transcript.forEach { u ->
            val utterance = json.parseToJsonElement(TranscriptJson.encode(TranscriptFile(listOf(u))))
                .let { (it as JsonObject)["transcript"] as JsonArray }[0]
            val body = buildJsonObject {
                put("type", "transcript.final")
                put("stream_id", streamId)
                put("utterance", utterance)
            }
            ws.send(body.toString())
        }
    }

    override fun sendTranscriptPartial(streamId: String, utterance: Utterance) {
        val ws = socket ?: return
        val body = buildJsonObject {
            put("type", "transcript.partial")
            put("stream_id", streamId)
            put("speaker_id", utterance.speakerId)
            put("start_ms", utterance.startMs)
            put("end_ms", utterance.endMs)
            put("text", utterance.text)
        }
        ws.send(body.toString())
    }

    /**
     * HTTP fallback from the contract: POST /v1/workers/register.
     * WebSocket demo must not depend on this.
     */
    fun registerWorker(config: PresentConnectConfig): String? {
        val payload = buildJsonObject {
            put("device_id", config.deviceId)
            put("max_streams", config.maxStreams)
            put("models", buildJsonArray { add(JsonPrimitive("zipformer-ru-int8")) })
        }.toString()
        val req = Request.Builder()
            .url(config.httpBaseUrl.trimEnd('/') + "/v1/workers/register")
            .post(payload.toRequestBody(JSON))
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            resp.body?.string()
        }
    }

    private inner class Listener(private val config: PresentConnectConfig) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val hello = buildJsonObject {
                put("type", "hello")
                put("protocol", PresentConnectLimits.PROTOCOL)
                put("max_streams", config.maxStreams)
                put("sample_rate", config.sampleRate)
                put("encoding", PresentConnectLimits.ENCODING)
                put("models", buildJsonArray { add(JsonPrimitive("zipformer-ru-int8")) })
                put("accepts_diarization", true)
            }
            webSocket.send(hello.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
            when (obj.str("type")) {
                "hello.ok" -> _events.tryEmit(
                    PresentConnectEvent.HelloOk(obj.str("session_id") ?: config.sessionId, obj.str("worker_id") ?: ""),
                )
                "ping" -> {
                    val t = obj["t"] ?: JsonPrimitive(0)
                    webSocket.send(buildJsonObject { put("type", "pong"); put("t", t) }.toString())
                }
                "stream.start" -> onStart(webSocket, obj)
                "audio.pcm16" -> onPcm(obj)
                "stream.end" -> onEnd(webSocket, obj.str("stream_id"))
                "stream.cancel" -> onCancel(obj.str("stream_id"))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _events.tryEmit(PresentConnectEvent.Disconnected)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "ws failure", t)
            _events.tryEmit(PresentConnectEvent.Error(null, PresentConnectCodes.PIPELINE, t.message ?: "ws"))
        }
    }

    private fun onStart(ws: WebSocket, obj: JsonObject) {
        val id = obj.str("stream_id") ?: return
        val encoding = obj.str("encoding")
        if (encoding != null && encoding != PresentConnectLimits.ENCODING) {
            ws.send(PresentConnectLimits.errorJson(id, PresentConnectCodes.BAD_ENCODING, "need pcm_s16le"))
            return
        }
        val sr = obj["sample_rate"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (sr != null && sr != PresentConnectLimits.SAMPLE_RATE) {
            ws.send(PresentConnectLimits.errorJson(id, PresentConnectCodes.BAD_ENCODING, "need 16 kHz"))
            return
        }
        if (!PresentConnectLimits.canAccept(count.get(), maxStreams)) {
            ws.send(PresentConnectLimits.errorJson(id, PresentConnectCodes.OVER_CAPACITY, "max $maxStreams streams"))
            _events.tryEmit(PresentConnectEvent.OverCapacity(id))
            return
        }
        openStreams[id] = StreamBuf()
        count.incrementAndGet()
        _events.tryEmit(PresentConnectEvent.StreamStarted(id))
    }

    private fun onPcm(obj: JsonObject) {
        val id = obj.str("stream_id") ?: return
        val buf = openStreams[id]
        if (buf == null) {
            _events.tryEmit(PresentConnectEvent.Error(id, PresentConnectCodes.UNKNOWN_STREAM, "no start"))
            return
        }
        val b64 = obj.str("pcm16_b64") ?: return
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        synchronized(buf) { buf.pcm.write(bytes) }
    }

    private fun onEnd(ws: WebSocket, id: String?) {
        if (id == null) return
        val buf = openStreams.remove(id) ?: return
        count.updateAndGet { n -> (n - 1).coerceAtLeast(0) }
        val pcm = synchronized(buf) { buf.pcm.toByteArray() }
        ws.send(buildJsonObject {
            put("type", "stream.closed")
            put("stream_id", id)
        }.toString())
        _events.tryEmit(PresentConnectEvent.StreamEnded(id, pcm))
    }

    private fun onCancel(id: String?) {
        if (id == null) return
        if (openStreams.remove(id) != null) {
            count.updateAndGet { n -> (n - 1).coerceAtLeast(0) }
        }
        _events.tryEmit(PresentConnectEvent.StreamEnded(id, ByteArray(0)))
    }

    private class StreamBuf {
        val pcm = java.io.ByteArrayOutputStream()
    }

    companion object {
        private const val TAG = "PresentConnect"
        private val JSON = "application/json; charset=utf-8".toMediaType()

    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
