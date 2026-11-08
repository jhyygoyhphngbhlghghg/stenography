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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import team.mikcoders.neurosteno.export.ExportFormat
import team.mikcoders.neurosteno.export.TranscriptExport
import team.mikcoders.neurosteno.pipeline.TranscriptView
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
        var sizeLabel: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    val sz = if (c.getColumnCount() > 1) c.getLong(1) else -1L
                    sizeLabel = if (sz >= 0) formatBytes(sz) else null
                }
            }
        vm.onFilePicked(uri, name, sizeLabel)
    }
    val savers = mutableMapOf<ExportFormat, androidx.activity.result.ActivityResultLauncher<String>>()
    ExportFormat.entries.forEach { fmt ->
        savers[fmt] = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(fmt.mime),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val rows = vm.state.value.transcript?.transcript.orEmpty()
            val bytes = TranscriptExport.bytes(fmt, rows, state.fileLabel ?: "Транскрипт")
            context.contentResolver.openOutputStream(uri)?.use { os -> os.write(bytes) }
        }
    }
    val tabs = listOf("transcript" to "Транскрипт", "summary" to "Резюме", "ask" to "Вопросы", "diary" to "Дневник")
    val recPerm = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.toggleRecord()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Нейро-стенографист") },
                actions = {
                    Text(
                        if (state.recording) "● LIVE" else "○ готово",
                        modifier = Modifier.padding(end = 12.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (state.recording) androidx.compose.ui.graphics.Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
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
                Button(onClick = { recPerm.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text(if (state.recording) "Стоп LIVE" else "LIVE-запись")
                }
                Button(
                    onClick = { vm.transcribe() },
                    enabled = !state.running,
                ) {
                    Text("Расшифровать")
                }
                OutlinedButton(
                    enabled = state.jsonPreview != null,
                    onClick = { savers[ExportFormat.JSON]!!.launch("transcript.json") },
                ) {
                    Text("Сохранить JSON")
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
                        context.startActivity(Intent.createChooser(send, "Поделиться JSON"))
                    },
                ) {
                    Text("Поделиться")
                }
            }
            if (state.transcript?.transcript?.isNotEmpty() == true) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportFormat.entries.forEach { fmt ->
                        if (fmt == ExportFormat.JSON) return@forEach
                        OutlinedButton(
                            onClick = { savers[fmt]!!.launch("transcript.${fmt.ext}") },
                        ) { Text(fmt.label) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                state.fileLabel?.let { "Файл: $it${state.fileSize?.let { s -> " · $s" } ?: ""}" } ?: "Файл не выбран — можно всё равно прогнать stub.",
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
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.clearError() }) { Text("Скрыть") }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabs.forEach { (key, label) ->
                    FilterChip(
                        selected = state.tab == key,
                        onClick = { vm.setTab(key) },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            when (state.tab) {
                "summary" -> {
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = { vm.summarize() }, enabled = !state.summaryRunning) {
                        Text(if (state.summaryRunning) "Резюмирую…" else "Сделать резюме (локальный LLM)")
                    }
                    Spacer(Modifier.height(8.dp))
                    if (state.summary.isNotBlank()) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(state.summary, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Text("Резюме появится здесь. Работает офлайн (Qwen2 0.5B) или экстрактивно без веса.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                "ask" -> {
                    OutlinedTextField(
                        value = state.askInput,
                        onValueChange = { vm.setAskInput(it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Вопрос по стенограмме…") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.ask() }, enabled = !state.askRunning) {
                        Text(if (state.askRunning) "Думаю…" else "Спросить (локальный LLM)")
                    }
                    Spacer(Modifier.height(8.dp))
                    if (state.askAnswer.isNotBlank()) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(state.askAnswer, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                "diary" -> {
                    OutlinedButton(onClick = { vm.saveToDiary() }, enabled = state.transcript?.transcript?.isNotEmpty() == true) {
                        Text("Сохранить в дневник")
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.diary, key = { it.createdMs }) { d ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(d.title, style = MaterialTheme.typography.titleMedium)
                                    Text("${d.transcript.size} реплик · ${d.createdMs}", style = MaterialTheme.typography.bodySmall)
                                    if (d.summary.isNotBlank()) Text(d.summary.take(200), style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { vm.loadFromDiary(d.createdMs) }) { Text("Открыть") }
                                        TextButton(onClick = { vm.deleteDiary(d.createdMs) }) { Text("Удалить") }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    val rows = state.transcript?.transcript.orEmpty()
                    if (state.liveInterim.isNotEmpty() && state.running) {
                        Text("Live (окна по 5 с):", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        state.liveInterim.forEach { u ->
                            Text("[${formatMs(u.startMs)}] ${u.speakerId}: ${u.text}", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (rows.isNotEmpty()) {
                        val stats = TranscriptView.stats(rows)
                        Text(
                            "Спикеров: ${stats.speakers} · слов: ${stats.words} · длительность: ${formatMs(stats.spanMs)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = state.mergeAdjacentTurns,
                                onClick = { vm.toggleMergeAdjacent() },
                                label = { Text("Склеить реплики") },
                            )
                            FilterChip(
                                selected = state.showAllText,
                                onClick = { vm.toggleShowAllText() },
                                label = { Text("Весь текст") },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        val speakers = rows.map { it.speakerId }.distinct()
                        if (speakers.size > 1) {
                            speakers.forEach { sid ->
                                var name by remember(state.speakerNames[sid]) { mutableStateOf(state.speakerNames[sid] ?: "") }
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text("$sid →", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.5f))
                                    OutlinedTextField(
                                        value = name,
                                        onValueChange = { name = it },
                                        label = { Text("Имя спикера") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1.4f),
                                    )
                                    TextButton(onClick = { vm.renameSpeaker(sid, name) }) { Text("OK") }
                                }
                                Spacer(Modifier.height(2.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
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
                                UtteranceRow(u, state.showAllText)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UtteranceRow(u: Utterance, expand: Boolean = false) {
    val palette = listOf(
        Color(0xFF1B7F5A), Color(0xFF2563EB), Color(0xFFB45309), Color(0xFF7C3AED),
        Color(0xFF0E7490), Color(0xFFBE123C), Color(0xFF4D7C0F), Color(0xFF9333EA),
    )
    val accent = palette[TranscriptView.speakerColorIndex(u.speakerId)]
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
                    color = accent,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            if (expand) Text(u.text, style = MaterialTheme.typography.bodyLarge)
            else Text(
                u.text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
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

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var b = bytes.toDouble()
    var i = 0
    while (b >= 1024 && i < units.lastIndex) { b /= 1024; i++ }
    return "%.1f %s".format(b, units[i])
}

@Suppress("unused")
@Composable
private fun BusyIf(running: Boolean) {
    if (running) CircularProgressIndicator()
}
