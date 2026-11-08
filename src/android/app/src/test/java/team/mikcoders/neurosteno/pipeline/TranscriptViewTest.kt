package team.mikcoders.neurosteno.pipeline

import team.mikcoders.neurosteno.export.Utterance
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptViewTest {
    @Test fun mergesAdjacentSameSpeakerWithinGap() {
        val src = listOf(
            Utterance("speaker_1", 0, 1000, "Привет"),
            Utterance("speaker_1", 1200, 2000, "как дела"),
            Utterance("speaker_2", 2500, 3000, "Хорошо"),
        )
        val merged = TranscriptView.mergeAdjacent(src)
        assertEquals(2, merged.size)
        assertEquals("Привет как дела", merged[0].text)
        assertEquals(2000L, merged[0].endMs)
    }

    @Test fun keepsGapBeyondThresholdSeparate() {
        val src = listOf(
            Utterance("speaker_1", 0, 1000, "А"),
            Utterance("speaker_1", 2000, 3000, "Б"),
        )
        assertEquals(2, TranscriptView.mergeAdjacent(src).size)
    }

    @Test fun dropsBlankAndCountsStats() {
        val src = listOf(
            Utterance("speaker_1", 0, 1000, "раз два"),
            Utterance("speaker_1", 1100, 1200, "   "),
            Utterance("speaker_2", 1500, 2500, "три"),
        )
        val merged = TranscriptView.mergeAdjacent(src)
        assertEquals(2, merged.size)
        val s = TranscriptView.stats(merged)
        assertEquals(2, s.speakers)
        assertEquals(3, s.words)
        assertEquals(2500L, s.spanMs)
    }

    @Test fun colorIndexStable() {
        assertEquals(TranscriptView.speakerColorIndex("speaker_1"), TranscriptView.speakerColorIndex("speaker_1"))
        assertEquals(1, TranscriptView.speakerColorIndex("speaker_1"))
        assertEquals(2, TranscriptView.speakerColorIndex("speaker_2"))
    }
}
