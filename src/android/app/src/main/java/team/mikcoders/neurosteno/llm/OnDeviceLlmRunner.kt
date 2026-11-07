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

    /**
     * prompts longer than this are skipped for the GGUF: a very long utterance
     * would overflow the small context (ctx 1024) and at best waste time,
     * at worst abort the llama.cpp context. Kotlin estimate: Russian text
     * tokenizes to roughly 3 chars/token, punctuation-less ASR output is
     * dense, so 2 chars/token is a conservative lower bound for skip length —
     * prompts under it stay comfortably inside ctx.
     */
    internal val promptSkipChars: Int = (contextSize - maxTokens - NEAR_HEADROOM_TOKENS) * CHARS_PER_TOKEN

    companion object {
        const val CHARS_PER_TOKEN = 2
        const val NEAR_HEADROOM_TOKENS = 96
    }

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

    override suspend fun complete(system: String, user: String): String {
        // Skip GGUF for prompts the small context cannot hold: throwing here
        // routes every caller to the heuristic punctuation fallback. The
        // measured native failure mode ({"error":"decode failed"}) is
        // catchable, but skipping costs nothing and cannot poison a session.
        require(user.length + system.length < promptSkipChars) {
            "prompt too long for GGUF context: ${user.length} chars (skip > $promptSkipChars)"
        }
        return session.run { getModel ->
            Llama.complete(
                getModel(),
                prompt = user,
                systemPrompt = system,
                maxTokens = maxTokens,
            ).text.trim()
        }
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
