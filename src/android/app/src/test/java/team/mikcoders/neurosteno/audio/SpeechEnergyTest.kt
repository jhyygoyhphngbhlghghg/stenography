package team.mikcoders.neurosteno.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Synthetic PCM only. Path B 20/20 speaker survival is a hard constraint.
 * Do not game Eval WORST_CASE / Common Voice fixtures.
 */
class SpeechEnergyTest {
    private val hz = Pcm.TARGET_HZ

    @Test
    fun hushIsNotSpeech() {
        assertFalse(SpeechEnergy.hasEnoughSpeech(FloatArray(hz), hz))
    }

    @Test
    fun quietOnMicTalkerIsSpeech() {
        // Peak ~0.02 / RMS ~0.014 — above StreamPcmGate 0.008, must not be wiped.
        val quiet = sine(hz, 180.0, 0.02f)
        assertTrue(SpeechEnergy.hasEnoughSpeech(quiet, hz))
        val gated = SpeechEnergy.zeroNonSpeech(quiet, hz)
        assertTrue(SpeechEndpointing.rms(gated) > 0.008f)
        assertEquals(quiet.size, gated.size)
    }

    @Test
    fun twentyQuietIsolatedTonesSurviveZeroing() {
        repeat(20) { i ->
            val tone = sine(hz / 2, 160.0 + i * 7, 0.018f)
            val gated = SpeechEnergy.zeroNonSpeech(tone, hz)
            assertTrue(
                "stream ${i + 1} must not be wiped (Path B 20/20)",
                SpeechEndpointing.rms(gated) >= SpeechEnergy.ABS_RMS_FLOOR,
            )
        }
    }

    @Test
    fun zeroNonSpeechDoesNotReplaceWholeBufferWithZeros() {
        val hush = FloatArray(hz)
        val out = SpeechEnergy.zeroNonSpeech(hush, hz)
        assertEquals(hush.size, out.size)
        // hush has no voiced frames — keep original rather than a fake empty clip
        assertTrue(out.contentEquals(hush))
    }

    @Test
    fun leadingSilenceZeroedKeepsBurst() {
        val pad = FloatArray(hz / 2)
        val tone = sine(hz / 2, 180.0, 0.35f)
        val mixed = pad + tone
        val gated = SpeechEnergy.zeroNonSpeech(mixed, hz)
        val mid = gated.copyOfRange(pad.size, mixed.size)
        assertTrue(SpeechEndpointing.rms(mid) > 0.1f)
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
