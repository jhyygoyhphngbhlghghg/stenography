package team.mikcoders.neurosteno.audio

import kotlin.math.abs

/**
 * Bad-mic gate: refuse hard-clipped / non-finite PCM instead of sending it to ASR
 * (Zipformer will hallucinate; Eval WER 1.0 on bad mic).
 *
 * Cite-only bar: bad mic WER 1.0. Not tuned on Common Voice.
 * Mild DC-block is resilience; hard clip **fails loud** ([Quality.Fail]).
 */
object AudioQualityGate {
    const val CLIP_ABS = 0.99f
    const val CLIP_RATIO_FAIL = 0.015f
    const val DC_BLOCK_R = 0.97f
    const val CODE = "BAD_AUDIO"

    sealed class Quality {
        data object Ok : Quality()
        data class Fail(val code: String, val message: String) : Quality()
    }

    class Rejected(val code: String, override val message: String) : RuntimeException(message)

    fun inspect(samples: FloatArray): Quality {
        if (samples.isEmpty()) return Quality.Ok
        var clip = 0
        var nonFinite = 0
        for (x in samples) {
            if (!x.isFinite()) {
                nonFinite++
                continue
            }
            if (abs(x) >= CLIP_ABS) clip++
        }
        if (nonFinite > 0) {
            return Quality.Fail(CODE, "non-finite PCM ($nonFinite samples)")
        }
        val ratio = clip.toFloat() / samples.size
        if (ratio >= CLIP_RATIO_FAIL) {
            return Quality.Fail(CODE, "clipped mic (clip_ratio=$ratio)")
        }
        return Quality.Ok
    }

    /** Cheap high-pass / DC blocker. Does not "repair" hard clip. */
    fun dcBlock(samples: FloatArray, r: Float = DC_BLOCK_R): FloatArray {
        if (samples.size < 2) return samples
        val out = FloatArray(samples.size)
        var prevX = 0f
        var prevY = 0f
        var i = 0
        while (i < samples.size) {
            val x = samples[i]
            val y = x - prevX + r * prevY
            out[i] = y
            prevX = x
            prevY = y
            i++
        }
        return out
    }
}
