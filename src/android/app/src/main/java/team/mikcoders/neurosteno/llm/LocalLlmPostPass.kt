package team.mikcoders.neurosteno.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import team.mikcoders.neurosteno.export.Utterance
import java.io.File

/**
 * On-device LLM post-pass (Hex.Team TZ «локальная LLM»).
 *
 * Identity / heuristic-only **fails** the brief. Tiny GGUF
 * (Qwen2-0.5B-Instruct Q4_0) is loaded from filesDir/models/llm/. That tree is
 * filled either by `AssetModelUnpack` when the APK was built with
 * `-PembedLlm=true` (the hand-in check APK) or by
 * `scripts/download-models.sh` + adb push during development. llama.cpp only
 * adds spoken-intonation punct ([IntonationPunct]): Zipformer emits NONE.
 */
interface LocalLlmPostPass {
    val weightsPresent: Boolean
    val isOnDeviceLlm: Boolean
    val displayName: String

    suspend fun cleanup(utterances: List<Utterance>): List<Utterance>
    suspend fun trimMemory() {}
    suspend fun close() {}
}

class IdentityLlmPostPass : LocalLlmPostPass {
    override val weightsPresent: Boolean = false
    override val isOnDeviceLlm: Boolean = false
    override val displayName: String = "llm-identity"

    override suspend fun cleanup(utterances: List<Utterance>): List<Utterance> = utterances
}

/**
 * Intonation-safe punct when GGUF is absent. **Not** the check-APK LLM.
 * Must not strip hyp punctuation or rewrite commas.
 */
class HeuristicPunctuationPostPass : LocalLlmPostPass {
    override val weightsPresent: Boolean = false
    override val isOnDeviceLlm: Boolean = false
    override val displayName: String = "llm-weights-missing (intonation punct only)"

    override suspend fun cleanup(utterances: List<Utterance>): List<Utterance> =
        applyIntonation(utterances)

    companion object {
        fun punctuate(raw: String, nextGapMs: Long = Long.MAX_VALUE): String =
            IntonationPunct.apply(raw, candidate = null, nextGapMs = nextGapMs)

        fun applyIntonation(utterances: List<Utterance>): List<Utterance> =
            utterances.mapIndexed { i, u ->
                val nextGap = if (i + 1 < utterances.size) {
                    (utterances[i + 1].startMs - u.endMs).coerceAtLeast(0L)
                } else {
                    Long.MAX_VALUE
                }
                u.copy(text = IntonationPunct.apply(u.text, nextGapMs = nextGap))
            }
    }
}

class MissingWeightsLlmPostPass : LocalLlmPostPass by HeuristicPunctuationPostPass()

/**
 * Real tiny GGUF via llama.cpp. Prompt asks only for spoken-intonation punct.
 * Output is merged with [IntonationPunct] so the model cannot strip marks or
 * rewrite commas to textbook placement.
 */
class GgufLlmPostPass(
    private val gguf: File,
    private val runner: OnDeviceLlmRunner,
) : LocalLlmPostPass {
    override val weightsPresent: Boolean = gguf.isFile && gguf.length() > 0L
    override val isOnDeviceLlm: Boolean = true
    override val displayName: String = runner.engineName

    override suspend fun trimMemory() = runner.trimMemory()
    override suspend fun close() = runner.close()

    override suspend fun cleanup(utterances: List<Utterance>): List<Utterance> {
        if (!weightsPresent) {
            return HeuristicPunctuationPostPass.applyIntonation(utterances)
        }
        return utterances.mapIndexed { i, u ->
            currentCoroutineContext().ensureActive()
            val nextGap = if (i + 1 < utterances.size) {
                (utterances[i + 1].startMs - u.endMs).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }
            val candidate = try {
                runner.complete(SYSTEM, userPrompt(u.text, nextGap))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            u.copy(text = IntonationPunct.apply(u.text, candidate, nextGap))
        }
    }

    companion object {
        const val SYSTEM =
            "ASR (Zipformer) не ставит знаки. ДОБАВЬ пунктуацию устной речи: запятые, точки, ? !. " +
                "Запятая = пауза/интонация говорящего, не школьное правило. " +
                "НЕ удаляй уже стоящие знаки. НЕ переставляй чужие запятые. " +
                "Не меняй слова. ё и е — один звук, пиши е. Верни только исправленную реплику."

        fun userPrompt(text: String, nextGapMs: Long): String =
            "Пауза после реплики: ${nextGapMs.coerceAtMost(10_000L)} мс.\nРеплика: $text"
    }
}

object LlmPostPassFactory {
    fun create(gguf: File?, runner: OnDeviceLlmRunner? = null): LocalLlmPostPass {
        if (gguf != null && gguf.isFile && gguf.length() > 0L) {
            return GgufLlmPostPass(gguf, runner ?: LlamaCppGgufRunner(gguf))
        }
        return MissingWeightsLlmPostPass()
    }
}
