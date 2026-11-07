package team.mikcoders.neurosteno.pipeline

import android.util.Log

/**
 * Best-effort native load for sherpa-onnx Android AAR (libsherpa-onnx-jni.so).
 * Called at process start so UnsatisfiedLinkError is visible without a transcript run.
 */
object SherpaJni {
    const val LIBRARY = "sherpa-onnx-jni"
    private const val TAG = "SherpaJni"

    data class Probe(val loaded: Boolean, val error: String?)

    @Volatile
    var last: Probe = Probe(loaded = false, error = "not probed")
        private set

    @Synchronized
    fun probe(): Probe {
        if (last.loaded) return last
        last = try {
            System.loadLibrary(LIBRARY)
            Log.i(TAG, "loaded lib$LIBRARY.so")
            Probe(loaded = true, error = null)
        } catch (t: UnsatisfiedLinkError) {
            Log.e(TAG, "UnsatisfiedLinkError loading $LIBRARY", t)
            Probe(loaded = false, error = t.message ?: t.javaClass.simpleName)
        } catch (t: Throwable) {
            Log.e(TAG, "native load failed", t)
            Probe(loaded = false, error = t.message ?: t.javaClass.simpleName)
        }
        return last
    }
}
