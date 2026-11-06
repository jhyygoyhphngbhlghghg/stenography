package team.mikcoders.neurosteno.present

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentConnectLimitsTest {
    @Test
    fun acceptsTwentyAndRejectsTwentyFirst() {
        assertEquals(20, PresentConnectLimits.MAX_STREAMS)
        repeat(20) { n ->
            assertTrue("slot $n", PresentConnectLimits.canAccept(n))
            assertNull(PresentConnectLimits.rejectReason(n))
        }
        assertFalse(PresentConnectLimits.canAccept(20))
        assertEquals(PresentConnectCodes.OVER_CAPACITY, PresentConnectLimits.rejectReason(20))
        assertFalse(PresentConnectLimits.canAccept(21))
    }

    @Test
    fun errorJsonMatchesContractKeys() {
        val raw = PresentConnectLimits.errorJson("s21", PresentConnectCodes.OVER_CAPACITY, "max 20 streams")
        assertTrue(raw.contains("\"type\":\"error\""))
        assertTrue(raw.contains("\"code\":\"OVER_CAPACITY\""))
        assertTrue(raw.contains("\"stream_id\":\"s21\""))
    }
}
