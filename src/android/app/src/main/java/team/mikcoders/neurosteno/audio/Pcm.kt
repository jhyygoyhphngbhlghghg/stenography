package team.mikcoders.neurosteno.audio

/** 16-bit PCM helpers. Pipeline consumes float32 in [-1, 1] at 16 kHz mono. */
object Pcm {
    const val TARGET_HZ = 16_000

    fun s16leToFloat(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): FloatArray {
        val n = length / 2
        val out = FloatArray(n)
        var i = 0
        var o = 0
        while (o < n) {
            val lo = bytes[offset + i].toInt() and 0xff
            val hi = bytes[offset + i + 1].toInt()
            val s = ((hi shl 8) or lo).toShort()
            out[o] = s / 32768f
            i += 2
            o += 1
        }
        return out
    }

    /** Stereo→mono for a **single** imported file. Not PresentConnect Path A mix of many mics. */
    fun downmixToMono(samples: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return samples
        val frames = samples.size / channels
        val out = FloatArray(frames)
        var i = 0
        var o = 0
        while (o < frames) {
            var acc = 0f
            var c = 0
            while (c < channels) {
                acc += samples[i + c]
                c++
            }
            out[o] = acc / channels
            i += channels
            o++
        }
        return out
    }

    fun resampleLinear(samples: FloatArray, srcHz: Int, dstHz: Int = TARGET_HZ): FloatArray {
        if (srcHz == dstHz || samples.isEmpty()) return samples
        val outLen = ((samples.size.toLong() * dstHz) / srcHz).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val ratio = srcHz.toDouble() / dstHz
        var o = 0
        while (o < outLen) {
            val src = o * ratio
            val i = src.toInt().coerceIn(0, samples.lastIndex)
            val frac = (src - i).toFloat()
            val j = (i + 1).coerceAtMost(samples.lastIndex)
            out[o] = samples[i] * (1f - frac) + samples[j] * frac
            o++
        }
        return out
    }
}
