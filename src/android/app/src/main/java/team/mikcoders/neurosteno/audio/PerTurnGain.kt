package team.mikcoders.neurosteno.audio

import team.mikcoders.neurosteno.pipeline.SpeechRegion

/**
 * Per-turn / per-chunk far-field gain (DEMO_STAGE6).
 *
 * Whole-file RMS AGC (Platform @ b66581c, Eval e0e64b85) did **not** recover s5
 * (agg WER 0.73→0.70) because the 3 m seat dominates the file RMS. This helper
 * stages each speech region (split into ≤[CHUNK_MS] chunks) with [FarFieldGain.stageTurn]
 * so 4–6 m turns lift independently and a hot near seat is not attenuated.
 *
 * Non-speech is left unchanged (HVAC is not boosted into a fake main).
 */
object PerTurnGain {
    const val CHUNK_MS = 3_000
    const val FADE_MS = 8

    fun apply(
        samples: FloatArray,
        regions: List<SpeechRegion>,
        sampleRateHz: Int = FarFieldGain.SAMPLE_RATE_HZ,
        chunkMs: Int = CHUNK_MS,
    ): PerTurnGainResult {
        if (samples.isEmpty() || regions.isEmpty()) {
            return PerTurnGainResult(samples = samples.copyOf(), turns = emptyList())
        }
        val out = samples.copyOf()
        val chunk = ((sampleRateHz.toLong() * chunkMs) / 1000L).toInt().coerceAtLeast(1)
        val fade = ((sampleRateHz * FADE_MS) / 1000).coerceAtLeast(1)
        val turns = ArrayList<TurnGain>()
        for (region in regions) {
            val a0 = region.startSample.coerceIn(0, samples.size)
            val a1 = region.endSample.coerceIn(a0, samples.size)
            var off = a0
            while (off < a1) {
                val end = (off + chunk).coerceAtMost(a1)
                val crop = samples.copyOfRange(off, end)
                val staged = FarFieldGain.stageTurn(crop)
                writeWithFade(out, off, staged.samples, fade)
                turns += TurnGain(
                    startSample = off,
                    endSample = end,
                    gainDb = staged.gainDb,
                    rmsBefore = staged.rmsBefore,
                    rmsAfter = staged.rmsAfter,
                    boosted = staged.boosted,
                    clipGuard = staged.clipGuard,
                )
                off = end
            }
        }
        return PerTurnGainResult(samples = out, turns = turns)
    }

    private fun writeWithFade(dest: FloatArray, destOff: Int, src: FloatArray, fade: Int) {
        var i = 0
        val n = src.size
        val fadeN = fade.coerceAtMost(n / 2)
        while (i < n) {
            val o = destOff + i
            if (o >= dest.size) break
            var y = src[i]
            if (fadeN > 0) {
                val w = when {
                    i < fadeN -> i.toFloat() / fadeN
                    i >= n - fadeN -> (n - 1 - i).toFloat() / fadeN
                    else -> 1f
                }
                y = dest[o] * (1f - w) + y * w
            }
            dest[o] = y
            i++
        }
    }
}

data class TurnGain(
    val startSample: Int,
    val endSample: Int,
    val gainDb: Double,
    val rmsBefore: Double,
    val rmsAfter: Double,
    val boosted: Boolean,
    val clipGuard: Boolean,
)

data class PerTurnGainResult(
    val samples: FloatArray,
    val turns: List<TurnGain>,
)
