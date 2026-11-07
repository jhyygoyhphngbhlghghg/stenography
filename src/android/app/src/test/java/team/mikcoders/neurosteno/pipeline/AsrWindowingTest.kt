package team.mikcoders.neurosteno.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrWindowingTest {
    private val hz = 16_000

    private fun loud(seconds: Double): FloatArray {
        val n = (seconds * hz).toInt()
        return FloatArray(n) { ((it % 320) / 320f * 2 - 1f).toFloat() * 0.3f }
    }

    @Test
    fun shortSegmentIsOneWindow() {
        val out = AsrWindowing.windows(loud(5.0), hz, 0, 5_000)
        assertEquals(listOf(0L to 5_000L), out)
    }

    @Test
    fun longSegmentStaysWithinTheBoundAndCoversFully() {
        val x = loud(70.0)
        val out = AsrWindowing.windows(x, hz, 0, 70_000)
        assertTrue(out.size >= 3)
        assertEquals(0L, out.first().first)
        assertEquals(70_000L, out.last().second)
        for (i in out.indices) {
            val (a, b) = out[i]
            assertTrue("window ${a}..${b} exceeds bound", b - a <= AsrWindowing.ASR_MAX_CROP_MS)
            if (i > 0) assertEquals(out[i - 1].second, a)
        }
    }

    @Test
    fun emptyAudioDoesNotSplit() {
        val out = AsrWindowing.windows(FloatArray(0), hz, 0, 200_000)
        assertEquals(listOf(0L to 200_000L), out)
    }

    @Test
    fun offsetRangeSplitsFromItsOwnStart() {
        val x = loud(100.0)
        val out = AsrWindowing.windows(x, hz, 5_000, 65_000)
        assertEquals(5_000L, out.first().first)
        assertEquals(65_000L, out.last().second)
        assertTrue(out.all { it.second - it.first <= AsrWindowing.ASR_MAX_CROP_MS })
    }

    @Test
    fun windowCountMatchesBoundForLongMono() {
        val x = loud(180.0)
        val out = AsrWindowing.windows(x, hz, 0, 180_000)
        // 30 s bound -> at least 6 windows
        assertTrue(out.size >= 6)
        assertTrue(out.all { it.second - it.first <= AsrWindowing.ASR_MAX_CROP_MS })
    }
}
