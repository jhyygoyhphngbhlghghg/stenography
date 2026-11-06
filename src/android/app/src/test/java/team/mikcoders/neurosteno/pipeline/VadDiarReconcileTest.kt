package team.mikcoders.neurosteno.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.mikcoders.neurosteno.audio.FarFieldSynth
import kotlin.math.sin

class VadDiarReconcileTest {
    private val hz = 16_000
    private val scene = FarFieldScene.stage6

    @Test
    fun orphanFifthTurnIsRecoveredAsSpeaker() {
        val vad = listOf(
            SpeechRegion(0, 16_000),
            SpeechRegion(16_000, 32_000),
            SpeechRegion(32_000, 48_000),
            SpeechRegion(48_000, 64_000),
            SpeechRegion(64_000, 80_000),
            SpeechRegion(80_000, 96_000),
        )
        val diar = listOf(
            DiarSeg(0, 0, 1_000),
            DiarSeg(1, 1_000, 2_000),
            DiarSeg(2, 2_000, 3_000),
            DiarSeg(3, 3_000, 4_000),
            DiarSeg(5, 5_000, 6_000),
        )
        val out = VadDiarReconcile.run(vad, diar, hz, scene)
        assertEquals(6, out.map { it.speakerIndex }.distinct().size)
        val fifth = out.single { it.startMs == 4_000L }
        assertTrue(fifth.endMs > fifth.startMs)
        assertTrue(out.none { it.speakerIndex >= 6 })
    }

    @Test
    fun twelveVadIslandsNeverInventSpeakerSeven() {
        val vad = (0 until 12).map { i ->
            SpeechRegion(i * 16_000, (i + 1) * 16_000)
        }
        val diar = listOf(
            DiarSeg(0, 0, 1_000),
            DiarSeg(1, 1_000, 2_000),
            DiarSeg(2, 2_000, 3_000),
            DiarSeg(3, 3_000, 4_000),
            DiarSeg(4, 4_000, 5_000),
        )
        val out = VadDiarReconcile.run(vad, diar, hz, scene)
        val n = out.map { it.speakerIndex }.distinct().size
        assertEquals("hard cap K=6, was $n labels ${out.map { it.speakerId }}", 6, n)
        assertTrue(out.none { it.speakerIndex > 5 })
        val near = out.minBy { it.startMs }
        assertEquals(0L, near.startMs)
        assertEquals(1_000L, near.endMs)
    }

    @Test
    fun largeVadBlobDoesNotSwallowNearSeat() {
        val vad = listOf(SpeechRegion(0, 16_000 * 6))
        val diar = listOf(
            DiarSeg(0, 0, 1_000),
            DiarSeg(1, 2_000, 2_202),
        )
        val out = VadDiarReconcile.run(vad, diar, hz, scene)
        val near = out.minBy { it.startMs }
        assertEquals(0L, near.startMs)
        assertEquals(1_000L, near.endMs)
        assertTrue(out.none { it.startMs == 0L && it.endMs == 6_000L })
        assertTrue(out.map { it.speakerIndex }.distinct().size <= 6)
    }

    @Test
    fun energyHoleRecoversQuietFarTurnWithoutMovingNear() {
        val (pcm, turns) = FarFieldSynth.sequentialMix()
        val near = turns.first { it.name == "near_3m" }
        val t0 = near.range.first * 1000L / hz
        val t1 = (near.range.last + 1) * 1000L / hz
        val diar = listOf(DiarSeg(0, t0, t1))
        val vad = listOf(SpeechRegion(near.range.first, near.range.last + 1))
        val out = VadDiarReconcile.run(vad, diar, hz, scene, samples = pcm)
        val kept = out.minBy { it.startMs }
        assertEquals(t0, kept.startMs)
        assertEquals(t1, kept.endMs)
        assertTrue("recover s5-like far island", out.size >= 2)
        assertTrue(out.any { it.startMs >= t1 && it.endMs - it.startMs >= 400 })
        assertTrue(out.map { it.speakerIndex }.distinct().size <= 6)
        assertEquals(kept.speakerIndex, 0)
        assertTrue(out.filter { it.startMs >= t1 }.none { it.speakerIndex == kept.speakerIndex })
    }

    @Test
    fun doesNotGlueS2AndS3NearFieldTurns() {
        val pcm = stage6LikePcm()
        val vad = listOf(
            SpeechRegion(ms(0), ms(7_000)),
            SpeechRegion(ms(7_456), ms(12_000)),
            SpeechRegion(ms(12_500), ms(19_066)),
            SpeechRegion(ms(20_000), ms(25_000)),
            SpeechRegion(ms(28_970), ms(33_650)),
            SpeechRegion(ms(34_000), ms(38_000)),
        )
        val diar = listOf(
            DiarSeg(0, 0, 7_000),
            DiarSeg(1, 7_456, 19_066),
            DiarSeg(2, 20_000, 25_000),
            DiarSeg(3, 34_000, 38_000),
        )
        val out = VadDiarReconcile.run(vad, diar, hz, scene, samples = pcm)
        assertTrue(out.none { it.speakerIndex > 5 })
        assertTrue(out.map { it.speakerIndex }.distinct().size <= 6)
        val smear = out.filter { it.startMs < 19_066 && it.endMs > 7_456 }
        assertTrue("s2+s3 must not stay one 7456–19066 island: $out", smear.none { it.startMs <= 7_456 && it.endMs >= 19_066 })
        val s2 = out.filter { overlaps(it, 7_456, 12_000) }
        val s3 = out.filter { overlaps(it, 12_500, 19_066) }
        assertTrue("s2 present $out", s2.isNotEmpty())
        assertTrue("s3 present $out", s3.isNotEmpty())
        assertNotEquals(
            "s2 @ 3.6 m must not share a cluster with s3",
            s2.first().speakerIndex,
            s3.first().speakerIndex,
        )
        val near = out.minBy { it.startMs }
        assertEquals(0, near.speakerIndex)
        assertTrue(s2.none { it.speakerIndex == near.speakerIndex })
        assertTrue(s3.none { it.speakerIndex == near.speakerIndex })
    }

    @Test
    fun hungarianMapsQuietFarToOwnSeatNotSpeakerTwoStub() {
        val pcm = stage6LikePcm()
        val vad = listOf(
            SpeechRegion(ms(0), ms(7_000)),
            SpeechRegion(ms(7_100), ms(7_400)),
            SpeechRegion(ms(7_456), ms(12_000)),
            SpeechRegion(ms(12_500), ms(19_066)),
            SpeechRegion(ms(20_000), ms(25_000)),
            SpeechRegion(ms(28_970), ms(33_650)),
            SpeechRegion(ms(34_000), ms(38_000)),
        )
        val diar = listOf(
            DiarSeg(0, 0, 7_000),
            DiarSeg(1, 7_456, 19_066),
            DiarSeg(2, 20_000, 25_000),
            DiarSeg(3, 34_000, 38_000),
        )
        val out = VadDiarReconcile.run(vad, diar, hz, scene, samples = pcm)
        assertTrue(out.map { it.speakerIndex }.distinct().size <= 6)
        val far = out.filter { overlaps(it, 28_970, 33_650) }
        assertTrue("recover s5-like quiet far: $out", far.isNotEmpty())
        val s2 = out.filter { overlaps(it, 7_456, 12_000) }
        assertTrue(s2.isNotEmpty())
        assertTrue(
            "quiet far must not share s2's cluster (Eval unmatched s5)",
            far.none { f -> s2.any { it.speakerIndex == f.speakerIndex } },
        )
        val near = out.minBy { it.startMs }
        assertTrue(far.none { it.speakerIndex == near.speakerIndex })
        val farDur = far.sumOf { it.endMs - it.startMs }
        assertTrue("s5 island should cover the quiet hole, not a да. stub: $far", farDur >= 1_200)
    }

    @Test
    fun presentConnectSceneLeavesDiarUntouched() {
        val vad = listOf(SpeechRegion(0, 16_000))
        val diar = listOf(DiarSeg(3, 100, 200))
        val out = VadDiarReconcile.run(vad, diar, hz, FarFieldScene.presentConnect)
        assertEquals(1, out.size)
        assertEquals(3, out[0].speakerIndex)
        assertEquals(100L, out[0].startMs)
    }

    @Test
    fun relabelByAppearanceIsSequential() {
        val segs = listOf(
            DiarSeg(4, 3_000, 4_000),
            DiarSeg(9, 0, 1_000),
            DiarSeg(4, 5_000, 6_000),
        )
        val out = VadDiarReconcile.relabelByAppearance(segs)
        assertEquals(listOf(1, 0, 1), out.map { it.speakerIndex })
    }

    private fun ms(t: Long): Int = (t * hz / 1_000L).toInt()

    private fun overlaps(s: DiarSeg, t0: Long, t1: Long): Boolean =
        s.startMs < t1 && t0 < s.endMs

    /**
     * Sequential STAGE6-like mix: loud 3 m, loud-ish 3.6 m + 4.2 m with a
     * dip, then quieter seats including 5.4 m. Not Eval wavs.
     */
    private fun stage6LikePcm(): FloatArray {
        val n = ms(40_000)
        val out = FloatArray(n)
        fun tone(t0: Long, t1: Long, amp: Double) {
            val a = ms(t0).coerceIn(0, n)
            val b = ms(t1).coerceIn(a, n)
            var i = a
            val twoPi = 2.0 * Math.PI
            while (i < b) {
                out[i] = (amp * sin(twoPi * 1000.0 * i / hz)).toFloat()
                i++
            }
        }
        tone(0, 7_000, 0.34)
        tone(7_100, 7_400, 0.012)
        tone(7_456, 12_000, 0.22)
        tone(12_500, 19_066, 0.16)
        tone(20_000, 25_000, 0.10)
        tone(28_970, 33_650, 0.035)
        tone(34_000, 38_000, 0.028)
        val hvac = FarFieldSynth.hvac(n)
        val mixed = FloatArray(n)
        var i = 0
        while (i < n) {
            mixed[i] = out[i] + hvac[i]
            i++
        }
        return mixed
    }
}
