package team.mikcoders.neurosteno.pipeline

/**
 * DEMO_STAGE6 single-mic scene vs PresentConnect (Path B, not the live claim).
 *
 * Per-turn gain uses [team.mikcoders.neurosteno.audio.FarFieldGain.stageTurn]
 * (0.08 FS / +24 dB / peak 0.95) on VAD chunks — **not** whole-file AGC
 * (Eval e0e64b85: 0.73→0.70, s5 still missing). Path B ingest is unchanged.
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
            diarMinDurationOnSec = 0.12f,
            diarMinDurationOffSec = 0.25f,
            diarClusterThreshold = 0.5f,
            asrCropPadMs = 200L,
            orphanMinMs = 400L,
            expandIfCoverageBelow = 0.5f,
            expandMaxRegionMs = 12_000L,
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
