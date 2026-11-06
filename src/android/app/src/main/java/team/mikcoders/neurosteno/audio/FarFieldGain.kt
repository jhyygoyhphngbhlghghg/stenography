package team.mikcoders.neurosteno.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Single-mic DEMO_STAGE6 far-field staging.
 *
 * **GAIN FROZEN** (Eval per-turn @ `54177a1e`: STAGE6 energy islands already
 * ≥0.08 FS → this AGC is a no-op on those wavs; do not retune targets).
 * Spectral / phone-distance EQ lives in [PhoneDistanceEq], not here.
 *
 * Whole-file RMS AGC is wrong: a loud near seat (Eval ffgain mix 0.119 FS)
 * pulls gain **down** (−3.46 dB) and 4–6 m turns stay below ASR. This path
 * uses **per-turn energy windows**: each quiet segment independently targets
 * [TARGET_RMS_FS]; loud turns are never attenuated (gain ≥ 0 dB).
 *
 * Thresholds: `python3 scripts/measure-farfield-gain.py --record`.
 */
object FarFieldGain {
    const val TARGET_RMS_FS = 0.08
    const val MAX_GAIN_DB = 24.0
    const val PEAK_LIMIT = 0.95
    const val MIN_RMS_FOR_GAIN = 1e-5
    const val FRAME_MS = 20
    const val VAD_RMS_FS = 0.008
    /** Below 6 m sine RMS (~0.0059), above digital silence. */
    const val SEGMENT_ONSET_FS = 0.0025
    const val HANGOVER_FRAMES = 3
    const val MIN_SEGMENT_FRAMES = 4
    const val DIP_RATIO = 0.4
    const val DIP_FRAMES = 3
    const val SAMPLE_RATE_HZ = Pcm.TARGET_HZ

    val maxGainLinear: Double
        get() = dbToLinear(MAX_GAIN_DB)

    fun stage(samples: FloatArray): FarFieldGainResult = stagePerSegment(samples)

    /**
     * Per-turn gain. Never apply gain &lt; 1 (does not crush a loud near seat).
     * Quiet islands independently target [TARGET_RMS_FS].
     */
    fun stagePerSegment(samples: FloatArray, sampleRateHz: Int = SAMPLE_RATE_HZ): FarFieldGainResult {
        val n = samples.size
        val rmsBefore = rms(samples)
        val peakBefore = peak(samples)
        val vadBefore = energyVadFireRate(samples, sampleRateHz)
        if (n == 0) {
            return emptyResult(samples)
        }
        val out = samples.copyOf()
        val frame = ((sampleRateHz * FRAME_MS) / 1000).coerceAtLeast(1)
        val ranges = findSegments(samples, sampleRateHz)
        val segs = ArrayList<SegmentGain>(ranges.size)
        var anyBoost = false
        var anyClip = false
        var maxG = 1.0
        val lim = PEAK_LIMIT.toFloat()
        for (range in ranges) {
            val slice = samples.copyOfRange(range.first, range.last + 1)
            val r0 = activeRms(samples, range, frame)
            val p0 = peak(slice)
            val g = gainForSegment(r0, p0)
            if (g > maxG) maxG = g
            val desiredBoost = r0 >= MIN_RMS_FOR_GAIN && r0 < TARGET_RMS_FS
            val clipGuard = desiredBoost && g + 1e-12 < TARGET_RMS_FS / r0.coerceAtLeast(1e-12)
            if (clipGuard) anyClip = true
            if (g > 1.0 + 1e-9) anyBoost = true
            var i = range.first
            val gf = g.toFloat()
            while (i <= range.last) {
                out[i] = (samples[i] * gf).coerceIn(-lim, lim)
                i++
            }
            val after = out.copyOfRange(range.first, range.last + 1)
            segs += SegmentGain(
                start = range.first,
                endInclusive = range.last,
                rmsBefore = r0,
                rmsAfter = rms(after),
                peakBefore = p0,
                gainLinear = g,
                gainDb = linearToDb(g),
                boosted = g > 1.0 + 1e-9,
                clipGuard = clipGuard,
            )
        }
        return FarFieldGainResult(
            samples = out,
            gainLinear = maxG,
            gainDb = linearToDb(maxG),
            rmsBefore = rmsBefore,
            rmsAfter = rms(out),
            peakBefore = peakBefore,
            peakAfter = peak(out),
            clipGuard = anyClip,
            boosted = anyBoost,
            vadFireRateBefore = vadBefore,
            vadFireRateAfter = energyVadFireRate(out, sampleRateHz),
            segments = segs,
        )
    }

    /** Gain for one turn: never below 0 dB (no crush). */
    fun gainForSegment(rmsFs: Double, peakFs: Double): Double {
        if (rmsFs < MIN_RMS_FOR_GAIN) return 1.0
        if (rmsFs >= TARGET_RMS_FS) return 1.0
        val desired = TARGET_RMS_FS / rmsFs
        val peakCap = PEAK_LIMIT / peakFs.coerceAtLeast(1e-12)
        val capped = min(desired, min(peakCap, maxGainLinear))
        return if (capped < 1.0) 1.0 else capped
    }

    fun findSegments(samples: FloatArray, sampleRateHz: Int = SAMPLE_RATE_HZ): List<IntRange> {
        val frame = ((sampleRateHz * FRAME_MS) / 1000).coerceAtLeast(1)
        if (samples.size < frame) {
            return if (rms(samples) >= SEGMENT_ONSET_FS) listOf(samples.indices) else emptyList()
        }
        val nFrames = samples.size / frame
        val rmsF = DoubleArray(nFrames)
        var f = 0
        while (f < nFrames) {
            val start = f * frame
            rmsF[f] = rmsRange(samples, start, start + frame)
            f++
        }
        val mask = BooleanArray(nFrames)
        var hold = 0
        f = 0
        while (f < nFrames) {
            if (rmsF[f] >= SEGMENT_ONSET_FS) {
                mask[f] = true
                hold = HANGOVER_FRAMES
            } else if (hold > 0) {
                mask[f] = true
                hold--
            }
            f++
        }
        val raw = ArrayList<IntRange>()
        f = 0
        while (f < nFrames) {
            if (!mask[f]) {
                f++
                continue
            }
            val a = f
            while (f < nFrames && mask[f]) f++
            val b = f - 1
            if (b - a + 1 >= MIN_SEGMENT_FRAMES) {
                raw += (a * frame) until ((b + 1) * frame).coerceAtMost(samples.size)
            }
        }
        return splitDips(samples, raw, frame)
    }

    private fun emptyResult(samples: FloatArray): FarFieldGainResult =
        FarFieldGainResult(
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
            segments = emptyList(),
        )

    /** RMS of frames at/above onset so hangover zeros do not inflate gain. */
    private fun activeRms(samples: FloatArray, range: IntRange, frame: Int): Double {
        var acc = 0.0
        var n = 0
        var i = range.first
        val end = range.last + 1
        while (i + frame <= end) {
            val r = rmsRange(samples, i, i + frame)
            if (r >= SEGMENT_ONSET_FS) {
                var k = i
                val stop = i + frame
                while (k < stop) {
                    val s = samples[k]
                    acc += s.toDouble() * s
                    k++
                }
                n += frame
            }
            i += frame
        }
        return if (n == 0) rmsRange(samples, range.first, end) else sqrt(acc / n)
    }

    private fun rmsRange(samples: FloatArray, start: Int, endExclusive: Int): Double {
        val n = (endExclusive - start).coerceAtLeast(0)
        if (n <= 0) return 0.0
        var acc = 0.0
        var i = start
        while (i < endExclusive) {
            val s = samples[i]
            acc += s.toDouble() * s
            i++
        }
        return sqrt(acc / n)
    }

    private fun splitDips(
        samples: FloatArray,
        islands: List<IntRange>,
        frame: Int,
    ): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (isl in islands) {
            val nFrames = ((isl.last - isl.first + 1) / frame).coerceAtLeast(1)
            if (nFrames < MIN_SEGMENT_FRAMES * 2) {
                out += isl
                continue
            }
            val rmsF = DoubleArray(nFrames)
            var peakF = 0.0
            var f = 0
            while (f < nFrames) {
                val a = isl.first + f * frame
                rmsF[f] = rmsRange(samples, a, (a + frame).coerceAtMost(isl.last + 1))
                if (rmsF[f] > peakF) peakF = rmsF[f]
                f++
            }
            val dip = peakF * DIP_RATIO
            val cuts = ArrayList<Int>()
            f = MIN_SEGMENT_FRAMES
            while (f < nFrames - MIN_SEGMENT_FRAMES) {
                var k = 0
                var ok = true
                while (k < DIP_FRAMES && f + k < nFrames) {
                    if (rmsF[f + k] >= dip) {
                        ok = false
                        break
                    }
                    k++
                }
                if (ok) {
                    cuts += f
                    f += DIP_FRAMES
                } else {
                    f++
                }
            }
            if (cuts.isEmpty()) {
                out += isl
            } else {
                var prev = 0
                for (c in cuts) {
                    if (c - prev >= MIN_SEGMENT_FRAMES) {
                        val a = isl.first + prev * frame
                        val b = (isl.first + c * frame - 1).coerceAtMost(isl.last)
                        out += a..b
                    }
                    prev = c + DIP_FRAMES
                }
                if (nFrames - prev >= MIN_SEGMENT_FRAMES) {
                    val a = isl.first + prev * frame
                    out += a..isl.last
                }
            }
        }
        return out.ifEmpty { islands }
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

data class SegmentGain(
    val start: Int,
    val endInclusive: Int,
    val rmsBefore: Double,
    val rmsAfter: Double,
    val peakBefore: Double,
    val gainLinear: Double,
    val gainDb: Double,
    val boosted: Boolean,
    val clipGuard: Boolean,
)

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
    val segments: List<SegmentGain> = emptyList(),
) {
    val loudCrushed: Boolean
        get() = segments.any { it.gainLinear < 1.0 - 1e-9 }
}

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

    /**
     * Near seat hot enough that a sequential mix (this + quiet 5–6 m + gaps)
     * sits ~0.12 FS overall — Eval ffgain mix was 0.119 FS, so whole-file AGC
     * would attenuate (~−3.5 dB) instead of lifting distant turns.
     */
    fun loudNearTurn(n: Int = N_400MS): FloatArray = closeTalkSine(n = n, amplitude = 0.34)

    fun gap(n: Int = 3_200): FloatArray = FloatArray(n)

    fun concat(parts: List<FloatArray>): FloatArray {
        val n = parts.sumOf { it.size }
        val out = FloatArray(n)
        var o = 0
        for (p in parts) {
            p.copyInto(out, o)
            o += p.size
        }
        return out
    }

    data class MixTurn(val name: String, val distanceM: Double, val range: IntRange)

    /** Harmonic stack so 300–3400 Hz band energy is measurable (not a 1 kHz sine). */
    fun speechStack(
        n: Int = N_400MS,
        peak: Double = 0.22,
        sampleRateHz: Int = Pcm.TARGET_HZ,
    ): FloatArray {
        val freqs = doubleArrayOf(400.0, 800.0, 1_200.0, 1_600.0, 2_000.0, 2_400.0, 2_800.0, 3_200.0, 3_600.0)
        val rel = doubleArrayOf(1.0, 0.75, 0.55, 0.40, 0.32, 0.25, 0.20, 0.16, 0.12)
        val raw = FloatArray(n)
        val twoPi = 2.0 * Math.PI
        var i = 0
        var pk = 0.0
        while (i < n) {
            var acc = 0.0
            var k = 0
            while (k < freqs.size) {
                acc += rel[k] * sin(twoPi * freqs[k] * i / sampleRateHz)
                k++
            }
            raw[i] = acc.toFloat()
            val a = abs(acc)
            if (a > pk) pk = a
            i++
        }
        val g = (peak / pk.coerceAtLeast(1e-12)).toFloat()
        i = 0
        while (i < n) {
            raw[i] *= g
            i++
        }
        return raw
    }

    /** Extra air / distance muffling on top of [phoneMic] (synth only). */
    fun distanceMuffle(
        samples: FloatArray,
        distanceM: Double,
        sampleRateHz: Int = Pcm.TARGET_HZ,
    ): FloatArray {
        val fc = (3_200.0 * 3.0 / distanceM).coerceIn(900.0, 4_000.0)
        val a = 1.0 - exp(-2.0 * Math.PI * fc / sampleRateHz)
        val xs = DoubleArray(samples.size) { samples[it].toDouble() }
        var stage = 0
        while (stage < 2) {
            var y = 0.0
            var i = 0
            while (i < xs.size) {
                y += a * (xs[i] - y)
                xs[i] = y
                i++
            }
            stage++
        }
        return FloatArray(xs.size) { xs[it].toFloat() }
    }

    /** Phone-mic + 1/r + extra muffling + HVAC. For EQ measure; not Eval wavs. */
    fun phoneDistanceScene(distanceM: Double, n: Int = N_400MS): FloatArray {
        val far = distanceMuffle(atDistance(speechStack(n), distanceM), distanceM)
        return mix(phoneMic(far), hvac(n))
    }

    /** Sequential mix: hot near speech (no phone crush) + muffled 5 m / 6 m phone scenes. */
    fun sequentialPhoneMix(): Pair<FloatArray, List<MixTurn>> {
        val near = speechStack(peak = 0.28)
        val g1 = gap()
        val far5 = phoneDistanceScene(5.0)
        val g2 = gap()
        val far6 = phoneDistanceScene(6.0)
        var o = 0
        fun take(p: FloatArray): IntRange {
            val a = o
            o += p.size
            return a until o
        }
        val turns = listOf(
            MixTurn("near_3m", 3.0, take(near)),
            MixTurn("gap1", 0.0, take(g1)),
            MixTurn("far_5m", 5.0, take(far5)),
            MixTurn("gap2", 0.0, take(g2)),
            MixTurn("far_6m", 6.0, take(far6)),
        )
        return concat(listOf(near, g1, far5, g2, far6)) to turns
    }

    /** Sequential DEMO_STAGE6-like file: loud 3 m, then quiet 5 m and 6 m. */
    fun sequentialMix(): Pair<FloatArray, List<MixTurn>> {
        val near = loudNearTurn()
        val g1 = gap()
        val far5 = scene(5.0)
        val g2 = gap()
        val far6 = scene(6.0)
        var o = 0
        fun take(p: FloatArray): IntRange {
            val a = o
            o += p.size
            return a until o
        }
        val turns = listOf(
            MixTurn("near_3m", 3.0, take(near)),
            MixTurn("gap1", 0.0, take(g1)),
            MixTurn("far_5m", 5.0, take(far5)),
            MixTurn("gap2", 0.0, take(g2)),
            MixTurn("far_6m", 6.0, take(far6)),
        )
        return concat(listOf(near, g1, far5, g2, far6)) to turns
    }
}
