package team.mikcoders.neurosteno.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * Synthetic PCM only. Do not game Common Voice / eval fixtures.
 * Cite-only bar: bad mic WER 1.0 — fail loud rather than hallucinate.
 */
class AudioQualityGateTest {
    private val hz = Pcm.TARGET_HZ

    @Test
    fun cleanSinePasses() {
        val s = sine(hz, 180.0, 0.3f)
        assertTrue(AudioQualityGate.inspect(s) is AudioQualityGate.Quality.Ok)
    }

    @Test
    fun hardClippedSquareFailsLoud() {
        val clip = FloatArray(hz) { i -> if (i % 2 == 0) 1f else -1f }
        val q = AudioQualityGate.inspect(clip)
        assertTrue(q is AudioQualityGate.Quality.Fail)
        q as AudioQualityGate.Quality.Fail
        assertEquals(AudioQualityGate.CODE, q.code)
        assertTrue(q.message.contains("clip"))
    }

    @Test
    fun twoPercentPeaksAtFullScaleFail() {
        val s = sine(hz, 180.0, 0.2f)
        val nClip = (s.size * 0.03).toInt()
        repeat(nClip) { i -> s[i] = if (i % 2 == 0) 1f else -1f }
        val q = AudioQualityGate.inspect(s)
        assertTrue(q is AudioQualityGate.Quality.Fail)
    }

    @Test
    fun nanFailsLoud() {
        val s = sine(hz / 4, 180.0, 0.2f)
        s[10] = Float.NaN
        val q = AudioQualityGate.inspect(s)
        assertTrue(q is AudioQualityGate.Quality.Fail)
        assertTrue((q as AudioQualityGate.Quality.Fail).message.contains("non-finite"))
    }

    @Test
    fun dcBlockRemovesOffsetWithoutInventingSpeech() {
        val tone = sine(hz, 200.0, 0.05f)
        val dc = FloatArray(tone.size) { i -> tone[i] + 0.4f }
        val blocked = AudioQualityGate.dcBlock(dc)
        val mean = blocked.average()
        assertTrue(abs(mean) < 0.05)
        assertTrue(AudioQualityGate.inspect(blocked) is AudioQualityGate.Quality.Ok)
    }

    private fun sine(n: Int, freq: Double, amp: Float): FloatArray {
        val out = FloatArray(n)
        var i = 0
        while (i < n) {
            out[i] = (amp * sin(2.0 * Math.PI * freq * i / hz)).toFloat()
            i++
        }
        return out
    }
}
