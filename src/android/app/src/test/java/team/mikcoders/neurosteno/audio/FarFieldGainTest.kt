package team.mikcoders.neurosteno.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.mikcoders.neurosteno.pipeline.DemoPcmPrep
import team.mikcoders.neurosteno.pipeline.PcmAudio
import team.mikcoders.neurosteno.present.PresentConnectCodes
import team.mikcoders.neurosteno.present.StreamPcmGate
import team.mikcoders.neurosteno.present.StreamPcmSynth

class FarFieldGainTest {
    @Test
    fun threeAndSixMeterSineRmsRiseAboveVadFloor() {
        val close = FarFieldSynth.closeTalkSine()
        val at3 = FarFieldSynth.atDistance(close, 3.0)
        val at6 = FarFieldSynth.atDistance(close, 6.0)
        val g3 = FarFieldGain.stageTurn(at3)
        val g6 = FarFieldGain.stageTurn(at6)
        assertTrue(g3.rmsBefore < StreamPcmGate.RMS_SOFT_FS)
        assertTrue(g3.rmsBefore > StreamPcmGate.RMS_REJECT_FS)
        assertTrue(g6.rmsBefore < StreamPcmGate.RMS_REJECT_FS)
        assertTrue(g3.rmsAfter >= StreamPcmGate.RMS_SOFT_FS)
        assertTrue(g6.rmsAfter >= StreamPcmGate.RMS_SOFT_FS)
        assertEquals(1.0, g3.vadFireRateAfter, 1e-9)
        assertEquals(0.0, g6.vadFireRateBefore, 1e-9)
        assertEquals(1.0, g6.vadFireRateAfter, 1e-9)
        assertTrue(g3.gainDb > 0.0)
        assertTrue(g6.gainDb > g3.gainDb)
        assertTrue(g6.gainDb <= FarFieldGain.MAX_GAIN_DB + 1e-6)
    }

    @Test
    fun stageTurnDoesNotAttenuateHotNearSeat() {
        val hot = FarFieldSynth.closeTalkSine(amplitude = 0.4)
        val before = FarFieldGain.rms(hot)
        assertTrue(before > FarFieldGain.TARGET_RMS_FS)
        val g = FarFieldGain.stageTurn(hot)
        assertFalse(g.boosted)
        assertEquals(1.0, g.gainLinear, 1e-9)
        assertEquals(before, g.rmsAfter, 1e-6)
        assertTrue(g.peakAfter <= FarFieldGain.PEAK_LIMIT + 1e-5)
    }

    @Test
    fun fourToSixMeterPhoneScenesNeverHardDropInDemoPrep() {
        for (d in listOf(4.0, 5.0, 6.0)) {
            val scene = FarFieldSynth.scene(d)
            val prep = DemoPcmPrep.prepare(PcmAudio(scene, sourceName = "s01-${d}m"))
            assertFalse("hard-skip at ${d}m", prep.skipAsr)
            assertNull(prep.skipCode)
            assertTrue("asr ${d}m", prep.afterGate.asrAllowed)
        }
    }

    @Test
    fun demoPrepDoesNotApplyWholeFileAgc() {
        val far = FarFieldSynth.scene(6.0)
        val prep = DemoPcmPrep.prepare(PcmAudio(far, sourceName = "s01"))
        assertFalse(prep.skipAsr)
        assertFalse(prep.gain.boosted)
        assertEquals(far.size, prep.audio.samples.size)
        assertEquals(far[100], prep.audio.samples[100], 0f)
    }

    @Test
    fun fiveAndSixMeterSineWouldHardSkipUnboundUntilTurnGain() {
        val close = FarFieldSynth.closeTalkSine()
        for (d in listOf(5.0, 6.0)) {
            val far = FarFieldSynth.atDistance(close, d)
            val before = StreamPcmGate.inspect(Pcm.floatToS16le(far), boundSeat = false)
            assertEquals("unbound ${d}m", PresentConnectCodes.LOW_ENERGY, before.rejectCode)
            val g = FarFieldGain.stageTurn(far)
            val afterUnbound = StreamPcmGate.inspect(Pcm.floatToS16le(g.samples), boundSeat = false)
            assertTrue("unbound after ${d}m", afterUnbound.asrAllowed)
            val afterBound = StreamPcmGate.inspect(Pcm.floatToS16le(g.samples), boundSeat = true)
            assertTrue(afterBound.asrAllowed)
        }
    }

    @Test
    fun silenceNotBoostedIntoHiss() {
        val z = FloatArray(FarFieldSynth.N_400MS)
        val g = FarFieldGain.stageTurn(z)
        assertFalse(g.boosted)
        assertEquals(1.0, g.gainLinear, 1e-12)
        assertEquals(0.0, g.rmsAfter, 1e-12)
    }

    @Test
    fun demoPrepHardSkipsClippedAndEmptyKeepsFarSpeech() {
        val clipped = DemoPcmPrep.prepare(
            PcmAudio(Pcm.s16leToFloat(StreamPcmSynth.clippedSquare()), sourceName = "clip"),
        )
        assertTrue(clipped.skipAsr)
        assertEquals(PresentConnectCodes.CLIPPED, clipped.skipCode)

        val empty = DemoPcmPrep.prepare(PcmAudio(FloatArray(0), sourceName = "empty"))
        assertTrue(empty.skipAsr)
        assertEquals(PresentConnectCodes.LOW_ENERGY, empty.skipCode)

        val far = DemoPcmPrep.prepare(
            PcmAudio(FarFieldSynth.scene(6.0), sourceName = "s01"),
        )
        assertFalse(far.skipAsr)
        assertTrue(far.afterGate.asrAllowed)
    }

    @Test
    fun clipGuardCapsPeak() {
        val sparse = FloatArray(8_000)
        sparse[100] = 0.99f
        val g = FarFieldGain.stage(sparse)
        assertTrue(g.clipGuard)
        assertTrue(g.peakAfter <= FarFieldGain.PEAK_LIMIT + 1e-5)
    }
}
