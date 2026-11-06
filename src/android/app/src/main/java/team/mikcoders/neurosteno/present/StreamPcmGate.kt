package team.mikcoders.neurosteno.present

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Path B pre-ASR gate: energy + mic-quality + spectral crush on **one stream's** pcm_s16le.
 *
 * Bound known seats (`s01`..`s20` after `bindStart`): near / below the RMS floor is
 * **SOFT_LOW** — still ASR. Hard `LOW_ENERGY` only for unbound/anonymous streams or
 * a true empty buffer (`n == 0`).
 *
 * Phone-mic crush often has no hard clip / DC: Goertzel HF/speech occupancy →
 * `BAND_LIMITED` (skip ASR). Thresholds: `python3 scripts/measure-stream-pcm-gate.py`.
 */
object StreamPcmGate {
    const val SAMPLE_RATE_HZ = 16_000
    const val FULL_SCALE = 32768.0
    const val CLIP_S16 = 32_760

    /** Unbound-only hard RMS floor. 0.008 ≈ −41.94 dBFS. */
    const val RMS_REJECT_FS = 0.008

    /** Soft-low (still ASR): below 0.020 FS (−34 dBFS), including bound seats under RMS_REJECT. */
    const val RMS_SOFT_FS = 0.020

    const val PEAK_REJECT_FS = 0.012
    const val CLIP_REJECT = 0.02
    const val CLIP_SOFT = 0.002
    const val DC_REJECT_FS = 0.20
    const val DC_SOFT_FS = 0.05
    const val STUCK_VARIANCE_FS = 1e-7
    const val MIN_SAMPLES = 800
    const val SNR_EPS = 1e-12

    /**
     * Goertzel bins @ 16 kHz (same as the measure script). Speech 250–3500 Hz;
     * HF 4000–7000 Hz. Occupancy = bins > 5% of peak-bin power.
     *
     * Measured: broadband_stack hf_ratio 0.400 occ_hf 2; phone_crush_stack
     * hf_ratio 0 occ_speech 3 occ_hf 0; phone_crush_lpf hf_ratio 0.008
     * occ_speech 2 occ_hf 0; clean 1 kHz occ_speech 1 (not crush).
     */
    val GOERTZEL_HZ: DoubleArray = doubleArrayOf(
        250.0, 500.0, 750.0, 1000.0, 1500.0, 2000.0, 2500.0, 3000.0, 3500.0,
        4000.0, 5000.0, 6000.0, 7000.0,
    )
    const val SPEECH_BANDS = 9
    const val OCCUPANCY_FRAC = 0.05
    const val BAND_LIMITED_HF_RATIO = 0.08
    const val BAND_LIMITED_SPEECH_OCC = 2
    const val BAND_LIMITED_HF_OCC = 0

    fun inspect(
        pcmS16le: ByteArray,
        sampleRateHz: Int = SAMPLE_RATE_HZ,
        boundSeat: Boolean = false,
    ): StreamPcmStats {
        val n = pcmS16le.size / 2
        if (n <= 0) {
            return emptyStats()
        }
        val xs = DoubleArray(n)
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
            xs[o] = s / FULL_SCALE
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
        o = 0
        i = 0
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
        val bands = if (n >= MIN_SAMPLES) bandEnergy(xs, sampleRateHz) else BandEnergy.NONE
        val durationMs = 1000.0 * n / sampleRateHz
        val (verdict, soft) = decide(
            n = n,
            rmsFs = rmsFs,
            peakFs = peakFs,
            dcFs = dcFs,
            varianceFs = varianceFs,
            clipRatio = clipRatio,
            bands = bands,
            boundSeat = boundSeat,
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
            hfRatio = bands.hfRatio,
            speechOccupancy = bands.speechOccupancy,
            hfOccupancy = bands.hfOccupancy,
            verdict = verdict,
            softFlags = soft,
        )
    }

    private fun emptyStats(): StreamPcmStats =
        StreamPcmStats(
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
            hfRatio = 0.0,
            speechOccupancy = 0,
            hfOccupancy = 0,
            verdict = StreamGateVerdict.Reject(PresentConnectCodes.LOW_ENERGY, "empty pcm"),
            softFlags = emptyList(),
        )

    private fun decide(
        n: Int,
        rmsFs: Double,
        peakFs: Double,
        dcFs: Double,
        varianceFs: Double,
        clipRatio: Double,
        bands: BandEnergy,
        boundSeat: Boolean,
    ): Pair<StreamGateVerdict, List<String>> {
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
        if (n >= MIN_SAMPLES && isBandLimited(rmsFs, bands)) {
            return StreamGateVerdict.Reject(
                PresentConnectCodes.BAND_LIMITED,
                "hf_ratio=${"%.4f".format(Locale.US, bands.hfRatio)} occ_speech=${bands.speechOccupancy} occ_hf=${bands.hfOccupancy}",
            ) to emptyList()
        }
        val belowFloor = n < MIN_SAMPLES || rmsFs < RMS_REJECT_FS || peakFs < PEAK_REJECT_FS
        if (belowFloor && !boundSeat) {
            return StreamGateVerdict.Reject(
                PresentConnectCodes.LOW_ENERGY,
                "rms_fs=${"%.5f".format(Locale.US, rmsFs)} peak_fs=${"%.5f".format(Locale.US, peakFs)} n=$n unbound",
            ) to emptyList()
        }
        val soft = ArrayList<String>(4)
        if (clipRatio >= CLIP_SOFT) soft += "SOFT_CLIP"
        if (belowFloor || rmsFs < RMS_SOFT_FS) soft += "SOFT_LOW"
        if (abs(dcFs) >= DC_SOFT_FS) soft += "SOFT_DC"
        return StreamGateVerdict.Pass to soft
    }

    private fun isBandLimited(rmsFs: Double, bands: BandEnergy): Boolean {
        if (rmsFs < RMS_SOFT_FS) return false
        return bands.hfRatio < BAND_LIMITED_HF_RATIO &&
            bands.speechOccupancy >= BAND_LIMITED_SPEECH_OCC &&
            bands.hfOccupancy <= BAND_LIMITED_HF_OCC
    }

    private fun bandEnergy(xs: DoubleArray, sampleRateHz: Int): BandEnergy {
        val powers = DoubleArray(GOERTZEL_HZ.size)
        var total = 0.0
        var peakP = 0.0
        var i = 0
        while (i < GOERTZEL_HZ.size) {
            val p = goertzelPower(xs, GOERTZEL_HZ[i], sampleRateHz)
            powers[i] = p
            total += p
            if (p > peakP) peakP = p
            i++
        }
        val thresh = OCCUPANCY_FRAC * peakP
        var occSpeech = 0
        var occHf = 0
        var speech = 0.0
        var hf = 0.0
        i = 0
        while (i < SPEECH_BANDS) {
            speech += powers[i]
            if (powers[i] > thresh) occSpeech++
            i++
        }
        while (i < powers.size) {
            hf += powers[i]
            if (powers[i] > thresh) occHf++
            i++
        }
        return BandEnergy(
            hfRatio = hf / (total + SNR_EPS),
            speechRatio = speech / (total + SNR_EPS),
            speechOccupancy = occSpeech,
            hfOccupancy = occHf,
        )
    }

    private fun goertzelPower(samples: DoubleArray, freq: Double, sr: Int): Double {
        val w = 2.0 * Math.PI * freq / sr
        val coeff = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        var i = 0
        while (i < samples.size) {
            val s0 = samples[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
            i++
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    private data class BandEnergy(
        val hfRatio: Double,
        val speechRatio: Double,
        val speechOccupancy: Int,
        val hfOccupancy: Int,
    ) {
        companion object {
            val NONE = BandEnergy(0.0, 0.0, 0, 0)
        }
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
    val snrProxy: Double,
    val hfRatio: Double,
    val speechOccupancy: Int,
    val hfOccupancy: Int,
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

/** Synthetic pcm_s16le; same generators as scripts/measure-stream-pcm-gate.py. */
object StreamPcmSynth {
    val BROADBAND_HZ: DoubleArray = doubleArrayOf(400.0, 1000.0, 2000.0, 3500.0, 5000.0, 7000.0)
    val PHONE_HZ: DoubleArray = doubleArrayOf(400.0, 1000.0, 2000.0, 3000.0)

    fun silence(n: Int = 6_400): ByteArray = shortsToS16le(ShortArray(n))

    fun empty(): ByteArray = ByteArray(0)

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

    fun toneStack(
        freqs: DoubleArray,
        amplitude: Double = 0.08,
        n: Int = 6_400,
        sampleRateHz: Int = StreamPcmGate.SAMPLE_RATE_HZ,
    ): ByteArray {
        val xs = DoubleArray(n)
        val twoPi = 2.0 * Math.PI
        for (hz in freqs) {
            var i = 0
            while (i < n) {
                xs[i] += amplitude * sin(twoPi * hz * i / sampleRateHz)
                i++
            }
        }
        return floatsToS16le(xs)
    }

    fun phoneCrushLpf(
        freqs: DoubleArray = BROADBAND_HZ,
        amplitude: Double = 0.08,
        fc: Double = 2400.0,
        stages: Int = 4,
        n: Int = 6_400,
        sampleRateHz: Int = StreamPcmGate.SAMPLE_RATE_HZ,
    ): ByteArray {
        val xs = DoubleArray(n)
        val twoPi = 2.0 * Math.PI
        for (hz in freqs) {
            var i = 0
            while (i < n) {
                xs[i] += amplitude * sin(twoPi * hz * i / sampleRateHz)
                i++
            }
        }
        val a = 1.0 - exp(-twoPi * fc / sampleRateHz)
        var stage = 0
        while (stage < stages) {
            var y = 0.0
            var i = 0
            while (i < n) {
                y += a * (xs[i] - y)
                xs[i] = y
                i++
            }
            stage++
        }
        return floatsToS16le(xs)
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

    private fun floatsToS16le(xs: DoubleArray): ByteArray {
        val s = ShortArray(xs.size)
        var i = 0
        while (i < xs.size) {
            s[i] = (xs[i] * 32767.0).roundToInt().coerceIn(-32767, 32767).toShort()
            i++
        }
        return shortsToS16le(s)
    }
}
