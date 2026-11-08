package team.mikcoders.neurosteno.pipeline

import android.content.Context
import android.util.Log
import team.mikcoders.neurosteno.BuildConfig
import team.mikcoders.neurosteno.llm.LlmPostPassFactory

object PipelineFactory {
    private const val TAG = "PipelineFactory"

    fun create(context: Context): Pair<StenoPipeline, ModelPaths> {
        val jni = SherpaJni.probe()
        if (!jni.loaded) {
            Log.w(TAG, "sherpa JNI not loaded: ${jni.error}")
        }
        val paths = ModelStore(context).resolve()
        val llm = LlmPostPassFactory.create(paths.llmGguf)
        if (!paths.hasPrimaryAsr && !paths.hasBackupAsr) {
            Log.w(TAG, "No ASR models in ${ModelStore(context).modelsDir()} — stub pipeline")
            return StubStenoPipeline() to paths
        }
        if (!jni.loaded) {
            Log.e(TAG, "models present but JNI failed — stub. ${jni.error}")
            return StubStenoPipeline() to paths
        }
        // Both flavors run the casual near-field scene (auto speaker count,
        // high-recall VAD, identity merge 0.55): host sweep 2026-11-08 showed
        // the stage6 seat reconcile fragments casual 2-speaker recordings into
        // per-line churn labels, and at expectedSpeakers=-1 the reconcile
        // returns clustering as-is, so the live path gets the same tested
        // diarization lineage as the offline demo. Frozen DEMO_STAGE6 eval
        // rows come from the host harness and are unaffected by this choice.
        val scene = FarFieldScene.casual
        return try {
            SherpaStenoPipeline(paths, llm, scene) to paths
        } catch (t: Throwable) {
            Log.e(TAG, "sherpa init failed, stub", t)
            StubStenoPipeline() to paths
        }
    }
}
