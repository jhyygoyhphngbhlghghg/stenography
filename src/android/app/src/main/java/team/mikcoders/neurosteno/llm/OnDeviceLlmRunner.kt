package team.mikcoders.neurosteno.llm

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import java.io.File

/** Tiny on-device GGUF completion. No cloud. */
interface OnDeviceLlmRunner {
    val engineName: String
    val ready: Boolean
    suspend fun complete(system: String, user: String): String
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

    @Volatile private var session: (suspend (String, String) -> String)? = null

    override suspend fun complete(system: String, user: String): String {
        val run = session ?: openSession()
        return run(system, user)
    }

    private suspend fun openSession(): suspend (String, String) -> String {
        require(gguf.isFile && gguf.length() > 0L) { "GGUF missing: ${gguf.path}" }
        val model = Llama.loadModel(
            modelPath = gguf.absolutePath,
            config = LlamaConfig(contextSize = contextSize, threads = threads),
        )
        val fn: suspend (String, String) -> String = { sys, usr ->
            Llama.complete(
                model,
                prompt = usr,
                systemPrompt = sys,
                maxTokens = maxTokens,
            ).text.trim()
        }
        session = fn
        ready = true
        return fn
    }
}

/** Test double — still the GGUF post-pass path, not identity. */
class ScriptedLlmRunner(
    private val replies: (String) -> String,
    override val engineName: String = "llama.cpp GGUF scripted",
) : OnDeviceLlmRunner {
    override val ready: Boolean = true
    override suspend fun complete(system: String, user: String): String = replies(user)
}
