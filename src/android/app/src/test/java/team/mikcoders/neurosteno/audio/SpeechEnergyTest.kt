package team.mikcoders.neurosteno.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic PCM only. Do not game Common Voice / eval fixtures.
 * Cite-only bar: noise false alarms DER 1.70.
 */
class SpeechEnergyTest {
    private val hz = Pcm.TARGET_HZ

    @Test
    fun hushIsNotSpeech() {
        val hush = FloatArray(hz) { (Random(1).nextFloat() - 0.5f) * 0.004f }
        assertFalse(SpeechEnergy.hasEnoughSpeech(hush, hz))
    }

    @Test
    fun stationaryNoiseBedIsNotSpeech() {
        val noise = FloatArray(hz * 2) { (Random(2).nextFloat() - 0.5f) * 0.08f }
        assertFalse(
            "flat-energy noise must not reach diar/ASR",
            SpeechEnergy.hasEnoughSpeech(noise, hz),
        )
    }

    @Test
    fun toneBurstIsSpeech() {
        val pad = FloatArray(hz / 2)
        val tone = sine(hz, 180.0, 0.3f)
        assertTrue(SpeechEnergy.hasEnoughSpeech(pad + tone + pad, hz))
    }

    @Test
    fun zeroNonSpeechClearsNoiseFramesKeepsBurst() {
        val pad = FloatArray(hz / 2) { (Random(3).nextFloat() - 0.5f) * 0.004f }
        val tone = sine(hz / 2, 180.0, 0.35f)
        val mixed = pad + tone + pad
        val gated = SpeechEnergy.zeroNonSpeech(mixed, hz)
        val padRms = SpeechEndpointing.rms(gated.copyOfRange(0, pad.size))
        val mid = gated.copyOfRange(pad.size, pad.size + tone.size)
        val midRms = SpeechEndpointing.rms(mid)
        assertTrue(padRms < 0.01f)
        assertTrue(midRms > 0.1f)
        assertEquals(mixed.size, gated.size)
    }

    @Test
    fun silenceHasNoSpeech() {
        assertFalse(SpeechEnergy.hasEnoughSpeech(FloatArray(hz), hz))
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
