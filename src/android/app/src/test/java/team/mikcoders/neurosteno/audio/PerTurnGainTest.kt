package team.mikcoders.neurosteno.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.mikcoders.neurosteno.pipeline.FarFieldScene

class FarFieldVadTest {
    @Test
    fun sixMeterSineIsNotDropped() {
        val at6 = FarFieldSynth.atDistance(FarFieldSynth.closeTalkSine(), 6.0)
        val regions = FarFieldVad.speechRegions(at6)
        assertTrue(regions.isNotEmpty())
        val covered = regions.sumOf { it.endSample - it.startSample }
        assertTrue(covered > at6.size / 2)
    }

    @Test
    fun silenceIsEmpty() {
        assertTrue(FarFieldVad.speechRegions(FloatArray(FarFieldSynth.N_400MS)).isEmpty())
    }
}

class FarFieldSceneTest {
    @Test
    fun stage6AsksForSixClustersPresentConnectDoesNot() {
        assertEquals(6, FarFieldScene.stage6.expectedSpeakers)
        assertEquals(-1, FarFieldScene.presentConnect.expectedSpeakers)
    }

    @Test
    fun stage6ClustersAutomaticallyButKeepsSixSpeakerCap() {
        // Live-mic churn hotfix: the diarizer must not force 6 clusters on a
        // 2-person casual recording; expectedSpeakers stays the reconcile cap.
        assertEquals(0, FarFieldScene.stage6.diarNumClusters)
        assertEquals(6, FarFieldScene.stage6.expectedSpeakers)
        // PresentConnect keeps the historical default (auto, == expectedSpeakers).
        assertEquals(-1, FarFieldScene.presentConnect.diarNumClusters)
    }
}
