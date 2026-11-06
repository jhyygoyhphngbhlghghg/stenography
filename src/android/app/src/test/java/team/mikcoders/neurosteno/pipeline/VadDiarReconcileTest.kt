package team.mikcoders.neurosteno.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun shortDiarStubExpandsToVadTurn() {
        val vad = listOf(SpeechRegion(0, 16_000 * 6))
        val diar = listOf(DiarSeg(0, 2_000, 2_202))
        val out = VadDiarReconcile.run(vad, diar, hz, scene)
        assertEquals(1, out.size)
        assertEquals(0L, out[0].startMs)
        assertEquals(6_000L, out[0].endMs)
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
