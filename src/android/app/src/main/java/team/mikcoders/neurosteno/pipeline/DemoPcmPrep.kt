package team.mikcoders.neurosteno.pipeline

import team.mikcoders.neurosteno.audio.FarFieldGain
import team.mikcoders.neurosteno.audio.FarFieldGainResult
import team.mikcoders.neurosteno.audio.Pcm
import team.mikcoders.neurosteno.present.PresentConnectCodes
import team.mikcoders.neurosteno.present.StreamPcmGate
import team.mikcoders.neurosteno.present.StreamPcmStats

/**
 * Single-channel **DEMO_STAGE6** prep (offline ViewModel path only — not Path B).
 * CLIPPED/BAD_MIC/empty on **raw** PCM (hard), then **per-turn** far-field
 * gain so quiet 4–6 m islands clear the ASR floor without attenuating a loud
 * near seat, then inspect as bound main: SOFT_LOW / soft BAND_LIMITED,
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
            )
        }
        val gain = FarFieldGain.stage(audio.samples)
        val afterGate = StreamPcmGate.inspect(
            Pcm.floatToS16le(gain.samples),
            audio.sampleRate,
            boundSeat = boundMain,
        )
        return PreparedDemoPcm(
            audio = PcmAudio(gain.samples, audio.sampleRate, audio.sourceName),
            skipAsr = false,
            skipCode = null,
            rawGate = rawGate,
            afterGate = afterGate,
            gain = gain,
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
)
