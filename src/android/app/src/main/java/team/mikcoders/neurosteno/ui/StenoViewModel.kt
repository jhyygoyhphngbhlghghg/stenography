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
