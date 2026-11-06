package team.mikcoders.neurosteno.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.mikcoders.neurosteno.BuildConfig
import team.mikcoders.neurosteno.audio.AudioDecoder
import team.mikcoders.neurosteno.audio.MicRecorder
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.TranscriptJson
import team.mikcoders.neurosteno.pipeline.DemoPcmPrep
import team.mikcoders.neurosteno.pipeline.PipelineFactory
import team.mikcoders.neurosteno.pipeline.PipelineProgress
import team.mikcoders.neurosteno.pipeline.PipelineStage
import team.mikcoders.neurosteno.pipeline.PcmAudio
import team.mikcoders.neurosteno.pipeline.SherpaJni
import team.mikcoders.neurosteno.pipeline.StenoPipeline
import team.mikcoders.neurosteno.present.OkHttpPresentConnectClient
import team.mikcoders.neurosteno.present.PathBSession
import team.mikcoders.neurosteno.present.PresentConnectConfig
import team.mikcoders.neurosteno.present.PresentConnectEvent

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
    val jniLoaded: Boolean = false,
    val jniError: String? = null,
    val activeStreams: List<ActiveStreamChip> = emptyList(),
    val overCapacityId: String? = null,
    val presentConnected: Boolean = false,
    val wsUrl: String = "wss://present.example.local/v1/present-connect?session=demo&role=worker&device=phone",
    val micFlags: List<MicFlagChip> = emptyList(),
)

data class ActiveStreamChip(
    val streamId: String,
    val speakerId: String,
    val role: String,
    val qualityCode: String? = null,
)

data class MicFlagChip(
    val streamId: String,
    val speakerId: String,
    val code: String,
    val hardReject: Boolean,
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
            jniLoaded = SherpaJni.last.loaded,
            jniError = SherpaJni.last.error,
        ),
    )
    val state: StateFlow<StenoUiState> = _state
    private val mic = MicRecorder()
    private val pathB = PathBSession.fromPipeline(pipeline)
    private val presentClient =
        if (BuildConfig.PRESENT_CONNECT) OkHttpPresentConnectClient(pathB = pathB) else null

    init {
        val client = presentClient
        if (client != null) {
            viewModelScope.launch {
                client.events.collect { onPresentEvent(it) }
            }
        }
    }

    private fun onPresentEvent(e: PresentConnectEvent) {
        when (e) {
            is PresentConnectEvent.HelloOk ->
                _state.update { it.copy(presentConnected = true, error = null) }
            is PresentConnectEvent.StreamStarted ->
                _state.update { s ->
                    s.copy(
                        activeStreams = s.activeStreams.filterNot { it.streamId == e.streamId } +
                            ActiveStreamChip(e.streamId, e.speakerId, e.role),
                        micFlags = s.micFlags.filterNot { it.streamId == e.streamId },
                    )
                }
            is PresentConnectEvent.StreamEnded ->
                _state.update { s ->
                    s.copy(activeStreams = s.activeStreams.filterNot { it.streamId == e.streamId })
                }
            is PresentConnectEvent.Final -> {
                val brief = pathB.brief()
                _state.update {
                    it.copy(
                        transcript = brief,
                        jsonPreview = team.mikcoders.neurosteno.present.PresentConnectWire.briefJson(brief.transcript),
                    )
                }
            }
            is PresentConnectEvent.OverCapacity ->
                _state.update { it.copy(overCapacityId = e.rejectedStreamId) }
            is PresentConnectEvent.Error ->
                _state.update { it.copy(error = "${e.code}: ${e.message}") }
            is PresentConnectEvent.MicQuality ->
                _state.update { s ->
                    s.copy(
                        activeStreams = s.activeStreams.map { chip ->
                            if (chip.streamId == e.streamId) chip.copy(qualityCode = e.code) else chip
                        },
                        micFlags = s.micFlags.filterNot { it.streamId == e.streamId } +
                            MicFlagChip(e.streamId, e.speakerId, e.code, e.hardReject),
                    )
                }
            PresentConnectEvent.Disconnected ->
                _state.update {
                    it.copy(presentConnected = false, activeStreams = emptyList(), micFlags = emptyList())
                }
            is PresentConnectEvent.Partial -> { }
        }
    }

    fun updateWsUrl(url: String) {
        _state.update { it.copy(wsUrl = url) }
    }

    fun connectPresent() {
        val client = presentClient ?: return
        val url = _state.value.wsUrl
        viewModelScope.launch {
            client.connect(
                PresentConnectConfig(
                    wsUrl = url,
                    httpBaseUrl = url.substringBefore("/v1").ifBlank { url },
                    sessionId = "demo",
                    deviceId = "phone",
                ),
            )
        }
    }

    fun disconnectPresent() {
        viewModelScope.launch { presentClient?.disconnect() }
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
                            "far-field +${prep.gain.gainDb} dB",
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
