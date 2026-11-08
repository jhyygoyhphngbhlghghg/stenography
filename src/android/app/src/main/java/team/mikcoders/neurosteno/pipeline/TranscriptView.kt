package team.mikcoders.neurosteno.pipeline

import team.mikcoders.neurosteno.export.Utterance
import kotlin.math.max

/**
 * Reader-friendly post-processing shared by the UI layer.
 * - Merge consecutive same-speaker rows separated by a tiny gap (diar
 *   churn often splits one breath into two adjacent labels).
 * - Filter empty/whitespace-only rows that serialize into noise JSON.
 * - Stable per-speaker color palette index (no model logic touched).
 */
object TranscriptView {
    fun mergeAdjacent(utterances: List<Utterance>, maxGapMs: Long = 400L): List<Utterance> {
        if (utterances.size <= 1) return utterances
        val sorted = utterances.sortedBy { it.startMs }
        val out = ArrayList<Utterance>()
        for (u in sorted) {
            if (u.text.isBlank()) continue
            val last = out.lastOrNull()
            if (last != null && last.speakerId == u.speakerId &&
                u.startMs - last.endMs in 0..maxGapMs) {
                out[out.lastIndex] = last.copy(
                    endMs = max(last.endMs, u.endMs),
                    text = last.text.trimEnd() + " " + u.text.trim(),
                )
            } else {
                out += u
            }
        }
        return out
    }

    fun speakerColorIndex(speakerId: String, paletteSize: Int = 8): Int {
        if (paletteSize <= 0) return 0
        val digits = speakerId.filter { it.isDigit() }
        if (digits.isNotEmpty()) return digits.toIntOrNull()?.mod(paletteSize) ?: 0
        return speakerId.hashCode().mod(paletteSize)
    }

    fun stats(utterances: List<Utterance>): ViewStats {
        if (utterances.isEmpty()) return ViewStats(0, 0, 0L)
        val speakers = utterances.map { it.speakerId }.distinct().size
        val words = utterances.sumOf { it.text.split(Regex("\\s+")).count { w -> w.isNotBlank() } }
        val duration = utterances.maxOf { it.endMs } - utterances.minOf { it.startMs }
        return ViewStats(speakers, words, max(0L, duration))
    }
}

data class ViewStats(val speakers: Int, val words: Int, val spanMs: Long)
