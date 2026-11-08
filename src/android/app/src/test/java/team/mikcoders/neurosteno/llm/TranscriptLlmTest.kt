package team.mikcoders.neurosteno.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import team.mikcoders.neurosteno.export.Utterance

class TranscriptLlmTest {
    private val rows = listOf(
        Utterance("speaker_1", 0, 2000, "Обсуждали сроки поставки к пятнице"),
        Utterance("speaker_2", 2100, 4000, "Подтверждаю, поставка в пятницу"),
    )

    @Test fun summarizeUsesLlmWhenPresent() = runBlocking {
        val out = TranscriptLlm.summarize(ScriptedLlmRunner({ "Итог: поставка в пятницу." }), rows)
        assertTrue(out.contains("пятницу"))
    }

    @Test fun summarizeFallsBackExtractively() = runBlocking {
        val out = TranscriptLlm.summarize(null, rows)
        assertTrue(out.contains("спикеров"))
        assertTrue(out.contains("speaker_1"))
    }

    @Test fun askFindsKeywordsWithoutLlm() = runBlocking {
        val out = TranscriptLlm.ask(null, rows, "когда поставка?")
        assertTrue(out.contains("пятниц"))
    }

    @Test fun emptyTranscriptGuards() = runBlocking {
        assertTrue(TranscriptLlm.summarize(null, emptyList()).contains("Пусто"))
        assertTrue(TranscriptLlm.ask(null, emptyList(), "что?").contains("расшифровку"))
    }

    @Test fun llmFailureFallsBack() = runBlocking {
        val bad = object : OnDeviceLlmRunner {
            override val ready = true
            override val engineName = "bad"
            override suspend fun complete(system: String, user: String): String = throw RuntimeException("boom")
        }
        assertTrue(TranscriptLlm.summarize(bad, rows).contains("спикеров"))
    }
}
