package team.mikcoders.neurosteno.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import team.mikcoders.neurosteno.pipeline.PcmAudio
import java.nio.ByteBuffer

/**
 * SAF / file URI → 16 kHz mono float PCM.
 * WAV 16-bit is parsed without MediaCodec; M4A/MP3 go through MediaExtractor.
 */
object AudioDecoder {
    fun decode(context: Context, uri: Uri, label: String): PcmAudio {
        context.contentResolver.openInputStream(uri)?.use { ins ->
            val wav = WavPcmReader.read(ins)
            if (wav != null) {
                return PcmAudio(samples = wav.samples, sampleRate = wav.sampleRate, sourceName = label)
            }
        }
        return decodeWithExtractor(context, uri, label)
    }

    private fun decodeWithExtractor(context: Context, uri: Uri, label: String): PcmAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("В файле нет аудиодорожки")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("unknown mime")
            val srcHz = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                Pcm.TARGET_HZ
            }
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val outChunks = ArrayList<ByteArray>()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIx = codec.dequeueInputBuffer(10_000)
                    if (inIx >= 0) {
                        val buf = codec.getInputBuffer(inIx) ?: ByteBuffer.allocate(0)
                        buf.clear()
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(
                                inIx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIx >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    val outBuf = codec.getOutputBuffer(outIx)
                    if (outBuf != null && info.size > 0) {
                        val chunk = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(chunk)
                        outChunks.add(chunk)
                    }
                    codec.releaseOutputBuffer(outIx, false)
                }
            }
            codec.stop()
            codec.release()
            val bytes = concat(outChunks)
            val floats = Pcm.s16leToFloat(bytes)
            val mono = Pcm.downmixToMono(floats, channels.coerceAtLeast(1))
            val resampled = Pcm.resampleLinear(mono, srcHz, Pcm.TARGET_HZ)
            return PcmAudio(samples = resampled, sampleRate = Pcm.TARGET_HZ, sourceName = label)
        } finally {
            extractor.release()
        }
    }

    private fun concat(chunks: List<ByteArray>): ByteArray {
        val n = chunks.sumOf { it.size }
        val all = ByteArray(n)
        var o = 0
        for (c in chunks) {
            System.arraycopy(c, 0, all, o, c.size)
            o += c.size
        }
        return all
    }
}
