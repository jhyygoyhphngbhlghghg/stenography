package team.mikcoders.neurosteno.pipeline

/**
 * Keep quiet distant seats that pyannote under-segmented.
 *
 * STAGE6 @ a8b39d5a / e0e64b85 (cite only): 5 hyp speakers, s5 missing, s4 was
 * a 202 ms stub. After per-turn gain, energy-VAD regions that diarization
 * missed (or barely covered) are injected / expanded. PresentConnect skips this.
 */
object VadDiarReconcile {
    fun run(
        vad: List<SpeechRegion>,
        diar: List<DiarSeg>,
        hz: Int,
        scene: FarFieldScene,
    ): List<DiarSeg> {
        if (scene.expectedSpeakers <= 0) {
            return diar.sortedBy { it.startMs }
        }
        val segs = ArrayList(diar)
        for (region in vad) {
            val r0 = region.startMs(hz)
            val r1 = region.endMs(hz)
            val dur = (r1 - r0).coerceAtLeast(1L)
            val hits = segs.withIndex().filter { (_, s) -> overlaps(s.startMs, s.endMs, r0, r1) }
            if (hits.isEmpty()) {
                if (dur >= scene.orphanMinMs) {
                    segs += DiarSeg(speakerIndex = -1, startMs = r0, endMs = r1)
                }
                continue
            }
            if (hits.size == 1 && dur <= scene.expandMaxRegionMs) {
                val (ix, s) = hits[0]
                val cov = overlapMs(s.startMs, s.endMs, r0, r1).toFloat() / dur.toFloat()
                if (cov < scene.expandIfCoverageBelow) {
                    segs[ix] = s.copy(
                        startMs = minOf(s.startMs, r0),
                        endMs = maxOf(s.endMs, r1),
                    )
                }
            }
        }
        val cleaned = segs.filter { it.endMs > it.startMs }
        val merged = mergeSameSpeaker(cleaned, scene.mergeGapMs)
        return relabelByAppearance(merged)
    }

    fun mergeSameSpeaker(segs: List<DiarSeg>, gapMs: Long): List<DiarSeg> {
        if (segs.size <= 1 || gapMs <= 0) return segs.sortedBy { it.startMs }
        val sorted = segs.sortedWith(compareBy<DiarSeg> { it.startMs }.thenBy { it.speakerIndex })
        val out = ArrayList<DiarSeg>(sorted.size)
        for (s in sorted) {
            val last = out.lastOrNull()
            if (
                last != null &&
                last.speakerIndex == s.speakerIndex &&
                s.startMs <= last.endMs + gapMs
            ) {
                out[out.lastIndex] = last.copy(endMs = maxOf(last.endMs, s.endMs))
            } else {
                out += s
            }
        }
        return out
    }

    fun relabelByAppearance(segs: List<DiarSeg>): List<DiarSeg> {
        val order = segs.sortedBy { it.startMs }.map { it.speakerIndex }.distinct()
        val map = order.withIndex().associate { it.value to it.index }
        return segs.map { s -> s.copy(speakerIndex = map.getValue(s.speakerIndex)) }
    }

    private fun overlaps(a0: Long, a1: Long, b0: Long, b1: Long): Boolean =
        a0 < b1 && b0 < a1

    private fun overlapMs(a0: Long, a1: Long, b0: Long, b1: Long): Long =
        (minOf(a1, b1) - maxOf(a0, b0)).coerceAtLeast(0L)
}
