package team.mikcoders.neurosteno.pipeline

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InferenceSessionTest {
    @Test
    fun reusesOneModelButExecutesEveryRequest() = runBlocking {
        var loads = 0
        var calls = 0
        var frees = 0
        val session = InferenceSession(create = { ++loads }, destroy = { frees++ })
        repeat(4) {
            assertEquals(1, session.run { get -> calls++; get() })
        }
        assertEquals(1, loads)
        assertEquals(4, calls)
        session.close()
        session.close()
        assertEquals(1, frees)
    }

    @Test
    fun doesNotLoadAnUnusedModel() = runBlocking {
        var loads = 0
        val session = InferenceSession(create = { ++loads }, destroy = { _: Int -> })
        assertEquals("no speech", session.run { "no speech" })
        session.evict()
        session.close()
        assertEquals(0, loads)
    }

    @Test(timeout = 10_000)
    fun allNativeWorkUsesWorkerDispatcher() = runBlocking {
        Executors.newSingleThreadExecutor { r -> Thread(r, "inference-test-worker") }
            .asCoroutineDispatcher().use { dispatcher ->
                val events = mutableListOf<String>()
                val session = InferenceSession(
                    create = { events += Thread.currentThread().name; Any() },
                    destroy = { events += Thread.currentThread().name },
                    dispatcher = dispatcher,
                )
                session.run { get -> get(); events += Thread.currentThread().name }
                session.close()
                assertEquals(3, events.size)
                // Coroutine debug mode decorates the worker name with an ID.
                assertTrue(events.all { it.substringBefore(" @") == "inference-test-worker" })
            }
    }

    @Test(timeout = 10_000)
    fun concurrentClientsShareOneSerializedSession() = runBlocking {
        val loads = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val session = InferenceSession(create = { loads.incrementAndGet() }, destroy = { _: Int -> })
        val first = async {
            session.run { get ->
                get()
                maxActive.updateAndGet { maxOf(it, active.incrementAndGet()) }
                firstEntered.complete(Unit)
                releaseFirst.await()
                active.decrementAndGet()
            }
        }
        firstEntered.await()
        val queued = List(8) {
            async {
                session.run { get ->
                    val id = get()
                    maxActive.updateAndGet { maxOf(it, active.incrementAndGet()) }
                    active.decrementAndGet()
                    id
                }
            }
        }
        releaseFirst.complete(Unit)
        first.await()
        assertEquals(List(8) { 1 }, queued.awaitAll())
        assertEquals(1, loads.get())
        assertEquals(1, maxActive.get())
        session.close()
    }

    @Test
    fun evictionReloadsButCloseRejectsNewWork() = runBlocking {
        var loads = 0
        var frees = 0
        val session = InferenceSession(create = { ++loads }, destroy = { frees++ })
        assertEquals(1, session.run { it() })
        session.evict()
        assertEquals(1, frees)
        assertEquals(2, session.run { it() })
        session.close()
        try {
            session.run { it() }
            fail("closed session accepted work")
        } catch (_: IllegalStateException) {
            assertEquals(2, loads)
            assertEquals(2, frees)
        }
    }

    @Test(timeout = 10_000)
    fun closeWaitsForOwnerAndRejectsQueuedWork() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var frees = 0
        val session = InferenceSession(create = { Any() }, destroy = { frees++ })
        val owner = launch {
            session.run { get -> get(); entered.complete(Unit); finish.await() }
        }
        entered.await()
        val closing = async(start = CoroutineStart.UNDISPATCHED) { session.close() }
        assertFalse(closing.isCompleted)
        assertEquals(0, frees)
        finish.complete(Unit)
        owner.join()
        closing.await()
        assertEquals(1, frees)
        try {
            session.run { it() }
            fail("work started after close")
        } catch (_: IllegalStateException) {
            assertEquals(1, frees)
        }
    }

    @Test(timeout = 10_000)
    fun evictionDoesNotFreeAnActiveHandle() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var frees = 0
        val session = InferenceSession(create = { Any() }, destroy = { frees++ })
        val owner = launch {
            session.run { get -> get(); entered.complete(Unit); finish.await() }
        }
        entered.await()
        val eviction = async(start = CoroutineStart.UNDISPATCHED) { session.evict() }
        assertFalse(eviction.isCompleted)
        assertEquals(0, frees)
        finish.complete(Unit)
        owner.join()
        eviction.await()
        assertEquals(1, frees)
        session.close()
    }

    @Test(timeout = 10_000)
    fun dependentCacheEvictionCompletesBeforeTheNextTransaction() = runBlocking {
        val trimming = CompletableDeferred<Unit>()
        val finishTrim = CompletableDeferred<Unit>()
        var ran = false
        val session = InferenceSession(create = { Any() }, destroy = { _: Any -> })
        session.run { it() }
        val eviction = async {
            session.evict { trimming.complete(Unit); finishTrim.await() }
        }
        trimming.await()
        val queued = async { session.run { ran = true; it() } }
        assertFalse(ran)
        finishTrim.complete(Unit)
        eviction.await()
        queued.await()
        assertTrue(ran)
        session.close()
    }

    @Test(timeout = 10_000)
    fun cancelledCloseStillDrainsNativeResources() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var frees = 0
        val session = InferenceSession(create = { Any() }, destroy = { frees++ })
        val owner = launch {
            session.run { get -> get(); entered.complete(Unit); finish.await() }
        }
        entered.await()
        val closing = launch(start = CoroutineStart.UNDISPATCHED) { session.close() }
        closing.cancel()
        assertEquals(0, frees)
        finish.complete(Unit)
        owner.join()
        closing.join()
        assertEquals(1, frees)
    }

    @Test(timeout = 10_000)
    fun cancelledOwnerInvalidatesAndNextRequestRecovers() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var loads = 0
        var frees = 0
        val session = InferenceSession(create = { ++loads }, destroy = { frees++ })
        val owner = launch {
            session.run { get -> get(); entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        owner.cancelAndJoin()
        assertEquals(1, frees)
        assertEquals(2, session.run { it() })
        session.close()
    }

    @Test(timeout = 10_000)
    fun cancellationDuringLoadDoesNotLeakTheReturnedHandle() = runBlocking {
        val loading = CompletableDeferred<Unit>()
        val loaded = CompletableDeferred<Unit>()
        var frees = 0
        val session = InferenceSession(
            create = { loading.complete(Unit); loaded.await(); Any() },
            destroy = { frees++ },
        )
        val owner = launch { session.run { it() } }
        loading.await()
        owner.cancel()
        loaded.complete(Unit)
        owner.join()
        assertEquals(1, frees)
        session.close()
        assertEquals(1, frees)
    }

    @Test(timeout = 10_000)
    fun cancellationWhileQueuedDoesNotTouchTheActiveModel() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var frees = 0
        var queuedRan = false
        val session = InferenceSession(create = { Any() }, destroy = { frees++ })
        val owner = launch {
            session.run { get -> get(); entered.complete(Unit); finish.await() }
        }
        entered.await()
        val queued = launch(start = CoroutineStart.UNDISPATCHED) {
            session.run { queuedRan = true; it() }
        }
        queued.cancelAndJoin()
        assertFalse(queuedRan)
        assertEquals(0, frees)
        finish.complete(Unit)
        owner.join()
        session.close()
        assertEquals(1, frees)
    }

    @Test
    fun failureInvalidatesModelAndPreservesOriginalException() = runBlocking {
        val original = IllegalArgumentException("decode failed")
        var loads = 0
        var frees = 0
        val session = InferenceSession(
            create = { ++loads },
            destroy = { if (++frees == 1) error("release failed") },
        )
        try {
            session.run { get -> get(); throw original }
            fail("exception swallowed")
        } catch (failure: IllegalArgumentException) {
            // Stack-trace recovery can copy exceptions at a coroutine boundary.
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === original })
            assertEquals("release failed", original.suppressed.single().message)
        }
        assertEquals(2, session.run { it() })
        session.close()
        assertEquals(2, frees)
    }

    @Test
    fun failedLoadCanBeRetriedWithoutReleasingAnUnownedHandle() = runBlocking {
        var loads = 0
        var frees = 0
        val session = InferenceSession(
            create = { if (++loads == 1) error("load failed") else loads },
            destroy = { frees++ },
        )
        try {
            session.run { it() }
            fail("load exception swallowed")
        } catch (_: IllegalStateException) {
            assertEquals(0, frees)
        }
        assertEquals(2, session.run { it() })
        session.close()
        assertEquals(1, frees)
    }
}
