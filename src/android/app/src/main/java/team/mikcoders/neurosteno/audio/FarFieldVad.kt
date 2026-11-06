package team.mikcoders.neurosteno.audio

import team.mikcoders.neurosteno.pipeline.SpeechRegion
import kotlin.math.sqrt

/**
 * Soft energy-VAD for DEMO_STAGE6. Not Silero JNI. Not a hard skip.
 *
 * Fire frames at [SOFT_ABS_FS] so 4–6 m sine (~0.006 FS at 6 m) is not dropped.
 * Keep a region only if it looks like speech (mean ≥ [KEEP_MEAN_FS] or any
 * frame ≥ [FarFieldGain.VAD_RMS_FS]) so HVAC (~0.004 FS) is not a "turn".
 * Distant talkers are never hard-dropped for energy/band-limit.
 */
object FarFieldVad {
    /** Below 6 m close-talk 1/r (~0.0059) and above HVAC 0.004. */
    const val SOFT_ABS_FS = 0.0035
    const val KEEP_MEAN_FS = 0.005

    fun speechRegions(
        samples: FloatArray,
        sampleRateHz: Int = FarFieldGain.SAMPLE_RATE_HZ,
        frameMs: Int = FarFieldGain.FRAME_MS,
        fireFs: Double = SOFT_ABS_FS,
        keepMeanFs: Double = KEEP_MEAN_FS,
        confirmFs: Double = FarFieldGain.VAD_RMS_FS,
        mergeGapFrames: Int = 2,
        minFrames: Int = 8,
    ): List<SpeechRegion> {
        val frame = ((sampleRateHz * frameMs) / 1000).coerceAtLeast(1)
        if (samples.isEmpty()) return emptyList()
        if (samples.size < frame) {
            val r = FarFieldGain.rms(samples)
            return if (r >= keepMeanFs || r >= confirmFs) {
                listOf(SpeechRegion(0, samples.size))
            } else {
                emptyList()
            }
        }
        val rmsFrames = ArrayList<Double>(samples.size / frame)
        var off = 0
        while (off + frame <= samples.size) {
            var acc = 0.0
            var i = 0
            while (i < frame) {
                val s = samples[off + i]
                acc += s.toDouble() * s
                i++
            }
            rmsFrames += sqrt(acc / frame)
            off += frame
        }
        val merged = ArrayList<SpeechRegion>()
        var i = 0
        while (i < rmsFrames.size) {
            if (rmsFrames[i] < fireFs) {
                i++
                continue
            }
            var j = i + 1
            var gap = 0
            var sum = rmsFrames[i]
            var peak = rmsFrames[i]
            var nFire = 1
            while (j < rmsFrames.size) {
                val r = rmsFrames[j]
                if (r >= fireFs) {
                    gap = 0
                    sum += r
                    if (r > peak) peak = r
                    nFire++
                    j++
                    continue
                }
                gap++
                if (gap > mergeGapFrames) break
                j++
            }
            val lastFire = j - gap
            val nFrames = lastFire - i
            val mean = if (nFire == 0) 0.0 else sum / nFire
            val keep = nFrames >= minFrames && (mean >= keepMeanFs || peak >= confirmFs)
            if (keep) {
                val a = i * frame
                val b = (lastFire * frame).coerceAtMost(samples.size)
                merged += SpeechRegion(a, b)
            }
            i = lastFire
        }
        return merged
    }

    fun union(a: List<SpeechRegion>, b: List<SpeechRegion>): List<SpeechRegion> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val all = (a + b).sortedBy { it.startSample }
        val out = ArrayList<SpeechRegion>(all.size)
        var cur = all[0]
        var i = 1
        while (i < all.size) {
            val n = all[i]
            if (n.startSample <= cur.endSample) {
                cur = SpeechRegion(cur.startSample, maxOf(cur.endSample, n.endSample))
            } else {
                out += cur
                cur = n
            }
            i++
        }
        out += cur
        return out
    }
}
