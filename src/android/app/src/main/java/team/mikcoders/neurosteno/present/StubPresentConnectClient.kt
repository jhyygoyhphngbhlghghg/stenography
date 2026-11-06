package team.mikcoders.neurosteno.present

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.Utterance

/** No-op worker until OkHttp WebSocket is wired. */
class StubPresentConnectClient : PresentConnectClient {
    private val _events = MutableSharedFlow<PresentConnectEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<PresentConnectEvent> = _events

    @Volatile
    private var streams: Int = 0

    override suspend fun connect(config: PresentConnectConfig) {
        _events.emit(PresentConnectEvent.HelloOk(config.sessionId, workerId = "stub-worker"))
    }

    override suspend fun disconnect() {
        streams = 0
        _events.emit(PresentConnectEvent.Disconnected)
    }

    override fun activeStreamCount(): Int = streams

    override fun sendTranscriptFinal(streamId: String, file: TranscriptFile) = Unit

    override fun sendTranscriptPartial(streamId: String, utterance: Utterance) = Unit

    override fun sendError(streamId: String?, code: String, message: String) = Unit
}
