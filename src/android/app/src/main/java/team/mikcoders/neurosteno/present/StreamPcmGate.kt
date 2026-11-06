package team.mikcoders.neurosteno.present

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Path B pre-ASR gate: energy + mic-quality on **one stream's** pcm_s16le only.
 *
 * Silero VAD in [team.mikcoders.neurosteno.pipeline.SherpaStenoPipeline] needs JNI +
 * `silero_vad.onnx` and runs *after* we already decided to ASR. This gate is
 * pure Kotlin so JVM tests and stub workers can reject noise/bad-mic **before**
 * ASR (cuts noise-only false alarms that inflate DER).
 *
 * Thresholds from `python3 scripts/measure-stream-pcm-gate.py` (16 kHz, FS=32768):
 * silence RMS 0; tiny_noise RMS 6.11e-5 (−84.3 dBFS); low-peak tone 0.00283
 * (−51.0 dBFS); clean 1 kHz −20 dBpeak RMS 0.0707 (−23.0 dBFS); clipped square
 * clip_ratio 1.0; stuck/extreme DC variance 0 with |dc| 0.24 / 0.61 FS.
 */
object StreamPcmGate {
    const val SAMPLE_RATE_HZ = 16_000
    /** Full-scale divisor for s16 → FS fraction. */
    const val FULL_SCALE = 32768.0

    /** |s16| ≥ this counts as clipped (7 counts from 32767). */
    const val CLIP_S16 = 32_760

    /**
     * Reject RMS below this (FS fraction). 0.008 ≈ −41.94 dBFS.
     * Measured gap: tiny_noise −84 dBFS / low-peak −51 dBFS vs clean −23 dBFS.
     */
    const val RMS_REJECT_FS = 0.008

    /** Soft-low band (still ASR): 0.008 .. 0.020 FS (−42 .. −34 dBFS). */
    const val RMS_SOFT_FS = 0.020

    /** Reject if peak < this (FS). 0.012 ≈ −38.4 dBFS. Clean peak 0.10. */
    const val PEAK_REJECT_FS = 0.012

    /** Hard clip: ≥2% of samples at full scale (square measures 1.0). */
    const val CLIP_REJECT = 0.02

    /** Soft clip (still ASR). */
    const val CLIP_SOFT = 0.002

    /** Extreme DC (FS). Stuck 8000/32768 = 0.244; extreme 20000/32768 = 0.610. */
    const val DC_REJECT_FS = 0.20

    const val DC_SOFT_FS = 0.05

    /** Near-zero AC energy (stuck mic). Tiny noise variance is 3.7e-9 but peak ≪ PEAK_REJECT. */
    const val STUCK_VARIANCE_FS = 1e-7

    /** 50 ms @ 16 kHz. Shorter buffers are not speech. */
    const val MIN_SAMPLES = 800

    const val SNR_EPS = 1e-12

    fun inspect(pcmS16le: ByteArray, sampleRateHz: Int = SAMPLE_RATE_HZ): StreamPcmStats {
        val n = pcmS16le.size / 2
        if (n <= 0) {
            return StreamPcmStats(
                n = 0,
                durationMs = 0.0,
                rmsFs = 0.0,
                rmsDbfs = Double.NEGATIVE_INFINITY,
                peakFs = 0.0,
                dcFs = 0.0,
                varianceFs = 0.0,
                acRmsFs = 0.0,
                clipRatio = 0.0,
                snrProxy = 0.0,
                verdict = StreamGateVerdict.Reject(PresentConnectCodes.LOW_ENERGY, "empty pcm"),
                softFlags = emptyList(),
            )
        }
        var sum = 0.0
        var sumSq = 0.0
        var peakAbs = 0
        var clip = 0
        var i = 0
        var o = 0
        while (o < n) {
            val lo = pcmS16le[i].toInt() and 0xff
            val hi = pcmS16le[i + 1].toInt()
            val s = ((hi shl 8) or lo).toShort().toInt()
            val a = abs(s)
            if (a > peakAbs) peakAbs = a
            if (a >= CLIP_S16) clip++
            sum += s
            sumSq += s.toDouble() * s
            i += 2
            o++
        }
        val rmsFs = sqrt(sumSq / n) / FULL_SCALE
        val dcFs = (sum / n) / FULL_SCALE
        val peakFs = peakAbs / FULL_SCALE
        val mean = sum / n
        var varAcc = 0.0
        i = 0
        o = 0
        while (o < n) {
            val lo = pcmS16le[i].toInt() and 0xff
            val hi = pcmS16le[i + 1].toInt()
            val s = ((hi shl 8) or lo).toShort().toInt()
            val d = s - mean
            varAcc += d * d
            i += 2
            o++
        }
        val varianceFs = (varAcc / n) / (FULL_SCALE * FULL_SCALE)
        val acRmsFs = sqrt(varianceFs.coerceAtLeast(0.0))
        val clipRatio = clip.toDouble() / n
        val rmsDbfs = if (rmsFs > 0.0) 20.0 * log10(rmsFs) else Double.NEGATIVE_INFINITY
        val snrProxy = acRmsFs / (abs(dcFs) + SNR_EPS)
        val durationMs = 1000.0 * n / sampleRateHz
        val (verdict, soft) = decide(
            n = n,
            rmsFs = rmsFs,
            peakFs = peakFs,
            dcFs = dcFs,
            varianceFs = varianceFs,
            clipRatio = clipRatio,
        )
        return StreamPcmStats(
            n = n,
            durationMs = durationMs,
            rmsFs = rmsFs,
            rmsDbfs = rmsDbfs,
            peakFs = peakFs,
            dcFs = dcFs,
            varianceFs = varianceFs,
            acRmsFs = acRmsFs,
            clipRatio = clipRatio,
            snrProxy = snrProxy,
            verdict = verdict,
            softFlags = soft,
        )
    }

    private fun decide(
        n: Int,
        rmsFs: Double,
        peakFs: Double,
        dcFs: Double,
        varianceFs: Double,
        clipRatio: Double,
    ): Pair<StreamGateVerdict, List<String>> {
        if (n < MIN_SAMPLES) {
            return StreamGateVerdict.Reject(
                PresentConnectCodes.LOW_ENERGY,
                "buffer < ${MIN_SAMPLES} samples (${n})",
            ) to emptyList()
        }
        if (clipRatio >= CLIP_REJECT) {
            return StreamGateVerdict.Reject(
                PresentConnectCodes.CLIPPED,
                "clip_ratio=${"%.3f".format(Locale.US, clipRatio)} ≥ $CLIP_REJECT",
            ) to emptyList()
        }
        val stuck = varianceFs < STUCK_VARIANCE_FS && peakFs >= PEAK_REJECT_FS
        if (abs(dcFs) >= DC_REJECT_FS || stuck) {
            return StreamGateVerdict.Reject(
                PresentConnectCodes.BAD_MIC,
                "dc_fs=${"%.3f".format(Locale.US, dcFs)} variance_fs=${"%.3g".format(Locale.US, varianceFs)}",
            ) to emptyList()
        }
        if (rmsFs < RMS_REJECT_FS || peakFs < PEAK_REJECT_FS) {
            return StreamGateVerdict.Reject(
                PresentConnectCodes.LOW_ENERGY,
                "rms_fs=${"%.5f".format(Locale.US, rmsFs)} peak_fs=${"%.5f".format(Locale.US, peakFs)}",
            ) to emptyList()
        }
        val soft = ArrayList<String>(3)
        if (clipRatio >= CLIP_SOFT) soft += "SOFT_CLIP"
        if (rmsFs < RMS_SOFT_FS) soft += "SOFT_LOW"
        if (abs(dcFs) >= DC_SOFT_FS) soft += "SOFT_DC"
        return StreamGateVerdict.Pass to soft
    }
}

data class StreamPcmStats(
    val n: Int,
    val durationMs: Double,
    val rmsFs: Double,
    val rmsDbfs: Double,
    val peakFs: Double,
    val dcFs: Double,
    val varianceFs: Double,
    val acRmsFs: Double,
    val clipRatio: Double,
    /** AC-RMS / (|DC| + eps); ~0 on stuck DC, large on zero-mean speech/tone. */
    val snrProxy: Double,
    val verdict: StreamGateVerdict,
    val softFlags: List<String>,
) {
    val rejectCode: String?
        get() = (verdict as? StreamGateVerdict.Reject)?.code
    val asrAllowed: Boolean
        get() = verdict is StreamGateVerdict.Pass
}

sealed class StreamGateVerdict {
    data object Pass : StreamGateVerdict()
    data class Reject(val code: String, val message: String) : StreamGateVerdict()
}

data class StreamMicFlag(
    val streamId: String,
    val speakerId: String,
    val code: String,
    val hardReject: Boolean,
    val flags: List<String> = emptyList(),
)

/** Synthetic pcm_s16le used by JVM tests; same generators as scripts/measure-stream-pcm-gate.py. */
object StreamPcmSynth {
    fun silence(n: Int = 6_400): ByteArray = shortsToS16le(ShortArray(n))

    fun tinyNoise(n: Int = 6_400, seed: Int = 1): ByteArray {
        var state = seed.toLong() and 0xFFFFFFFFL
        val s = ShortArray(n)
        var i = 0
        while (i < n) {
            state = state xor ((state shl 13) and 0xFFFFFFFFL)
            state = state xor ((state shr 17) and 0xFFFFFFFFL)
            state = state xor ((state shl 5) and 0xFFFFFFFFL)
            state = state and 0xFFFFFFFFL
            s[i] = ((state % 7L).toInt() - 3).toShort()
            i++
        }
        return shortsToS16le(s)
    }

    fun clippedSquare(n: Int = 6_400): ByteArray {
        val s = ShortArray(n)
        var i = 0
        while (i < n) {
            s[i] = if (i and 1 == 0) 32767 else -32767
            i++
        }
        return shortsToS16le(s)
    }

    fun sine(
        amplitude: Double = 0.1,
        hz: Double = 1_000.0,
        n: Int = 6_400,
        sampleRateHz: Int = StreamPcmGate.SAMPLE_RATE_HZ,
    ): ByteArray {
        val s = ShortArray(n)
        val twoPi = 2.0 * Math.PI
        var i = 0
        while (i < n) {
            val x = amplitude * sin(twoPi * hz * i / sampleRateHz)
            val v = (x * 32767.0).roundToInt().coerceIn(-32767, 32767)
            s[i] = v.toShort()
            i++
        }
        return shortsToS16le(s)
    }

    fun constant(level: Int, n: Int = 6_400): ByteArray {
        val s = ShortArray(n) { level.coerceIn(-32767, 32767).toShort() }
        return shortsToS16le(s)
    }

    fun shortsToS16le(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var i = 0
        var o = 0
        while (i < samples.size) {
            val v = samples[i].toInt()
            out[o] = (v and 0xff).toByte()
            out[o + 1] = ((v shr 8) and 0xff).toByte()
            i++
            o += 2
        }
        return out
    }
}
