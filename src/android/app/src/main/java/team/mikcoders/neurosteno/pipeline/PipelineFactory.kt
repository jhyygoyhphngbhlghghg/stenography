package team.mikcoders.neurosteno.pipeline

import android.content.Context
import android.util.Log
import team.mikcoders.neurosteno.llm.IdentityLlmPostPass

object PipelineFactory {
    private const val TAG = "PipelineFactory"

    fun create(context: Context): Pair<StenoPipeline, ModelPaths> {
        val jni = SherpaJni.probe()
        if (!jni.loaded) {
            Log.w(TAG, "sherpa JNI not loaded: ${jni.error}")
        }
        val paths = ModelStore(context).resolve()
        if (!paths.hasPrimaryAsr && !paths.hasBackupAsr) {
            Log.w(TAG, "No ASR models in ${ModelStore(context).modelsDir()} — stub pipeline")
            return StubStenoPipeline() to paths
        }
        if (!jni.loaded) {
            Log.e(TAG, "models present but JNI failed — stub. ${jni.error}")
            return StubStenoPipeline() to paths
        }
        return try {
            SherpaStenoPipeline(paths, IdentityLlmPostPass()) to paths
        } catch (t: Throwable) {
            Log.e(TAG, "sherpa init failed, stub", t)
            StubStenoPipeline() to paths
        }
    }
}
