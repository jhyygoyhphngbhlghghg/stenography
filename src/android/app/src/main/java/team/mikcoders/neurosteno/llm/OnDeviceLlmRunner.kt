package team.mikcoders.neurosteno.llm

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import java.io.File
import team.mikcoders.neurosteno.pipeline.InferenceSession

/** Tiny on-device GGUF completion. No cloud. */
interface OnDeviceLlmRunner {
    val engineName: String
    val ready: Boolean
    suspend fun complete(system: String, user: String): String
    suspend fun trimMemory() {}
    suspend fun close() {}
}

/**
 * llama.cpp JNI via `dev.ffmpegkit-maintained:llama-android` (GGUF, arm64).
 * Loads [gguf] from filesDir/models/llm (Platform unpack; we do not pack assets).
 */
class LlamaCppGgufRunner(
    private val gguf: File,
    private val contextSize: Int = 1_024,
    private val threads: Int = 2,
    private val maxTokens: Int = 96,
) : OnDeviceLlmRunner {
    override val engineName: String = "llama.cpp GGUF ${gguf.name}"

    @Volatile override var ready: Boolean = false
        private set

    private val session = InferenceSession(
        create = {
            require(gguf.isFile && gguf.length() > 0L) { "GGUF missing: ${gguf.path}" }
            Llama.loadModel(
                modelPath = gguf.absolutePath,
                config = LlamaConfig(contextSize = contextSize, threads = threads),
            ).also { ready = true }
        },
        destroy = { model ->
            ready = false
            Llama.releaseModel(model)
        },
    )

    override suspend fun complete(system: String, user: String): String =
        session.run { getModel ->
            Llama.complete(
                getModel(),
                prompt = user,
                systemPrompt = system,
                maxTokens = maxTokens,
            ).text.trim()
        }

    override suspend fun trimMemory() = session.evict()

    override suspend fun close() = session.close()
}

/** Test double — still the GGUF post-pass path, not identity. */
class ScriptedLlmRunner(
    private val replies: (String) -> String,
    override val engineName: String = "llama.cpp GGUF scripted",
) : OnDeviceLlmRunner {
    override val ready: Boolean = true
    override suspend fun complete(system: String, user: String): String = replies(user)
}
