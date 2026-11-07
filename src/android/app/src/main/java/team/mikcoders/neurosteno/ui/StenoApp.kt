package team.mikcoders.neurosteno.ui

import android.Manifest
import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import team.mikcoders.neurosteno.export.Utterance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StenoApp(vm: StenoViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        var name: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        vm.onFilePicked(uri, name)
    }
    val recPerm = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.toggleRecord()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Нейро-стенографист") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                if (state.presentConnect) {
                    "PresentConnect Path B ≤20 — опциональный сетевой режим, не слайд. Live demo = DEMO_STAGE6: один телефонный mic."
                } else {
                    "Live demo = DEMO_STAGE6: один mic (Nothing-class), 6 говорящих по-русски, 3–6 м, большая комната, не студия. Path B ≤20 — опциональный PresentConnect."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.presentConnect) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.wsUrl,
                    onValueChange = { vm.updateWsUrl(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("PresentConnect wss") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.connectPresent() }, enabled = !state.presentConnected) {
                        Text("WS connect")
                    }
                    OutlinedButton(onClick = { vm.disconnectPresent() }, enabled = state.presentConnected) {
                        Text("Disconnect")
                    }
                    Text(
                        "потоков ${state.activeStreams.size}/20",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (state.activeStreams.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.activeStreams.forEach { s ->
                            FilterChip(
                                selected = s.role == "main",
                                onClick = {},
                                label = {
                                    Text(
                                        buildString {
                                            append(s.streamId)
                                            append('→')
                                            append(s.speakerId)
                                            s.qualityCode?.let { append(" $it") }
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
                state.overCapacityId?.let { id ->
                    Text(
                        "OVER_CAPACITY: $id (stream 21)",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.micFlags.forEach { f ->
                    Text(
                        "${f.code}: ${f.streamId}→${f.speakerId}" +
                            if (f.hardReject) " (ASR skipped)" else " (soft, ASR ran)",
                        color = if (f.hardReject) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (state.modelsLoading) {
                Text(
                    "Загрузка pipeline…",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (state.stubPath) {
                Text(
                    "Сейчас pipeline = ${state.engineName}. Не показывать судьям как ASR.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (state.modelsReady) {
                Text(
                    "Офлайн: Zipformer-ru + Silero VAD + diar (models в APK).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                if (state.jniLoaded) {
                    "JNI libsherpa-onnx-jni.so: loaded"
                } else {
                    "JNI libsherpa-onnx-jni.so: FAIL ${state.jniError ?: "not probed"}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "LLM: ${state.llmName}",
                color = if (state.llmOnDevice) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("audio/*", "application/octet-stream")) }) {
                    Text("Импорт")
                }
                OutlinedButton(onClick = { recPerm.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text(if (state.recording) "Стоп записи" else "Запись")
                }
                Button(
                    onClick = { vm.transcribe() },
                    enabled = !state.running,
                ) {
                    Text("Расшифровать")
                }
                OutlinedButton(
                    enabled = state.jsonPreview != null,
                    onClick = {
                        val json = state.jsonPreview ?: return@OutlinedButton
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_TEXT, json)
                            putExtra(Intent.EXTRA_TITLE, "transcript.json")
                        }
                        context.startActivity(Intent.createChooser(send, "Экспорт JSON"))
                    },
                ) {
                    Text("Экспорт JSON")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                state.fileLabel?.let { "Файл: $it" } ?: "Файл не выбран — можно всё равно прогнать stub.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.running) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.progress?.fraction ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(state.progress?.message ?: "…", style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            val rows = state.transcript?.transcript.orEmpty()
            if (rows.isEmpty() && !state.running) {
                Text(
                    if (state.presentConnect) {
                        "Path B: жду stream.start (s01→speaker_1 … s20→speaker_20). Brief без stream_id."
                    } else {
                        "Пусто. Импортируйте запись и нажмите «Расшифровать»."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = { it.startMs.toString() + it.speakerId + it.text }) { u ->
                        UtteranceRow(u)
                    }
                }
            }
        }
    }
}

@Composable
private fun UtteranceRow(u: Utterance) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text(u.speakerId) },
                )
                Text(
                    formatMs(u.startMs) + " – " + formatMs(u.endMs),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(u.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    val m = s / 60
    val rem = s % 60
    val frac = (ms % 1000) / 100
    return "%d:%02d.%d".format(m, rem, frac)
}

@Suppress("unused")
@Composable
private fun BusyIf(running: Boolean) {
    if (running) CircularProgressIndicator()
}
