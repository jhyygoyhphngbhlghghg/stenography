package team.mikcoders.neurosteno.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import team.mikcoders.neurosteno.pipeline.PcmAudio
import java.util.concurrent.atomic.AtomicBoolean

/** In-app record → 16 kHz mono float. Separate from PresentConnect browser mics. */
class MicRecorder {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private val pcm = java.io.ByteArrayOutputStream()

    val isRecording: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        pcm.reset()
        val min = AudioRecord.getMinBufferSize(
            Pcm.TARGET_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            Pcm.TARGET_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            min.coerceAtLeast(Pcm.TARGET_HZ),
        )
        rec.startRecording()
        thread = Thread {
            val buf = ByteArray(min.coerceAtLeast(3200))
            try {
                while (running.get()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        synchronized(pcm) { pcm.write(buf, 0, n) }
                    }
                }
            } finally {
                rec.stop()
                rec.release()
            }
        }.also { it.start() }
    }

    fun stop(): PcmAudio {
        running.set(false)
        thread?.join(2_000)
        thread = null
        val bytes = synchronized(pcm) { pcm.toByteArray() }
        val samples = Pcm.s16leToFloat(bytes)
        return PcmAudio(samples = samples, sampleRate = Pcm.TARGET_HZ, sourceName = "mic")
    }
}
