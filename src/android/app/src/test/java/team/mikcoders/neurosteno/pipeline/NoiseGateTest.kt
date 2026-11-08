package team.mikcoders.neurosteno.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NoiseGate (casual/presentConnect phantom-speaker fix, host sweep
 * scripts/noise_gate_sweep.py 2026-11-08): drop raw diarization segments with
 * duration < noiseGateMinMs OR RMS < noiseGateRmsRatio * median(segment RMS).
 * Fixture chosen per scene defaults: noiseGateMinMs=900, noiseGateRmsRatio=0.25.
 */
class NoiseGateTest {
    private val hz = 16_000

    /** PCM of the given duration whose RMS is approximately [rms]. */
    private fun tone(durationMs: Long, rms: Double): FloatArray {
        val n = (durationMs * hz / 1000).toInt()
        // square wave at +/- rms has exact RMS = rms
        return FloatArray(n) { if (it % 2 == 0) rms.toFloat() else -rms.toFloat() }
    }

    private fun pcm(vararg chunks: FloatArray): FloatArray {
        val total = chunks.sumOf { it.size }
        val out = FloatArray(total)
        var pos = 0
        for (c in chunks) {
            c.copyInto(out, pos)
            pos += c.size
        }
        return out
    }

    @Test
    fun `phantom noise burst is dropped and real turns kept`() {
        // Three real 3 s turns at RMS 0.12 separated by 0.4 s gaps; a 0.5 s
        // "noise burst" island at RMS 0.02 in one gap (the device-test
        // phantom-speaker segment: short AND quiet vs the median).
        val turn = tone(3_000, 0.12)
        val gap = tone(400, 0.0)
        val burst = tone(500, 0.02)
        val samples = pcm(turn, burst, gap, turn, gap, turn)
        val segs = listOf(
            DiarSeg(0, 0, 3_000),
            DiarSeg(1, 3_000, 3_500), // burst: 500 ms < 900 minMs, RMS 0.02 < 0.25*median
            DiarSeg(0, 3_900, 6_900),
            DiarSeg(2, 7_300, 10_300),
        )
        val kept = NoiseGate.apply(segs, samples, hz, minMs = 900L, rmsRatio = 0.25f)
        assertEquals(listOf(0L, 3_900L, 7_300L), kept.map { it.startMs })
        assertTrue(kept.none { it.speakerIndex == 1 })
    }

    @Test
    fun `real short turn at the minMs boundary is kept when loud enough`() {
        // duration == minMs is NOT < minMs -> kept; RMS 0.10 >= 0.25 * median
        // (median of {0.10, 0.12} = 0.11 -> cutoff 0.0275).
        val a = tone(900, 0.10)
        val b = tone(3_000, 0.12)
        val samples = pcm(a, tone(400, 0.0), b)
        val segs = listOf(DiarSeg(0, 0, 900), DiarSeg(1, 1_100, 4_100))
        val kept = NoiseGate.apply(segs, samples, hz, minMs = 900L, rmsRatio = 0.25f)
        assertEquals(2, kept.size)
    }

    @Test
    fun `short segment just below minMs is dropped even when loud`() {
        val a = tone(899, 0.30)
        val b = tone(3_000, 0.12)
        val samples = pcm(a, tone(400, 0.0), b)
        val segs = listOf(DiarSeg(0, 0, 899), DiarSeg(1, 1_299, 4_299))
        val kept = NoiseGate.apply(segs, samples, hz, minMs = 900L, rmsRatio = 0.25f)
        assertEquals(listOf(1_299L), kept.map { it.startMs })
    }

    @Test
    fun `quiet long segment below rms ratio boundary is dropped, above is kept`() {
        // median of {0.0170, 0.12} = 0.0685; cutoff = 0.25 * 0.0685 = 0.017125.
        val loud = tone(3_000, 0.12)
        val quiet = tone(2_000, 0.0170) // below cutoff -> dropped
        var samples = pcm(quiet, loud)
        var segs = listOf(DiarSeg(0, 0, 2_000), DiarSeg(1, 2_000, 5_000))
        assertEquals(1, NoiseGate.apply(segs, samples, hz, 900L, 0.25f).size)

        // median of {0.0172, 0.12} = 0.0686; cutoff = 0.017150 -> 0.0172 kept.
        val above = tone(2_000, 0.0172)
        samples = pcm(above, loud)
        segs = listOf(DiarSeg(0, 0, 2_000), DiarSeg(1, 2_000, 5_000))
        assertEquals(2, NoiseGate.apply(segs, samples, hz, 900L, 0.25f).size)
    }

    @Test
    fun `gate disabled (minMs or ratio non-positive) keeps everything`() {
        val turn = tone(3_000, 0.12)
        val burst = tone(300, 0.01)
        val samples = pcm(turn, burst, turn)
        val segs = listOf(DiarSeg(0, 0, 3_000), DiarSeg(1, 3_000, 3_300), DiarSeg(0, 3_300, 6_300))
        assertEquals(segs, NoiseGate.apply(segs, samples, hz, minMs = 0L, rmsRatio = 0.25f))
        assertEquals(segs, NoiseGate.apply(segs, samples, hz, minMs = 900L, rmsRatio = 0f))
    }

    @Test
    fun `single segment and empty samples are returned unchanged`() {
        val one = listOf(DiarSeg(0, 0, 300))
        assertEquals(one, NoiseGate.apply(one, tone(300, 0.01), hz, 900L, 0.25f))
        val segs = listOf(DiarSeg(0, 0, 300), DiarSeg(1, 300, 600))
        assertEquals(segs, NoiseGate.apply(segs, FloatArray(0), hz, 900L, 0.25f))
    }

    @Test
    fun `stage6 scene ships the gate disabled and casual ships it enabled`() {
        assertEquals(0L, FarFieldScene.stage6.noiseGateMinMs)
        assertEquals(0f, FarFieldScene.stage6.noiseGateRmsRatio, 0f)
        assertTrue(FarFieldScene.casual.expectedSpeakers <= 0)
        assertTrue(FarFieldScene.casual.noiseGateMinMs > 0L)
        assertTrue(FarFieldScene.casual.noiseGateRmsRatio > 0f)
        assertEquals(FarFieldScene.casual.noiseGateMinMs, FarFieldScene.presentConnect.noiseGateMinMs)
        assertEquals(FarFieldScene.casual.noiseGateRmsRatio, FarFieldScene.presentConnect.noiseGateRmsRatio)
    }

    @Test
    fun `median helper matches numpy convention`() {
        assertEquals(0.0, NoiseGate.median(doubleArrayOf()), 1e-12)
        assertEquals(2.0, NoiseGate.median(doubleArrayOf(2.0)), 1e-12)
        assertEquals(2.0, NoiseGate.median(doubleArrayOf(3.0, 1.0, 2.0)), 1e-12)
        assertEquals(1.5, NoiseGate.median(doubleArrayOf(1.0, 2.0)), 1e-12)
    }
}
