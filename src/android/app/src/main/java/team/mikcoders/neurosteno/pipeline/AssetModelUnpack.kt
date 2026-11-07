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

    /**
     * Packed only when the build ran with `-PembedLlm=true` (the 353 MB Qwen2
     * GGUF roughly doubles the APK). The TZ requires a local LLM, so the
     * hand-in APK is built with it; an APK without it still does
     * ASR + VAD + diarization and falls back to intonation-punct only.
     * Absent optional assets are never reported as `missing`.
     */
    val OPTIONAL: List<String> = listOf(
        "llm/qwen2-0_5b-instruct-q4_0.gguf",
    )

    data class Report(
        val copied: Int,
        val skippedExisting: Int,
        val missing: List<String>,
        val ready: Boolean,
        val optionalCopied: Int = 0,
    )

    fun needed(destRoot: File, assetExists: (String) -> Boolean = { false }): Boolean {
        if (!File(destRoot, MARKER).isFile) return true
        val requiredMissing = REQUIRED.any { rel ->
            val f = File(destRoot, rel)
            !f.isFile || f.length() <= 0L
        }
        if (requiredMissing) return true
        // A newer APK that starts shipping the GGUF must still land on a phone
        // that already unpacked the ASR/VAD/diar set.
        return OPTIONAL.any { rel ->
            assetExists("$ASSET_ROOT/$rel") && !File(destRoot, rel).isFile
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
        var optionalCopied = 0
        for (rel in OPTIONAL) {
            val dest = File(destRoot, rel)
            if (dest.isFile && dest.length() > 0L) continue
            val input = openAsset("$ASSET_ROOT/$rel") ?: continue
            dest.parentFile?.mkdirs()
            input.use { ins -> dest.outputStream().use { outs -> ins.copyTo(outs) } }
            optionalCopied++
        }
        val ready = REQUIRED.all { rel ->
            val f = File(destRoot, rel)
            f.isFile && f.length() > 0L
        }
        if (ready) {
            File(destRoot, MARKER).writeText("neurosteno-embedded-models\n")
        }
        return Report(
            copied = copied,
            skippedExisting = skipped,
            missing = missing,
            ready = ready,
            optionalCopied = optionalCopied,
        )
    }
}
