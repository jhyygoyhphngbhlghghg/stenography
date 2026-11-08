package team.mikcoders.neurosteno.llm

import team.mikcoders.neurosteno.export.Utterance

/** Offline summary + Q&A over the finished transcript. Deliberately outside
 *  [IntonationPunct]: it must never rewrite the transcript JSON. The 0.5B
 *  GGUF gets a bounded chunk (ctx ~1024) and a Heuristic fallback for stub
 *  builds so the feature degrades to extractive bullets, never crashes. */
object TranscriptLlm {
    const val SUM_SYSTEM =
        "Ты — помощник протокола. Резюмируй русскую стенограмму: что обсуждали, решения, задачи, сроки. " +
            "Только по тексту ниже, без выдумок. Коротко, булгетами."
    const val ASK_SYSTEM =
        "Ты отвечаешь по русской стенограмме. Отвечай только из текста ниже, если данных нет — так и скажи."

    fun transcriptText(rows: List<Utterance>): String =
        rows.joinToString("\n") { "${it.speakerId}: ${it.text}" }

    fun chunkPrompt(rows: List<Utterance>, maxChars: Int = 1600): String {
        val text = transcriptText(rows)
        if (text.length <= maxChars) return text
        val head = text.take(maxChars / 2)
        val tail = text.takeLast(maxChars / 2)
        return head + "\n…\n" + tail
    }

    suspend fun summarize(runner: OnDeviceLlmRunner?, rows: List<Utterance>): String {
        if (rows.isEmpty()) return "Пусто: нечего резюмировать."
        val prompt = chunkPrompt(rows)
        runner?.let {
            try {
                val out = it.complete(SUM_SYSTEM, prompt)
                if (out.isNotBlank()) return out
            } catch (_: Throwable) {}
        }
        return heuristicSummary(rows)
    }

    suspend fun ask(runner: OnDeviceLlmRunner?, rows: List<Utterance>, question: String): String {
        val q = question.trim()
        if (q.isEmpty()) return "Задайте вопрос по стенограмме."
        if (rows.isEmpty()) return "Сначала выполните расшифровку."
        val context = chunkPrompt(rows, 1200)
        runner?.let {
            try {
                val out = it.complete(ASK_SYSTEM, "Стенограмма:\n$context\n\nВопрос: $q")
                if (out.isNotBlank()) return out
            } catch (_: Throwable) {}
        }
        return heuristicAnswer(rows, q)
    }

    fun heuristicSummary(rows: List<Utterance>): String = buildString {
        val speakers = rows.map { it.speakerId }.distinct()
        val words = rows.sumOf { it.text.split(Regex("\\s+")).count { w -> w.isNotBlank() } }
        appendLine("Экстрактивное резюме (офлайн, без GGUF):")
        appendLine("- Реплик: ${rows.size}, спикеров: ${speakers.size} (${speakers.joinToString(", ")}), слов: $words")
        for (u in rows.take(5)) appendLine("- ${u.speakerId}: ${u.text.take(120)}")
        if (rows.size > 5) appendLine("- … и ещё ${rows.size - 5} реплик")
    }

    fun heuristicAnswer(rows: List<Utterance>, question: String): String {
        val words = question.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }
        val hits = if (words.isEmpty()) rows else rows.filter { u ->
            words.any { u.text.lowercase().contains(it) }
        }
        if (hits.isEmpty()) {
            return "По найденным репликам прямого ответа нет. В стенограмме ${rows.size} реплик, ${rows.map { it.speakerId }.distinct().size} спикера(ов)."
        }
        return buildString {
            appendLine("Найдено по вашим словам (${hits.size}):")
            hits.take(4).forEach { appendLine("- ${it.speakerId}: ${it.text.take(180)}") }
        }
    }
}
