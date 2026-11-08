package team.mikcoders.neurosteno.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.mikcoders.neurosteno.export.Utterance

class LiveFeedTest {
    @Test fun windowsCoverDuration() {
        val w = LiveFeed.windowsFor(12_000)
        assertEquals(3, w.size)
        assertEquals(0L, w[0].first)
        assertEquals(12_000L, w.last().second)
    }

    @Test fun interimGroupsByWindow() {
        val rows = listOf(
            Utterance("speaker_1", 500, 2000, "Привет"),
            Utterance("speaker_2", 6000, 8000, "Добрый день"),
        )
        val out = LiveFeed.interim(rows, LiveFeed.windowsFor(10_000))
        assertEquals(2, out.size)
        assertEquals("speaker_1", out[0].speakerId)
        assertTrue(out[1].text.contains("Добрый"))
    }

    @Test fun emptyWindowsAreMarked() {
        val out = LiveFeed.interim(emptyList(), LiveFeed.windowsFor(6_000))
        assertTrue(out.all { it.text.contains("окно") })
    }
}
