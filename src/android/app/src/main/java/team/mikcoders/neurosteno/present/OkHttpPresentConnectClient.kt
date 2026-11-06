package team.mikcoders.neurosteno.present

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Path B WebSocket worker: cap 20, **per-stream** PCM → ASR, oracle speaker_id.
 * Never mixes open streams into one cocktail buffer.
 */
class OkHttpPresentConnectClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
    private val pathB: PathBSession? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PresentConnectClient {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)
    private val _events = MutableSharedFlow<PresentConnectEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<PresentConnectEvent> = _events

    private val json = Json { ignoreUnknownKeys = true }
    private val openStreams = ConcurrentHashMap<String, StreamBuf>()
    private var table = PresentConnectStreamTable(20)
    private var socket: WebSocket? = null
    private var maxStreams: Int = 20

    override suspend fun connect(config: PresentConnectConfig) {
        disconnect()
        maxStreams = config.maxStreams
        table = PresentConnectStreamTable(config.maxStreams)
        val req = Request.Builder()
            .url(config.wsUrl)
            .build()
        socket = http.newWebSocket(req, Listener(config))
    }

    override suspend fun disconnect() {
        job.cancelChildren()
        socket?.close(1000, "bye")
        socket = null
        openStreams.clear()
        table.clear()
        _events.tryEmit(PresentConnectEvent.Disconnected)
    }

    override fun activeStreamCount(): Int = table.activeCount()

    private inner class Listener(private val config: PresentConnectConfig) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val hello = buildJsonObject {
                put("type", "hello")
                put("protocol", "present-connect/1")
                put("max_streams", config.maxStreams)
                put("sample_rate", config.sampleRate)
                put("encoding", "pcm_s16le")
                put("models", JsonPrimitive("zipformer-ru-int8"))
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
                "ping" -> webSocket.send("""{"type":"pong","t":${obj["t"] ?: 0}}""")
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
        if (obj.str("encoding") != null && obj.str("encoding") != "pcm_s16le") {
            ws.send(
                """{"type":"error","stream_id":"$id","code":"${PresentConnectCodes.BAD_ENCODING}","message":"need pcm_s16le"}""",
            )
            return
        }
        when (val admitted = table.tryStart(id)) {
            is AdmitResult.OverCapacity -> {
                ws.send(
                    """{"type":"error","stream_id":"$id","code":"${PresentConnectCodes.OVER_CAPACITY}","message":"max $maxStreams streams"}""",
                )
                _events.tryEmit(PresentConnectEvent.OverCapacity(id))
            }
            is AdmitResult.Accepted -> {
                val hint = obj.str("speaker_hint")
                val clock = obj.long("offset_ms")
                val label = pathB?.bindStart(id, hint, clock) ?: StreamLabeler.oracle().bind(id, hint, clock)
                if (!admitted.duplicate) {
                    openStreams[id] = StreamBuf(label)
                }
                _events.tryEmit(PresentConnectEvent.StreamStarted(id, label.speakerId, label.role))
            }
        }
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
        table.end(id)
        val buf = openStreams.remove(id) ?: return
        val pcm = synchronized(buf) { buf.pcm.toByteArray() }
        val session = pathB
        if (session == null) {
            ws.send("""{"type":"stream.closed","stream_id":"$id"}""")
            _events.tryEmit(PresentConnectEvent.StreamEnded(id, buf.label.speakerId))
            return
        }
        scope.launch {
            try {
                val result = session.finish(id, pcm)
                for (frame in result.wireJson) {
                    ws.send(frame)
                }
                for (u in result.utterances) {
                    _events.tryEmit(
                        PresentConnectEvent.Final(id, u.speakerId, u.startMs, u.endMs, u.text),
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Path B ASR $id", t)
                ws.send(
                    buildJsonObject {
                        put("type", "error")
                        put("stream_id", id)
                        put("code", PresentConnectCodes.PIPELINE)
                        put("message", t.message ?: "asr")
                    }.toString(),
                )
                _events.tryEmit(PresentConnectEvent.Error(id, PresentConnectCodes.PIPELINE, t.message ?: "asr"))
            } finally {
                session.unbind(id)
                ws.send("""{"type":"stream.closed","stream_id":"$id"}""")
                _events.tryEmit(PresentConnectEvent.StreamEnded(id, buf.label.speakerId))
            }
        }
    }

    private fun onCancel(id: String?) {
        if (id == null) return
        table.end(id)
        openStreams.remove(id)
        pathB?.unbind(id)
        _events.tryEmit(PresentConnectEvent.StreamEnded(id))
    }

    private class StreamBuf(val label: StreamLabel) {
        val pcm = java.io.ByteArrayOutputStream()
    }

    companion object {
        private const val TAG = "PresentConnect"
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

@Suppress("unused")
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

@Suppress("unused")
private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
