package team.mikcoders.neurosteno.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntonationPunctTest {
    @Test
    fun zipformerNoneGetsTerminalPeriod() {
        assertEquals("Добрый день.", IntonationPunct.apply("Добрый день"))
    }

    @Test
    fun greetingPlusVocativeGetsIntonationCommaAndPeriod() {
        val out = IntonationPunct.apply("Здравствуйте коллеги сегодня")
        assertTrue(out.contains(","))
        assertTrue(out.endsWith("."))
        assertTrue(out.contains("коллеги"))
    }

    @Test
    fun s1LikeTurnGetsMultipleSpokenCommasNotJustPeriod() {
        val out = IntonationPunct.apply("Здравствуйте коллеги сегодня мы начинаем но это важно")
        assertTrue("need commas, not only terminal: $out", out.count { it == ',' } >= 2)
        assertTrue(out.endsWith("."))
        assertTrue(out.contains("но"))
    }

    @Test
    fun leadingFillerGetsSpokenComma() {
        val out = IntonationPunct.addSpokenCommas("ну вот послушайте")
        assertTrue(out.startsWith("ну,"))
        assertTrue("discourse commas, not only first filler: $out", out.contains("вот,"))
    }

    @Test
    fun neverStripsExistingCommas() {
        val src = "Ну, вот, послушайте"
        assertEquals("Ну, вот, послушайте.", IntonationPunct.apply(src))
    }

    @Test
    fun neverStripsExistingQuestionMark() {
        assertEquals("кто здесь?", IntonationPunct.apply("кто здесь?"))
    }

    @Test
    fun questionWordGetsQuestionMarkNotPeriod() {
        assertEquals("Кто здесь?", IntonationPunct.apply("Кто здесь"))
    }

    @Test
    fun foldsYoForDisplay() {
        assertEquals("Елка.", IntonationPunct.apply("Ёлка"))
        assertEquals("елка", IntonationPunct.foldYo("ёлка"))
    }

    @Test
    fun punctlessHypAcceptsGgufIntonationComma() {
        val hyp = "Здравствуйте коллеги"
        val cand = "Здравствуйте, коллеги."
        val out = IntonationPunct.apply(hyp, cand)
        assertTrue(out.contains(","))
        assertTrue(out.endsWith("."))
        assertFalse(out.contains("ё"))
    }

    @Test
    fun existingCommaLayoutNotRewrittenToTextbook() {
        val hyp = "Ну, вот послушайте"
        val textbook = "Ну вот, послушайте."
        val out = IntonationPunct.apply(hyp, textbook)
        assertTrue(out.contains("Ну, вот"))
        assertFalse(out.contains("Ну вот,"))
    }

    @Test
    fun candidateThatDropsCommaIsRejected() {
        val hyp = "Ну, хорошо"
        val stripped = "Ну хорошо."
        val out = IntonationPunct.mergeCandidate(hyp, stripped)
        assertTrue(out.contains(","))
    }

    @Test
    fun inventedWordsRejected() {
        val hyp = "привет"
        val out = IntonationPunct.apply(hyp, "Здравствуйте, коллеги!")
        assertTrue(out.startsWith("привет") || out.startsWith("Привет"))
        assertFalse(out.contains("коллеги"))
    }
}
