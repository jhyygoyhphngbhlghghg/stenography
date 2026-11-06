package team.mikcoders.neurosteno.llm

import team.mikcoders.neurosteno.export.Utterance

/**
 * On-device LLM post-pass required by the brief (punctuation / cleanup).
 *
 * TODO(weights): ship a small GGUF (Qwen2-0.5B / Phi / Gemma-2B) via llama.cpp or
 * MediaPipe and run it here. Do not call a cloud LLM.
 *
 * Until weights exist in models/llm/, [IdentityLlmPostPass] is wired — identity,
 * not a fake “smart” rewrite.
 */
interface LocalLlmPostPass {
    val weightsPresent: Boolean

    suspend fun cleanup(utterances: List<Utterance>): List<Utterance>
}

class IdentityLlmPostPass : LocalLlmPostPass {
    override val weightsPresent: Boolean = false

    override suspend fun cleanup(utterances: List<Utterance>): List<Utterance> = utterances
}
