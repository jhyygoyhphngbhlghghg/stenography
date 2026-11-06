package team.mikcoders.neurosteno.present

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentStreamRegistryTest {
    private fun startOk(
        reg: PresentStreamRegistry,
        id: String,
        hint: String? = null,
    ): StreamSession {
        val r = reg.tryStart(id, PresentConnectLimits.ENCODING, PresentConnectLimits.SAMPLE_RATE, hint)
        return (r as PresentStreamRegistry.StartResult.Accepted).session
    }

    @Test
    fun acceptsTwentyAndRejectsTwentyFirst() {
        val reg = PresentStreamRegistry()
        repeat(20) { i ->
            val id = "s%02d".format(i + 1)
            val r = reg.tryStart(id, PresentConnectLimits.ENCODING, 16_000, null)
            assertTrue("slot $id", r is PresentStreamRegistry.StartResult.Accepted)
        }
        assertEquals(20, reg.activeCount())
        val r21 = reg.tryStart("s21", PresentConnectLimits.ENCODING, 16_000, null)
        assertTrue(r21 is PresentStreamRegistry.StartResult.OverCapacity)
        assertEquals("s21", (r21 as PresentStreamRegistry.StartResult.OverCapacity).streamId)
        assertEquals(20, reg.activeCount())
        assertFalse(reg.activeStreamIds().contains("s21"))
    }

    @Test
    fun nOpenStreamsAreNSeparateBuffersNotOneMix() {
        val reg = PresentStreamRegistry()
        val toneA = ByteArray(3200) { 0x11 }
        val toneB = ByteArray(3200) { 0x22 }
        val toneC = ByteArray(1600) { 0x33.toByte() }
        startOk(reg, "s01")
        startOk(reg, "s02")
        startOk(reg, "s03")
        reg.appendPcm("s01", toneA)
        reg.appendPcm("s02", toneB)
        reg.appendPcm("s01", toneA)
        reg.appendPcm("s03", toneC)

        val snaps = reg.isolatedSnapshots()
        assertEquals(3, snaps.size)
        assertEquals(6400, snaps.getValue("s01").size)
        assertEquals(3200, snaps.getValue("s02").size)
        assertEquals(1600, snaps.getValue("s03").size)
        assertTrue(snaps.getValue("s01").all { it == 0x11.toByte() })
        assertTrue(snaps.getValue("s02").all { it == 0x22.toByte() })
        assertTrue(snaps.getValue("s03").all { it == 0x33.toByte() })
        assertFalse(snaps.getValue("s01").contentEquals(snaps.getValue("s02")))

        // A mixdown would interleave or sum 0x11 with 0x22. Neither isolated buffer does that.
        val mixedGuess = ByteArray(3200) { i -> if (i % 2 == 0) 0x11 else 0x22 }
        assertFalse(snaps.getValue("s01").contentEquals(mixedGuess))
        assertFalse(snaps.getValue("s02").contentEquals(mixedGuess))
    }

    @Test
    fun endReturnsOnlyThatStreamPcm() {
        val reg = PresentStreamRegistry()
        startOk(reg, "s01")
        startOk(reg, "s02")
        reg.appendPcm("s01", byteArrayOf(1, 2, 3, 4))
        reg.appendPcm("s02", byteArrayOf(9, 8, 7, 6))
        val ended = reg.takeForEnd("s01")!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), ended.snapshot())
        assertEquals(1, reg.activeCount())
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), reg.get("s02")!!.snapshot())
        assertNull(reg.get("s01"))
    }

    @Test
    fun unknownStreamDoesNotCreateABuffer() {
        val reg = PresentStreamRegistry()
        val r = reg.appendPcm("ghost", byteArrayOf(1, 2))
        assertTrue(r is PresentStreamRegistry.AppendResult.UnknownStream)
        assertEquals(0, reg.activeCount())
    }

    @Test
    fun badEncodingRejectedWithoutOccupyingASlot() {
        val reg = PresentStreamRegistry()
        val r = reg.tryStart("s01", "aac", 16_000, null)
        assertTrue(r is PresentStreamRegistry.StartResult.Rejected)
        assertEquals(PresentConnectCodes.BAD_ENCODING, (r as PresentStreamRegistry.StartResult.Rejected).code)
        assertEquals(0, reg.activeCount())
    }

    @Test
    fun duplicateStartDoesNotCountAsTwentyFirst() {
        val reg = PresentStreamRegistry()
        repeat(20) { i -> startOk(reg, "s%02d".format(i + 1)) }
        val again = reg.tryStart("s01", PresentConnectLimits.ENCODING, 16_000, "hint")
        assertTrue(again is PresentStreamRegistry.StartResult.Accepted)
        assertTrue((again as PresentStreamRegistry.StartResult.Accepted).alreadyOpen)
        assertEquals(20, reg.activeCount())
        val r21 = reg.tryStart("s21", PresentConnectLimits.ENCODING, 16_000, null)
        assertTrue(r21 is PresentStreamRegistry.StartResult.OverCapacity)
    }

    @Test
    fun speakerHintStaysOnSessionNotAsMixKey() {
        val reg = PresentStreamRegistry()
        startOk(reg, "s01", hint = "seat-qr-3")
        assertEquals("seat-qr-3", reg.get("s01")!!.speakerHint)
        assertEquals("s01", reg.get("s01")!!.streamId)
    }

    @Test
    fun partialCadenceUsesIsolatedSnapshot() {
        val reg = PresentStreamRegistry()
        startOk(reg, "s01")
        startOk(reg, "s02")
        val chunk = ByteArray(PresentConnectLimits.PARTIAL_PERIOD_BYTES) { 0x11 }
        val other = ByteArray(100) { 0x22 }
        reg.appendPcm("s02", other)
        val r = reg.appendPcm("s01", chunk) as PresentStreamRegistry.AppendResult.Ok
        assertEquals(PresentConnectLimits.PARTIAL_PERIOD_BYTES, r.partialSnapshot!!.size)
        assertTrue(r.partialSnapshot.all { it == 0x11.toByte() })
        // s02 bytes must not leak into s01 partial
        assertFalse(r.partialSnapshot.any { it == 0x22.toByte() })
    }
}
