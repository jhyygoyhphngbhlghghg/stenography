package team.mikcoders.neurosteno.audio

/**
 * Conservative frame helpers. **Not** a pre-diar skip.
 *
 * H1 gated remeasure: the old stationary-bed wipe / hush skip dropped 9 Path B
 * speakers. Ingest hard-reject stays [team.mikcoders.neurosteno.present.StreamPcmGate]
 * (byte-identical to platform). Path B 20/20 speaker survival is a hard constraint.
 *
 * [zeroNonSpeech] only zeros near-digital-silence frames (below StreamPcmGate
 * RMS_REJECT_FS). It never replaces a whole buffer with zeros.
 */
object SpeechEnergy {
    /** Match StreamPcmGate.RMS_REJECT_FS — do not raise this or quiet talkers die. */
    const val ABS_RMS_FLOOR = 0.008f

    fun hasEnoughSpeech(samples: FloatArray, sampleRate: Int): Boolean {
        if (samples.isEmpty()) return false
        return SpeechEndpointing.rms(samples) >= ABS_RMS_FLOOR
    }

    /**
     * Zero frames below [ABS_RMS_FLOOR] only. If that would wipe the whole clip,
     * return the original samples so a quiet on-mic speaker is not deleted.
     */
    fun zeroNonSpeech(samples: FloatArray, sampleRate: Int): FloatArray {
        val frames = SpeechEndpointing.frameRms(samples, sampleRate)
        if (frames.isEmpty()) return samples.copyOf()
        val mask = BooleanArray(frames.size) { i -> frames[i] >= ABS_RMS_FLOOR }
        var kept = 0
        for (v in mask) if (v) kept++
        if (kept == 0) return samples.copyOf()
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
}
