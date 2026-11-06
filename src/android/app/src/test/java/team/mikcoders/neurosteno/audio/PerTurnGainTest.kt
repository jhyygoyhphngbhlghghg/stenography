package team.mikcoders.neurosteno.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.mikcoders.neurosteno.pipeline.FarFieldScene
import team.mikcoders.neurosteno.pipeline.SpeechRegion
import team.mikcoders.neurosteno.present.StreamPcmGate

class PerTurnGainTest {
    @Test
    fun mixedNearAndFarSineLiftsFarWithoutCrushingHotNear() {
        val n = FarFieldSynth.N_400MS
        val near = FarFieldSynth.closeTalkSine(n = n, amplitude = 0.4)
        val far = FarFieldSynth.atDistance(FarFieldSynth.closeTalkSine(n = n), 6.0)
        val mixed = FloatArray(n * 2)
        System.arraycopy(near, 0, mixed, 0, n)
        System.arraycopy(far, 0, mixed, n, n)
        val nearPeakBefore = FarFieldGain.peak(near)
        val farRmsBefore = FarFieldGain.rms(far)
        val regions = listOf(SpeechRegion(0, n), SpeechRegion(n, n * 2))
        val out = PerTurnGain.apply(mixed, regions, chunkMs = 400)
        val nearAfter = out.samples.copyOfRange(0, n)
        val farAfter = out.samples.copyOfRange(n, n * 2)
        assertEquals(nearPeakBefore, FarFieldGain.peak(nearAfter), 1e-4)
        assertTrue(FarFieldGain.rms(farAfter) > farRmsBefore * 2)
        assertTrue(FarFieldGain.rms(farAfter) >= StreamPcmGate.RMS_SOFT_FS)
        assertTrue(FarFieldGain.peak(nearAfter) <= FarFieldGain.PEAK_LIMIT + 1e-5)
        assertTrue(out.turns.any { it.startSample >= n && it.boosted })
        assertTrue(out.turns.filter { it.endSample <= n }.none { it.boosted })
    }

    @Test
    fun sixMeterChunkClearsEnergyFloor() {
        val far = FarFieldSynth.atDistance(FarFieldSynth.closeTalkSine(), 6.0)
        val regions = listOf(SpeechRegion(0, far.size))
        val out = PerTurnGain.apply(far, regions, chunkMs = 400)
        assertTrue(FarFieldGain.rms(out.samples) >= StreamPcmGate.RMS_SOFT_FS)
        assertEquals(1.0, FarFieldGain.energyVadFireRate(out.samples), 1e-9)
    }

    @Test
    fun emptyRegionsLeaveAudioUnchanged() {
        val src = FarFieldSynth.atDistance(FarFieldSynth.closeTalkSine(), 6.0)
        val out = PerTurnGain.apply(src, emptyList())
        assertEquals(src[10], out.samples[10], 0f)
        assertTrue(out.turns.isEmpty())
    }
}

class FarFieldVadTest {
    @Test
    fun sixMeterSineIsNotDropped() {
        val at6 = FarFieldSynth.atDistance(FarFieldSynth.closeTalkSine(), 6.0)
        val regions = FarFieldVad.speechRegions(at6)
        assertTrue(regions.isNotEmpty())
        val covered = regions.sumOf { it.endSample - it.startSample }
        assertTrue(covered > at6.size / 2)
    }

    @Test
    fun hvacAloneIsNotATurn() {
        val noise = FarFieldSynth.hvac()
        val regions = FarFieldVad.speechRegions(noise)
        assertTrue(regions.isEmpty())
    }

    @Test
    fun silenceIsEmpty() {
        assertTrue(FarFieldVad.speechRegions(FloatArray(FarFieldSynth.N_400MS)).isEmpty())
    }
}

class FarFieldSceneTest {
    @Test
    fun stage6AsksForSixClustersPresentConnectDoesNot() {
        assertEquals(6, FarFieldScene.stage6.expectedSpeakers)
        assertEquals(-1, FarFieldScene.presentConnect.expectedSpeakers)
    }
}
