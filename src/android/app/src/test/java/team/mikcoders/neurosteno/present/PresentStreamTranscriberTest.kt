package team.mikcoders.neurosteno.present

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.mikcoders.neurosteno.audio.Pcm
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.Utterance
import team.mikcoders.neurosteno.pipeline.PipelineProgress
import team.mikcoders.neurosteno.pipeline.PcmAudio
import team.mikcoders.neurosteno.pipeline.StenoPipeline

class PresentStreamTranscriberTest {
    @Test
    fun nStreamsAreNPipelineCallsNeverMixedPcm() = runBlocking {
        val fake = RecordingPipeline()
        val t = PresentStreamTranscriber(fake, modelsMissing = false)
        val a = s16leSine(nSamples = Pcm.TARGET_HZ / 2, freq = 180.0, amp = 0.35f)
        val b = s16leSine(nSamples = Pcm.TARGET_HZ / 2, freq = 240.0, amp = 0.28f)
        val ra = t.transcribeIsolated("s01", a, speakerHint = "qr-1") as IsolatedResult.Ok
        val rb = t.transcribeIsolated("s02", b, speakerHint = "qr-2") as IsolatedResult.Ok
        assertEquals(2, fake.calls.size)
        assertEquals("present:s01", fake.calls[0].sourceName)
        assertEquals("present:s02", fake.calls[1].sourceName)
        assertEquals(Pcm.TARGET_HZ / 2, fake.calls[0].samples.size)
        assertEquals(Pcm.TARGET_HZ / 2, fake.calls[1].samples.size)
        assertFalse(fake.calls[0].samples.contentEquals(fake.calls[1].samples))
        val concat = fake.calls[0].samples + fake.calls[1].samples
        assertFalse(
            "pipeline must not receive concatenated PCM from two streams",
            fake.calls.any { it.samples.contentEquals(concat) },
        )
        assertEquals("s01", ra.streamId)
        assertEquals("s02", rb.streamId)
        assertEquals("qr-1", ra.speakerHint)
        assertEquals("speaker_1", ra.file.transcript.single().speakerId)
        assertEquals("speaker_1", rb.file.transcript.single().speakerId)
    }

    @Test
    fun modelsMissingDoesNotCallPipeline() = runBlocking {
        val fake = RecordingPipeline()
        val t = PresentStreamTranscriber(fake, modelsMissing = true)
        val r = t.transcribeIsolated("s01", ByteArray(32) { 1 })
        assertTrue(r is IsolatedResult.ModelsMissing)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun clippedMicFailsLoudWithoutCallingPipeline() = runBlocking {
        val fake = RecordingPipeline()
        val t = PresentStreamTranscriber(fake, modelsMissing = false)
        // 16-bit full-scale square wave → clip_ratio ~1.0
        val pcm = ByteArray(16000) { i ->
            when (i % 4) {
                0 -> 0xff.toByte()
                1 -> 0x7f.toByte()
                2 -> 0x00
                else -> 0x80.toByte()
            }
        }
        val r = t.transcribeIsolated("s01", pcm)
        assertTrue(r is IsolatedResult.Rejected)
        r as IsolatedResult.Rejected
        assertEquals(PresentConnectCodes.BAD_AUDIO, r.code)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun hushDoesNotReachPipeline() = runBlocking {
        val fake = RecordingPipeline()
        val t = PresentStreamTranscriber(fake, modelsMissing = false)
        val pcm = ByteArray(Pcm.TARGET_HZ * 2) { 0 } // 1 s of silence s16le
        val r = t.transcribeIsolated("s01", pcm) as IsolatedResult.Ok
        assertTrue(r.file.transcript.isEmpty())
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun emptyPcmSkipped() = runBlocking {
        val fake = RecordingPipeline()
        val t = PresentStreamTranscriber(fake, modelsMissing = false)
        val r = t.transcribeIsolated("s01", ByteArray(0))
        assertTrue(r is IsolatedResult.Empty)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun twentyIsolatedCallsStayTwentyBuffers() = runBlocking {
        val fake = RecordingPipeline()
        val t = PresentStreamTranscriber(fake, modelsMissing = false)
        repeat(20) { i ->
            val id = "s%02d".format(i + 1)
            val pcm = s16leSine(nSamples = Pcm.TARGET_HZ / 2, freq = 160.0 + i * 7, amp = 0.3f)
            t.transcribeIsolated(id, pcm)
        }
        assertEquals(20, fake.calls.size)
        assertEquals(20, fake.calls.map { it.sourceName }.toSet().size)
        fake.calls.forEachIndexed { i, audio ->
            assertEquals("present:s%02d".format(i + 1), audio.sourceName)
            assertEquals(Pcm.TARGET_HZ / 2, audio.samples.size)
        }
    }

    @Test
    fun speakerIdIsDiarInsideStreamNotStreamId() = runBlocking {
        val fake = RecordingPipeline(speakerId = "speaker_2")
        val t = PresentStreamTranscriber(fake, modelsMissing = false)
        val r = t.transcribeIsolated(
            "s09",
            s16leSine(nSamples = Pcm.TARGET_HZ / 2, freq = 190.0, amp = 0.3f),
            speakerHint = "seat-9",
        ) as IsolatedResult.Ok
        assertEquals("speaker_2", r.file.transcript.single().speakerId)
        assertEquals("seat-9", r.speakerHint)
        assertEquals("s09", r.streamId)
    }
}

private fun s16leSine(nSamples: Int, freq: Double, amp: Float, hz: Int = Pcm.TARGET_HZ): ByteArray {
    val out = ByteArray(nSamples * 2)
    var i = 0
    while (i < nSamples) {
        val s = (amp * kotlin.math.sin(2.0 * Math.PI * freq * i / hz) * 32767.0)
            .toInt()
            .coerceIn(-32767, 32767)
        out[i * 2] = (s and 0xff).toByte()
        out[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        i++
    }
    return out
}

private class RecordingPipeline(
    private val speakerId: String = "speaker_1",
) : StenoPipeline {
    override val displayName: String = "recording-fake"
    val calls = mutableListOf<PcmAudio>()

    override suspend fun transcribe(
        audio: PcmAudio,
        onProgress: (PipelineProgress) -> Unit,
    ): TranscriptFile {
        calls += audio
        return TranscriptFile(
            listOf(
                Utterance(
                    speakerId = speakerId,
                    startMs = 0,
                    endMs = audio.durationMs,
                    text = audio.sourceName,
                ),
            ),
        )
    }
}
