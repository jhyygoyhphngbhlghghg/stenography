package team.mikcoders.neurosteno.pipeline

/**
 * Fix "app crashes after Расшифровать when the voice recording is too long".
 *
 * Zipformer (the app's ASR model) decodes a whole crop at once with
 * native activations proportional to crop length. Measured on host
 * (sherpa-onnx 2.0.1 + app encoder):
 *
 * | single crop | peak native RSS |
 * |---|---|
 * | 30 s        | 437 MB |
 * | 60 s        | 947 MB |
 * | 120 s       | 3.2 GB |
 * | 180 s       | 8.8 GB |
 *
 * One long voice line -> one long diar segment (mergeSameSpeaker has no
 * length cap) -> native allocation blows past the phone's budget and the
 * process is killed mid-transcription. The VAD path already bounds crops via
 * Silero maxSpeechDuration (30 s); the diar path had no bound.
 *
 * So every ASR decode is limited to [ASR_MAX_CROP_MS]: a segment longer than
 * the bound is split at its quietest 20 ms frames near the boundary (RFC
 * style: cut where energy dips, not mid-syllable) and the pieces are combined
 * back into the same utterance. 30 s matches the existing Silero clamp, is
 * at the top of the measured-safe region, and keeps Zipformer's offline
 * encoder in its validated range.
 */
internal object AsrWindowing {
    const val ASR_MAX_CROP_MS = 30_000L
    private const val LOOKAHEAD_MS = 6_000L
    private const val FRAME_MS = 20L

    /** Split [startMs]..[endMs] into consecutive windows each <= [maxMs]. */
    fun windows(
        samples: FloatArray,
        hz: Int,
        startMs: Long,
        endMs: Long,
        maxMs: Long = ASR_MAX_CROP_MS,
    ): List<Pair<Long, Long>> {
        require(hz > 0) { "hz must be positive" }
        require(endMs >= startMs) { "endMs must not precede startMs" }
        if (endMs - startMs <= maxMs || samples.isEmpty()) return listOf(startMs to endMs)
        val out = ArrayList<Pair<Long, Long>>()
        var start = startMs
        while (start < endMs) {
            val limit = minOf(endMs, start + maxMs)
            val cut = if (limit >= endMs) {
                endMs
            } else {
                quietestAround(samples, hz, limit, LOOKAHEAD_MS)?.takeIf { it in (start + 1) until endMs }
                    ?: limit
            }
            out += start to cut
            start = cut
        }
        return out
    }

    /** Lowest-RMS 20 ms frame near [targetMs] (search ±[around]), as ms. */
    internal fun quietestAround(
        samples: FloatArray,
        hz: Int,
        targetMs: Long,
        aroundMs: Long,
    ): Long? {
        if (samples.isEmpty() || hz <= 0) return null
        val frame = ((hz * FRAME_MS) / 1000).toInt().coerceAtLeast(1)
        val low = maxOf(0L, targetMs - aroundMs)
        val high = targetMs + aroundMs
        var bestMs: Long? = null
        var best = Double.POSITIVE_INFINITY
        var cursor = low
        while (cursor <= high) {
            val a = ((cursor * hz) / 1000).toInt().coerceIn(0, samples.size)
            var acc = 0.0
            var i = a
            val end = minOf(samples.size, a + frame)
            while (i < end) {
                val v = samples[i].toDouble()
                acc += v * v
                i++
            }
            val n = (end - a).coerceAtLeast(1)
            val rms = kotlin.math.sqrt(acc / n)
            if (rms < best) {
                best = rms
                bestMs = cursor
            }
            cursor += FRAME_MS
        }
        return bestMs
    }
}
