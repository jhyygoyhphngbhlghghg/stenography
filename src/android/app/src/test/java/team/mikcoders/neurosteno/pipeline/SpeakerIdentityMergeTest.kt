package team.mikcoders.neurosteno.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerIdentityMergeTest {
    private fun unit(v: FloatArray): FloatArray {
        var acc = 0.0
        for (x in v) acc += x.toDouble() * x.toDouble()
        val n = kotlin.math.sqrt(acc).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    private val voiceA = unit(floatArrayOf(1f, 0.2f, 0.1f, 0.05f))
    private val voiceB = unit(floatArrayOf(0.1f, 1f, 0.2f, 0.4f))
    private val voiceA2 = unit(floatArrayOf(0.9f, 0.25f, 0.15f, 0.08f))
    private val voiceB2 = unit(floatArrayOf(0.15f, 0.9f, 0.25f, 0.35f))

    @Test
    fun sequentialReplicasOfSamePersonShareOneLabel() {
        // Exactly the user report: one person's sequential replicas currently
        // end up labelled as fresh speakers by the time/energy reconcile.
        val segs = listOf(
            DiarSeg(0, 0, 2_000),
            DiarSeg(1, 3_000, 5_000),
            DiarSeg(2, 6_000, 8_000),
            DiarSeg(3, 9_000, 11_000),
        )
        val embeddings = listOf(voiceA, voiceA2, voiceA, voiceA2)
        val out = SpeakerIdentityMerge.merge(segs, embeddings)
        assertEquals(1, out.map { it.speakerIndex }.distinct().size)
        assertTrue(out.all { it.speakerId == "speaker_1" })
    }

    @Test
    fun alternatingTwoPeopleKeepTwoLabels() {
        val segs = listOf(
            DiarSeg(0, 0, 2_000),
            DiarSeg(1, 3_000, 5_000),
            DiarSeg(2, 6_000, 8_000),
            DiarSeg(3, 9_000, 11_000),
        )
        val embeddings = listOf(voiceA, voiceB, voiceA2, voiceB2)
        val out = SpeakerIdentityMerge.merge(segs, embeddings)
        assertEquals(2, out.map { it.speakerIndex }.distinct().size)
        assertEquals(out[0].speakerIndex, out[2].speakerIndex)
        assertEquals(out[1].speakerIndex, out[3].speakerIndex)
    }

    @Test
    fun distinctSpeakersAreNeverGlueMerged() {
        val segs = listOf(DiarSeg(0, 0, 2_000), DiarSeg(1, 3_000, 5_000))
        val out = SpeakerIdentityMerge.merge(segs, listOf(voiceA, voiceB))
        assertEquals(2, out.map { it.speakerIndex }.distinct().size)
    }

    @Test
    fun shortStubsWithoutEmbeddingsAttachToNeighbors() {
        val segs = listOf(
            DiarSeg(0, 0, 2_000),
            DiarSeg(1, 2_100, 2_400),
            DiarSeg(2, 3_000, 5_000),
        )
        val out = SpeakerIdentityMerge.merge(segs, listOf(voiceA, null, voiceB))
        assertEquals(2, out.map { it.speakerIndex }.distinct().size)
        // stub attaches to whoever is temporally closest (A here)
        assertEquals(0, out[1].speakerIndex)
    }

    @Test
    fun allMissingEmbeddingsKeepsExistingLabels() {
        val segs = listOf(DiarSeg(0, 0, 2_000), DiarSeg(1, 3_000, 5_000))
        val out = SpeakerIdentityMerge.merge(segs, listOf(null, null))
        assertEquals(2, out.map { it.speakerIndex }.distinct().size)
    }

    @Test
    fun distanceIsCosineAndSymmetric() {
        assertEquals(0f, SpeakerIdentityMerge.distance(voiceA, voiceA), 1e-6f)
        assertEquals(
            SpeakerIdentityMerge.distance(voiceA, voiceB),
            SpeakerIdentityMerge.distance(voiceB, voiceA),
            1e-6f,
        )
        assertTrue(SpeakerIdentityMerge.distance(voiceA, voiceB) > 0.5f)
    }

    @Test
    fun embeddingWindowsCoverLongSegments() {
        val wins = SpeakerIdentityMerge.embeddingWindows(0L, 10_000L)
        assertEquals(3, wins.size)
        assertEquals(0L, wins[0].first)
        assertTrue(wins[0].last - wins[0].first == 1_500L)
        assertTrue(wins[2].last == 10_000L)
    }

    @Test
    fun embeddingWindowsEmptyForShortSegments() {
        assertTrue(SpeakerIdentityMerge.embeddingWindows(0L, 200L).isEmpty())
        assertEquals(listOf(0L..1_000L), SpeakerIdentityMerge.embeddingWindows(0L, 1_000L))
    }

    @Test
    fun averageEmbeddingsNormalizes() {
        val avg = SpeakerIdentityMerge.averageEmbeddings(listOf(voiceA, voiceA2))
        assertEquals(voiceA.size, avg!!.size)
        var acc = 0.0
        for (x in avg) acc += x.toDouble() * x.toDouble()
        assertEquals(1.0, kotlin.math.sqrt(acc), 1e-6)
    }
}
