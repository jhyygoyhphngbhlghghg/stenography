package team.mikcoders.neurosteno.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.mikcoders.neurosteno.export.Utterance

class HeuristicPunctuationPostPassTest {
    @Test
    fun zipformerNoneGetsPeriodWithoutInventingWords() {
        val out = HeuristicPunctuationPostPass.punctuate("  добрый   день  ")
        assertEquals("добрый день.", out)
    }

    @Test
    fun addsGreetingVocativeComma() {
        val out = HeuristicPunctuationPostPass.punctuate("здравствуйте коллеги")
        assertTrue(out.contains(","))
        assertTrue(out.endsWith("."))
    }

    @Test
    fun keepsExistingQuestionMark() {
        assertEquals("кто здесь?", HeuristicPunctuationPostPass.punctuate("кто здесь?"))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", HeuristicPunctuationPostPass.punctuate("   "))
    }

    @Test
    fun weightsAreNotPresent() {
        assertFalse(HeuristicPunctuationPostPass().weightsPresent)
        assertFalse(HeuristicPunctuationPostPass().isOnDeviceLlm)
    }

    @Test
    fun utteranceCopyKeepsIdsAndTimes() {
        val u = Utterance("speaker_1", 0, 1000, "привет")
        assertEquals("speaker_1", u.speakerId)
        assertEquals("привет.", HeuristicPunctuationPostPass.punctuate(u.text))
    }

    @Test
    fun keepsIntonationCommas() {
        assertEquals("ну, вот.", HeuristicPunctuationPostPass.punctuate("ну, вот"))
    }

    @Test
    fun zipformerLikeStringGetsSpokenComma() {
        val out = HeuristicPunctuationPostPass.punctuate("сегодня мы начинаем работу если все готовы")
        assertTrue(out.contains(","))
        assertTrue(out.endsWith("."))
    }
}
