package team.mikcoders.neurosteno.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptJsonTest {
    @Test
    fun encodesBriefFieldNames() {
        val file = TranscriptFile(
            transcript = listOf(
                Utterance(
                    speakerId = "speaker_1",
                    startMs = 1250,
                    endMs = 4380,
                    text = "Добрый день!",
                ),
            ),
        )
        val raw = TranscriptJson.encode(file)
        assertTrue(raw.contains("\"speaker_id\""))
        assertTrue(raw.contains("\"start_ms\""))
        assertTrue(raw.contains("\"end_ms\""))
        assertTrue(raw.contains("\"transcript\""))
        val back = TranscriptJson.decode(raw)
        assertEquals("speaker_1", back.transcript[0].speakerId)
        assertEquals(1250L, back.transcript[0].startMs)
    }
}
