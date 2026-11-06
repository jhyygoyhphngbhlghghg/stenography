package team.mikcoders.neurosteno

import android.app.Application
import android.util.Log
import team.mikcoders.neurosteno.pipeline.SherpaJni

class NeuroStenoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val jni = SherpaJni.probe()
        Log.i(TAG, "sherpa-onnx-jni loaded=${jni.loaded} error=${jni.error}")
    }

    companion object {
        private const val TAG = "NeuroStenoApp"
    }
}
