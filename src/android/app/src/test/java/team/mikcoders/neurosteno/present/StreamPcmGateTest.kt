package team.mikcoders.neurosteno.present

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.mikcoders.neurosteno.export.Utterance

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
    fun sessionSilenceSkipsAsrAndEmitsLowEnergy() = runBlocking {
        var asrCalls = 0
        val session = PathBSession(
            asr = PathBAsr { _, _ ->
                asrCalls++
                listOf(Utterance("cluster", 0, 1, "nope"))
            },
        )
        session.bindStart("s04")
        val out = session.finish("s04", StreamPcmSynth.silence())
        assertEquals(0, asrCalls)
        assertFalse(out.asrInvoked)
        assertEquals(PresentConnectCodes.LOW_ENERGY, out.rejectCode)
        assertTrue(out.utterances.isEmpty())
        assertTrue(session.brief().transcript.isEmpty())
        assertTrue(out.wireJson.single().contains("\"code\":\"LOW_ENERGY\""))
        assertTrue(out.wireJson.single().contains("\"stream_id\":\"s04\""))
        assertEquals(PresentConnectCodes.LOW_ENERGY, session.micFlags().single().code)
    }

    @Test
    fun sessionClippedSkipsAsr() = runBlocking {
        var asrCalls = 0
        val session = PathBSession(
            asr = PathBAsr { _, _ ->
                asrCalls++
                listOf(Utterance("cluster", 0, 1, "nope"))
            },
        )
        session.bindStart("s07")
        val out = session.finish("s07", StreamPcmSynth.clippedSquare())
        assertEquals(0, asrCalls)
        assertEquals(PresentConnectCodes.CLIPPED, out.rejectCode)
        assertTrue(session.brief().transcript.isEmpty())
    }

    @Test
    fun sessionCleanToneInvokesAsrAndKeepsFourFieldBrief() = runBlocking {
        var asrCalls = 0
        val session = PathBSession(
            asr = PathBAsr { streamId, audio ->
                asrCalls++
                assertTrue(audio.samples.size >= StreamPcmGate.MIN_SAMPLES)
                listOf(Utterance("cluster", 10, 40, "from $streamId"))
            },
        )
        session.bindStart("s01")
        val out = session.finish("s01", StreamPcmSynth.sine())
        assertEquals(1, asrCalls)
        assertTrue(out.asrInvoked)
        assertEquals(null, out.rejectCode)
        assertEquals("speaker_1", out.utterances.single().speakerId)
        val encoded = PresentConnectWire.briefJson(session.brief().transcript)
        val root = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("transcript"), root.keys)
        val item = root["transcript"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), item.keys)
        assertFalse(item.containsKey("stream_id"))
        assertFalse(item.containsKey("quality"))
        assertFalse(item.containsKey("code"))
    }
}
