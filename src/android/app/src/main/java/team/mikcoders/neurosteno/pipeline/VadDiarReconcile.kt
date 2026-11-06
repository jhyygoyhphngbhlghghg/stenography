package team.mikcoders.neurosteno.pipeline

import team.mikcoders.neurosteno.audio.FarFieldGain
import kotlin.math.sqrt

/**
 * Recover quiet distant seats pyannote dropped (STAGE6 s5 @ 5.4 m) **without**
 * inventing speaker_7..12 **and without** gluing distinct near-field turns.
 *
 * Cite-only Eval `a2219ef` (hardcap @ 3c3f9ce): hyp 6/6 fixed, but merge
 * smeared s2+s3 into one 7456–19066 island (s3 WER 1.0) and left s5 unmatched
 * (hyp speaker_2 stub «да.»). HARD CAP stays 6. Orphans attach only to
 * time-adjacent **same-energy** hosts; two substantial near-field islands
 * never share a cluster (protect s2 @ 3.6 m from s3). Quiet far maps to its
 * own seat (Hungarian-style), not onto s2. Near 3 m is not expanded.
 * PresentConnect skips this.
 */
object VadDiarReconcile {
    /** Turns longer than this are distinct seats, not mergeable stubs. */
    internal const val SUBSTANTIAL_MS = 1_200L
    private const val STUB_EXTEND_MS = 800L
    private const val SAME_ENERGY_RATIO = 2.5
    private const val NEAR_FIELD_RATIO = 0.42
    /** Quiet far VAD/energy islands (s5 ~4.7 s) may snap; near-field may not. */
    private const val FAR_EXPAND_MAX_MS = 6_000L

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
        val energy = EnergyIndex(samples, hz, near)
        val segs = ArrayList(diar)
        for (region in vad) {
            val r0 = region.startMs(hz)
            val r1 = region.endMs(hz)
            val pieces = subtract(r0, r1, segs)
            for (p in pieces) {
                adopt(segs, p.first, p.second, scene, energy, k)
            }
            snapEndpoint(segs, r0, r1, scene, energy)
        }
        if (samples != null && samples.isNotEmpty()) {
            fillEnergyHoles(segs, samples, hz, scene, energy, k)
            splitLongByEnergyDip(segs, samples, hz, scene, energy, k)
        }
        splitNonNearByVad(segs, vad, hz, scene, energy, k)
        val clipped = segs.mapNotNull { s ->
            val c = clipAwayFromNear(s, near)
            if (c.endMs > c.startMs) c else null
        }
        val capped = capToExpected(clipped, k, energy)
        val merged = mergeSameSpeaker(capped, scene.mergeGapMs)
        return if (samples != null && samples.isNotEmpty()) {
            SpeakerSeatAssign.assign(merged, energy, k)
        } else {
            relabelByAppearance(merged)
        }
    }

    /**
     * New cluster while unique < K. At cap, attach only to a time-adjacent
     * same-energy host — never glue two substantial near-field turns.
     */
    internal fun adopt(
        segs: MutableList<DiarSeg>,
        startMs: Long,
        endMs: Long,
        scene: FarFieldScene,
        energy: EnergyIndex,
        k: Int,
    ) {
        if (endMs - startMs < scene.orphanMinMs) return
        if (overlapsNear(startMs, endMs, energy.near)) return
        val orphan = DiarSeg(speakerIndex = -1, startMs = startMs, endMs = endMs)
        if (uniqueSpeakers(segs) < k) {
            segs += DiarSeg(speakerIndex = nextId(segs), startMs = startMs, endMs = endMs)
            return
        }
        if (energy.substantial(orphan)) {
            stealStub(segs, energy)
            segs += DiarSeg(speakerIndex = nextId(segs), startMs = startMs, endMs = endMs)
            return
        }
        val host = nearestCompatibleHost(startMs, endMs, segs, energy) ?: return
        val neighbor = segs.filter { it.speakerIndex == host }
            .minByOrNull { gap(it, startMs, endMs) } ?: return
        if (!energy.mayShare(neighbor, orphan)) return
        if (energy.canExtendStub(neighbor, orphan)) {
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

    private fun snapEndpoint(
        segs: MutableList<DiarSeg>,
        r0: Long,
        r1: Long,
        scene: FarFieldScene,
        energy: EnergyIndex,
    ) {
        val dur = r1 - r0
        if (dur <= 0L) return
        val hits = segs.withIndex().filter { (_, s) -> overlaps(s.startMs, s.endMs, r0, r1) }
        if (hits.size != 1) return
        val (ix, s) = hits[0]
        if (energy.isNearSeat(s) || energy.isNearField(s)) {
            if (dur > scene.expandMaxRegionMs) return
        } else if (dur > FAR_EXPAND_MAX_MS) {
            return
        }
        val cov = overlapMs(s.startMs, s.endMs, r0, r1).toFloat() / dur.toFloat()
        if (cov < scene.expandIfCoverageBelow) {
            val next = s.copy(startMs = minOf(s.startMs, r0), endMs = maxOf(s.endMs, r1))
            if (overlapsNear(next.startMs, next.endMs, energy.near) && !energy.isNearSeat(s)) {
                return
            }
            segs[ix] = next
        }
    }

    private fun fillEnergyHoles(
        segs: MutableList<DiarSeg>,
        samples: FloatArray,
        hz: Int,
        scene: FarFieldScene,
        energy: EnergyIndex,
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
                adopt(segs, s0, s1, scene, energy, k)
            }
        }
    }

    /**
     * pyannote sometimes emits one span over two near-field turns. Split on
     * energy dips so s2 @ 3.6 m is not glued to s3.
     */
    private fun splitLongByEnergyDip(
        segs: MutableList<DiarSeg>,
        samples: FloatArray,
        hz: Int,
        scene: FarFieldScene,
        energy: EnergyIndex,
        k: Int,
    ) {
        val keep = ArrayList<DiarSeg>(segs.size)
        val extra = ArrayList<Pair<Long, Long>>()
        for (s in segs) {
            if (energy.isNearSeat(s) || s.endMs - s.startMs < 2 * SUBSTANTIAL_MS) {
                keep += s
                continue
            }
            val a = ((s.startMs * hz) / 1000L).toInt().coerceIn(0, samples.size)
            val b = ((s.endMs * hz) / 1000L).toInt().coerceIn(a, samples.size)
            if (b - a < 2) {
                keep += s
                continue
            }
            val found = FarFieldGain.findSegments(samples.copyOfRange(a, b), hz)
            if (found.size < 2) {
                keep += s
                continue
            }
            val first = found[0]
            keep += DiarSeg(
                s.speakerIndex,
                s.startMs + first.first * 1000L / hz,
                s.startMs + (first.last + 1) * 1000L / hz,
            )
            for (r in found.drop(1)) {
                extra += (s.startMs + r.first * 1000L / hz) to
                    (s.startMs + (r.last + 1) * 1000L / hz)
            }
        }
        segs.clear()
        segs.addAll(keep)
        for ((t0, t1) in extra) {
            adopt(segs, t0, t1, scene, energy, k)
        }
    }

    /**
     * If a non-near diar span covers two VAD islands, keep the first island
     * on the original speaker; later islands go through [adopt] (new speaker
     * when they are distinct substantial turns).
     */
    private fun splitNonNearByVad(
        segs: MutableList<DiarSeg>,
        vad: List<SpeechRegion>,
        hz: Int,
        scene: FarFieldScene,
        energy: EnergyIndex,
        k: Int,
    ) {
        val extra = ArrayList<Triple<Long, Long, Int>>()
        val remove = ArrayList<DiarSeg>()
        for (s in segs) {
            if (energy.isNearSeat(s)) continue
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
                adopt(segs, a, b, scene, energy, k)
            }
        }
    }

    internal fun capToExpected(segs: List<DiarSeg>, k: Int, energy: EnergyIndex): List<DiarSeg> {
        if (k <= 0 || segs.isEmpty()) return segs
        val work = segs.toMutableList()
        var guard = 0
        while (uniqueSpeakers(work) > k && guard++ < 32) {
            if (!stealStub(work, energy)) {
                val victim = work
                    .filter { !energy.isNearSeat(it) && it.speakerIndex != energy.near?.speakerIndex }
                    .groupBy { it.speakerIndex }
                    .minByOrNull { (_, xs) -> xs.sumOf { it.endMs - it.startMs } }
                    ?.key ?: break
                reassignSpeaker(work, victim, energy)
            }
        }
        return mergeSameSpeaker(work, 0)
    }

    /** Reassign the shortest non-near stub onto a same-energy neighbor. */
    internal fun stealStub(segs: MutableList<DiarSeg>, energy: EnergyIndex): Boolean {
        val groups = segs.groupBy { it.speakerIndex }
        val victim = groups
            .filter { (id, xs) ->
                val dur = xs.sumOf { it.endMs - it.startMs }
                val dummy = xs.minBy { it.startMs }
                id != energy.near?.speakerIndex &&
                    !energy.isNearSeat(dummy) &&
                    dur < SUBSTANTIAL_MS
            }
            .minByOrNull { (_, xs) -> xs.sumOf { it.endMs - it.startMs } }
            ?.key ?: return false
        reassignSpeaker(segs, victim, energy)
        return true
    }

    private fun reassignSpeaker(work: MutableList<DiarSeg>, victim: Int, energy: EnergyIndex) {
        val rest = work.filter { it.speakerIndex != victim }
        if (rest.isEmpty()) return
        val moved = ArrayList<DiarSeg>(work.size)
        for (s in work) {
            if (s.speakerIndex != victim) {
                moved += s
                continue
            }
            val host = nearestCompatibleHost(s.startMs, s.endMs, rest, energy)
                ?: rest.filter { !energy.isNearSeat(it) }.minByOrNull { gap(it, s.startMs, s.endMs) }?.speakerIndex
                ?: rest.first().speakerIndex
            moved += s.copy(speakerIndex = host)
        }
        work.clear()
        work.addAll(moved)
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

    internal fun uniqueSpeakers(segs: List<DiarSeg>): Int =
        segs.map { it.speakerIndex }.distinct().size

    private fun nextId(segs: List<DiarSeg>): Int =
        (segs.maxOfOrNull { it.speakerIndex } ?: -1) + 1

    private fun nearestCompatibleHost(
        startMs: Long,
        endMs: Long,
        segs: List<DiarSeg>,
        energy: EnergyIndex,
    ): Int? {
        val orphan = DiarSeg(-1, startMs, endMs)
        val cand = segs.filter {
            !energy.isNearSeat(it) &&
                it.speakerIndex != energy.near?.speakerIndex &&
                energy.mayShare(it, orphan)
        }
        if (cand.isEmpty()) return null
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

    internal fun gap(s: DiarSeg, startMs: Long, endMs: Long): Long =
        when {
            s.endMs <= startMs -> startMs - s.endMs
            endMs <= s.startMs -> s.startMs - endMs
            else -> 0L
        }

    private fun overlapsNear(a0: Long, a1: Long, near: DiarSeg?): Boolean =
        near != null && overlaps(a0, a1, near.startMs, near.endMs)

    internal fun clipAwayFromNear(s: DiarSeg, near: DiarSeg?): DiarSeg {
        if (near == null || (s.speakerIndex == near.speakerIndex && s.startMs == near.startMs && s.endMs == near.endMs)) {
            return s
        }
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

    internal fun overlaps(a0: Long, a1: Long, b0: Long, b1: Long): Boolean =
        a0 < b1 && b0 < a1

    private fun overlapMs(a0: Long, a1: Long, b0: Long, b1: Long): Long =
        (minOf(a1, b1) - maxOf(a0, b0)).coerceAtLeast(0L)
}

/**
 * RMS / near-field view used to decide who may share a cluster.
 * Missing samples → energy unknown: substantial turns still never glue.
 */
internal class EnergyIndex(
    val samples: FloatArray?,
    val hz: Int,
    val near: DiarSeg?,
) {
    fun isNearSeat(s: DiarSeg): Boolean =
        near != null &&
            s.speakerIndex == near.speakerIndex &&
            s.startMs == near.startMs &&
            s.endMs == near.endMs

    fun rms(startMs: Long, endMs: Long): Double {
        val pcm = samples ?: return -1.0
        if (hz <= 0 || pcm.isEmpty()) return -1.0
        val a = ((startMs * hz) / 1000L).toInt().coerceIn(0, pcm.size)
        val b = ((endMs * hz) / 1000L).toInt().coerceIn(a, pcm.size)
        val n = b - a
        if (n <= 0) return 0.0
        var acc = 0.0
        var i = a
        while (i < b) {
            val v = pcm[i].toDouble()
            acc += v * v
            i++
        }
        return sqrt(acc / n)
    }

    fun rms(s: DiarSeg): Double = rms(s.startMs, s.endMs)

    fun nearRms(): Double = near?.let { rms(it) } ?: -1.0

    fun substantial(s: DiarSeg): Boolean = s.endMs - s.startMs >= VadDiarReconcile.SUBSTANTIAL_MS

    fun isNearField(s: DiarSeg): Boolean {
        val r = rms(s)
        val n = nearRms()
        if (r < 0.0 || n < 0.0) return isNearSeat(s)
        if (n < 1e-8) return false
        return r >= n * 0.42
    }

    fun sameEnergy(a: DiarSeg, b: DiarSeg): Boolean {
        val ra = rms(a)
        val rb = rms(b)
        if (ra < 0.0 || rb < 0.0) return !substantial(a) || !substantial(b)
        val lo = minOf(ra, rb).coerceAtLeast(1e-12)
        val hi = maxOf(ra, rb)
        return hi / lo <= 2.5
    }

    /**
     * Distinct substantial near-field turns (s2 vs s3) never share a cluster.
     * Two substantial seats never share. Stubs may join a time-adjacent
     * same-energy host that is not the 3 m seat.
     */
    fun mayShare(host: DiarSeg, orphan: DiarSeg): Boolean {
        if (isNearSeat(host) || host.speakerIndex == near?.speakerIndex) return false
        val bridged = minOf(host.startMs, orphan.startMs) to maxOf(host.endMs, orphan.endMs)
        if (near != null && VadDiarReconcile.overlaps(bridged.first, bridged.second, near.startMs, near.endMs) &&
            !isNearSeat(host)
        ) {
            return false
        }
        if (substantial(host) && substantial(orphan)) return false
        if (isNearField(host) && isNearField(orphan) && substantial(host) && substantial(orphan)) {
            return false
        }
        if (isNearField(host) && isNearField(orphan) && VadDiarReconcile.gap(host, orphan.startMs, orphan.endMs) > 0L) {
            return false
        }
        return sameEnergy(host, orphan)
    }

    fun canExtendStub(host: DiarSeg, orphan: DiarSeg): Boolean {
        if (!mayShare(host, orphan)) return false
        if (substantial(orphan)) return false
        return VadDiarReconcile.gap(host, orphan.startMs, orphan.endMs) <= 800L
    }
}

/**
 * Map fragments onto ≤K time-ordered seats so Eval Hungarian can match
 * quiet far hyp to ref s5 instead of leaving a speaker_2 stub unmapped.
 */
internal object SpeakerSeatAssign {
    fun assign(segs: List<DiarSeg>, energy: EnergyIndex, k: Int): List<DiarSeg> {
        if (segs.isEmpty() || k <= 0) return segs
        val anchors = pickAnchors(segs, energy, k)
        if (anchors.isEmpty()) {
            return VadDiarReconcile.relabelByAppearance(segs)
        }
        val ordered = anchors.sortedBy { it.startMs }
        val mapped = segs.map { s ->
            val host = closestAnchor(s, ordered, energy)
            s.copy(speakerIndex = host)
        }
        val kept = mapped.filter { s ->
            val host = ordered.getOrNull(s.speakerIndex)
            energy.substantial(s) ||
                energy.isNearSeat(s) ||
                (host != null && VadDiarReconcile.gap(host, s.startMs, s.endMs) <= 1_500L)
        }
        val out = kept.ifEmpty { mapped }
        return VadDiarReconcile.relabelByAppearance(out)
    }

    internal fun pickAnchors(segs: List<DiarSeg>, energy: EnergyIndex, k: Int): List<DiarSeg> {
        val near = energy.near
        val picked = ArrayList<DiarSeg>()
        val nearSeg = segs.filter { energy.isNearSeat(it) }.minByOrNull { it.startMs }
            ?: segs.minByOrNull { it.startMs }
        if (nearSeg != null) picked += nearSeg
        val rest = segs
            .filter { s -> picked.none { it.startMs == s.startMs && it.endMs == s.endMs } }
            .sortedWith(
                compareByDescending<DiarSeg> { it.endMs - it.startMs }
                    .thenByDescending { energy.rms(it) },
            )
        for (s in rest) {
            if (picked.size >= k) break
            if (!energy.substantial(s) && energy.isNearField(s).not() && picked.size >= 1) {
                continue
            }
            val overlapsExisting = picked.any { h ->
                VadDiarReconcile.overlaps(h.startMs, h.endMs, s.startMs, s.endMs) &&
                    overlapRatio(h, s) > 0.5
            }
            if (overlapsExisting) continue
            picked += s
        }
        if (picked.size < k) {
            for (s in rest) {
                if (picked.size >= k) break
                if (picked.any { it.startMs == s.startMs && it.endMs == s.endMs }) continue
                if (energy.rms(s) >= 0.0 && energy.nearRms() > 0.0 &&
                    energy.rms(s) < energy.nearRms() * 0.42
                ) {
                    picked += s
                }
            }
        }
        if (near != null) {
            val hasFarQuiet = picked.any { !energy.isNearField(it) && !energy.isNearSeat(it) }
            if (!hasFarQuiet) {
                val quiet = rest
                    .filter { !energy.isNearField(it) && !energy.isNearSeat(it) }
                    .maxByOrNull { it.endMs - it.startMs }
                if (quiet != null && picked.size < k) picked += quiet
                else if (quiet != null && picked.size >= k) {
                    val victimIx = picked.indices
                        .filter { i -> !energy.isNearSeat(picked[i]) && !energy.substantial(picked[i]) }
                        .minByOrNull { i -> picked[i].endMs - picked[i].startMs }
                    if (victimIx != null) picked[victimIx] = quiet
                }
            }
        }
        return picked.take(k)
    }

    /**
     * Min-cost (Hungarian-style) assignment of one fragment onto K anchors.
     * Quiet far never maps onto a near-field seat when a quiet anchor exists.
     */
    internal fun closestAnchor(s: DiarSeg, anchors: List<DiarSeg>, energy: EnergyIndex): Int {
        var best = 0
        var bestCost = Double.POSITIVE_INFINITY
        for ((i, a) in anchors.withIndex()) {
            val c = cost(s, a, energy, anchors)
            if (c < bestCost) {
                bestCost = c
                best = i
            }
        }
        return best
    }

    internal fun cost(s: DiarSeg, a: DiarSeg, energy: EnergyIndex, anchors: List<DiarSeg>): Double {
        val gap = VadDiarReconcile.gap(a, s.startMs, s.endMs).toDouble()
        val ov = overlapMs(s, a).toDouble()
        var c = gap - 4.0 * ov
        if (energy.isNearSeat(a) && !energy.isNearSeat(s) && !energy.isNearField(s)) {
            c += 1_000_000.0
        }
        if (energy.isNearField(a) && !energy.isNearField(s) && anchors.any { !energy.isNearField(it) }) {
            c += 200_000.0
        }
        if (energy.isNearField(s) && energy.isNearField(a) && s.startMs != a.startMs &&
            energy.substantial(s) && energy.substantial(a)
        ) {
            c += 500_000.0
        }
        if (!energy.sameEnergy(s, a)) c += 50_000.0
        return c
    }

    private fun overlapMs(a: DiarSeg, b: DiarSeg): Long =
        (minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)).coerceAtLeast(0L)

    private fun overlapRatio(a: DiarSeg, b: DiarSeg): Double {
        val ov = overlapMs(a, b).toDouble()
        val den = minOf(a.endMs - a.startMs, b.endMs - b.startMs).toDouble().coerceAtLeast(1.0)
        return ov / den
    }
}
