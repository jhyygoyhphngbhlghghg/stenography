package team.mikcoders.neurosteno.present

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Brief / demo hard cap: 20 concurrent streams per phone worker. */
object PresentConnectLimits {
    const val MAX_STREAMS = 20
    const val PROTOCOL = "present-connect/1"
    const val ENCODING = "pcm_s16le"
    const val SAMPLE_RATE = 16_000

    fun canAccept(active: Int, max: Int = MAX_STREAMS): Boolean = active in 0 until max

    fun rejectReason(active: Int, max: Int = MAX_STREAMS): String? =
        if (canAccept(active, max)) null else PresentConnectCodes.OVER_CAPACITY

    fun errorJson(streamId: String, code: String, message: String): String =
        buildJsonObject {
            put("type", "error")
            put("stream_id", streamId)
            put("code", code)
            put("message", message)
        }.toString()
}
