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
    /**
     * FastClustering numClusters for OfflineSpeakerDiarization. <=0 selects the
     * threshold path (auto speaker count at [diarClusterThreshold]).
     * [expectedSpeakers] stays the reconcile HARD CAP (never speaker_7..12);
     * this field only decides how many clusters the embedding clustering
     * forces. Default: keep the historical behaviour (K = expectedSpeakers).
     */
    val diarNumClusters: Int = expectedSpeakers,
    /**
     * NoiseGate (casual/presentConnect only, expectedSpeakers <= 0): drop raw
     * diarization segments shorter than this. <=0 disables. Stage6 keeps 0:
     * its quiet far seats are exactly the short/low-RMS segments the reconcile
     * recovers (s5 @ 5.4 m), and the stage6 path must never gate.
     */
    val noiseGateMinMs: Long = 0L,
    /**
     * NoiseGate RMS floor as a fraction of the median segment RMS. <=0
     * disables. See [noiseGateMinMs].
     */
    val noiseGateRmsRatio: Float = 0f,
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
            // Clustering: AUTO (numClusters=0 -> threshold 0.5). Host sweep
            // (scripts/mic_diar_sweep.py, 2026-11-08): on the casual 2-person
            // mic fixture forced K=6 and auto both yielded raw labels=2, and on
            // frozen DEMO_STAGE6 both found 5 — auto matches K=6 on the frozen
            // fixture while never forcing split/empty clusters when a live
            // recording has fewer speakers than K. expectedSpeakers stays 6 as
            // the reconcile HARD CAP. Soft min-on so embedding path does not
            // drop a quiet 5.4 m island.
            diarMinDurationOnSec = 0.08f,
            diarMinDurationOffSec = 0.18f,
            diarClusterThreshold = 0.5f,
            asrCropPadMs = 200L,
            orphanMinMs = 400L,
            expandIfCoverageBelow = 0.5f,
            // Endpoint snap only. A 12 s expand would swallow the 3 m seat.
            expandMaxRegionMs = 1_500L,
            mergeGapMs = 280L,
            diarNumClusters = 0,
        )

        /**
         * Casual near-field recording (phone on a table, 1-4 people, live mic).
         * Host sweep 2026-11-08 (scripts/mic_diar_sweep.py): raw clustering on a
         * casual 2-speaker fixture already finds 2 labels; the stage6 seat/adopt
         * reconcile (expectedSpeakers=6) is what fragments them into 6-8 churn
         * labels. So casual skips FarFieldGain staging and the seat machinery
         * (expectedSpeakers=-1 => reconcile returns diar as-is, hard cap off)
         * and keeps stage6's high-recall VAD + auto clustering + identity merge.
         * Frozen DEMO_STAGE6 eval rows come from the host harness (hardcoded
         * STAGE6 params), so this app-side scene does not touch them.
         */
        val casual: FarFieldScene = FarFieldScene(
            name = "casual-mic",
            expectedSpeakers = -1,
            vadThreshold = 0.32f,
            vadMinSilenceSec = 0.18f,
            vadMinSpeechSec = 0.20f,
            vadMaxSpeechSec = 30f,
            diarMinDurationOnSec = 0.08f,
            diarMinDurationOffSec = 0.18f,
            // 0.65 (was 0.5): device verdict 2026-11-08 on a 3-person casual
            // recording = 7 speakers (each person split into 2-3 embedding
            // clusters; expectedSpeakers=-1 returns the clustering as-is, so
            // the raw threshold IS the speaker count). Host sweep v2
            // (scripts/mic_diar_sweep.py, 3spk/2spk casual fixtures + frozen
            // DEMO_STAGE6): all ct in {0.5..0.7} leave both casual fixtures
            // unchanged (no host-side regression), diff-voice segment distance
            // min is 0.727 (2spk) / 0.844 (3spk) and distinct-seat turn min
            // 0.718 (stage6) — 0.65 keeps >=0.068 margin to every measured
            // distinct-voice distance while gluing the 0.5-0.65 per-person
            // cluster fragments; 0.7 would leave only 0.027 to 0.727.
            diarClusterThreshold = 0.65f,
            asrCropPadMs = 200L,
            orphanMinMs = 400L,
            expandIfCoverageBelow = 0.5f,
            expandMaxRegionMs = 1_500L,
            mergeGapMs = 280L,
            diarNumClusters = 0,
            // Noise gate (host sweep 2026-11-08, scripts/noise_gate_sweep.py
            // on the deterministic noise fixtures of
            // scripts/make_noise_fixtures.py — casual 3spk + 0.3-1.5 s
            // modulated pink/white bursts at -20/-15/-10 dBFS, +/-35 dBFS
            // continuous room noise): with room noise the raw clustering at
            // ct=0.65 yields 4 labels and merge@0.60 yields 6 labels vs
            // truth 3, one of them a 455 ms fragment with 0.38 speech
            // overlap (the phantom-speaker mechanism). min_ms=900 drops it
            // (and the 489 ms fragment churn) -> 4 labels, agree 0.83->1.00,
            // purity 0.94->1.00, while clean guards IMPROVE: clean_2spk
            // 5->2 labels (=truth, purity 0.91->1.00), clean_3spk agree
            // 0.57->1.00, purity 0.94->1.00. 500/700 leave clean_2spk at 3
            // labels with purity 0.90 < 0.91 pre-gate. rms_ratio never fired
            // on these fixtures (burst RMS is median-comparable to speech);
            // 0.25 stays as the quiet-continuous-noise floor. Worst-case
            // simulation shows the same gate on the frozen stage6 rig would
            // cost seats (5/6 -> 1/6) — hence casual-only by construction
            // (expectedSpeakers <= 0), guarded by NoiseGateTest.
            noiseGateMinMs = 900L,
            noiseGateRmsRatio = 0.25f,
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
            // Same noise gate as casual (expectedSpeakers=-1 path); defaults
            // come from scripts/noise_gate_sweep.py.
            noiseGateMinMs = 900L,
            noiseGateRmsRatio = 0.25f,
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
