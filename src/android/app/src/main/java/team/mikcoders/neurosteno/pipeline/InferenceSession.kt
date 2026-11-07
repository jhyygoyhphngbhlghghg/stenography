package team.mikcoders.neurosteno.pipeline

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One lazy native model, serialized transactions, no cached PCM or transcripts.
 *
 * Native calls cannot be preempted safely. Cancellation is observed between
 * calls; eviction/close wait for the owning transaction before freeing JNI.
 * Creation and destruction stay on [dispatcher], including teardown after the
 * caller is cancelled. A failed transaction invalidates possibly poisoned state.
 */
internal class InferenceSession<M : Any>(
    private val create: suspend () -> M,
    private val destroy: (M) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private var model: M? = null

    /** [getModel] is valid only inside the transaction and must not escape it. */
    suspend fun <T> run(operation: suspend (getModel: suspend () -> M) -> T): T =
        withContext(dispatcher) {
            mutex.withLock {
                check(!closed.get()) { "Inference session is closed" }
                try {
                    currentCoroutineContext().ensureActive()
                    val result = operation {
                        currentCoroutineContext().ensureActive()
                        // Publish ownership before observing cancellation of a
                        // suspending native load, so its handle is not lost.
                        val loaded = model ?: withContext(NonCancellable) { create() }
                            .also { model = it }
                        currentCoroutineContext().ensureActive()
                        loaded
                    }
                    currentCoroutineContext().ensureActive()
                    result
                } catch (failure: Throwable) {
                    try {
                        discard()
                    } catch (cleanup: Throwable) {
                        failure.addSuppressed(cleanup)
                    }
                    throw failure
                }
            }
        }

    /** Drop warm weights and dependent caches between complete transactions. */
    suspend fun evict(onEvict: suspend () -> Unit = {}) = withContext(dispatcher + NonCancellable) {
        mutex.withLock {
            try {
                discard()
            } finally {
                onEvict()
            }
        }
    }

    /** Reject queued/new work and release once the active transaction finishes. */
    suspend fun close() {
        closed.set(true)
        withContext(dispatcher + NonCancellable) {
            mutex.withLock { discard() }
        }
    }

    private fun discard() {
        val old = model
        model = null
        if (old != null) destroy(old)
    }
}
