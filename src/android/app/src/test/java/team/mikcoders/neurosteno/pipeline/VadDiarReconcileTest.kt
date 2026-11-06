package team.mikcoders.neurosteno.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.mikcoders.neurosteno.audio.FarFieldSynth

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
        assertEquals(6, out.size)
        assertEquals(6, out.map { it.speakerIndex }.distinct().size)
        val fifth = out.single { it.startMs == 4_000L }
        assertTrue(fifth.endMs > fifth.startMs)
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
        assertTrue(out.size >= 3)
        assertTrue(out.none { it.startMs == 0L && it.endMs == 6_000L })
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
}
