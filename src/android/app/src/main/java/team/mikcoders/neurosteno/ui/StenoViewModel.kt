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
import team.mikcoders.neurosteno.audio.Pcm
import team.mikcoders.neurosteno.pipeline.DemoPcmPrep
import team.mikcoders.neurosteno.pipeline.PipelineFactory
import team.mikcoders.neurosteno.pipeline.PipelineProgress
import team.mikcoders.neurosteno.pipeline.PipelineStage
import team.mikcoders.neurosteno.pipeline.PcmAudio
import team.mikcoders.neurosteno.pipeline.StenoPipeline
import team.mikcoders.neurosteno.present.OkHttpPresentConnectClient
import team.mikcoders.neurosteno.present.PresentConnectConfig
import team.mikcoders.neurosteno.present.PresentConnectEvent
import team.mikcoders.neurosteno.present.PresentConnectLimits

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

    init {
        val client = present
        if (client != null) {
            viewModelScope.launch {
                client.events.collect { ev ->
                    when (ev) {
                        is PresentConnectEvent.HelloOk -> _state.update {
                            it.copy(presentConnected = true, presentWorkerId = ev.workerId, error = null)
                        }
                        is PresentConnectEvent.Disconnected -> _state.update {
                            it.copy(presentConnected = false, presentStreams = 0)
                        }
                        is PresentConnectEvent.StreamStarted -> _state.update {
                            it.copy(presentStreams = client.activeStreamCount())
                        }
                        is PresentConnectEvent.StreamEnded -> {
                            _state.update { it.copy(presentStreams = client.activeStreamCount()) }
                            if (ev.pcmS16le.isNotEmpty()) {
                                transcribeStream(ev.streamId, ev.pcmS16le)
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

    private fun transcribeStream(streamId: String, pcmS16le: ByteArray) {
        viewModelScope.launch {
            try {
                val audio = withContext(Dispatchers.Default) {
                    PcmAudio(Pcm.s16leToFloat(pcmS16le), Pcm.TARGET_HZ, "stream-$streamId")
                }
                val result = pipeline.transcribe(audio) { p ->
                    _state.update { it.copy(progress = p, running = true) }
                }
                present?.sendTranscriptFinal(streamId, result)
                _state.update {
                    it.copy(
                        running = false,
                        transcript = result,
                        jsonPreview = TranscriptJson.encode(result),
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(running = false, error = t.message ?: "pipeline") }
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
                val prep = DemoPcmPrep.prepare(audio)
                if (prep.skipAsr) {
                    _state.update {
                        it.copy(
                            running = false,
                            error = prep.skipCode,
                            transcript = TranscriptFile(emptyList()),
                            jsonPreview = null,
                        )
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        progress = PipelineProgress(
                            PipelineStage.Decode,
                            "per-turn FarFieldGain +${"%.1f".format(prep.gain.gainDb)} dB (${prep.gain.segments.size} windows)",
                            0.08f,
                        ),
                    )
                }
                val result = pipeline.transcribe(prep.audio) { p ->
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
