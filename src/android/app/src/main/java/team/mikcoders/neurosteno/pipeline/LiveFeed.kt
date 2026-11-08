package team.mikcoders.neurosteno.pipeline

import team.mikcoders.neurosteno.export.Utterance

/** Live windowed transcript feed: the pipeline emits one interim row per
 *  window so the UI can show diar labels while audio is still streaming.
 *  Windows are non-overlapping; interim rows are replaced by the final
 *  full-file transcript (single source of truth unchanged). */
object LiveFeed {
    const val WINDOW_MS = 5_000L

    fun interim(rows: List<Utterance>, windows: List<Pair<Long, Long>>): List<Utterance> =
        windows.mapIndexed { i, (a, b) ->
            val inside = rows.filter { it.startMs in a until b }
            val text = inside.joinToString(" ") { it.text }.trim()
            Utterance(
                speakerId = inside.firstOrNull()?.speakerId ?: "speaker_?",
                startMs = a,
                endMs = b,
                text = text.ifBlank { "…(окно ${i + 1}: речь не распознана)" },
            )
        }

    fun windowsFor(durationMs: Long, windowMs: Long = WINDOW_MS): List<Pair<Long, Long>> {
        if (durationMs <= 0) return emptyList()
        val out = ArrayList<Pair<Long, Long>>()
        var a = 0L
        while (a < durationMs) {
            val b = minOf(a + windowMs, durationMs)
            out += a to b
            a = b
        }
        return out
    }
}
