package team.mikcoders.neurosteno.present

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.TranscriptJson
import team.mikcoders.neurosteno.export.Utterance
import team.mikcoders.neurosteno.pipeline.PipelineProgress
import team.mikcoders.neurosteno.pipeline.PcmAudio
import team.mikcoders.neurosteno.pipeline.StenoPipeline
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Thresholds mirrored from platform PR #3 @ f5ff5e2. Synthetic PCM only.
 * Do not game Eval WORST_CASE wavs.
 */
class StreamPcmGateTest {
    @Test
    fun silenceIsLowEnergy() {
        val q = StreamPcmGate.inspect(StreamPcmSynth.silence())
        assertEquals(PresentConnectCodes.LOW_ENERGY, q.rejectCode)
        assertFalse(q.asrAllowed)
        assertEquals(0.0, q.rmsFs, 1e-12)
    }

    @Test
    fun tinyNoiseIsLowEnergy() {
        val q = StreamPcmGate.inspect(StreamPcmSynth.tinyNoise())
        assertEquals(PresentConnectCodes.LOW_ENERGY, q.rejectCode)
        assertTrue(q.rmsFs < StreamPcmGate.RMS_REJECT_FS)
        assertTrue(q.rmsDbfs < -80.0)
    }

    @Test
    fun clippedSquareIsClipped() {
        val q = StreamPcmGate.inspect(StreamPcmSynth.clippedSquare())
        assertEquals(PresentConnectCodes.CLIPPED, q.rejectCode)
        assertEquals(1.0, q.clipRatio, 1e-9)
        assertFalse(q.asrAllowed)
    }

    @Test
    fun stuckAndExtremeDcAreBadMic() {
        val stuck = StreamPcmGate.inspect(StreamPcmSynth.constant(8000))
        assertEquals(PresentConnectCodes.BAD_MIC, stuck.rejectCode)
        val extreme = StreamPcmGate.inspect(StreamPcmSynth.constant(20000))
        assertEquals(PresentConnectCodes.BAD_MIC, extreme.rejectCode)
    }

    @Test
    fun cleanTonePasses() {
        val q = StreamPcmGate.inspect(StreamPcmSynth.sine())
        assertTrue(q.asrAllowed)
        assertEquals(null, q.rejectCode)
        assertTrue(q.rmsFs > StreamPcmGate.RMS_SOFT_FS)
        assertEquals(0.0, q.clipRatio, 1e-12)
    }

    @Test
    fun constantsMatchPlatformF5ff5e2() {
        assertEquals(0.008, StreamPcmGate.RMS_REJECT_FS, 0.0)
        assertEquals(0.012, StreamPcmGate.PEAK_REJECT_FS, 0.0)
        assertEquals(0.02, StreamPcmGate.CLIP_REJECT, 0.0)
        assertEquals(0.20, StreamPcmGate.DC_REJECT_FS, 0.0)
        assertEquals(1e-7, StreamPcmGate.STUCK_VARIANCE_FS, 0.0)
        assertEquals(800, StreamPcmGate.MIN_SAMPLES)
        assertEquals(32_760, StreamPcmGate.CLIP_S16)
        assertEquals(32768.0, StreamPcmGate.FULL_SCALE, 0.0)
    }

    @Test
    fun transcriberSilenceSkipsAsrEmitsLowEnergy() = runBlocking {
        val fake = GateRecordingPipeline()
        val t = PresentStreamTranscriber(fake, modelsMissing = false)
        val r = t.transcribeIsolated("s04", StreamPcmSynth.silence())
        assertTrue(r is IsolatedResult.Rejected)
        r as IsolatedResult.Rejected
        assertEquals(PresentConnectCodes.LOW_ENERGY, r.code)
        assertTrue(fake.calls.isEmpty())
        val wire = PresentConnectFrames.hardRejectThenClosed("s04", r.code, r.message)
        assertEquals(2, wire.size)
        assertTrue(wire[0].contains("\"code\":\"LOW_ENERGY\""))
        assertTrue(wire[0].contains("\"stream_id\":\"s04\""))
        assertTrue(wire[1].contains("\"type\":\"stream.closed\""))
    }

    @Test
    fun transcriberClippedSkipsAsr() = runBlocking {
        val fake = GateRecordingPipeline()
        val t = PresentStreamTranscriber(fake, modelsMissing = false)
        val r = t.transcribeIsolated("s07", StreamPcmSynth.clippedSquare())
        assertTrue(r is IsolatedResult.Rejected)
        assertEquals(PresentConnectCodes.CLIPPED, (r as IsolatedResult.Rejected).code)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun transcriberCleanToneInvokesAsrFourFieldBrief() = runBlocking {
        val fake = GateRecordingPipeline()
        val t = PresentStreamTranscriber(fake, modelsMissing = false)
        val r = t.transcribeIsolated("s01", StreamPcmSynth.sine()) as IsolatedResult.Ok
        assertEquals(1, fake.calls.size)
        assertTrue(fake.calls.single().samples.size >= StreamPcmGate.MIN_SAMPLES)
        val encoded = TranscriptJson.encode(r.file)
        val root = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("transcript"), root.keys)
        val item = root["transcript"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), item.keys)
        assertFalse(item.containsKey("stream_id"))
        assertFalse(item.containsKey("quality"))
        assertFalse(item.containsKey("code"))
        assertFalse(item.containsKey("softFlags"))
    }

    @Test
    fun twoStreamsGatedSeparatelyNeverMixed() {
        val quiet = StreamPcmGate.inspect(StreamPcmSynth.silence())
        val tone = StreamPcmGate.inspect(StreamPcmSynth.sine())
        assertEquals(PresentConnectCodes.LOW_ENERGY, quiet.rejectCode)
        assertTrue(tone.asrAllowed)
        assertFalse(quiet.asrAllowed)
    }
}

private class GateRecordingPipeline : StenoPipeline {
    override val displayName: String = "gate-recording"
    val calls = mutableListOf<PcmAudio>()

    override suspend fun transcribe(
        audio: PcmAudio,
        onProgress: (PipelineProgress) -> Unit,
    ): TranscriptFile {
        calls += audio
        return TranscriptFile(listOf(Utterance("speaker_1", 10, 40, "from ${audio.sourceName}")))
    }
}
