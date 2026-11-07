package team.mikcoders.neurosteno.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.mikcoders.neurosteno.export.Utterance

/**
 * Transcript Q&A over the on-device GGUF model (Qwen2-0.5B-Instruct Q4_0).
 *
 * All three tasks — question answering, summarization, key-point extraction —
 * run fully offline through [OnDeviceLlmRunner]. Prompts pin the model to the
 * transcript context only: the tiny model must not hallucinate answers that
 * were never spoken. The transcript is rendered as
 * `[speaker_1] 0:01 – text` and truncated to [MAX_TRANSCRIPT_CHARS] to fit
 * the small context window.
 */
class TranscriptQa(private val runner: OnDeviceLlmRunner) {

    /**
     * Ask a question about the transcript. The LLM answers based ONLY on the
     * transcript context — it must not hallucinate beyond what was said.
     */
    suspend fun ask(transcript: List<Utterance>, question: String): String {
        val prompt = buildString {
            append(QA_INSTRUCTION)
            append("\n\n")
            append("Транскрипт:\n")
            append(formatTranscript(transcript))
            append("\n\nВопрос: ")
            append(question.trim())
            append("\nОтвет:")
        }
        return run(prompt)
    }

    /** Generate a summary of the dialogue. */
    suspend fun summarize(transcript: List<Utterance>): String {
        val prompt = buildString {
            append(QA_INSTRUCTION)
            append("\n\n")
            append("Транскрипт:\n")
            append(formatTranscript(transcript))
            append("\n\n")
            append(SUMMARIZE_INSTRUCTION)
            append("\nОтвет:")
        }
        return run(prompt)
    }

    /** Extract action items / decisions / key points from the dialogue. */
    suspend fun extractKeyPoints(transcript: List<Utterance>): String {
        val prompt = buildString {
            append(QA_INSTRUCTION)
            append("\n\n")
            append("Транскрипт:\n")
            append(formatTranscript(transcript))
            append("\n\n")
            append(KEY_POINTS_INSTRUCTION)
            append("\nОтвет:")
        }
        return run(prompt)
    }

    private suspend fun run(prompt: String): String =
        withContext(Dispatchers.IO) {
            runner.complete(system = SYSTEM, user = prompt).trim()
        }

    /** `[speaker_1] 0:01 – text` per line, truncated to [MAX_TRANSCRIPT_CHARS]. */
    internal fun formatTranscript(transcript: List<Utterance>): String {
        if (transcript.isEmpty()) return "(транскрипт пуст)"
        val full = transcript.joinToString("\n") { u ->
            "[${u.speakerId}] ${formatMs(u.startMs)} – ${u.text.trim()}"
        }
        return if (full.length <= MAX_TRANSCRIPT_CHARS) {
            full
        } else {
            full.take(MAX_TRANSCRIPT_CHARS) + "\n… (транскрипт сокращён)"
        }
    }

    private fun formatMs(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L)) / 1_000L
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes:${"%02d".format(seconds)}"
    }

    companion object {
        const val SYSTEM: String =
            "Ты ассистент, который отвечает на вопросы СТРОГО по предоставленному " +
                "транскрипту диалога. Если ответа нет в транскрипте, скажи " +
                "'В транскрипте нет этой информации'. Не выдумывай."

        const val QA_INSTRUCTION: String =
            "Отвечай на вопрос СТРОГО по транскрипту диалога ниже. " +
                "Если ответа нет в транскрипте, скажи " +
                "'В транскрипте нет этой информации'. Не выдумывай."

        const val SUMMARIZE_INSTRUCTION: String =
            "Сделай краткое саммари диалога (3-5 предложений)."

        const val KEY_POINTS_INSTRUCTION: String =
            "Выпиши ключевые решения, договорённости и action items из диалога. " +
                "Если их нет, скажи 'Ключевых решений не найдено'."

        /** Cap the rendered transcript so it fits the tiny model's context. */
        const val MAX_TRANSCRIPT_CHARS: Int = 3_000
    }
}
