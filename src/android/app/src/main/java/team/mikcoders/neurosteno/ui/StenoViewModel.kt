package team.mikcoders.neurosteno.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.mikcoders.neurosteno.BuildConfig
import team.mikcoders.neurosteno.audio.AudioDecoder
import team.mikcoders.neurosteno.audio.MicRecorder
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.TranscriptJson
import team.mikcoders.neurosteno.pipeline.PipelineFactory
import team.mikcoders.neurosteno.pipeline.PipelineProgress
import team.mikcoders.neurosteno.pipeline.PipelineStage
import team.mikcoders.neurosteno.pipeline.PcmAudio
import team.mikcoders.neurosteno.pipeline.StenoPipeline
import team.mikcoders.neurosteno.present.IsolatedResult
import team.mikcoders.neurosteno.present.OkHttpPresentConnectClient
import team.mikcoders.neurosteno.present.PresentConnectCodes
import team.mikcoders.neurosteno.present.PresentConnectConfig
import team.mikcoders.neurosteno.present.PresentConnectEvent
import team.mikcoders.neurosteno.present.PresentConnectLimits
import team.mikcoders.neurosteno.present.PresentStreamTranscriber
import java.util.concurrent.ConcurrentHashMap

data class StenoUiState(
    val fileLabel: String? = null,
    val importedUri: String? = null,
    val running: Boolean = false,
    val progress: PipelineProgress? = null,
    val transcript: TranscriptFile? = null,
    val jsonPreview: String? = null,
    val error: String? = null,
    val stubPath: Boolean = true,
    val presentConnect: Boolean = BuildConfig.PRESENT_CONNECT,
    val engineName: String = "stub",
    val modelsReady: Boolean = false,
    val recording: Boolean = false,
    val heldPcm: PcmAudio? = null,
    val presentWsUrl: String = "ws://10.0.2.2:8080/v1/present-connect",
    val presentSession: String = "sess_demo",
    val presentConnected: Boolean = false,
    val presentStreams: Int = 0,
    val presentWorkerId: String = "",
    val presentLastStreamId: String = "",
)

class StenoViewModel(app: Application) : AndroidViewModel(app) {
    private val created = PipelineFactory.create(app)
    private val pipeline: StenoPipeline = created.first
    private val models = created.second

    private val _state = MutableStateFlow(
        StenoUiState(
            engineName = pipeline.displayName,
            stubPath = pipeline is team.mikcoders.neurosteno.pipeline.StubStenoPipeline,
            modelsReady = models.readyForOfflineDemo,
        ),
    )
    val state: StateFlow<StenoUiState> = _state
    private val mic = MicRecorder()
    private val present = if (BuildConfig.PRESENT_CONNECT) OkHttpPresentConnectClient() else null
    private val streamAsr = PresentStreamTranscriber(
        pipeline,
        modelsMissing = pipeline is team.mikcoders.neurosteno.pipeline.StubStenoPipeline,
    )
    private val partialInFlight = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var modelsMissingSent = false

    init {
        val client = present
        if (client != null) {
            viewModelScope.launch {
                client.events.collect { ev ->
                    when (ev) {
                        is PresentConnectEvent.HelloOk -> _state.update {
                            it.copy(presentConnected = true, presentWorkerId = ev.workerId, error = null)
                        }
                        is PresentConnectEvent.Disconnected -> {
                            modelsMissingSent = false
                            _state.update { it.copy(presentConnected = false, presentStreams = 0) }
                        }
                        is PresentConnectEvent.StreamStarted -> _state.update {
                            it.copy(
                                presentStreams = client.activeStreamCount(),
                                presentLastStreamId = ev.streamId,
                            )
                        }
                        is PresentConnectEvent.PartialDue -> {
                            transcribeStream(ev.streamId, ev.pcmS16le, ev.speakerHint, final = false)
                        }
                        is PresentConnectEvent.StreamEnded -> {
                            _state.update {
                                it.copy(
                                    presentStreams = client.activeStreamCount(),
                                    presentLastStreamId = ev.streamId,
                                )
                            }
                            if (ev.pcmS16le.isNotEmpty()) {
                                transcribeStream(ev.streamId, ev.pcmS16le, ev.speakerHint, final = true)
                            }
                        }
                        is PresentConnectEvent.OverCapacity -> _state.update {
                            it.copy(error = "OVER_CAPACITY: поток ${ev.rejectedStreamId} (уже ${PresentConnectLimits.MAX_STREAMS})")
                        }
                        is PresentConnectEvent.Error -> _state.update {
                            it.copy(error = "${ev.code}: ${ev.message}")
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    fun onPresentWsUrl(url: String) {
        _state.update { it.copy(presentWsUrl = url) }
    }

    fun onPresentSession(session: String) {
        _state.update { it.copy(presentSession = session) }
    }

    fun togglePresentConnect() {
        val client = present ?: return
        viewModelScope.launch {
            if (_state.value.presentConnected) {
                client.disconnect()
            } else {
                client.connect(
                    PresentConnectConfig(
                        wsUrl = _state.value.presentWsUrl,
                        httpBaseUrl = _state.value.presentWsUrl.substringBefore("/v1").replace("ws://", "http://").replace("wss://", "https://"),
                        sessionId = _state.value.presentSession,
                        deviceId = "phone-demo",
                    ),
                )
            }
        }
    }

    /**
     * Path B: ASR+diar **this** stream's PCM only. Never mix with other stream_id buffers.
     * [speakerHint] is seat/QR metadata from BE; speaker_id in the utterance is diarization
     * inside the stream. Web maps stream_id → seat.
     */
    private fun transcribeStream(
        streamId: String,
        pcmS16le: ByteArray,
        speakerHint: String?,
        final: Boolean,
    ) {
        if (!final && !partialInFlight.add(streamId)) return
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    streamAsr.transcribeIsolated(streamId, pcmS16le, speakerHint) { p ->
                        _state.update { it.copy(progress = p, running = true) }
                    }
                }
                when (result) {
                    is IsolatedResult.ModelsMissing -> {
                        if (!modelsMissingSent) {
                            modelsMissingSent = true
                            present?.sendError(
                                streamId,
                                PresentConnectCodes.MODELS_MISSING,
                                "ONNX missing — stub worker, not for stage",
                            )
                        }
                        _state.update {
                            it.copy(
                                running = false,
                                error = "${PresentConnectCodes.MODELS_MISSING}: положите Zipformer-ru на устройство",
                            )
                        }
                    }
                    is IsolatedResult.Empty -> _state.update { it.copy(running = false) }
                    is IsolatedResult.Rejected -> {
                        present?.sendError(streamId, result.code, result.message)
                        _state.update {
                            it.copy(running = false, error = "${result.code}: ${result.message}")
                        }
                    }
                    is IsolatedResult.Ok -> {
                        if (final) {
                            present?.sendTranscriptFinal(streamId, result.file)
                            _state.update {
                                it.copy(
                                    running = false,
                                    transcript = result.file,
                                    jsonPreview = TranscriptJson.encode(result.file),
                                    presentLastStreamId = streamId,
                                )
                            }
                        } else {
                            result.file.transcript.lastOrNull()?.let { u ->
                                present?.sendTranscriptPartial(streamId, u)
                            }
                            _state.update { it.copy(running = false, presentLastStreamId = streamId) }
                        }
                    }
                }
            } catch (t: Throwable) {
                present?.sendError(streamId, PresentConnectCodes.PIPELINE, t.message ?: "pipeline")
                _state.update { it.copy(running = false, error = t.message ?: "pipeline") }
            } finally {
                if (!final) partialInFlight.remove(streamId)
            }
        }
    }

    fun onFilePicked(uri: Uri, displayName: String?) {
        _state.update {
            it.copy(
                fileLabel = displayName ?: uri.lastPathSegment ?: uri.toString(),
                importedUri = uri.toString(),
                error = null,
                transcript = null,
                jsonPreview = null,
                heldPcm = null,
            )
        }
    }

    fun toggleRecord() {
        if (mic.isRecording) {
            val audio = mic.stop()
            _state.update {
                it.copy(
                    recording = false,
                    heldPcm = audio,
                    fileLabel = "mic ${audio.durationMs} ms",
                    importedUri = null,
                )
            }
        } else {
            mic.start()
            _state.update { it.copy(recording = true, error = null) }
        }
    }

    fun transcribe() {
        val label = _state.value.fileLabel ?: "mic-or-unknown"
        if (_state.value.running) return
        viewModelScope.launch {
            _state.update { it.copy(running = true, error = null, progress = PipelineProgress(PipelineStage.Decode, "start", 0f)) }
            try {
                val audio = withContext(Dispatchers.Default) {
                    val held = _state.value.heldPcm
                    val uriStr = _state.value.importedUri
                    when {
                        held != null && uriStr == null -> held
                        uriStr != null -> AudioDecoder.decode(getApplication(), Uri.parse(uriStr), label)
                        else -> PcmAudio(
                            samples = FloatArray(16_000 * 4),
                            sampleRate = 16_000,
                            sourceName = label,
                        )
                    }
                }
                val result = pipeline.transcribe(audio) { p ->
                    _state.update { it.copy(progress = p) }
                }
                _state.update {
                    it.copy(
                        running = false,
                        transcript = result,
                        jsonPreview = TranscriptJson.encode(result),
                        stubPath = pipeline is team.mikcoders.neurosteno.pipeline.StubStenoPipeline,
                        engineName = pipeline.displayName,
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(running = false, error = t.message ?: t::class.java.simpleName)
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
