package team.mikcoders.neurosteno.audio

import team.mikcoders.neurosteno.pipeline.SpeechRegion

/**
 * Soft energy-VAD windows for DEMO_STAGE6. Delegates to [FarFieldGain.findSegments]
 * (Platform PR #3 @ db3afe44 / ae86903): onset 0.0025 FS, hangover, dip-split.
 * Not a hard skip — distant 4–6 m windows are kept for per-turn gain / diar.
 */
object FarFieldVad {
    fun speechRegions(
        samples: FloatArray,
        sampleRateHz: Int = FarFieldGain.SAMPLE_RATE_HZ,
    ): List<SpeechRegion> =
        FarFieldGain.findSegments(samples, sampleRateHz).map { r ->
            SpeechRegion(startSample = r.first, endSample = r.last + 1)
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
