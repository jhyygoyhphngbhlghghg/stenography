package team.mikcoders.neurosteno.present

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PresentConnectStreamTableTest {
    @Test
    fun acceptsTwentyAndRejectsTwentyFirst() {
        val table = PresentConnectStreamTable(maxStreams = 20)
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        for (i in 1..21) {
            val id = "s%02d".format(i)
            when (val r = table.tryStart(id)) {
                is AdmitResult.Accepted -> accepted += r.streamId
                is AdmitResult.OverCapacity -> rejected += r.streamId
            }
        }
        assertEquals((1..20).map { "s%02d".format(it) }, accepted)
        assertEquals(listOf("s21"), rejected)
        assertEquals(20, table.activeCount())
        assertTrue(table.isOpen("s01"))
        assertFalse(table.isOpen("s21"))
    }

    @Test
    fun endFreesSlotForNextStart() {
        val table = PresentConnectStreamTable(maxStreams = 20)
        for (i in 1..20) {
            assertTrue(table.tryStart("s$i") is AdmitResult.Accepted)
        }
        assertTrue(table.tryStart("s21") is AdmitResult.OverCapacity)
        assertTrue(table.end("s1"))
        val again = table.tryStart("s21")
        assertTrue(again is AdmitResult.Accepted)
        assertEquals(20, table.activeCount())
    }

    @Test
    fun duplicateStartDoesNotConsumeSecondSlot() {
        val table = PresentConnectStreamTable(maxStreams = 2)
        assertTrue(table.tryStart("a") is AdmitResult.Accepted)
        val dup = table.tryStart("a") as AdmitResult.Accepted
        assertTrue(dup.duplicate)
        assertEquals(1, table.activeCount())
        assertTrue(table.tryStart("b") is AdmitResult.Accepted)
        assertTrue(table.tryStart("c") is AdmitResult.OverCapacity)
    }

    @Test
    fun concurrentStartsNeverExceedCap() {
        val table = PresentConnectStreamTable(maxStreams = 20)
        val pool = Executors.newFixedThreadPool(32)
        val started = CountDownLatch(1)
        val done = CountDownLatch(64)
        val accepted = AtomicInteger(0)
        val rejected = AtomicInteger(0)
        repeat(64) { i ->
            pool.execute {
                started.await()
                when (table.tryStart("c$i")) {
                    is AdmitResult.Accepted -> accepted.incrementAndGet()
                    is AdmitResult.OverCapacity -> rejected.incrementAndGet()
                }
                done.countDown()
            }
        }
        started.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(20, accepted.get())
        assertEquals(44, rejected.get())
        assertEquals(20, table.activeCount())
    }
}
