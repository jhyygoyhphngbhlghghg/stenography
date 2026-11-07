package team.mikcoders.neurosteno.pipeline

import android.content.Context
import android.util.Log
import team.mikcoders.neurosteno.BuildConfig
import java.io.File
import java.io.IOException

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
        get() {
            val dir = zipformerDir ?: return false
            return ModelStore.zipformerFile(dir, "tokens.txt") != null &&
                ModelStore.zipformerFile(dir, "encoder") != null &&
                ModelStore.zipformerFile(dir, "decoder") != null &&
                ModelStore.zipformerFile(dir, "joiner") != null
        }

    val hasVad: Boolean get() = sileroVad != null
    val hasDiar: Boolean get() = diarSegmentation != null && diarEmbedding != null
    val hasBackupAsr: Boolean
        get() {
            val dir = whisperTinyDir ?: return false
            return ModelStore.whisperFile(dir, "encoder") != null &&
                ModelStore.whisperFile(dir, "decoder") != null &&
                ModelStore.whisperFile(dir, "tokens") != null
        }
    val hasEmergencyAsr: Boolean get() = voskDir != null
    val hasLlm: Boolean get() = llmGguf != null

    val readyForOfflineDemo: Boolean get() = hasPrimaryAsr && hasVad && hasDiar
}

class ModelStore(private val context: Context) {
    fun modelsDir(): File = File(context.filesDir, "models")

    /**
     * First-launch copy of the weights packed into the judge APK
     * (Platform PR #3 @ af71d91). No-op on the slim build or when filesDir
     * already holds the packs, so an adb-pushed models/ tree still wins.
     */
    fun ensureUnpacked(): AssetModelUnpack.Report? {
        if (!BuildConfig.EMBEDDED_MODELS) return null
        val dest = modelsDir()
        if (!AssetModelUnpack.needed(dest, ::assetExists)) {
            return AssetModelUnpack.Report(
                copied = 0,
                skippedExisting = AssetModelUnpack.REQUIRED.size,
                missing = emptyList(),
                ready = true,
            )
        }
        val report = AssetModelUnpack.unpack(dest) { assetPath ->
            try {
                context.assets.open(assetPath)
            } catch (_: IOException) {
                null
            }
        }
        if (!report.ready) {
            Log.e(TAG, "embedded models incomplete missing=${report.missing}")
        } else {
            Log.i(
                TAG,
                "embedded models ready copied=${report.copied} skipped=${report.skippedExisting} llmPacked=${report.optionalCopied}",
            )
        }
        return report
    }

    /** Assets live under assets/models/…; a missing dir or file is not an error. */
    private fun assetExists(path: String): Boolean {
        val slash = path.lastIndexOf('/')
        val dir = if (slash >= 0) path.substring(0, slash) else ""
        val name = if (slash >= 0) path.substring(slash + 1) else path
        return runCatching { context.assets.list(dir)?.contains(name) == true }.getOrDefault(false)
    }

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
        private const val TAG = "ModelStore"

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

        fun whisperFile(dir: File, kind: String): File? {
            val names = dir.listFiles() ?: return null
            return names.firstOrNull { f ->
                val n = f.name.lowercase()
                when (kind) {
                    "tokens" -> n.endsWith("tokens.txt")
                    "encoder" -> n.contains("encoder") && n.endsWith(".onnx")
                    "decoder" -> n.contains("decoder") && n.endsWith(".onnx")
                    else -> false
                }
            }
        }
    }
}
