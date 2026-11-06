package team.mikcoders.neurosteno.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmTest {
    @Test
    fun downmixStereoAverages() {
        val stereo = floatArrayOf(1f, -1f, 0.5f, 0.5f)
        val mono = Pcm.downmixToMono(stereo, 2)
        assertEquals(2, mono.size)
        assertEquals(0f, mono[0], 1e-5f)
        assertEquals(0.5f, mono[1], 1e-5f)
    }

    @Test
    fun resampleDoublesLengthWhenHalvingRate() {
        val src = FloatArray(8) { it.toFloat() }
        val dst = Pcm.resampleLinear(src, srcHz = 16_000, dstHz = 8_000)
        assertEquals(4, dst.size)
    }

    @Test
    fun floatToS16leRoundTripPreservesQuietFarSample() {
        val src = floatArrayOf(0.0f, 0.01f, -0.01f, 0.5f, -0.5f)
        val bytes = Pcm.floatToS16le(src)
        val back = Pcm.s16leToFloat(bytes)
        assertEquals(src.size, back.size)
        for (i in src.indices) {
            assertEquals(src[i], back[i], 2f / 32768f)
        }
    }
}
