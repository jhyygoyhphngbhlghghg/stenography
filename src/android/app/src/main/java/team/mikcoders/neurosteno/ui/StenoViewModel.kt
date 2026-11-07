package team.mikcoders.neurosteno.ui

import android.app.Application
import android.content.Intent
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
import team.mikcoders.neurosteno.audio.Pcm
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.TranscriptJson
import team.mikcoders.neurosteno.export.TranscriptExport
import team.mikcoders.neurosteno.llm.TranscriptQa
import team.mikcoders.neurosteno.llm.OnDeviceLlmRunner
import team.mikcoders.neurosteno.pipeline.DemoPcmPrep
import team.mikcoders.neurosteno.pipeline.ModelPaths
import team.mikcoders.neurosteno.pipeline.PipelineFactory
import team.mikcoders.neurosteno.pipeline.PipelineProgress
import team.mikcoders.neurosteno.pipeline.PipelineStage
import team.mikcoders.neurosteno.pipeline.PcmAudio
import team.mikcoders.neurosteno.pipeline.SherpaJni
import team.mikcoders.neurosteno.pipeline.StenoPipeline
import team.mikcoders.neurosteno.pipeline.StubStenoPipeline
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
    // Export
    val exportFormat: String = "json",
    // Q&A
    val qaQuestion: String = "",
    val qaAnswer: String? = null,
    val qaRunning: Boolean = false,
    val qaSummary: String? = null,
    val qaKeyPoints: String? = null,
    // Speaker names
    val speakerNames: Map<String, String> = emptyMap(),
    // Presentation mode
    val presentMode: Boolean = false,
    val presentApiKey: String = "",
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

    init {
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
            // Load saved speaker names
            SpeakerNames.load(getApplication())
            _state.update { it.copy(speakerNames = SpeakerNames.names.toMap()) }

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

    fun onFilePicked(uri: Uri, displayName: String?) {
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
                            samples = FloatArray(Pcm.TARGET_HZ * 4),
                            sampleRate = Pcm.TARGET_HZ,
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
            } catch (t: Throwable) {
                _state.update {
                    it.copy(running = false, error = t.message ?: t::class.java.simpleName)
                }
            }
        }
    }

    /* ---------------------------------------------------------- Export */

    fun setExportFormat(fmt: String) {
        _state.update { it.copy(exportFormat = fmt) }
    }

    fun exportContent(): Pair<String, ByteArray?> {
        val rows = _state.value.transcript?.transcript ?: emptyList()
        return when (_state.value.exportFormat) {
            "srt" -> TranscriptExport.toSrt(rows) to null
            "txt" -> TranscriptExport.toTxt(rows) to null
            "csv" -> TranscriptExport.toCsv(rows) to null
            "docx" -> "" to TranscriptExport.toDocx(rows)
            else -> (_state.value.jsonPreview ?: "[]") to null
        }
    }

    fun exportMimeType(): String = when (_state.value.exportFormat) {
        "srt" -> "text/plain"
        "txt" -> "text/plain"
        "csv" -> "text/csv"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        else -> "application/json"
    }

    fun exportFileName(): String = "transcript.${_state.value.exportFormat}"

    /* ------------------------------------------------------------- Q&A */

    fun setQaQuestion(q: String) {
        _state.update { it.copy(qaQuestion = q) }
    }

    fun askQa() {
        val question = _state.value.qaQuestion.trim()
        val rows = _state.value.transcript?.transcript ?: emptyList()
        if (question.isEmpty() || rows.isEmpty() || _state.value.qaRunning) return
        val runner = pipeline.llmRunner ?: return
        val qa = TranscriptQa(runner)
        _state.update { it.copy(qaRunning = true, qaAnswer = null) }
        viewModelScope.launch {
            try {
                val answer = qa.ask(rows, question)
                _state.update { it.copy(qaRunning = false, qaAnswer = answer) }
            } catch (t: Throwable) {
                _state.update { it.copy(qaRunning = false, qaAnswer = "Ошибка: ${t.message}") }
            }
        }
    }

    fun summarizeQa() {
        val rows = _state.value.transcript?.transcript ?: emptyList()
        if (rows.isEmpty() || _state.value.qaRunning) return
        val runner = pipeline.llmRunner ?: return
        val qa = TranscriptQa(runner)
        _state.update { it.copy(qaRunning = true, qaSummary = null) }
        viewModelScope.launch {
            try {
                val summary = qa.summarize(rows)
                _state.update { it.copy(qaRunning = false, qaSummary = summary) }
            } catch (t: Throwable) {
                _state.update { it.copy(qaRunning = false, qaSummary = "Ошибка: ${t.message}") }
            }
        }
    }

    fun extractKeyPoints() {
        val rows = _state.value.transcript?.transcript ?: emptyList()
        if (rows.isEmpty() || _state.value.qaRunning) return
        val runner = pipeline.llmRunner ?: return
        val qa = TranscriptQa(runner)
        _state.update { it.copy(qaRunning = true, qaKeyPoints = null) }
        viewModelScope.launch {
            try {
                val points = qa.extractKeyPoints(rows)
                _state.update { it.copy(qaRunning = false, qaKeyPoints = points) }
            } catch (t: Throwable) {
                _state.update { it.copy(qaRunning = false, qaKeyPoints = "Ошибка: ${t.message}") }
            }
        }
    }

    /* ----------------------------------------------------- Speaker names */

    fun setSpeakerName(speakerId: String, name: String) {
        SpeakerNames.setName(speakerId, name)
        SpeakerNames.persist(getApplication())
        _state.update { it.copy(speakerNames = SpeakerNames.names.toMap()) }
    }

    fun loadSpeakerNames() {
        SpeakerNames.load(getApplication())
        _state.update { it.copy(speakerNames = SpeakerNames.names.toMap()) }
    }

    /* ------------------------------------------------- Presentation mode */

    companion object {
        /** Hardcoded presentation server domain for the demo build. */
        const val PRESENT_DOMAIN = "http://192.168.1.100:8080"
        const val PRESENT_WS_PATH = "/v1/present-connect"
        const val PRESENT_HTTP_PATH = "/v1/transcript"
    }

    fun setPresentApiKey(key: String) {
        _state.update { it.copy(presentApiKey = key) }
    }

    fun togglePresentMode() {
        _state.update { it.copy(presentMode = !it.presentMode) }
    }

    /**
     * Connect to the presentation server in presentation mode.
     * Uses the hardcoded domain + API key for auth.
     */
    fun connectPresentation() {
        val key = _state.value.presentApiKey.trim()
        if (key.isEmpty()) {
            _state.update { it.copy(error = "Введите API-ключ") }
            return
        }
        val wsUrl = "$PRESENT_DOMAIN$PRESENT_WS_PATH?session=present&role=phone&device=phone_1&key=$key"
        val client = presentClient ?: return
        viewModelScope.launch {
            client.connect(
                PresentConnectConfig(
                    wsUrl = wsUrl,
                    httpBaseUrl = PRESENT_DOMAIN,
                    sessionId = "present",
                    deviceId = "phone_1",
                ),
            )
        }
    }

    /**
     * Push the current transcript to the presentation server via HTTP.
     */
    fun pushToPresentation() {
        val rows = _state.value.transcript?.transcript ?: emptyList()
        if (rows.isEmpty()) return
        val key = _state.value.presentApiKey.trim()
        viewModelScope.launch {
            try {
                val json = TranscriptJson.encode(TranscriptFile(rows))
                val engine = _state.value.engineName
                val body = buildString {
                    append("{\"transcript\": ")
                    append(json)
                    append(", \"engine\": \"")
                    append(engine)
                    append("\"}")
                }
                withContext(Dispatchers.IO) {
                    val url = java.net.URL("$PRESENT_DOMAIN$PRESENT_HTTP_PATH")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    if (key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $key")
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    conn.responseCode
                }
                _state.update { it.copy(error = null) }
            } catch (t: Throwable) {
                _state.update { it.copy(error = "Push: ${t.message}") }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
