package team.mikcoders.neurosteno.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.TranscriptJson
import team.mikcoders.neurosteno.export.Utterance
import java.io.File

class GgufLlmPostPassTest {
    private fun gguf(): File {
        val f = File.createTempFile("qwen2-0_5b-instruct-q4_0", ".gguf")
        f.writeBytes(byteArrayOf(1, 2, 3, 4))
        f.deleteOnExit()
        return f
    }

    @Test
    fun factoryWithWeightsIsOnDeviceLlamaNotIdentity() {
        val pass = LlmPostPassFactory.create(
            gguf(),
            ScriptedLlmRunner({ it.substringAfter("Реплика: ") }),
        )
        assertTrue(pass.weightsPresent)
        assertTrue(pass.isOnDeviceLlm)
        assertTrue(pass.displayName.contains("llama.cpp"))
        assertFalse(pass.displayName.contains("identity"))
        assertFalse(pass is IdentityLlmPostPass)
        assertFalse(pass is HeuristicPunctuationPostPass)
    }

    @Test
    fun factoryWithoutWeightsDoesNotClaimIdentityAsLlm() {
        val pass = LlmPostPassFactory.create(null)
        assertFalse(pass.weightsPresent)
        assertFalse(pass.isOnDeviceLlm)
        assertFalse(pass.displayName.contains("identity"))
    }

    @Test
    fun punctlessZipformerAcceptsAddedIntonationPunct() = runBlocking {
        val runner = ScriptedLlmRunner({ "Здравствуйте, коллеги." })
        val pass = GgufLlmPostPass(gguf(), runner)
        val u = Utterance("speaker_1", 0, 4_000, "Здравствуйте коллеги")
        val out = pass.cleanup(listOf(u)).single()
        assertTrue("must ADD comma (Zipformer emits none)", out.text.contains(","))
        assertTrue(out.text.endsWith("."))
        assertEquals("speaker_1", out.speakerId)
        val json = TranscriptJson.encode(TranscriptFile(listOf(out)))
        assertTrue(TranscriptJson.matchesBriefShape(json))
    }

    @Test
    fun cancellationStopsLaterUtterancesInsteadOfUsingHeuristics() = runBlocking {
        var calls = 0
        val runner = object : OnDeviceLlmRunner {
            override val ready = true
            override val engineName = "test"
            override suspend fun complete(system: String, user: String): String {
                calls++
                throw CancellationException("cancelled")
            }
        }
        val pass = GgufLlmPostPass(gguf(), runner)
        try {
            pass.cleanup(List(3) { Utterance("speaker_1", 0, 1_000, "Текст") })
            org.junit.Assert.fail("cancellation was swallowed")
        } catch (_: CancellationException) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun memoryAndLifecycleSignalsReachTheNativeOwner() = runBlocking {
        var trims = 0
        var closes = 0
        val runner = object : OnDeviceLlmRunner {
            override val ready = true
            override val engineName = "test"
            override suspend fun complete(system: String, user: String) = user
            override suspend fun trimMemory() { trims++ }
            override suspend fun close() { closes++ }
        }
        val pass = GgufLlmPostPass(gguf(), runner)
        pass.trimMemory()
        pass.close()
        assertEquals(1, trims)
        assertEquals(1, closes)
    }

    @Test
    fun neverStripsAsrPunctuation() = runBlocking {
        val runner = ScriptedLlmRunner({ "Ну хорошо." })
        val pass = GgufLlmPostPass(gguf(), runner)
        val out = pass.cleanup(listOf(Utterance("speaker_1", 0, 1_000, "Ну, хорошо"))).single()
        assertTrue(out.text.contains(","))
    }
}
