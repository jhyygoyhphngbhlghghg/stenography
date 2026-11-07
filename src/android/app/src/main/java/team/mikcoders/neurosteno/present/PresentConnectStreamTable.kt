package team.mikcoders.neurosteno.present

/**
 * In-process admission table for PresentConnect audio streams.
 *
 * Cap is [maxStreams] (brief/demo: 20). Stream 21 with a new id is
 * [AdmitResult.OverCapacity]; ids 1..20 are [AdmitResult.Accepted].
 * Duplicate start of an already-open id is a no-op accept (does not consume
 * another slot). End/cancel free a slot.
 *
 * Pure Kotlin so JVM unit tests can prove the cap without an emulator.
 */
class PresentConnectStreamTable(val maxStreams: Int = 20) {
    private val lock = Any()
    private val open = LinkedHashSet<String>()

    fun activeCount(): Int = synchronized(lock) { open.size }

    fun isOpen(streamId: String): Boolean = synchronized(lock) { streamId in open }

    fun tryStart(streamId: String): AdmitResult {
        require(streamId.isNotBlank()) { "stream_id required" }
        synchronized(lock) {
            if (streamId in open) return AdmitResult.Accepted(streamId, duplicate = true)
            if (open.size >= maxStreams) return AdmitResult.OverCapacity(streamId, maxStreams)
            open.add(streamId)
            return AdmitResult.Accepted(streamId, duplicate = false)
        }
    }

    fun end(streamId: String): Boolean = synchronized(lock) { open.remove(streamId) }

    fun clear() = synchronized(lock) { open.clear() }

    fun snapshot(): List<String> = synchronized(lock) { open.toList() }
}

sealed class AdmitResult {
    data class Accepted(val streamId: String, val duplicate: Boolean) : AdmitResult()

    data class OverCapacity(val streamId: String, val maxStreams: Int) : AdmitResult()
}
