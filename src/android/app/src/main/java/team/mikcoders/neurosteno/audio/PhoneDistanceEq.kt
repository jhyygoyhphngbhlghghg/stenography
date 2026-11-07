package team.mikcoders.neurosteno.audio

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * DEMO_STAGE6 phone-distance EQ (offline ViewModel path only — not Path B).
 *
 * Eval per-turn @ `54177a1e`: energy islands already ≥0.08 FS so [FarFieldGain]
 * is a no-op; remaining damage is Nothing-class phone high-end crush + 4–6 m
 * muffling. Per-turn 1-pole high-shelf on **far** islands only. Loud near seat
 * (RMS ≥ [NEAR_IDENTITY_RMS_FS]) is identity (0 dB). Peak cap by reducing the
 * shelf, never by attenuating the whole turn (gain stays frozen).
 *
 * Thresholds: `python3 scripts/measure-phone-distance-eq.py --record`.
 */
object PhoneDistanceEq {
    const val SAMPLE_RATE_HZ = Pcm.TARGET_HZ
    const val SHELF_FC_HZ = 1_600.0
    /** +9 dB on the high residual (x − LPF). */
    const val MAX_SHELF_DB = 9.0
    const val NEAR_IDENTITY_RMS_FS = 0.12
    const val PEAK_LIMIT = FarFieldGain.PEAK_LIMIT
    /** Nothing-class speech band (johann H1): 250–4500 Hz. */
    const val SPEECH_LO_HZ = 250.0
    const val SPEECH_HI_HZ = 4_500.0
    const val PRESENCE_LO_HZ = 2_000.0
    const val PRESENCE_TARGET = 0.22

    val MAX_HIGH_GAIN: Double
        get() = FarFieldGain.dbToLinear(MAX_SHELF_DB)

    val GOERTZEL_HZ: DoubleArray = doubleArrayOf(
        250.0, 500.0, 800.0, 1_000.0, 1_500.0, 2_000.0, 2_500.0, 3_000.0, 3_500.0,
        4_000.0, 4_500.0, 5_000.0, 6_000.0, 7_000.0,
    )

    fun apply(samples: FloatArray, sampleRateHz: Int = SAMPLE_RATE_HZ): PhoneDistanceEqResult {
        val n = samples.size
        if (n == 0) return identityResult(samples)
        val ranges = FarFieldGain.findSegments(samples, sampleRateHz)
        if (ranges.isEmpty()) return identityResult(samples)
        val frame = ((sampleRateHz * FarFieldGain.FRAME_MS) / 1000).coerceAtLeast(1)
        var maxRms = 0.0
        val rmsSeg = DoubleArray(ranges.size)
        var s = 0
        while (s < ranges.size) {
            val r = ranges[s]
            val v = activeRms(samples, r, frame)
            rmsSeg[s] = v
            if (v > maxRms) maxRms = v
            s++
        }
        val out = samples.copyOf()
        val segs = ArrayList<SegmentEq>(ranges.size)
        var anyEq = false
        s = 0
        while (s < ranges.size) {
            val range = ranges[s]
            val near = isNearSeat(rmsSeg[s], maxRms)
            val slice = samples.copyOfRange(range.first, range.last + 1)
            val bands0 = bandEnergy(slice, sampleRateHz)
            val highGain: Double
            val applied: Boolean
            if (near) {
                highGain = 1.0
                applied = false
            } else {
                highGain = shelfGainFor(bands0)
                applied = highGain > 1.0 + 1e-9
                if (applied) {
                    val eqed = highShelf(slice, SHELF_FC_HZ, highGain, sampleRateHz)
                    eqed.copyInto(out, range.first)
                    anyEq = true
                }
            }
            val after = out.copyOfRange(range.first, range.last + 1)
            segs += SegmentEq(
                start = range.first,
                endInclusive = range.last,
                rmsBefore = rmsSeg[s],
                rmsAfter = activeRms(out, range, frame),
                peakBefore = FarFieldGain.peak(slice),
                peakAfter = FarFieldGain.peak(after),
                nearIdentity = near,
                highGainLinear = highGain,
                shelfDb = FarFieldGain.linearToDb(highGain),
                applied = applied,
                speechRatioBefore = bands0.speechToFull,
                speechRatioAfter = bandEnergy(after, sampleRateHz).speechToFull,
            )
            s++
        }
        return PhoneDistanceEqResult(
            samples = out,
            applied = anyEq,
            nearCrushed = segs.any { it.nearIdentity && it.rmsAfter + 1e-6 < it.rmsBefore },
            segments = segs,
        )
    }

    fun isNearSeat(rmsFs: Double, maxRmsFs: Double): Boolean {
        if (rmsFs >= NEAR_IDENTITY_RMS_FS) return true
        return maxRmsFs >= NEAR_IDENTITY_RMS_FS && rmsFs >= 0.92 * maxRmsFs
    }

    fun shelfGainFor(bands: SpeechBandEnergy): Double {
        val crushed = ((PRESENCE_TARGET - bands.presenceToSpeech) / PRESENCE_TARGET).coerceIn(0.0, 1.0)
        val highGain = 1.0 + crushed * (MAX_HIGH_GAIN - 1.0)
        return if (highGain < 1.0 + 1e-9) 1.0 else highGain
    }

    fun highShelf(
        samples: FloatArray,
        fcHz: Double = SHELF_FC_HZ,
        highGain: Double,
        sampleRateHz: Int = SAMPLE_RATE_HZ,
        peakLimit: Double = PEAK_LIMIT,
    ): FloatArray {
        if (highGain <= 1.0 + 1e-12) return samples.copyOf()
        var g = highGain
        var out = shelfOnce(samples, fcHz, g, sampleRateHz)
        if (FarFieldGain.peak(out) <= peakLimit) return out
        var lo = 1.0
        var hi = g
        var k = 0
        while (k < 12) {
            val mid = 0.5 * (lo + hi)
            val cand = shelfOnce(samples, fcHz, mid, sampleRateHz)
            if (FarFieldGain.peak(cand) > peakLimit) {
                hi = mid
            } else {
                lo = mid
                out = cand
                g = mid
            }
            k++
        }
        val lim = peakLimit.toFloat()
        var i = 0
        while (i < out.size) {
            out[i] = out[i].coerceIn(-lim, lim)
            i++
        }
        return out
    }

    fun bandEnergy(samples: FloatArray, sampleRateHz: Int = SAMPLE_RATE_HZ): SpeechBandEnergy {
        val xs = DoubleArray(samples.size) { samples[it].toDouble() }
        var speech = 0.0
        var presence = 0.0
        var full = 0.0
        var i = 0
        while (i < GOERTZEL_HZ.size) {
            val hz = GOERTZEL_HZ[i]
            val p = goertzelPower(xs, hz, sampleRateHz)
            full += p
            if (hz + 1e-9 >= SPEECH_LO_HZ && hz - 1e-9 <= SPEECH_HI_HZ) speech += p
            if (hz + 1e-9 >= PRESENCE_LO_HZ && hz - 1e-9 <= SPEECH_HI_HZ) presence += p
            i++
        }
        val eps = 1e-18
        return SpeechBandEnergy(
            speech = speech,
            presence = presence,
            full = full,
            rms = FarFieldGain.rms(samples),
            peak = FarFieldGain.peak(samples),
            speechToFull = speech / (full + eps),
            presenceToSpeech = presence / (speech + eps),
        )
    }

    /** RMS of frames at/above onset so hangover zeros do not mask a near seat. */
    private fun activeRms(samples: FloatArray, range: IntRange, frame: Int): Double {
        var acc = 0.0
        var n = 0
        var i = range.first
        val end = range.last + 1
        while (i + frame <= end) {
            var fAcc = 0.0
            var k = i
            val stop = i + frame
            while (k < stop) {
                val s = samples[k]
                fAcc += s.toDouble() * s
                k++
            }
            val r = sqrt(fAcc / frame)
            if (r >= FarFieldGain.SEGMENT_ONSET_FS) {
                acc += fAcc
                n += frame
            }
            i += frame
        }
        return if (n == 0) FarFieldGain.rms(samples.copyOfRange(range.first, end)) else sqrt(acc / n)
    }

    private fun identityResult(samples: FloatArray): PhoneDistanceEqResult =
        PhoneDistanceEqResult(
            samples = samples.copyOf(),
            applied = false,
            nearCrushed = false,
            segments = emptyList(),
        )

    private fun shelfOnce(
        samples: FloatArray,
        fcHz: Double,
        highGain: Double,
        sampleRateHz: Int,
    ): FloatArray {
        val a = 1.0 - exp(-2.0 * Math.PI * fcHz / sampleRateHz)
        val out = FloatArray(samples.size)
        var lp = 0.0
        var i = 0
        while (i < samples.size) {
            val x = samples[i].toDouble()
            lp += a * (x - lp)
            out[i] = (lp + highGain * (x - lp)).toFloat()
            i++
        }
        return out
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
}

data class SpeechBandEnergy(
    val speech: Double,
    val presence: Double,
    val full: Double,
    val rms: Double,
    val peak: Double,
    val speechToFull: Double,
    val presenceToSpeech: Double,
)

data class SegmentEq(
    val start: Int,
    val endInclusive: Int,
    val rmsBefore: Double,
    val rmsAfter: Double,
    val peakBefore: Double,
    val peakAfter: Double,
    val nearIdentity: Boolean,
    val highGainLinear: Double,
    val shelfDb: Double,
    val applied: Boolean,
    val speechRatioBefore: Double,
    val speechRatioAfter: Double,
)

data class PhoneDistanceEqResult(
    val samples: FloatArray,
    val applied: Boolean,
    val nearCrushed: Boolean,
    val segments: List<SegmentEq>,
)
