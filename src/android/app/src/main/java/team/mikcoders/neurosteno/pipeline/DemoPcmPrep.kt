package team.mikcoders.neurosteno.pipeline

import team.mikcoders.neurosteno.audio.FarFieldGain
import team.mikcoders.neurosteno.audio.FarFieldGainResult
import team.mikcoders.neurosteno.audio.Pcm
import team.mikcoders.neurosteno.audio.PhoneDistanceEq
import team.mikcoders.neurosteno.audio.PhoneDistanceEqResult
import team.mikcoders.neurosteno.present.PresentConnectCodes
import team.mikcoders.neurosteno.present.StreamPcmGate
import team.mikcoders.neurosteno.present.StreamPcmStats

/**
 * Single-channel **DEMO_STAGE6** prep (offline ViewModel path only — not Path B).
 * CLIPPED/BAD_MIC/empty on **raw** PCM (hard), then frozen per-turn gain
 * (do not retune), then **phone-distance EQ** on far islands only (near seat
 * identity). Inspect as bound main: SOFT_LOW / soft BAND_LIMITED,
 * **never hard-drop distant talkers**.
 */
object DemoPcmPrep {
    fun prepare(audio: PcmAudio, boundMain: Boolean = true): PreparedDemoPcm {
        val s16 = Pcm.floatToS16le(audio.samples)
        val rawGate = StreamPcmGate.inspect(s16, audio.sampleRate, boundSeat = boundMain)
        val hard = hardSkipCode(rawGate)
        if (hard != null) {
            return PreparedDemoPcm(
                audio = audio,
                skipAsr = true,
                skipCode = hard,
                rawGate = rawGate,
                afterGate = rawGate,
                gain = FarFieldGain.stage(FloatArray(0)),
                eq = PhoneDistanceEq.apply(FloatArray(0)),
            )
        }
        val gain = FarFieldGain.stage(audio.samples)
        val eq = PhoneDistanceEq.apply(gain.samples)
        val afterGate = StreamPcmGate.inspect(
            Pcm.floatToS16le(eq.samples),
            audio.sampleRate,
            boundSeat = boundMain,
        )
        return PreparedDemoPcm(
            audio = PcmAudio(eq.samples, audio.sampleRate, audio.sourceName),
            skipAsr = false,
            skipCode = null,
            rawGate = rawGate,
            afterGate = afterGate,
            gain = gain,
            eq = eq,
        )
    }

    private fun hardSkipCode(stats: StreamPcmStats): String? {
        val code = stats.rejectCode ?: return null
        return when (code) {
            PresentConnectCodes.CLIPPED,
            PresentConnectCodes.BAD_MIC,
            -> code
            PresentConnectCodes.LOW_ENERGY -> if (stats.n == 0) code else null
            else -> null
        }
    }
}

data class PreparedDemoPcm(
    val audio: PcmAudio,
    val skipAsr: Boolean,
    val skipCode: String?,
    val rawGate: StreamPcmStats,
    val afterGate: StreamPcmStats,
    val gain: FarFieldGainResult,
    val eq: PhoneDistanceEqResult,
)
