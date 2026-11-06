package team.mikcoders.neurosteno.audio

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Tighter VAD / endpointing for Path B overlap/bleed.
 *
 * Eval bar (cite only, not recomputed): EDGE_overlap WER 0.74 DER 0.99.
 * Goal: do not decode silence or cross-stream bleed as main speech.
 * Synthetic-PCM unit tests only — not tuned on clean Common Voice.
 */
object SpeechEndpointing {
    /** Silero: higher than the 0.5 default so mid-confidence bleed is dropped. */
    const val VAD_THRESHOLD = 0.65f

    /** End an utterance on a shorter pause; don't hold through overlap gaps. */
    const val MIN_SILENCE_SEC = 0.45f

    /** Ignore short crosstalk bursts (was 0.25 s). */
    const val MIN_SPEECH_SEC = 0.45f

    /** Don't let overlapping mush become one 20 s decode. */
    const val MAX_SPEECH_SEC = 12f

    const val FRAME_MS = 20
    const val MIN_VOICED_MS = 350
    const val ABS_RMS_FLOOR = 0.02f

    /** Segment RMS must reach this fraction of the stream's p80 RMS, else treat as bleed. */
    const val BLEED_RELATIVE = 0.28f

    const val TRIM_RELATIVE = 0.15f

    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var acc = 0.0
        for (x in samples) acc += x * x
        return sqrt(acc / samples.size).toFloat()
    }

    fun frameRms(samples: FloatArray, sampleRate: Int, frameMs: Int = FRAME_MS): FloatArray {
        val frame = ((sampleRate * frameMs) / 1000).coerceAtLeast(1)
        val n = samples.size / frame
        if (n <= 0) return FloatArray(0)
        val out = FloatArray(n)
        var i = 0
        while (i < n) {
            var acc = 0.0
            val start = i * frame
            var j = 0
            while (j < frame) {
                val x = samples[start + j]
                acc += x * x
                j++
            }
            out[i] = sqrt(acc / frame).toFloat()
            i++
        }
        return out
    }

    fun percentile(values: FloatArray, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.copyOf().also { it.sort() }
        val idx = ((sorted.size - 1) * p.coerceIn(0f, 1f)).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    /** Drop leading/trailing low-energy frames so ASR is not fed silence pads. */
    fun trimToSpeech(samples: FloatArray, sampleRate: Int): FloatArray {
        if (samples.isEmpty()) return samples
        val frames = frameRms(samples, sampleRate)
        if (frames.isEmpty()) return FloatArray(0)
        val floor = max(ABS_RMS_FLOOR, percentile(frames, 0.8f) * TRIM_RELATIVE)
        var a = 0
        while (a < frames.size && frames[a] < floor) a++
        var b = frames.size - 1
        while (b >= a && frames[b] < floor) b--
        if (a > b) return FloatArray(0)
        val frame = ((sampleRate * FRAME_MS) / 1000).coerceAtLeast(1)
        val start = a * frame
        val end = ((b + 1) * frame).coerceAtMost(samples.size)
        return samples.copyOfRange(start, end)
    }

    /**
     * True when this crop looks like on-mic speech, not silence or quieter bleed.
     * [contextP80Rms] is the parent stream's p80 frame RMS (Path B: that stream only).
     */
    fun isMainSpeech(
        segment: FloatArray,
        sampleRate: Int,
        contextP80Rms: Float? = null,
    ): Boolean {
        val trimmed = trimToSpeech(segment, sampleRate)
        val minSamples = (sampleRate * MIN_VOICED_MS) / 1000
        if (trimmed.size < minSamples) return false
        val r = rms(trimmed)
        val floor = max(ABS_RMS_FLOOR, (contextP80Rms ?: 0f) * BLEED_RELATIVE)
        return r >= floor
    }

    fun streamP80Rms(samples: FloatArray, sampleRate: Int): Float =
        percentile(frameRms(samples, sampleRate), 0.8f)
}
