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
    fun unboundSilenceIsLowEnergy() {
        val q = StreamPcmGate.inspect(StreamPcmSynth.silence())
        assertEquals(PresentConnectCodes.LOW_ENERGY, q.rejectCode)
        assertFalse(q.asrAllowed)
        assertEquals(0.0, q.rmsFs, 1e-12)
    }

    @Test
    fun boundNearSilenceIsSoftLowStillAsr() {
        val q = StreamPcmGate.inspect(StreamPcmSynth.silence(), boundSeat = true)
        assertTrue(q.asrAllowed)
        assertEquals(null, q.rejectCode)
        assertTrue(q.softFlags.contains("SOFT_LOW"))
        val tiny = StreamPcmGate.inspect(StreamPcmSynth.tinyNoise(), boundSeat = true)
        assertTrue(tiny.asrAllowed)
        assertTrue(tiny.softFlags.contains("SOFT_LOW"))
    }

    @Test
    fun emptyBufferHardLowEnergyEvenWhenBound() {
        val q = StreamPcmGate.inspect(StreamPcmSynth.empty(), boundSeat = true)
        assertEquals(PresentConnectCodes.LOW_ENERGY, q.rejectCode)
        assertFalse(q.asrAllowed)
        assertEquals(0, q.n)
    }

    @Test
    fun unboundTinyNoiseIsLowEnergy() {
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
    fun unboundPhoneCrushIsHardBandLimited() {
        val stack = StreamPcmGate.inspect(StreamPcmSynth.toneStack(StreamPcmSynth.PHONE_HZ))
        assertEquals(PresentConnectCodes.BAND_LIMITED, stack.rejectCode)
        assertFalse(stack.asrAllowed)
        assertEquals(0.0, stack.clipRatio, 1e-9)
        assertTrue(absDc(stack) < StreamPcmGate.DC_REJECT_FS)
        assertTrue(stack.hfRatio < StreamPcmGate.BAND_LIMITED_HF_RATIO)
        assertTrue(stack.speechOccupancy >= StreamPcmGate.BAND_LIMITED_SPEECH_OCC)
        assertEquals(0, stack.hfOccupancy)

        val lpf = StreamPcmGate.inspect(StreamPcmSynth.phoneCrushLpf())
        assertEquals(PresentConnectCodes.BAND_LIMITED, lpf.rejectCode)
        assertFalse(lpf.asrAllowed)
        assertEquals(0.0, lpf.clipRatio, 1e-9)
        assertTrue(absDc(lpf) < StreamPcmGate.DC_REJECT_FS)
        assertTrue(lpf.hfRatio < StreamPcmGate.BAND_LIMITED_HF_RATIO)
    }

    @Test
    fun boundPhoneCrushIsSoftBandLimitedStillAsr() {
        val pcm = StreamPcmSynth.phoneCrushLpf()
        val q = StreamPcmGate.inspect(pcm, boundSeat = true)
        assertTrue(q.asrAllowed)
        assertEquals(null, q.rejectCode)
        assertTrue(q.softFlags.contains(PresentConnectCodes.BAND_LIMITED))
        val stack = StreamPcmGate.inspect(StreamPcmSynth.toneStack(StreamPcmSynth.PHONE_HZ), boundSeat = true)
        assertTrue(stack.asrAllowed)
        assertTrue(stack.softFlags.contains(PresentConnectCodes.BAND_LIMITED))
    }

    @Test
    fun broadbandStackAndCleanToneAreNotBandLimited() {
        val broad = StreamPcmGate.inspect(StreamPcmSynth.toneStack(StreamPcmSynth.BROADBAND_HZ))
        assertTrue(broad.asrAllowed)
        assertTrue(broad.hfRatio > StreamPcmGate.BAND_LIMITED_HF_RATIO)
        val tone = StreamPcmGate.inspect(StreamPcmSynth.sine())
        assertTrue(tone.asrAllowed)
        assertEquals(null, tone.rejectCode)
        assertEquals(1, tone.speechOccupancy)
        assertEquals(0.0, tone.clipRatio, 1e-12)
    }

    @Test
    fun sessionBoundNearSilenceInvokesAsrSoftLow() = runBlocking {
        var asrCalls = 0
        val session = PathBSession(
            asr = PathBAsr { _, _ ->
                asrCalls++
                listOf(Utterance("cluster", 0, 1, "kept"))
            },
        )
        session.bindStart("s04")
        assertTrue(StreamLabeler.isKnownSeat("s04"))
        val out = session.finish("s04", StreamPcmSynth.silence())
        assertEquals(1, asrCalls)
        assertTrue(out.asrInvoked)
        assertEquals(null, out.rejectCode)
        assertTrue(out.quality.softFlags.contains("SOFT_LOW"))
        assertEquals("speaker_4", out.utterances.single().speakerId)
        assertEquals("SOFT_LOW", session.micFlags().single().code)
        assertFalse(session.micFlags().single().hardReject)
        val encoded = PresentConnectWire.briefJson(session.brief().transcript)
        val item = Json.parseToJsonElement(encoded).jsonObject["transcript"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), item.keys)
    }

    @Test
    fun sessionEmptyBoundSkipsAsr() = runBlocking {
        var asrCalls = 0
        val session = PathBSession(
            asr = PathBAsr { _, _ ->
                asrCalls++
                listOf(Utterance("cluster", 0, 1, "nope"))
            },
        )
        session.bindStart("s01")
        val out = session.finish("s01", StreamPcmSynth.empty())
        assertEquals(0, asrCalls)
        assertEquals(PresentConnectCodes.LOW_ENERGY, out.rejectCode)
        assertTrue(session.brief().transcript.isEmpty())
        assertTrue(out.wireJson.single().contains("\"code\":\"LOW_ENERGY\""))
    }

    @Test
    fun sessionUnboundSilenceSkipsAsr() = runBlocking {
        var asrCalls = 0
        val session = PathBSession(
            asr = PathBAsr { _, _ ->
                asrCalls++
                listOf(Utterance("cluster", 0, 1, "nope"))
            },
        )
        val out = session.finish("mic-a", StreamPcmSynth.silence())
        assertEquals(0, asrCalls)
        assertEquals(PresentConnectCodes.LOW_ENERGY, out.rejectCode)
        assertFalse(StreamLabeler.isKnownSeat("mic-a"))
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
    fun sessionBoundCrushInvokesAsrSoftBandLimited() = runBlocking {
        var asrCalls = 0
        val session = PathBSession(
            asr = PathBAsr { _, _ ->
                asrCalls++
                listOf(Utterance("cluster", 0, 1, "kept"))
            },
        )
        session.bindStart("s09")
        val out = session.finish("s09", StreamPcmSynth.phoneCrushLpf())
        assertEquals(1, asrCalls)
        assertTrue(out.asrInvoked)
        assertEquals(null, out.rejectCode)
        assertTrue(out.quality.softFlags.contains(PresentConnectCodes.BAND_LIMITED))
        assertEquals("speaker_9", out.utterances.single().speakerId)
        assertEquals(PresentConnectCodes.BAND_LIMITED, session.micFlags().single().code)
        assertFalse(session.micFlags().single().hardReject)
        assertEquals(1, session.brief().transcript.size)
    }

    @Test
    fun sessionUnboundCrushHardBandLimited() = runBlocking {
        var asrCalls = 0
        val session = PathBSession(
            asr = PathBAsr { _, _ ->
                asrCalls++
                listOf(Utterance("cluster", 0, 1, "nope"))
            },
        )
        val out = session.finish("mic-a", StreamPcmSynth.phoneCrushLpf())
        assertEquals(0, asrCalls)
        assertEquals(PresentConnectCodes.BAND_LIMITED, out.rejectCode)
        assertTrue(out.wireJson.single().contains("\"stream_id\":\"mic-a\""))
        assertTrue(session.brief().transcript.isEmpty())
    }

    @Test
    fun boundMainS01NeverHardSkippedByEnergyOrBandLimit() = runBlocking {
        var asrCalls = 0
        val session = PathBSession(
            asr = PathBAsr { _, _ ->
                asrCalls++
                listOf(Utterance("cluster", 0, 1, "main"))
            },
        )
        session.bindStart("s01")
        val quiet = session.finish("s01", StreamPcmSynth.silence())
        assertEquals(null, quiet.rejectCode)
        assertTrue(quiet.asrInvoked)
        assertTrue(quiet.quality.softFlags.contains("SOFT_LOW"))
        val crush = session.finish("s01", StreamPcmSynth.phoneCrushLpf())
        assertEquals(null, crush.rejectCode)
        assertTrue(crush.asrInvoked)
        assertTrue(crush.quality.softFlags.contains(PresentConnectCodes.BAND_LIMITED))
        assertEquals(2, asrCalls)
        assertEquals("speaker_1", crush.utterances.single().speakerId)
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

    private fun absDc(q: StreamPcmStats): Double = kotlin.math.abs(q.dcFs)
}
