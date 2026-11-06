package team.mikcoders.neurosteno.pipeline

import java.io.File
import java.io.InputStream

/**
 * Copy judge-APK assets/models into filesDir/models on first launch.
 * sherpa-onnx needs filesystem paths; assets alone are not enough at inference.
 * download-models.sh / adb-push is not the judge path.
 */
object AssetModelUnpack {
    const val ASSET_ROOT = "models"
    const val MARKER = ".unpacked"

    val REQUIRED: List<String> = listOf(
        "asr/zipformer-ru-int8/encoder.int8.onnx",
        "asr/zipformer-ru-int8/decoder.onnx",
        "asr/zipformer-ru-int8/joiner.int8.onnx",
        "asr/zipformer-ru-int8/tokens.txt",
        "vad/silero_vad.onnx",
        "diar/segmentation.onnx",
        "diar/embedding.onnx",
    )

    data class Report(
        val copied: Int,
        val skippedExisting: Int,
        val missing: List<String>,
        val ready: Boolean,
    )

    fun needed(destRoot: File): Boolean {
        if (!File(destRoot, MARKER).isFile) return true
        return REQUIRED.any { rel ->
            val f = File(destRoot, rel)
            !f.isFile || f.length() <= 0L
        }
    }

    fun unpack(destRoot: File, openAsset: (String) -> InputStream?): Report {
        destRoot.mkdirs()
        var copied = 0
        var skipped = 0
        val missing = ArrayList<String>()
        for (rel in REQUIRED) {
            val dest = File(destRoot, rel)
            if (dest.isFile && dest.length() > 0L) {
                skipped++
                continue
            }
            val input = openAsset("$ASSET_ROOT/$rel")
            if (input == null) {
                missing += rel
                continue
            }
            dest.parentFile?.mkdirs()
            input.use { ins -> dest.outputStream().use { outs -> ins.copyTo(outs) } }
            copied++
        }
        val ready = REQUIRED.all { rel ->
            val f = File(destRoot, rel)
            f.isFile && f.length() > 0L
        }
        if (ready) {
            File(destRoot, MARKER).writeText("neurosteno-embedded-models\n")
        }
        return Report(copied = copied, skippedExisting = skipped, missing = missing, ready = ready)
    }
}
