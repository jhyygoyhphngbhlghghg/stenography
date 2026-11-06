package team.mikcoders.neurosteno.pipeline

import android.content.Context
import android.util.Log
import team.mikcoders.neurosteno.BuildConfig
import team.mikcoders.neurosteno.llm.LlmPostPassFactory

object PipelineFactory {
    private const val TAG = "PipelineFactory"

    fun create(context: Context): Pair<StenoPipeline, ModelPaths> {
        val paths = ModelStore(context).resolve()
        val llm = LlmPostPassFactory.create(paths.llmGguf)
        if (!paths.hasPrimaryAsr && !paths.hasBackupAsr) {
            Log.w(TAG, "No ASR models in ${ModelStore(context).modelsDir()} — stub pipeline")
            return StubStenoPipeline() to paths
        }
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
