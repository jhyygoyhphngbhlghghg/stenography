package team.mikcoders.neurosteno.ui

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import team.mikcoders.neurosteno.audio.Pcm
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.TranscriptJson
import team.mikcoders.neurosteno.pipeline.DemoPcmPrep
import team.mikcoders.neurosteno.pipeline.ModelPaths
import team.mikcoders.neurosteno.pipeline.PipelineFactory
import team.mikcoders.neurosteno.pipeline.PipelineProgress
import team.mikcoders.neurosteno.pipeline.PipelineStage
import team.mikcoders.neurosteno.pipeline.PcmAudio
import team.mikcoders.neurosteno.pipeline.SherpaJni
import team.mikcoders.neurosteno.pipeline.StenoPipeline
import team.mikcoders.neurosteno.pipeline.StubStenoPipeline
import team.mikcoders.neurosteno.pipeline.TranscriptView
import team.mikcoders.neurosteno.present.OkHttpPresentConnectClient
import team.mikcoders.neurosteno.present.PathBSession
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
    // Model load (ONNX unpack out of the judge-APK assets + GGUF probe) runs off
    // the main thread; the UI shows "Загрузка pipeline…" while this is true.
    val modelsLoading: Boolean = true,
    val recording: Boolean = false,
    val heldPcm: PcmAudio? = null,
    val jniLoaded: Boolean = false,
    val jniError: String? = null,
    val llmName: String = "llm-unset",
    val llmOnDevice: Boolean = false,
    val activeStreams: List<ActiveStreamChip> = emptyList(),
    val overCapacityId: String? = null,
    val presentConnected: Boolean = false,
    val presentWorkerId: String = "",
    val presentStreams: Int = 0,
    val wsUrl: String = "wss://present.example.local/v1/present-connect?session=demo&role=worker&device=phone",
    val micFlags: List<MicFlagChip> = emptyList(),
    val mergeAdjacentTurns: Boolean = true,
    val showAllText: Boolean = false,
    val fileSize: String? = null,
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
    @Volatile private var pipeline: StenoPipeline = StubStenoPipeline()
    @Volatile private var models: ModelPaths? = null
    @Volatile private var pathB: PathBSession? = null
    @Volatile private var presentClient: OkHttpPresentConnectClient? = null

    private val _state = MutableStateFlow(
        StenoUiState(
            jniLoaded = SherpaJni.last.loaded,
            jniError = SherpaJni.last.error,
        ),
    )
    val state: StateFlow<StenoUiState> = _state
    private val mic = MicRecorder()
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() = trimModels()
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trimModels()
        }
    }

    private fun trimModels() {
        viewModelScope.launch {
            try {
                pipeline.trimMemory()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.w("StenoViewModel", "Native model eviction failed", failure)
            }
        }
    }

    override fun onCleared() {
        getApplication<Application>().unregisterComponentCallbacks(memoryCallbacks)
        val retiringPipeline = pipeline
        val retiringClient = presentClient
        // viewModelScope is cancelled at this point. Teardown must outlive it,
        // wait for JNI to return, and never block the main thread.
        CoroutineScope(Dispatchers.Default).launch {
            try {
                try {
                    retiringClient?.disconnect()
                } finally {
                    retiringPipeline.close()
                }
            } catch (failure: Throwable) {
                Log.w("StenoViewModel", "Native model teardown failed", failure)
            }
        }
        super.onCleared()
    }

    init {
        app.registerComponentCallbacks(memoryCallbacks)
        viewModelScope.launch {
            val created = withContext(Dispatchers.IO) { PipelineFactory.create(getApplication()) }
            pipeline = created.first
            models = created.second
            _state.update {
                it.copy(
                    engineName = created.first.displayName,
                    stubPath = created.first is StubStenoPipeline,
                    modelsReady = created.second.readyForOfflineDemo,
                    modelsLoading = false,
                    llmName = created.first.llmDisplayName,
                    llmOnDevice = created.first.llmIsOnDevice,
                    jniLoaded = SherpaJni.last.loaded,
                    jniError = SherpaJni.last.error,
                )
            }
            if (BuildConfig.PRESENT_CONNECT && presentClient == null) {
                val session = PathBSession.fromPipeline(created.first)
                pathB = session
                val client = OkHttpPresentConnectClient(pathB = session)
                presentClient = client
                viewModelScope.launch { client.events.collect { onPresentEvent(it) } }
            }
        }
    }

    private fun onPresentEvent(e: PresentConnectEvent) {
        when (e) {
            is PresentConnectEvent.HelloOk ->
                _state.update {
                    it.copy(presentConnected = true, presentWorkerId = e.workerId, error = null)
                }
            is PresentConnectEvent.StreamStarted ->
                _state.update { s ->
                    s.copy(
                        activeStreams = s.activeStreams.filterNot { it.streamId == e.streamId } +
                            ActiveStreamChip(e.streamId, e.speakerId, e.role),
                        micFlags = s.micFlags.filterNot { it.streamId == e.streamId },
                        presentStreams = (presentClient?.activeStreamCount() ?: s.presentStreams)
                            .coerceAtMost(PresentConnectLimits.MAX_STREAMS),
                    )
                }
            is PresentConnectEvent.StreamEnded ->
                _state.update { s ->
                    s.copy(
                        activeStreams = s.activeStreams.filterNot { it.streamId == e.streamId },
                        presentStreams = presentClient?.activeStreamCount() ?: s.presentStreams,
                    )
                }
            is PresentConnectEvent.Final -> {
                val brief = pathB?.brief() ?: return
                _state.update {
                    it.copy(
                        transcript = brief,
                        jsonPreview = team.mikcoders.neurosteno.present.PresentConnectWire
                            .briefJson(brief.transcript),
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
                    it.copy(
                        presentConnected = false,
                        presentStreams = 0,
                        activeStreams = emptyList(),
                        micFlags = emptyList(),
                    )
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

    fun onFilePicked(uri: Uri, displayName: String?, sizeLabel: String? = null) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // One-shot SAF grant is enough for this session.
        }
        _state.update {
            it.copy(
                fileLabel = displayName ?: uri.lastPathSegment ?: uri.toString(),
                fileSize = sizeLabel,
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
        if (_state.value.running || _state.value.modelsLoading) return
        val request = _state.value
        viewModelScope.launch {
            _state.update { it.copy(running = true, error = null, progress = PipelineProgress(PipelineStage.Decode, "start", 0f)) }
            try {
                val audio = withContext(Dispatchers.Default) {
                    val held = request.heldPcm
                    val uriStr = request.importedUri
                    when {
                        held != null && uriStr == null -> held
                        uriStr != null -> AudioDecoder.decode(getApplication(), Uri.parse(uriStr), label)
                        else -> PcmAudio(
                            samples = FloatArray(Pcm.TARGET_HZ * 4),
                            sampleRate = Pcm.TARGET_HZ,
                            sourceName = label,
                        )
                    }
                }
                val prep = withContext(Dispatchers.Default) { DemoPcmPrep.prepare(audio) }
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
                            if (prep.eq.applied) "phone-distance EQ" else "per-turn far-field +${prep.gain.gainDb} dB",
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
                        stubPath = pipeline is StubStenoPipeline,
                        engineName = pipeline.displayName,
                        llmName = pipeline.llmDisplayName,
                        llmOnDevice = pipeline.llmIsOnDevice,
                    )
                }
                _state.update { s -> if (s.mergeAdjacentTurns) refreshTranscriptPreview(s) else s }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(running = false) }
                throw cancelled
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

    fun toggleMergeAdjacent() {
        _state.update { s ->
            val next = s.copy(mergeAdjacentTurns = !s.mergeAdjacentTurns)
            refreshTranscriptPreview(next)
        }
    }

    fun toggleShowAllText() {
        _state.update { s -> s.copy(showAllText = !s.showAllText) }
    }

    fun retimeUtterance(index: Int, newStartMs: Long, newEndMs: Long) {
        _state.update { s ->
            val rows = s.transcript?.transcript.orEmpty()
            if (index !in rows.indices) return@update s
            val fixed = rows.mapIndexed { i, u ->
                if (i == index) u.copy(startMs = newStartMs, endMs = newEndMs) else u
            }.sortedBy { it.startMs }
            refreshTranscriptPreview(s.copy(transcript = TranscriptFile(fixed)))
        }
    }

    private fun refreshTranscriptPreview(s: StenoUiState): StenoUiState {
        val raw = s.transcript?.transcript.orEmpty()
        val shown = if (s.mergeAdjacentTurns) TranscriptView.mergeAdjacent(raw) else raw.filter { it.text.isNotBlank() }
        val file = TranscriptFile(shown)
        return s.copy(
            transcript = file,
            jsonPreview = TranscriptJson.encode(file),
        )
    }
}
