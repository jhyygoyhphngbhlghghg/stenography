package team.mikcoders.neurosteno.llm

import team.mikcoders.neurosteno.export.Utterance
import java.io.File

/**
 * On-device LLM post-pass required by the brief (punctuation / cleanup).
 *
 * TODO(weights): ship a small GGUF (Qwen2-0.5B / Phi-3-mini / Gemma-2B) via
 * llama.cpp or MediaPipe and run it in [GgufLlmPostPass]. Do **not** call a
 * cloud LLM. Until a GGUF file exists under models/llm/, [HeuristicPunctuationPostPass]
 * is the wired implementation — light punctuation/cleanup, not a fake rewrite.
 */
interface LocalLlmPostPass {
    val weightsPresent: Boolean
    val displayName: String

    suspend fun cleanup(utterances: List<Utterance>): List<Utterance>
}

class IdentityLlmPostPass : LocalLlmPostPass {
    override val weightsPresent: Boolean = false
    override val displayName: String = "llm-identity"

    override suspend fun cleanup(utterances: List<Utterance>): List<Utterance> = utterances
}

/**
 * Deterministic punctuation / whitespace cleanup so the module exists in the
 * APK and satisfies the brief checkbox. Not an LLM. Does not invent words.
 */
class HeuristicPunctuationPostPass : LocalLlmPostPass {
    override val weightsPresent: Boolean = false
    override val displayName: String = "llm-heuristic-punctuation (no GGUF)"

    override suspend fun cleanup(utterances: List<Utterance>): List<Utterance> =
        utterances.map { it.copy(text = punctuate(it.text)) }

    companion object {
        fun punctuate(raw: String): String {
            var t = raw.replace('\n', ' ').replace('\t', ' ')
            t = t.replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty()) return t
            // Drop leftover ASR tokens that are clearly markup, not speech.
            t = t.replace(Regex("^\\[/?[A-Za-z0-9_]+]"), "").trim()
            if (t.isEmpty()) return t
            val first = t[0]
            t = if (first.isLetter() && first.isLowerCase()) {
                first.uppercaseChar() + t.substring(1)
            } else {
                t
            }
            val last = t.last()
            if (last !in ".?!…") {
                t += "."
            }
            return t
        }
    }
}

/**
 * Placeholder for a local GGUF runner.
 *
 * TODO(weights): load [gguf] with llama.cpp JNI or MediaPipe LLM Inference.
 * Until that runner lands, we refuse to pretend — cleanup falls back to the
 * heuristic and [weightsPresent] is true only so the UI can say "GGUF on disk
 * but runner not wired".
 */
class GgufLlmPostPass(
    private val gguf: File?,
    private val fallback: LocalLlmPostPass = HeuristicPunctuationPostPass(),
) : LocalLlmPostPass {
    override val weightsPresent: Boolean = gguf != null && gguf.isFile
    override val displayName: String =
        if (weightsPresent) "llm-gguf TODO runner (${gguf!!.name})" else fallback.displayName

    override suspend fun cleanup(utterances: List<Utterance>): List<Utterance> {
        // TODO(weights): run GGUF prompt "Исправь пунктуацию, не меняй слова: …"
        return fallback.cleanup(utterances)
    }
}

object LlmPostPassFactory {
    fun create(gguf: File?): LocalLlmPostPass {
        return if (gguf != null && gguf.isFile) {
            GgufLlmPostPass(gguf)
        } else {
            HeuristicPunctuationPostPass()
        }
    }
}
