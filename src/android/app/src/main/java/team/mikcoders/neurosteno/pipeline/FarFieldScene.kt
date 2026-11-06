package team.mikcoders.neurosteno.pipeline

/**
 * DEMO_STAGE6 single-mic scene vs PresentConnect (Path B, not the live claim).
 *
 * FarFieldGain.stage and EQ are **frozen**. Live work: diar/VAD recover s5
 * with a **hard cap of 6 speakers** (Eval 9b29b9c1 over-split 12/6;
 * Eval a2219ef glued s2+s3 — do not merge distinct near-field turns).
 * Path B ingest is unchanged.
 */
data class FarFieldScene(
    val name: String,
    /** 6 for DEMO_STAGE6; -1 = overnight auto-cluster (PresentConnect). */
    val expectedSpeakers: Int,
    val vadThreshold: Float,
    val vadMinSilenceSec: Float,
    val vadMinSpeechSec: Float,
    val vadMaxSpeechSec: Float,
    val diarMinDurationOnSec: Float,
    val diarMinDurationOffSec: Float,
    val diarClusterThreshold: Float,
    val asrCropPadMs: Long,
    val orphanMinMs: Long,
    val expandIfCoverageBelow: Float,
    val expandMaxRegionMs: Long,
    val mergeGapMs: Long,
) {
    companion object {
        val stage6: FarFieldScene = FarFieldScene(
            name = "DEMO_STAGE6",
            expectedSpeakers = 6,
            // High-recall Silero after FarFieldGain; do not hard-drop 4–6 m.
            vadThreshold = 0.32f,
            vadMinSilenceSec = 0.18f,
            vadMinSpeechSec = 0.20f,
            vadMaxSpeechSec = 30f,
            // Keep short far fragments (s5 was 4.7 s at 5.4 m, missing @ a8b39d5a).
            // Clustering: K=6 (threshold unused when numClusters>0). Soft min-on
            // so embedding path does not drop a quiet 5.4 m island.
            diarMinDurationOnSec = 0.08f,
            diarMinDurationOffSec = 0.18f,
            diarClusterThreshold = 0.5f,
            asrCropPadMs = 200L,
            orphanMinMs = 400L,
            expandIfCoverageBelow = 0.5f,
            // Endpoint snap only. A 12 s expand would swallow the 3 m seat.
            expandMaxRegionMs = 1_500L,
            mergeGapMs = 280L,
        )

        /** Overnight diar/VAD; do not retune Path B. */
        val presentConnect: FarFieldScene = FarFieldScene(
            name = "presentConnect",
            expectedSpeakers = -1,
            vadThreshold = 0.5f,
            vadMinSilenceSec = 0.25f,
            vadMinSpeechSec = 0.25f,
            vadMaxSpeechSec = 20f,
            diarMinDurationOnSec = 0.2f,
            diarMinDurationOffSec = 0.5f,
            diarClusterThreshold = 0.5f,
            asrCropPadMs = 0L,
            orphanMinMs = Long.MAX_VALUE,
            expandIfCoverageBelow = 0f,
            expandMaxRegionMs = 0L,
            mergeGapMs = 0L,
        )
    }
}

data class SpeechRegion(
    val startSample: Int,
    val endSample: Int,
) {
    fun startMs(hz: Int): Long = if (hz <= 0) 0L else startSample * 1000L / hz
    fun endMs(hz: Int): Long = if (hz <= 0) 0L else endSample * 1000L / hz
    fun durationMs(hz: Int): Long = (endMs(hz) - startMs(hz)).coerceAtLeast(0L)
}

data class DiarSeg(
    val speakerIndex: Int,
    val startMs: Long,
    val endMs: Long,
) {
    val speakerId: String
        get() = "speaker_${speakerIndex + 1}"
}
