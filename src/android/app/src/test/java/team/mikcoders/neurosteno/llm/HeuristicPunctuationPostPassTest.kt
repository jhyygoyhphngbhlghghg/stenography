package team.mikcoders.neurosteno.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import team.mikcoders.neurosteno.export.Utterance

class HeuristicPunctuationPostPassTest {
    @Test
    fun capitalizesAndAddsPeriodWithoutInventingWords() {
        val out = HeuristicPunctuationPostPass.punctuate("  добрый   день  ")
        assertEquals("Добрый день.", out)
    }

    @Test
    fun keepsExistingQuestionMark() {
        assertEquals("Кто здесь?", HeuristicPunctuationPostPass.punctuate("кто здесь?"))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", HeuristicPunctuationPostPass.punctuate("   "))
    }

    @Test
    fun weightsAreNotPresent() {
        assertFalse(HeuristicPunctuationPostPass().weightsPresent)
    }

    @Test
    fun utteranceCopyKeepsIdsAndTimes() {
        val u = Utterance("speaker_1", 0, 1000, "привет")
        assertEquals("speaker_1", u.speakerId)
        assertEquals("Привет.", HeuristicPunctuationPostPass.punctuate(u.text))
    }
}
