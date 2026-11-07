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
        // PresentConnect stays on overnight clustering; the offline demo claims
        // DEMO_STAGE6 (one mic, 6 speakers, 3–6 m).
        val scene = if (BuildConfig.PRESENT_CONNECT) {
            FarFieldScene.presentConnect
        } else {
            FarFieldScene.stage6
        }
        return try {
            SherpaStenoPipeline(paths, llm, scene) to paths
        } catch (t: Throwable) {
            Log.e(TAG, "sherpa init failed, stub", t)
            StubStenoPipeline() to paths
        }
    }
}
