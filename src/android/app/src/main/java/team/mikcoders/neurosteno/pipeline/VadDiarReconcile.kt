package team.mikcoders.neurosteno.pipeline

import team.mikcoders.neurosteno.audio.FarFieldGain

/**
 * Recover quiet distant seats that pyannote dropped (STAGE6 s5 @ 5.4 m).
 *
 * Cite-only: a8b39d5a / e0e64b85 / 54177a1e — 5 hyp speakers, s5 missing,
 * 3.0 m WER 0. Do **not** expand a far stub over the near seat. Uncovered
 * VAD pieces and energy holes become new speakers. PresentConnect skips this.
 */
object VadDiarReconcile {
    fun run(
        vad: List<SpeechRegion>,
        diar: List<DiarSeg>,
        hz: Int,
        scene: FarFieldScene,
        samples: FloatArray? = null,
    ): List<DiarSeg> {
        if (scene.expectedSpeakers <= 0) {
            return diar.sortedBy { it.startMs }
        }
        val near = diar.minByOrNull { it.startMs }
        val segs = ArrayList(diar)
        var orphanId = -1
        for (region in vad) {
            val r0 = region.startMs(hz)
            val r1 = region.endMs(hz)
            val pieces = subtract(r0, r1, segs)
            for (p in pieces) {
                if (p.second - p.first >= scene.orphanMinMs && !overlapsNear(p.first, p.second, near)) {
                    segs += DiarSeg(speakerIndex = orphanId--, startMs = p.first, endMs = p.second)
                }
            }
            snapTinyEndpoint(segs, r0, r1, scene, near)
        }
        if (samples != null && samples.isNotEmpty()) {
            orphanId = fillEnergyHoles(segs, samples, hz, scene, near, orphanId)
        }
        splitNonNearByVad(segs, vad, hz, scene, near)
        val clipped = segs.mapNotNull { s ->
            val c = clipAwayFromNear(s, near)
            if (c.endMs > c.startMs) c else null
        }
        val merged = mergeSameSpeaker(clipped, scene.mergeGapMs)
        return relabelByAppearance(merged)
    }

    /**
     * Snap a single overlapping diar stub only when the VAD island is short
     * (endpointing, ≤ [FarFieldScene.expandMaxRegionMs]). Never move the 3 m seat.
     */
    private fun snapTinyEndpoint(
        segs: MutableList<DiarSeg>,
        r0: Long,
        r1: Long,
        scene: FarFieldScene,
        near: DiarSeg?,
    ) {
        val dur = r1 - r0
        if (dur <= 0L || dur > scene.expandMaxRegionMs) return
        val hits = segs.withIndex().filter { (_, s) -> overlaps(s.startMs, s.endMs, r0, r1) }
        if (hits.size != 1) return
        val (ix, s) = hits[0]
        if (isNearSeat(s, near)) return
        val cov = overlapMs(s.startMs, s.endMs, r0, r1).toFloat() / dur.toFloat()
        if (cov < scene.expandIfCoverageBelow) {
            segs[ix] = s.copy(startMs = minOf(s.startMs, r0), endMs = maxOf(s.endMs, r1))
        }
    }

    private fun fillEnergyHoles(
        segs: MutableList<DiarSeg>,
        samples: FloatArray,
        hz: Int,
        scene: FarFieldScene,
        near: DiarSeg?,
        startOrphanId: Int,
    ): Int {
        var orphanId = startOrphanId
        val durationMs = if (hz <= 0) 0L else samples.size * 1000L / hz
        val holes = gaps(segs, 0L, durationMs)
        for (hole in holes) {
            if (hole.second - hole.first < scene.orphanMinMs) continue
            val a = ((hole.first * hz) / 1000L).toInt().coerceIn(0, samples.size)
            val b = ((hole.second * hz) / 1000L).toInt().coerceIn(a, samples.size)
            if (b - a < 2) continue
            val crop = samples.copyOfRange(a, b)
            val found = FarFieldGain.findSegments(crop, hz)
            for (r in found) {
                val s0 = hole.first + r.first * 1000L / hz
                val s1 = hole.first + (r.last + 1) * 1000L / hz
                if (s1 - s0 >= scene.orphanMinMs && !overlapsNear(s0, s1, near)) {
                    segs += DiarSeg(speakerIndex = orphanId--, startMs = s0, endMs = s1)
                }
            }
        }
        return orphanId
    }

    /**
     * If a non-near diar span covers two VAD islands, keep the first island
     * on the original speaker and emit later islands as new speakers (s5
     * swallowed into s4). Never split the 3 m seat.
     */
    private fun splitNonNearByVad(
        segs: MutableList<DiarSeg>,
        vad: List<SpeechRegion>,
        hz: Int,
        scene: FarFieldScene,
        near: DiarSeg?,
    ) {
        val extra = ArrayList<DiarSeg>()
        val remove = ArrayList<DiarSeg>()
        var orphanId = (segs.minOfOrNull { it.speakerIndex } ?: 0) - 1
        if (orphanId >= 0) orphanId = -1
        for (s in segs) {
            if (isNearSeat(s, near)) continue
            val hits = vad.filter { r ->
                overlaps(s.startMs, s.endMs, r.startMs(hz), r.endMs(hz))
            }.sortedBy { it.startSample }
            if (hits.size < 2) continue
            remove += s
            val first = hits[0]
            extra += s.copy(
                startMs = maxOf(s.startMs, first.startMs(hz)),
                endMs = minOf(s.endMs, first.endMs(hz)),
            )
            for (h in hits.drop(1)) {
                val a = maxOf(s.startMs, h.startMs(hz))
                val b = minOf(s.endMs, h.endMs(hz))
                if (b - a >= scene.orphanMinMs && !overlapsNear(a, b, near)) {
                    extra += DiarSeg(speakerIndex = orphanId--, startMs = a, endMs = b)
                }
            }
        }
        segs.removeAll(remove.toSet())
        segs += extra
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
                s.speakerIndex >= 0 &&
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

    internal fun subtract(r0: Long, r1: Long, occupied: List<DiarSeg>): List<Pair<Long, Long>> {
        var cur = r0
        val out = ArrayList<Pair<Long, Long>>()
        val hits = occupied.filter { overlaps(it.startMs, it.endMs, r0, r1) }.sortedBy { it.startMs }
        for (s in hits) {
            val a = maxOf(s.startMs, r0)
            val b = minOf(s.endMs, r1)
            if (a > cur) out += cur to a
            cur = maxOf(cur, b)
        }
        if (r1 > cur) out += cur to r1
        return out
    }

    private fun gaps(segs: List<DiarSeg>, t0: Long, t1: Long): List<Pair<Long, Long>> =
        subtract(t0, t1, segs)

    private fun overlapsNear(a0: Long, a1: Long, near: DiarSeg?): Boolean =
        near != null && overlaps(a0, a1, near.startMs, near.endMs)

    private fun isNearSeat(s: DiarSeg, near: DiarSeg?): Boolean =
        near != null &&
            s.speakerIndex == near.speakerIndex &&
            s.startMs == near.startMs &&
            s.endMs == near.endMs

    /** Orphans / later stubs must not eat the first (~3 m) seat. */
    internal fun clipAwayFromNear(s: DiarSeg, near: DiarSeg?): DiarSeg {
        if (near == null || isNearSeat(s, near)) return s
        if (!overlaps(s.startMs, s.endMs, near.startMs, near.endMs)) return s
        if (s.startMs >= near.startMs && s.endMs <= near.endMs) {
            return s.copy(endMs = s.startMs)
        }
        var start = s.startMs
        var end = s.endMs
        if (start < near.startMs && end > near.endMs) {
            start = near.endMs
        } else if (start < near.endMs && end > near.endMs) {
            start = near.endMs
        } else if (end > near.startMs && start < near.startMs) {
            end = near.startMs
        }
        return s.copy(startMs = start, endMs = end)
    }

    private fun overlaps(a0: Long, a1: Long, b0: Long, b1: Long): Boolean =
        a0 < b1 && b0 < a1

    private fun overlapMs(a0: Long, a1: Long, b0: Long, b1: Long): Long =
        (minOf(a1, b1) - maxOf(a0, b0)).coerceAtLeast(0L)
}
