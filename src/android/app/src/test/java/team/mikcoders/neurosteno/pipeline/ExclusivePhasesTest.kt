package team.mikcoders.neurosteno.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExclusivePhasesTest {
    @Test
    fun releasesAsrBeforeLlmAndPassesRawThrough() = runBlocking {
        val events = mutableListOf<String>()
        val out = runExclusivePhases(
            asrPhase = { events += "asr"; listOf("raw") },
            releaseAsr = { events += "release" },
            llmPhase = { raw -> events += "llm"; raw.single() + "!" },
        )
        assertEquals(listOf("asr", "release", "llm"), events)
        assertEquals("raw!", out)
    }

    @Test
    fun releasesAsrWhenAsrFailsAndSkipsLlm() = runBlocking {
        val events = mutableListOf<String>()
        try {
            runExclusivePhases<String, String>(
                asrPhase = { events += "asr"; error("decode failed") },
                releaseAsr = { events += "release" },
                llmPhase = { events += "llm"; it },
            )
            fail("failure swallowed")
        } catch (_: IllegalStateException) {
            assertEquals(listOf("asr", "release"), events)
        }
    }

    @Test
    fun releasesAsrOnCancellationAndSkipsLlm() = runBlocking {
        val events = mutableListOf<String>()
        try {
            runExclusivePhases<String, String>(
                asrPhase = { events += "asr"; throw CancellationException("cancelled") },
                releaseAsr = { events += "release" },
                llmPhase = { events += "llm"; it },
            )
            fail("cancellation swallowed")
        } catch (_: CancellationException) {
            assertEquals(listOf("asr", "release"), events)
        }
    }

    @Test
    fun llmFailureDoesNotResurrectAsr() = runBlocking {
        val events = mutableListOf<String>()
        try {
            runExclusivePhases<String, String>(
                asrPhase = { events += "asr"; "raw" },
                releaseAsr = { events += "release" },
                llmPhase = { events += "llm"; error("llm failed") },
            )
            fail("failure swallowed")
        } catch (_: IllegalStateException) {
            assertEquals(listOf("asr", "release", "llm"), events)
        }
    }

    @Test
    fun releaseFailurePropagatesWithoutRunningLlm() = runBlocking {
        val events = mutableListOf<String>()
        try {
            runExclusivePhases<String, String>(
                asrPhase = { events += "asr"; "raw" },
                releaseAsr = { events += "release"; error("release failed") },
                llmPhase = { events += "llm"; it },
            )
            fail("release failure swallowed")
        } catch (_: IllegalStateException) {
            assertEquals(listOf("asr", "release"), events)
        }
    }
}
