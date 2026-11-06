package team.mikcoders.neurosteno.pipeline

import android.content.Context
import java.io.File

/**
 * Looks for gitignored ONNX/Vosk/GGUF trees in filesDir/models and
 * (optional) copied assets. Missing files → stub pipeline.
 */
data class ModelPaths(
    val zipformerDir: File?,
    val whisperTinyDir: File?,
    val sileroVad: File?,
    val diarSegmentation: File?,
    val diarEmbedding: File?,
    val voskDir: File?,
    val llmGguf: File?,
) {
    val hasPrimaryAsr: Boolean
        get() = zipformerDir != null && zipformerFile(zipformerDir, "tokens.txt") != null &&
            (zipformerFile(zipformerDir, "encoder") != null)

    val hasVad: Boolean get() = sileroVad != null
    val hasDiar: Boolean get() = diarSegmentation != null && diarEmbedding != null
    val hasBackupAsr: Boolean get() = whisperTinyDir != null
    val hasEmergencyAsr: Boolean get() = voskDir != null
    val hasLlm: Boolean get() = llmGguf != null

    val readyForOfflineDemo: Boolean get() = hasPrimaryAsr && hasVad && hasDiar
}

class ModelStore(private val context: Context) {
    fun modelsDir(): File = File(context.filesDir, "models")

    fun resolve(): ModelPaths {
        val root = modelsDir()
        return ModelPaths(
            zipformerDir = dirIfExists(File(root, "asr/zipformer-ru-int8")),
            whisperTinyDir = dirIfExists(File(root, "asr/whisper-tiny")),
            sileroVad = firstExisting(
                File(root, "vad/silero_vad.onnx"),
                File(root, "vad/silero_vad.int8.onnx"),
            ),
            diarSegmentation = firstExisting(
                File(root, "diar/segmentation.onnx"),
                File(root, "diar/model.onnx"),
            ),
            diarEmbedding = firstExisting(
                File(root, "diar/embedding.onnx"),
                File(root, "diar/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"),
            ),
            voskDir = dirIfExists(File(root, "vosk-small-ru")),
            llmGguf = root.resolve("llm").listFiles()?.firstOrNull { it.extension == "gguf" },
        )
    }

    companion object {
        fun dirIfExists(dir: File): File? = dir.takeIf { it.isDirectory && it.list()?.isNotEmpty() == true }

        fun firstExisting(vararg files: File): File? = files.firstOrNull { it.isFile }

        fun zipformerFile(dir: File, kind: String): File? {
            val names = dir.listFiles() ?: return null
            return names.firstOrNull { f ->
                val n = f.name.lowercase()
                when (kind) {
                    "tokens.txt" -> n == "tokens.txt"
                    "encoder" -> n.contains("encoder") && n.endsWith(".onnx")
                    "decoder" -> n.contains("decoder") && n.endsWith(".onnx")
                    "joiner" -> n.contains("joiner") && n.endsWith(".onnx")
                    else -> false
                }
            }
        }
    }
}
