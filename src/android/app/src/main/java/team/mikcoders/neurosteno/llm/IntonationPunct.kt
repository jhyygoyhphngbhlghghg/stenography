package team.mikcoders.neurosteno.llm

/**
 * Hex.Team scoring lock (PR #4 @ 0824df64, cite only): keep-punct WER
 * 0.7321→0.7794; s1 0.00→0.2857 = four punct deletions. Zipformer emits
 * **NONE**, so this path **must ADD** spoken-intonation `,` `.` `?` `!`.
 *
 * Never strip hyp punct. Never rewrite existing commas to textbook placement
 * (commas = speaker intonation gold). е/ё does not count — fold ё→е.
 */
object IntonationPunct {
    private val terminal = setOf('.', '?', '!', '…')
    private val punctChars = setOf('.', '?', '!', ',', ';', ':', '…', '—', '–')
    private val questionStart = Regex(
        "^(кто|что|чего|чем|кому|где|куда|откуда|когда|почему|зачем|как|какой|какая|какое|какие|сколько|разве|неужели)($|\\s|[.,?!])",
        RegexOption.IGNORE_CASE,
    )
    private val greeting = Regex(
        "^(здравствуйте|добрый день|доброе утро|добрый вечер|привет)(?=\\s+\\S)",
        RegexOption.IGNORE_CASE,
    )
    private val vocative = Regex(
        "(^|\\s)(коллеги|друзья|ребята|господа)(?=\\s+\\S)",
        RegexOption.IGNORE_CASE,
    )
    private val leadingFiller = Regex(
        "^(ну|вот|да|так|значит|короче|слушай|ага|ладно|хорошо)(?=\\s+\\S)",
        RegexOption.IGNORE_CASE,
    )
    private val please = Regex("(?<=\\S)\\s+(пожалуйста)($|\\s|[.,?!])", RegexOption.IGNORE_CASE)

    fun foldYo(text: String): String =
        text.replace('ё', 'е').replace('Ё', 'Е')

    fun apply(
        original: String,
        candidate: String? = null,
        nextGapMs: Long = Long.MAX_VALUE,
    ): String {
        val merged = mergeCandidate(original, candidate)
        val withCommas = if (hasComma(merged)) merged else addSpokenCommas(merged)
        return foldYo(restoreTerminal(withCommas, nextGapMs))
    }

    /**
     * Zipformer-empty hyp: take candidate punct (including new commas).
     * If hyp already has commas, keep that layout — only borrow a missing terminal.
     */
    fun mergeCandidate(original: String, candidate: String?): String {
        val src = squeezeSpace(original)
        if (src.isEmpty()) return src
        val cand = candidate?.let { squeezeSpace(it) }.orEmpty()
        if (cand.isEmpty()) return src
        if (contentWords(src) != contentWords(cand)) return src
        if (punctCount(cand) < punctCount(src)) return src
        if (hasComma(src)) return takeTerminalOnly(src, cand)
        return cand
    }

    /** Spoken-intonation commas when Zipformer left the line punct-less. */
    fun addSpokenCommas(text: String): String {
        var t = squeezeSpace(text)
        if (t.isEmpty() || hasComma(t)) return t
        t = greeting.replace(t) { it.value + "," }
        t = vocative.replace(t) { m ->
            val prefix = m.groupValues[1]
            val word = m.groupValues[2]
            "$prefix$word,"
        }
        repeat(3) {
            t = leadingFiller.replace(t) { m ->
                if (m.value.endsWith(",")) m.value else m.value + ","
            }
        }
        t = please.replace(t) { m -> ", ${m.groupValues[1]}${m.groupValues[2]}" }
        return squeezeSpace(t)
    }

    fun restoreTerminal(text: String, nextGapMs: Long): String {
        val t = squeezeSpace(text)
        if (t.isEmpty()) return t
        if (t.last() in terminal) return t
        if (nextGapMs < 0L) return t
        return if (looksLikeQuestion(t)) "$t?" else "$t."
    }

    fun looksLikeQuestion(text: String): Boolean {
        val t = squeezeSpace(text).trimStart(*punctChars.toCharArray())
        return questionStart.containsMatchIn(t)
    }

    private fun takeTerminalOnly(src: String, cand: String): String {
        if (src.isNotEmpty() && src.last() in terminal) return src
        if (cand.isNotEmpty() && cand.last() in terminal) return src + cand.last()
        return src
    }

    private fun hasComma(text: String): Boolean = text.any { it == ',' || it == '—' }

    private fun punctCount(text: String): Int = text.count { it in punctChars }

    private fun squeezeSpace(text: String): String =
        text.replace('\n', ' ').replace('\t', ' ').replace(Regex("\\s+"), " ").trim()

    internal fun contentWords(text: String): List<String> =
        foldYo(text).lowercase()
            .replace(Regex("[^0-9a-zа-яе\\s]+"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
}
