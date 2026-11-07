package team.mikcoders.neurosteno.pipeline

/**
 * Fix "sequential replicas from the same person get a new speaker every time".
 *
 * [VadDiarReconcile] labels segments by time/energy (adopt + seat assignment),
 * and can absorb a one-person recording into one "speaker" per VAD island
 * (when raw pyannote output is empty it labels every island a fresh speaker
 * up to the K cap). Neither stage knows the voice identity.
 *
 * This pass re-labels the reconciled segments by **voice embedding** instead:
 * segments whose embeddings are closer (cosine distance) than
 * [SpeakerIdentityMerge.threshold] are the same person, regardless of time.
 * It only ever MERGES labels (never splits), so the frozen stage6 K=6 cap and
 * s5 recovery keep their roles, and distinct seats stay distinct.
 *
 * Threshold choice (measured on host with the app's 3D-Speaker embedding,
 * sherpa-onnx 2.0.1, scripts/host_asr_diar.py model set):
 *  - same voice, different crops (1.5-3 s gated windows): p25 0.33, median 0.51
 *    for the noisiest crops; long/repeated documents are far tighter
 *    (repeated clip 3 s windows: 0.01-0.03).
 *  - different voices (the 6 DEMO_STAGE6 seats, in-mix): min 0.62 (s3 vs s5),
 *    median 0.82-0.84.
 * Merging happens at the anchored-embedding level (each segment -> one unit
 * embedding), not per-window, so intra-segment variation is averaged out and
 * the practical merge threshold is set at 0.45, well between the repeat-copy
 * distances and the nearest distinct-seat distance (0.62).
 */
internal object SpeakerIdentityMerge {
    const val SAME_VOICE_MAX_DISTANCE = 0.45f
    private const val WINDOW_MS = 1_500L
    private const val MIN_EMBED_MS = 300L

    /**
     * @param embeddings unit-norm embedding per segment, or null when no
     * speech is available to embed (e.g. very short stub).
     */
    fun merge(
        segs: List<DiarSeg>,
        embeddings: List<FloatArray?>,
        threshold: Float = SAME_VOICE_MAX_DISTANCE,
    ): List<DiarSeg> {
        require(embeddings.size == segs.size) { "embeddings must align with segs" }
        if (segs.size <= 1) return segs
        val parent = IntArray(segs.size) { it }

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) {
                val n = parent[c]
                parent[c] = r
                c = n
            }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }

        for (i in segs.indices) {
            val a = embeddings[i] ?: continue
            for (j in i + 1 until segs.size) {
                val b = embeddings[j] ?: continue
                if (distance(a, b) <= threshold) union(i, j)
            }
        }

        // Segments without an embedding (short stubs) keep their time-ordered
        // label unless they clearly belong to a same-person neighbor: attach to
        // a temporally adjacent embedded segment with the same pre-merge label
        // if one exists, else the nearest embedded segment in time.
        for (i in segs.indices) {
            if (embeddings[i] != null) continue
            val sameLabel = segs.indices.filter { it != i && embeddings[it] != null && segs[it].speakerIndex == segs[i].speakerIndex }
            val host = sameLabel.minByOrNull { gap(it, i, segs) }
                ?: segs.indices.filter { embeddings[it] != null }.minByOrNull { gap(it, i, segs) }
            if (host != null) union(i, host)
        }

        val labels = segs.mapIndexed { i, _ -> find(i) }
        return VadDiarReconcile.relabelByAppearance(
            segs.mapIndexed { i, s -> s.copy(speakerIndex = labels[i]) },
        )
    }

    internal fun distance(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size && a.isNotEmpty()) { "embedding size mismatch" }
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return (1f - dot).coerceIn(0f, 2f)
    }

    internal fun averageEmbeddings(
        embeddings: List<FloatArray>,
    ): FloatArray? {
        val valid = embeddings.filter { it.isNotEmpty() }
        if (valid.isEmpty()) return null
        val dim = valid[0].size
        val acc = FloatArray(dim)
        for (emb in valid) {
            require(emb.size == dim) { "embedding size mismatch" }
            val norm = norm(emb)
            if (norm <= 0f) continue
            for (i in 0 until dim) acc[i] += emb[i] / norm
        }
        if (acc.all { it == 0f }) return null
        val norm = norm(acc)
        if (norm <= 0f) return null
        return FloatArray(dim) { acc[it] / norm }
    }

    private fun norm(v: FloatArray): Float {
        var acc = 0.0
        for (x in v) acc += x.toDouble() * x.toDouble()
        return kotlin.math.sqrt(acc).toFloat()
    }

    private fun gap(candidate: Int, index: Int, segs: List<DiarSeg>): Long {
        val a = segs[candidate]
        val b = segs[index]
        return when {
            a.endMs <= b.startMs -> b.startMs - a.endMs
            b.endMs <= a.startMs -> a.startMs - b.endMs
            else -> 0L
        }
    }

    /**
     * Window positions (startMs,endMs) to embed so a long segment's embedding
     * represents the whole turn, not one syllable.
     */
    internal fun embeddingWindows(startMs: Long, endMs: Long): List<LongRange> {
        val dur = endMs - startMs
        if (dur < MIN_EMBED_MS) return emptyList()
        if (dur <= WINDOW_MS) return listOf(startMs..endMs)
        val span = dur - WINDOW_MS
        val anchors = longArrayOf(0L, span / 2, span)
        return anchors.map { a -> (startMs + a)..(startMs + a + WINDOW_MS) }
    }
}
