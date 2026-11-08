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
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
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
        val builder = Request.Builder()
            .url(config.wsUrl)
        if (config.phoneToken.isNotBlank()) {
            builder.header("Authorization", "Bearer ${config.phoneToken.trim()}")
        }
        val req = builder.build()
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
                when (val verdict = result.quality.verdict) {
                    is StreamGateVerdict.Reject -> {
                        _events.tryEmit(PresentConnectEvent.Error(id, verdict.code, verdict.message))
                        _events.tryEmit(
                            PresentConnectEvent.MicQuality(
                                streamId = id,
                                speakerId = result.label.speakerId,
                                code = verdict.code,
                                hardReject = true,
                            ),
                        )
                    }
                    StreamGateVerdict.Pass -> {
                        for (u in result.utterances) {
                            _events.tryEmit(
                                PresentConnectEvent.Final(id, u.speakerId, u.startMs, u.endMs, u.text),
                            )
                        }
                        if (result.quality.softFlags.isNotEmpty()) {
                            Log.i(
                                TAG,
                                "Path B soft mic $id ${result.quality.softFlags} rms_fs=${result.quality.rmsFs}",
                            )
                            _events.tryEmit(
                                PresentConnectEvent.MicQuality(
                                    streamId = id,
                                    speakerId = result.label.speakerId,
                                    code = result.quality.softFlags.first(),
                                    hardReject = false,
                                    flags = result.quality.softFlags,
                                ),
                            )
                        }
                    }
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

/**
 * Phone-side ingest for the single-phone demo.
 *
 * The presenter phone **owns the mic** (DEMO_STAGE6: one mic, 6 seats). This client
 * sends `hello` → `transcript.final` over the *same* WebSocket the web BE expects
 * from its `phone` role, and mirrors each utterance over HTTP `POST /v1/transcript`
 * as a fallback if the socket drops mid-utterance. Mirroring `src/simulate-phone.ts`,
 * but the utterance text is real on-device ASR+diar output — never simulated.
 *
 * Token is the `PHONE_TOKEN` from `/etc/neurosteno-web.env` (`isPhone()` in server.ts):
 * `Authorization: Bearer <token>` on both WS upgrade and HTTP ingest.
 */
class PhoneDemoClient(
    private val http: OkHttpClient = OkHttpClient.Builder().build(),
) : PresentConnectClient {
    private val _events = MutableSharedFlow<PresentConnectEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<PresentConnectEvent> = _events
    private var socket: WebSocket? = null
    private var activeUrl: String = ""
    private var activeToken: String = ""

    override suspend fun connect(config: PresentConnectConfig) {
        disconnect()
        activeUrl = config.wsUrl
        activeToken = config.phoneToken
        val builder = Request.Builder().url(config.wsUrl)
        if (config.phoneToken.isNotBlank()) {
            builder.header("Authorization", "Bearer ${config.phoneToken.trim()}")
        }
        socket = http.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = buildJsonObject {
                    put("type", "hello")
                    put("protocol", "present-connect/1")
                    put("max_streams", 20)
                    put("sample_rate", 16_000)
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
                        PresentConnectEvent.HelloOk(obj.str("session_id") ?: config.sessionId, obj.str("worker_id") ?: config.deviceId),
                    )
                    "ping" -> webSocket.send("""{"type":"pong","t":${obj["t"] ?: 0}}""")
                    "error" -> _events.tryEmit(
                        PresentConnectEvent.Error(null, obj.str("code") ?: "ERROR", obj.str("message") ?: ""),
                    )
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _events.tryEmit(PresentConnectEvent.Disconnected)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "phone demo ws", t)
                _events.tryEmit(PresentConnectEvent.Error(null, PresentConnectCodes.PIPELINE, t.message ?: "ws"))
            }
        })
    }

    override suspend fun disconnect() {
        socket?.close(1000, "bye")
        socket = null
        _events.tryEmit(PresentConnectEvent.Disconnected)
    }

    override fun activeStreamCount(): Int = 0

    /** Send one diarized utterance as `transcript.final` (WS) + HTTP fallback mirror. */
    fun publishUtterance(streamId: String, utterance: team.mikcoders.neurosteno.export.Utterance) {
        val ws = socket
        val wire = PresentConnectWire.transcriptFinal(streamId, utterance)
        if (ws != null && ws.send(wire)) return
        // WS down mid-utterance: mirror over HTTP so the checkpoint still advances.
        if (activeUrl.isNotBlank()) {
            val base = activeUrl.substringBefore("/v1").ifBlank { activeUrl }
            val payload = """{"engine":"zipformer-ru-int8","transcript":[{"speaker_id":"${escape(utterance.speakerId)}","start_ms":${utterance.startMs},"end_ms":${utterance.endMs},"text":"${escape(utterance.text)}"}]}"""
            val rb = payload.toRequestBody("application/json".toMediaTypeOrNull())
            val req = okhttp3.Request.Builder().url("$base/v1/transcript").post(rb)
                .header("Content-Type", "application/json")
            if (activeToken.isNotBlank()) req.header("Authorization", "Bearer ${activeToken.trim()}")
            try {
                http.newCall(req.build()).execute().use { res ->
                    Log.i(TAG, "http transcript ${res.code}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "http transcript mirror failed", t)
            }
        }
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val TAG = "PhoneDemo"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

@Suppress("unused")
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

@Suppress("unused")
private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
