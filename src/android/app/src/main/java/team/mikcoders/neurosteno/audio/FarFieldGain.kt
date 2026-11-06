package team.mikcoders.neurosteno.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Single-mic DEMO_STAGE6 far-field staging: boost 3–6 m speech onto a usable
 * ASR RMS without hard-clipping. Clip guard caps peak at [PEAK_LIMIT].
 *
 * Digital silence (RMS < [MIN_RMS_FOR_GAIN]) is not boosted into hiss.
 * Thresholds from `python3 scripts/measure-farfield-gain.py --record`.
 */
object FarFieldGain {
    const val TARGET_RMS_FS = 0.08
    const val MAX_GAIN_DB = 24.0
    const val PEAK_LIMIT = 0.95
    const val MIN_RMS_FOR_GAIN = 1e-5
    const val FRAME_MS = 20
    const val VAD_RMS_FS = 0.008
    const val SAMPLE_RATE_HZ = Pcm.TARGET_HZ

    val maxGainLinear: Double
        get() = dbToLinear(MAX_GAIN_DB)

    fun stage(samples: FloatArray): FarFieldGainResult {
        val n = samples.size
        if (n == 0) {
            return FarFieldGainResult(
                samples = samples,
                gainLinear = 1.0,
                gainDb = 0.0,
                rmsBefore = 0.0,
                rmsAfter = 0.0,
                peakBefore = 0.0,
                peakAfter = 0.0,
                clipGuard = false,
                boosted = false,
                vadFireRateBefore = 0.0,
                vadFireRateAfter = 0.0,
            )
        }
        val rmsBefore = rms(samples)
        val peakBefore = peak(samples)
        val vadBefore = energyVadFireRate(samples)
        if (rmsBefore < MIN_RMS_FOR_GAIN) {
            return FarFieldGainResult(
                samples = samples.copyOf(),
                gainLinear = 1.0,
                gainDb = 0.0,
                rmsBefore = rmsBefore,
                rmsAfter = rmsBefore,
                peakBefore = peakBefore,
                peakAfter = peakBefore,
                clipGuard = false,
                boosted = false,
                vadFireRateBefore = vadBefore,
                vadFireRateAfter = vadBefore,
            )
        }
        val desired = TARGET_RMS_FS / rmsBefore
        val peakCap = PEAK_LIMIT / peakBefore.coerceAtLeast(1e-12)
        val gain = min(desired, min(peakCap, maxGainLinear))
        val clipGuard = gain + 1e-12 < desired
        val out = FloatArray(n)
        var i = 0
        var peakAfter = 0.0
        var sumSq = 0.0
        val g = gain.toFloat()
        val lim = PEAK_LIMIT.toFloat()
        while (i < n) {
            val y = (samples[i] * g).coerceIn(-lim, lim)
            out[i] = y
            val a = abs(y.toDouble())
            if (a > peakAfter) peakAfter = a
            sumSq += y.toDouble() * y
            i++
        }
        val rmsAfter = sqrt(sumSq / n)
        return FarFieldGainResult(
            samples = out,
            gainLinear = gain,
            gainDb = linearToDb(gain),
            rmsBefore = rmsBefore,
            rmsAfter = rmsAfter,
            peakBefore = peakBefore,
            peakAfter = peakAfter,
            clipGuard = clipGuard,
            boosted = gain > 1.0 + 1e-9,
            vadFireRateBefore = vadBefore,
            vadFireRateAfter = energyVadFireRate(out),
        )
    }

    fun rms(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var acc = 0.0
        for (s in samples) acc += s.toDouble() * s
        return sqrt(acc / samples.size)
    }

    fun peak(samples: FloatArray): Double {
        var p = 0.0
        for (s in samples) {
            val a = abs(s.toDouble())
            if (a > p) p = a
        }
        return p
    }

    /** Host energy-VAD proxy (not Silero JNI): 20 ms frames, fire if RMS ≥ [VAD_RMS_FS]. */
    fun energyVadFireRate(
        samples: FloatArray,
        sampleRateHz: Int = SAMPLE_RATE_HZ,
        frameMs: Int = FRAME_MS,
        thresholdFs: Double = VAD_RMS_FS,
    ): Double {
        val frame = ((sampleRateHz * frameMs) / 1000).coerceAtLeast(1)
        if (samples.size < frame) {
            return if (rms(samples) >= thresholdFs) 1.0 else 0.0
        }
        var fires = 0
        var frames = 0
        var off = 0
        while (off + frame <= samples.size) {
            var acc = 0.0
            var i = 0
            while (i < frame) {
                val s = samples[off + i]
                acc += s.toDouble() * s
                i++
            }
            if (sqrt(acc / frame) >= thresholdFs) fires++
            frames++
            off += frame
        }
        return if (frames == 0) 0.0 else fires.toDouble() / frames
    }

    fun dbToLinear(db: Double): Double = Math.pow(10.0, db / 20.0)

    fun linearToDb(g: Double): Double = if (g <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * ln(g) / ln(10.0)
}

data class FarFieldGainResult(
    val samples: FloatArray,
    val gainLinear: Double,
    val gainDb: Double,
    val rmsBefore: Double,
    val rmsAfter: Double,
    val peakBefore: Double,
    val peakAfter: Double,
    val clipGuard: Boolean,
    val boosted: Boolean,
    val vadFireRateBefore: Double,
    val vadFireRateAfter: Double,
)

/** Synthetic far-field phone-mic scenes for host tests. Not Eval DEMO_STAGE6 wavs. */
object FarFieldSynth {
    const val REF_DISTANCE_M = 0.5
    const val HVAC_RMS_FS = 0.004
    const val CLOSE_SINE_AMP = 0.1
    const val PHONE_LP_HZ = 2400.0
    const val PHONE_LP_STAGES = 4
    const val COMPRESS_DRIVE = 2.0
    const val N_400MS = 6_400

    fun closeTalkSine(
        n: Int = N_400MS,
        amplitude: Double = CLOSE_SINE_AMP,
        hz: Double = 1_000.0,
        sampleRateHz: Int = Pcm.TARGET_HZ,
    ): FloatArray {
        val out = FloatArray(n)
        val twoPi = 2.0 * Math.PI
        var i = 0
        while (i < n) {
            out[i] = (amplitude * sin(twoPi * hz * i / sampleRateHz)).toFloat()
            i++
        }
        return out
    }

    fun atDistance(samples: FloatArray, distanceM: Double, refM: Double = REF_DISTANCE_M): FloatArray {
        val g = (refM / distanceM).toFloat()
        val out = FloatArray(samples.size)
        var i = 0
        while (i < samples.size) {
            out[i] = samples[i] * g
            i++
        }
        return out
    }

    fun hvac(n: Int = N_400MS, targetRms: Double = HVAC_RMS_FS, seed: Int = 1): FloatArray {
        var state = seed.toLong() and 0xFFFFFFFFL
        val raw = FloatArray(n)
        var i = 0
        var sumSq = 0.0
        while (i < n) {
            state = state xor ((state shl 13) and 0xFFFFFFFFL)
            state = state xor ((state shr 17) and 0xFFFFFFFFL)
            state = state xor ((state shl 5) and 0xFFFFFFFFL)
            state = state and 0xFFFFFFFFL
            val v = ((state % 7L).toInt() - 3).toFloat() / 32768f
            raw[i] = v
            sumSq += v.toDouble() * v
            i++
        }
        val rms = sqrt(sumSq / n).coerceAtLeast(1e-12)
        val g = (targetRms / rms).toFloat()
        i = 0
        while (i < n) {
            raw[i] *= g
            i++
        }
        return raw
    }

    fun phoneMic(samples: FloatArray, sampleRateHz: Int = Pcm.TARGET_HZ): FloatArray {
        val xs = DoubleArray(samples.size) { samples[it].toDouble() }
        val a = 1.0 - exp(-2.0 * Math.PI * PHONE_LP_HZ / sampleRateHz)
        var stage = 0
        while (stage < PHONE_LP_STAGES) {
            var y = 0.0
            var i = 0
            while (i < xs.size) {
                y += a * (xs[i] - y)
                xs[i] = y
                i++
            }
            stage++
        }
        val drive = COMPRESS_DRIVE
        val den = tanh(drive)
        val out = FloatArray(xs.size)
        var i = 0
        while (i < xs.size) {
            out[i] = (tanh(drive * xs[i]) / den).toFloat()
            i++
        }
        return out
    }

    fun mix(a: FloatArray, b: FloatArray): FloatArray {
        val n = min(a.size, b.size)
        val out = FloatArray(n)
        var i = 0
        while (i < n) {
            out[i] = a[i] + b[i]
            i++
        }
        return out
    }

    /** Close-talk sine, 1/r distance, phone LPF+compress, plus HVAC. */
    fun scene(distanceM: Double, n: Int = N_400MS): FloatArray {
        val far = atDistance(closeTalkSine(n), distanceM)
        return mix(phoneMic(far), hvac(n))
    }
}
