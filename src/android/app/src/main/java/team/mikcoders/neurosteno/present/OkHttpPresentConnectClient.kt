package team.mikcoders.neurosteno.present

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.Utterance
import java.util.concurrent.TimeUnit

/**
 * WebSocket worker from docs/PRESENT_CONNECT.md.
 * Path B: [PresentConnectProtocol] keeps ≤20 isolated PCM streams (no mixdown).
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

    private var protocol = PresentConnectProtocol()
    private var socket: WebSocket? = null
    private var maxStreams: Int = PresentConnectLimits.MAX_STREAMS

    override suspend fun connect(config: PresentConnectConfig) {
        disconnect()
        maxStreams = config.maxStreams.coerceAtMost(PresentConnectLimits.MAX_STREAMS)
        protocol = PresentConnectProtocol(
            registry = PresentStreamRegistry(maxStreams),
            sessionIdFallback = config.sessionId,
        )
        val req = Request.Builder()
            .url(config.wsUrl)
            .build()
        socket = http.newWebSocket(req, Listener(config))
    }

    override suspend fun disconnect() {
        socket?.close(1000, "bye")
        socket = null
        protocol.disconnect()
        _events.tryEmit(PresentConnectEvent.Disconnected)
    }

    override fun activeStreamCount(): Int = protocol.registry.activeCount()

    override fun sendTranscriptFinal(streamId: String, file: TranscriptFile) {
        val ws = socket ?: return
        PresentConnectFrames.transcriptFinal(streamId, file).forEach { ws.send(it) }
    }

    override fun sendTranscriptPartial(streamId: String, utterance: Utterance) {
        val ws = socket ?: return
        ws.send(PresentConnectFrames.transcriptPartial(streamId, utterance))
    }

    override fun sendError(streamId: String?, code: String, message: String) {
        val ws = socket ?: return
        ws.send(PresentConnectFrames.error(streamId, code, message))
    }

    override fun sendStreamClosed(streamId: String) {
        val ws = socket ?: return
        ws.send(PresentConnectFrames.streamClosed(streamId))
    }

    /**
     * HTTP fallback from the contract: POST /v1/workers/register.
     * WebSocket demo must not depend on this.
     */
    fun registerWorker(config: PresentConnectConfig): String? {
        val payload = buildJsonObject {
            put("device_id", config.deviceId)
            put("max_streams", config.maxStreams)
            put("models", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("zipformer-ru-int8")) })
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
            webSocket.send(PresentConnectFrames.hello(config.maxStreams, config.sampleRate))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            for (out in protocol.onText(text)) {
                when (out) {
                    is Outbound.Send -> webSocket.send(out.json)
                    is Outbound.Raise -> _events.tryEmit(out.event)
                }
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

    companion object {
        private const val TAG = "PresentConnect"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
