package team.mikcoders.neurosteno.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Synthetic PCM only. Not Common Voice, not eval clips — do not game fixtures.
 * Bar we are hardening against (cite only): EDGE_overlap WER 0.74 DER 0.99.
 */
class SpeechEndpointingTest {
    private val hz = Pcm.TARGET_HZ

    @Test
    fun silenceIsNotMainSpeech() {
        assertFalse(SpeechEndpointing.isMainSpeech(FloatArray(hz), hz))
    }

    @Test
    fun shortQuietBurstIsBleedNotMainSpeech() {
        // ~80 ms quiet ping — typical crosstalk blip, shorter than MIN_VOICED_MS.
        val bleed = sine(n = hz / 12, freq = 400.0, amp = 0.04f)
        assertFalse(SpeechEndpointing.isMainSpeech(bleed, hz, contextP80Rms = 0.25f))
    }

    @Test
    fun sustainedToneIsMainSpeech() {
        val speech = sine(n = hz, freq = 180.0, amp = 0.25f)
        assertTrue(SpeechEndpointing.isMainSpeech(speech, hz))
    }

    @Test
    fun quietSegmentDroppedAgainstLoudStreamContext() {
        val loud = sine(n = hz, freq = 180.0, amp = 0.4f)
        val p80 = SpeechEndpointing.streamP80Rms(loud, hz)
        val bleed = sine(n = hz, freq = 300.0, amp = 0.03f)
        assertTrue(p80 > 0.1f)
        assertFalse(
            "bleed RMS must not pass as main speech on a loud Path B stream",
            SpeechEndpointing.isMainSpeech(bleed, hz, contextP80Rms = p80),
        )
        assertTrue(SpeechEndpointing.isMainSpeech(loud, hz, contextP80Rms = p80))
    }

    @Test
    fun trimDropsLeadingAndTrailingSilence() {
        val pad = FloatArray(hz / 2) // 500 ms zeros
        val speech = sine(n = hz / 2, freq = 200.0, amp = 0.3f)
        val padded = pad + speech + pad
        val trimmed = SpeechEndpointing.trimToSpeech(padded, hz)
        assertTrue(trimmed.size < padded.size)
        assertTrue(trimmed.size >= speech.size - hz / 50)
        assertTrue(SpeechEndpointing.rms(trimmed) > SpeechEndpointing.rms(padded))
    }

    @Test
    fun vadPolicyIsTighterThanOvernightDefaults() {
        assertTrue(SpeechEndpointing.VAD_THRESHOLD > 0.5f)
        assertTrue(SpeechEndpointing.MIN_SILENCE_SEC > 0.25f)
        assertTrue(SpeechEndpointing.MIN_SPEECH_SEC > 0.25f)
        assertTrue(SpeechEndpointing.MAX_SPEECH_SEC < 20f)
    }

    @Test
    fun pathBContextDoesNotMixStreams() {
        val a = sine(n = hz, freq = 180.0, amp = 0.4f)
        val b = sine(n = hz, freq = 180.0, amp = 0.03f)
        val p80a = SpeechEndpointing.streamP80Rms(a, hz)
        val p80b = SpeechEndpointing.streamP80Rms(b, hz)
        assertFalse(p80a == p80b)
        // Quiet stream B still uses its own context, not stream A's floor.
        assertEquals(p80b, SpeechEndpointing.streamP80Rms(b, hz), 1e-6f)
        assertTrue(p80a > p80b)
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
