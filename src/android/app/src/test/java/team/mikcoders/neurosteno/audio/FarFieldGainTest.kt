package team.mikcoders.neurosteno.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val g3 = FarFieldGain.stage(at3)
        val g6 = FarFieldGain.stage(at6)
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
    fun fourToSixMeterPhoneScenesClearFloorAndNeverHardDrop() {
        for (d in listOf(4.0, 5.0, 6.0)) {
            val scene = FarFieldSynth.scene(d)
            val prep = DemoPcmPrep.prepare(PcmAudio(scene, sourceName = "s01-${d}m"))
            assertFalse("hard-skip at ${d}m", prep.skipAsr)
            assertTrue("boost ${d}m", prep.gain.boosted)
            assertTrue("rms after ${d}m ${prep.gain.rmsAfter}", prep.gain.rmsAfter >= StreamPcmGate.RMS_REJECT_FS)
            assertTrue("asr ${d}m", prep.afterGate.asrAllowed)
            assertTrue("vad after ${d}m", prep.gain.vadFireRateAfter > 0.5)
        }
    }

    @Test
    fun fiveAndSixMeterSineWouldHardSkipUnboundUntilGain() {
        val close = FarFieldSynth.closeTalkSine()
        for (d in listOf(5.0, 6.0)) {
            val far = FarFieldSynth.atDistance(close, d)
            val before = StreamPcmGate.inspect(Pcm.floatToS16le(far), boundSeat = false)
            assertEquals("unbound ${d}m", PresentConnectCodes.LOW_ENERGY, before.rejectCode)
            val g = FarFieldGain.stage(far)
            val afterUnbound = StreamPcmGate.inspect(Pcm.floatToS16le(g.samples), boundSeat = false)
            assertTrue("unbound after ${d}m", afterUnbound.asrAllowed)
            val afterBound = StreamPcmGate.inspect(Pcm.floatToS16le(g.samples), boundSeat = true)
            assertTrue(afterBound.asrAllowed)
        }
    }

    @Test
    fun silenceNotBoostedIntoHiss() {
        val z = FloatArray(FarFieldSynth.N_400MS)
        val g = FarFieldGain.stage(z)
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
        assertTrue(far.gain.boosted)
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

    @Test
    fun sequentialMixBoostsQuietFarWithoutCrushingNear() {
        val (pcm, turns) = FarFieldSynth.sequentialMix()
        val mixRms = FarFieldGain.rms(pcm)
        assertTrue("mix already loud like Eval ffgain 0.119 FS: $mixRms", mixRms > FarFieldGain.TARGET_RMS_FS)
        val wholeFileGain = FarFieldGain.TARGET_RMS_FS / mixRms
        assertTrue("whole-file AGC would attenuate", wholeFileGain < 1.0)

        val g = FarFieldGain.stage(pcm)
        assertFalse("never crush loud turns", g.loudCrushed)
        assertTrue(g.boosted)
        assertTrue(g.segments.size >= 3)

        val near = turns.first { it.name == "near_3m" }
        val far5 = turns.first { it.name == "far_5m" }
        val far6 = turns.first { it.name == "far_6m" }
        fun slice(src: FloatArray, r: IntRange) = src.copyOfRange(r.first, r.last + 1)

        val nearBefore = FarFieldGain.rms(slice(pcm, near.range))
        val nearAfter = FarFieldGain.rms(slice(g.samples, near.range))
        val far5Before = FarFieldGain.rms(slice(pcm, far5.range))
        val far5After = FarFieldGain.rms(slice(g.samples, far5.range))
        val far6Before = FarFieldGain.rms(slice(pcm, far6.range))
        val far6After = FarFieldGain.rms(slice(g.samples, far6.range))

        assertTrue("near already above target $nearBefore", nearBefore > FarFieldGain.TARGET_RMS_FS)
        assertEquals("near not attenuated", nearBefore, nearAfter, 1e-6)
        assertTrue("far 5 m below target $far5Before", far5Before < FarFieldGain.TARGET_RMS_FS)
        assertTrue("far 6 m below target $far6Before", far6Before < FarFieldGain.TARGET_RMS_FS)
        assertTrue("far 5 m lifted $far5After", far5After > far5Before * 1.5)
        assertTrue("far 6 m lifted $far6After", far6After > far6Before * 1.5)
        assertTrue("far 5 m after $far5After", far5After >= StreamPcmGate.RMS_SOFT_FS * 0.9)
        assertTrue("far 6 m after $far6After", far6After >= StreamPcmGate.RMS_REJECT_FS)

        val prep = DemoPcmPrep.prepare(PcmAudio(pcm, sourceName = "mix-3m-5m-6m"))
        assertFalse("demo never hard-drops the mix", prep.skipAsr)
        assertTrue(prep.afterGate.asrAllowed)
        assertEquals(null, prep.skipCode)
    }

    @Test
    fun gainForSegmentNeverAttenuatesLoudTurn() {
        assertEquals(1.0, FarFieldGain.gainForSegment(0.119, 0.4), 1e-12)
        assertEquals(1.0, FarFieldGain.gainForSegment(0.08, 0.2), 1e-12)
        val quiet = FarFieldGain.gainForSegment(0.01, 0.02)
        assertTrue(quiet > 1.0)
        assertTrue(20.0 * kotlin.math.ln(quiet) / kotlin.math.ln(10.0) <= FarFieldGain.MAX_GAIN_DB + 1e-6)
    }
}
