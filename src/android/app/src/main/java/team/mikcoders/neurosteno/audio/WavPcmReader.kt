package team.mikcoders.neurosteno.audio

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavPcm(
    val sampleRate: Int,
    val channels: Int,
    val samples: FloatArray,
)

/** Minimal PCM WAV reader (16-bit). Compressed WAV returns null. */
object WavPcmReader {
    fun read(input: InputStream): WavPcm? {
        val all = input.readBytes()
        if (all.size < 44) return null
        if (String(all, 0, 4) != "RIFF" || String(all, 8, 4) != "WAVE") return null
        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var dataOffset = -1
        var dataSize = 0
        while (pos + 8 <= all.size) {
            val id = String(all, pos, 4)
            val size = le32(all, pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                val format = le16(all, body)
                channels = le16(all, body + 2)
                sampleRate = le32(all, body + 4)
                bits = le16(all, body + 14)
                if (format != 1 && format != 3) return null
                if (format == 3) {
                    // float wav — not handled in this reader
                    return null
                }
            } else if (id == "data") {
                dataOffset = body
                dataSize = size
                break
            }
            pos = body + size + (size and 1)
        }
        if (dataOffset < 0 || bits != 16 || channels <= 0 || sampleRate <= 0) return null
        val pcm = Pcm.s16leToFloat(all, dataOffset, dataSize.coerceAtMost(all.size - dataOffset))
        val mono = Pcm.downmixToMono(pcm, channels)
        val resampled = Pcm.resampleLinear(mono, sampleRate, Pcm.TARGET_HZ)
        return WavPcm(Pcm.TARGET_HZ, 1, resampled)
    }

    private fun le16(b: ByteArray, i: Int): Int {
        return ByteBuffer.wrap(b, i, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
    }

    private fun le32(b: ByteArray, i: Int): Int {
        return ByteBuffer.wrap(b, i, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }
}
