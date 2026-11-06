package team.mikcoders.neurosteno.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.mikcoders.neurosteno.pipeline.DemoPcmPrep
import team.mikcoders.neurosteno.pipeline.PcmAudio
import team.mikcoders.neurosteno.present.PresentConnectCodes
import team.mikcoders.neurosteno.present.StreamPcmSynth

class PhoneDistanceEqTest {
    @Test
    fun gainTargetsFrozen() {
        assertEquals(0.08, FarFieldGain.TARGET_RMS_FS, 0.0)
        assertEquals(24.0, FarFieldGain.MAX_GAIN_DB, 0.0)
        assertEquals(1.0, FarFieldGain.gainForSegment(0.12, 0.4), 1e-12)
    }

    @Test
    fun nearSeatIsIdentity() {
        val near = FarFieldSynth.speechStack(peak = 0.28)
        val eq = PhoneDistanceEq.apply(near)
        assertFalse(eq.applied)
        assertFalse(eq.nearCrushed)
        assertEquals(FarFieldGain.rms(near), FarFieldGain.rms(eq.samples), 1e-9)
        assertEquals(FarFieldGain.peak(near), FarFieldGain.peak(eq.samples), 1e-9)
        var i = 0
        while (i < near.size) {
            assertEquals(near[i], eq.samples[i], 1e-7f)
            i++
        }
    }

    @Test
    fun farPhoneScenesLiftSpeechBandAndNeverHardDrop() {
        for (d in listOf(3.0, 4.0, 5.0, 6.0)) {
            val scene = FarFieldSynth.phoneDistanceScene(d)
            val prep = DemoPcmPrep.prepare(PcmAudio(scene, sourceName = "s01-${d}m-eq"))
            assertFalse("hard-skip at ${d}m", prep.skipAsr)
            assertTrue("asr ${d}m", prep.afterGate.asrAllowed)
            val before = PhoneDistanceEq.bandEnergy(prep.gain.samples)
            val after = PhoneDistanceEq.bandEnergy(prep.eq.samples)
            assertTrue("speech energy ${d}m ${before.speech} -> ${after.speech}", after.speech > before.speech)
            assertTrue("presence ${d}m", after.presence > before.presence)
            assertTrue("eq applied ${d}m", prep.eq.applied)
        }
    }

    @Test
    fun sequentialMixDoesNotCrushNearSeat() {
        val (pcm, turns) = FarFieldSynth.sequentialPhoneMix()
        val prep = DemoPcmPrep.prepare(PcmAudio(pcm, sourceName = "eq-mix"))
        assertFalse(prep.skipAsr)
        assertFalse(prep.eq.nearCrushed)
        val near = turns.first { it.name == "near_3m" }
        val far6 = turns.first { it.name == "far_6m" }
        fun slice(src: FloatArray, r: IntRange) = src.copyOfRange(r.first, r.last + 1)
        val near0 = slice(pcm, near.range)
        val near1 = slice(prep.audio.samples, near.range)
        assertEquals("near RMS", FarFieldGain.rms(near0), FarFieldGain.rms(near1), 1e-6)
        assertEquals("near peak", FarFieldGain.peak(near0), FarFieldGain.peak(near1), 1e-6)
        val farG = PhoneDistanceEq.bandEnergy(slice(prep.gain.samples, far6.range))
        val farE = PhoneDistanceEq.bandEnergy(slice(prep.eq.samples, far6.range))
        assertTrue("far 6 m speech ${farG.speech} -> ${farE.speech}", farE.speech > farG.speech)
    }

    @Test
    fun clippedAndEmptyStillHardSkip() {
        val clipped = DemoPcmPrep.prepare(
            PcmAudio(Pcm.s16leToFloat(StreamPcmSynth.clippedSquare()), sourceName = "clip"),
        )
        assertTrue(clipped.skipAsr)
        assertEquals(PresentConnectCodes.CLIPPED, clipped.skipCode)
        val empty = DemoPcmPrep.prepare(PcmAudio(FloatArray(0), sourceName = "empty"))
        assertTrue(empty.skipAsr)
        assertEquals(PresentConnectCodes.LOW_ENERGY, empty.skipCode)
    }
}
