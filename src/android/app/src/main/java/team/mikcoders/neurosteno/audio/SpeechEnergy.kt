package team.mikcoders.neurosteno.audio

import kotlin.math.sqrt

/**
 * Energy gate **before** diar/ASR so stationary noise does not spawn speakers.
 *
 * Cite-only bar: noise false alarms DER 1.70. Path B applies this per stream_id
 * (never a mix). Synthetic tests only — not tuned on Common Voice.
 */
object SpeechEnergy {
    const val ABS_RMS_FLOOR = 0.015f
    const val MIN_VOICED_MS = 400
    const val MIN_SPEECH_RATIO = 0.06f

    /** Coefficient of variation below this on frame RMS ⇒ stationary bed, not speech. */
    const val STATIONARY_CV = 0.28f

    /**
     * Only treat low-CV as a noise bed when mean RMS is in the hiss band.
     * On-mic speech (and a test sine) is louder; Silero VAD handles loud noise.
     */
    const val STATIONARY_MAX_MEAN = 0.08f

    fun hasEnoughSpeech(samples: FloatArray, sampleRate: Int): Boolean {
        if (samples.isEmpty()) return false
        val frames = SpeechEndpointing.frameRms(samples, sampleRate)
        if (frames.isEmpty()) return false
        if (isStationaryNoise(frames)) return false
        val mask = voicedMask(frames)
        var voiced = 0
        for (v in mask) if (v) voiced++
        val voicedMs = voiced * SpeechEndpointing.FRAME_MS
        val ratio = voiced.toFloat() / mask.size
        return voicedMs >= MIN_VOICED_MS && ratio >= MIN_SPEECH_RATIO
    }

    /**
     * Zero frames that fail the energy gate so pyannote/Zipformer never see the noise bed.
     * Does not mix other streams.
     */
    fun zeroNonSpeech(samples: FloatArray, sampleRate: Int): FloatArray {
        val frames = SpeechEndpointing.frameRms(samples, sampleRate)
        if (frames.isEmpty()) return FloatArray(samples.size)
        if (isStationaryNoise(frames)) return FloatArray(samples.size)
        val mask = voicedMask(frames)
        val frame = ((sampleRate * SpeechEndpointing.FRAME_MS) / 1000).coerceAtLeast(1)
        val out = samples.copyOf()
        var i = 0
        while (i < mask.size) {
            if (!mask[i]) {
                val start = i * frame
                val end = (start + frame).coerceAtMost(out.size)
                var j = start
                while (j < end) {
                    out[j] = 0f
                    j++
                }
            }
            i++
        }
        return out
    }

    fun isStationaryNoise(frameRms: FloatArray): Boolean {
        if (frameRms.size < 8) return false
        var sum = 0.0
        for (x in frameRms) sum += x
        val mean = (sum / frameRms.size).toFloat()
        if (mean < ABS_RMS_FLOOR) return true
        if (mean > STATIONARY_MAX_MEAN) return false
        var varAcc = 0.0
        for (x in frameRms) {
            val d = (x - mean).toDouble()
            varAcc += d * d
        }
        val std = sqrt(varAcc / frameRms.size).toFloat()
        val cv = std / mean
        return cv < STATIONARY_CV
    }

    private fun voicedMask(frameRms: FloatArray): BooleanArray {
        val p80 = SpeechEndpointing.percentile(frameRms, 0.8f)
        val floor = maxOf(ABS_RMS_FLOOR, p80 * SpeechEndpointing.BLEED_RELATIVE)
        return BooleanArray(frameRms.size) { i -> frameRms[i] >= floor }
    }
}
