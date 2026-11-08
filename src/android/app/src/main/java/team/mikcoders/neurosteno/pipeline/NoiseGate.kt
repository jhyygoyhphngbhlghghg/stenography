package team.mikcoders.neurosteno.pipeline

import kotlin.math.sqrt

/**
 * Drop noise-burst segments before clustering/reconcile on casual scenes.
 *
 * Device verdict 2026-11-08 (present branch): a 3-person casual recording in a
 * live room still produced 7 speakers at diarClusterThreshold=0.65 /
 * SAME_VOICE_MAX_DISTANCE=0.60. Mechanism confirmed on host fixtures
 * (scripts/make_noise_fixtures.py + scripts/noise_gate_sweep.py): background
 * noise bursts (0.3-1.5 s, -20..-10 dBFS vs speech peak, plus -35 dBFS room
 * noise) become Silero/energy VAD islands -> pyannote segments -> scattered
 * noise embeddings -> their own FastClustering clusters; with
 * expectedSpeakers=-1 [VadDiarReconcile.run] returns the clustering as-is and
 * [SpeakerIdentityMerge] re-roots every unglued fragment as a fresh label, so
 * each noise cluster surfaces as a phantom speaker that "says" hallucinated
 * words.
 *
 * Gate rule (pure, deterministic): drop a raw diarization segment when
 *   duration < [FarFieldScene.noiseGateMinMs]  OR
 *   segment RMS < [FarFieldScene.noiseGateRmsRatio] * median(segment RMS).
 * Applied in SherpaStenoPipeline AFTER raw segment mapping and BEFORE
 * reconcile/merge, and ONLY when scene.expectedSpeakers <= 0 (casual /
 * presentConnect). The stage6 rig path (expectedSpeakers=6) never gates:
 * its quiet far seats (s5 @ 5.4 m) are exactly the short/low-RMS segments
 * the reconcile is built to recover.
 */
object NoiseGate {
    /**
     * @param segs raw mapped diarization segments (pre-reconcile).
     * @param samples PCM the segments were produced from.
     * @param hz sample rate of [samples].
     * @param minMs segments strictly shorter than this are dropped; <=0 disables.
     * @param rmsRatio drop segments whose RMS is below this fraction of the
     *   median segment RMS; <=0 disables.
     * @return kept segments in input order (possibly all of them).
     */
    fun apply(
        segs: List<DiarSeg>,
        samples: FloatArray,
        hz: Int,
        minMs: Long,
        rmsRatio: Float,
    ): List<DiarSeg> {
        if (segs.size <= 1 || minMs <= 0L || rmsRatio <= 0f) return segs
        if (samples.isEmpty() || hz <= 0) return segs
        val rmss = DoubleArray(segs.size) { rms(samples, hz, segs[it].startMs, segs[it].endMs) }
        val median = median(rmss)
        val cutoff = rmsRatio.toDouble() * median
        val out = ArrayList<DiarSeg>(segs.size)
        for (i in segs.indices) {
            val s = segs[i]
            if (s.endMs - s.startMs < minMs) continue
            if (rmss[i] < cutoff) continue
            out += s
        }
        return out
    }

    internal fun rms(samples: FloatArray, hz: Int, startMs: Long, endMs: Long): Double {
        val a = ((startMs * hz) / 1000L).toInt().coerceIn(0, samples.size)
        val b = ((endMs * hz) / 1000L).toInt().coerceIn(a, samples.size)
        val n = b - a
        if (n <= 0) return 0.0
        var acc = 0.0
        for (i in a until b) {
            val v = samples[i].toDouble()
            acc += v * v
        }
        return sqrt(acc / n)
    }

    internal fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sortedArray()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }
}
