package team.mikcoders.neurosteno.pipeline

import team.mikcoders.neurosteno.audio.FarFieldGain
import team.mikcoders.neurosteno.audio.FarFieldGainResult
import team.mikcoders.neurosteno.audio.Pcm
import team.mikcoders.neurosteno.present.PresentConnectCodes
import team.mikcoders.neurosteno.present.StreamPcmGate
import team.mikcoders.neurosteno.present.StreamPcmStats

/**
 * Single-channel **DEMO_STAGE6** prep (offline ViewModel path only — not Path B).
 *
 * Hard skip **raw** PCM for CLIPPED / BAD_MIC / empty `n==0` only.
 * Energy / band-limit on bound main is **soft** (never hard-drop 4–6 m).
 *
 * Does **not** apply whole-file RMS AGC (Eval e0e64b85: 0.73→0.70, s5 still
 * missing). Per-turn gain runs later in [SherpaStenoPipeline] after soft VAD.
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
        return PreparedDemoPcm(
            audio = audio,
            skipAsr = false,
            skipCode = null,
            rawGate = rawGate,
            afterGate = rawGate,
            gain = FarFieldGain.stage(FloatArray(0)),
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
