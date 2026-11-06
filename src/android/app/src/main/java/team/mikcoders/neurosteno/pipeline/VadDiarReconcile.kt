package team.mikcoders.neurosteno.pipeline

import team.mikcoders.neurosteno.audio.FarFieldGain

/**
 * Recover quiet distant seats pyannote dropped (STAGE6 s5 @ 5.4 m) **without**
 * inventing speaker_7..12.
 *
 * Cite-only Eval `9b29b9c1` (mirror 552b142): hyp/ref 12/6, JER 0.6821 —
 * previous orphan/hole/split path over-split. HARD CAP = [FarFieldScene.expectedSpeakers]
 * (6). Extra islands merge into existing clusters, never a new label past K.
 * Near (~3 m) seat is not expanded and does not absorb far orphans.
 * PresentConnect skips this.
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
        val k = scene.expectedSpeakers
        val near = diar.minByOrNull { it.startMs }
        val segs = ArrayList(diar)
        for (region in vad) {
            val r0 = region.startMs(hz)
            val r1 = region.endMs(hz)
            val pieces = subtract(r0, r1, segs)
            for (p in pieces) {
                adopt(segs, p.first, p.second, scene, near, k)
            }
            snapTinyEndpoint(segs, r0, r1, scene, near)
        }
        if (samples != null && samples.isNotEmpty()) {
            fillEnergyHoles(segs, samples, hz, scene, near, k)
        }
        splitNonNearByVad(segs, vad, hz, scene, near, k)
        val clipped = segs.mapNotNull { s ->
            val c = clipAwayFromNear(s, near)
            if (c.endMs > c.startMs) c else null
        }
        val capped = capToExpected(clipped, k, near)
        val merged = mergeSameSpeaker(capped, scene.mergeGapMs)
        return relabelByAppearance(merged)
    }

    /**
     * New cluster only while unique speakers < K. Otherwise attach to the
     * nearest **non-near** cluster (never speaker_7+).
     */
    internal fun adopt(
        segs: MutableList<DiarSeg>,
        startMs: Long,
        endMs: Long,
        scene: FarFieldScene,
        near: DiarSeg?,
        k: Int,
    ) {
        if (endMs - startMs < scene.orphanMinMs) return
        if (overlapsNear(startMs, endMs, near)) return
        val unique = uniqueSpeakers(segs)
        if (unique < k) {
            segs += DiarSeg(speakerIndex = nextId(segs), startMs = startMs, endMs = endMs)
            return
        }
        val host = nearestNonNearSpeaker(startMs, endMs, segs, near) ?: return
        val neighbor = segs.filter { it.speakerIndex == host }.minByOrNull { gap(it, startMs, endMs) }
        if (neighbor != null && canExtend(neighbor, startMs, endMs, near)) {
            val ix = segs.indexOf(neighbor)
            if (ix >= 0) {
                segs[ix] = neighbor.copy(
                    startMs = minOf(neighbor.startMs, startMs),
                    endMs = maxOf(neighbor.endMs, endMs),
                )
                return
            }
        }
        segs += DiarSeg(speakerIndex = host, startMs = startMs, endMs = endMs)
    }

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
        k: Int,
    ) {
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
                adopt(segs, s0, s1, scene, near, k)
            }
        }
    }

    /**
     * If a non-near diar span covers two VAD islands, keep the first island
     * on the original speaker; later islands go through [adopt] (new only if < K).
     */
    private fun splitNonNearByVad(
        segs: MutableList<DiarSeg>,
        vad: List<SpeechRegion>,
        hz: Int,
        scene: FarFieldScene,
        near: DiarSeg?,
        k: Int,
    ) {
        val extra = ArrayList<Triple<Long, Long, Int>>()
        val remove = ArrayList<DiarSeg>()
        for (s in segs) {
            if (isNearSeat(s, near)) continue
            val hits = vad.filter { r ->
                overlaps(s.startMs, s.endMs, r.startMs(hz), r.endMs(hz))
            }.sortedBy { it.startSample }
            if (hits.size < 2) continue
            remove += s
            val first = hits[0]
            extra += Triple(
                maxOf(s.startMs, first.startMs(hz)),
                minOf(s.endMs, first.endMs(hz)),
                s.speakerIndex,
            )
            for (h in hits.drop(1)) {
                extra += Triple(
                    maxOf(s.startMs, h.startMs(hz)),
                    minOf(s.endMs, h.endMs(hz)),
                    -1,
                )
            }
        }
        segs.removeAll(remove.toSet())
        for ((a, b, id) in extra) {
            if (id >= 0 && b > a) {
                segs += DiarSeg(id, a, b)
            } else {
                adopt(segs, a, b, scene, near, k)
            }
        }
    }

    internal fun capToExpected(segs: List<DiarSeg>, k: Int, near: DiarSeg?): List<DiarSeg> {
        if (k <= 0 || segs.isEmpty()) return segs
        val work = segs.toMutableList()
        var guard = 0
        while (uniqueSpeakers(work) > k && guard++ < 32) {
            val nearId = near?.speakerIndex
            val victim = work
                .filter { it.speakerIndex != nearId }
                .groupBy { it.speakerIndex }
                .minByOrNull { (_, xs) -> xs.sumOf { it.endMs - it.startMs } }
                ?.key ?: break
            val rest = work.filter { it.speakerIndex != victim }
            if (rest.isEmpty()) break
            val moved = ArrayList<DiarSeg>()
            for (s in work) {
                if (s.speakerIndex != victim) {
                    moved += s
                    continue
                }
                val host = nearestNonNearSpeaker(s.startMs, s.endMs, rest, near)
                    ?: rest.first().speakerIndex
                moved += s.copy(speakerIndex = host)
            }
            work.clear()
            work.addAll(moved)
        }
        return mergeSameSpeaker(work, 0)
    }

    fun mergeSameSpeaker(segs: List<DiarSeg>, gapMs: Long): List<DiarSeg> {
        if (segs.size <= 1) return segs.sortedBy { it.startMs }
        val sorted = segs.sortedWith(compareBy<DiarSeg> { it.startMs }.thenBy { it.speakerIndex })
        if (gapMs < 0) return sorted
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

    private fun uniqueSpeakers(segs: List<DiarSeg>): Int =
        segs.map { it.speakerIndex }.distinct().size

    private fun nextId(segs: List<DiarSeg>): Int =
        (segs.maxOfOrNull { it.speakerIndex } ?: -1) + 1

    private fun nearestNonNearSpeaker(
        startMs: Long,
        endMs: Long,
        segs: List<DiarSeg>,
        near: DiarSeg?,
    ): Int? {
        val cand = segs.filter { !isNearSeat(it, near) && it.speakerIndex != near?.speakerIndex }
        if (cand.isEmpty()) {
            val others = segs.filter { it.speakerIndex != near?.speakerIndex }
            return others.minByOrNull { gap(it, startMs, endMs) }?.speakerIndex
        }
        val left = cand.filter { it.endMs <= startMs }.maxByOrNull { it.endMs }
        val right = cand.filter { it.startMs >= endMs }.minByOrNull { it.startMs }
        return when {
            left != null && right != null ->
                if (startMs - left.endMs <= right.startMs - endMs) left.speakerIndex else right.speakerIndex
            left != null -> left.speakerIndex
            right != null -> right.speakerIndex
            else -> cand.minByOrNull { gap(it, startMs, endMs) }?.speakerIndex
        }
    }

    private fun canExtend(neighbor: DiarSeg, startMs: Long, endMs: Long, near: DiarSeg?): Boolean {
        if (isNearSeat(neighbor, near) || neighbor.speakerIndex == near?.speakerIndex) return false
        val bridged = minOf(neighbor.startMs, startMs) to maxOf(neighbor.endMs, endMs)
        if (overlapsNear(bridged.first, bridged.second, near)) return false
        return gap(neighbor, startMs, endMs) <= 2_000L
    }

    private fun gap(s: DiarSeg, startMs: Long, endMs: Long): Long =
        when {
            s.endMs <= startMs -> startMs - s.endMs
            endMs <= s.startMs -> s.startMs - endMs
            else -> 0L
        }

    private fun overlapsNear(a0: Long, a1: Long, near: DiarSeg?): Boolean =
        near != null && overlaps(a0, a1, near.startMs, near.endMs)

    private fun isNearSeat(s: DiarSeg, near: DiarSeg?): Boolean =
        near != null &&
            s.speakerIndex == near.speakerIndex &&
            s.startMs == near.startMs &&
            s.endMs == near.endMs

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
