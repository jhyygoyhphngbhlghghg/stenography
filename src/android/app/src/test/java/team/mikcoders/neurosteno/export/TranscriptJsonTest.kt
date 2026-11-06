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
        assertEquals(4380L, back.transcript[0].endMs)
        assertEquals("Добрый день!", back.transcript[0].text)
        assertTrue(TranscriptJson.matchesBriefShape(raw))
        assertEquals(
            setOf("transcript"),
            kotlinx.serialization.json.Json.parseToJsonElement(raw)
                .let { it as kotlinx.serialization.json.JsonObject }.keys,
        )
        val item = kotlinx.serialization.json.Json.parseToJsonElement(raw)
            .let { it as kotlinx.serialization.json.JsonObject }["transcript"]
            .let { it as kotlinx.serialization.json.JsonArray }[0]
            .let { it as kotlinx.serialization.json.JsonObject }
        assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), item.keys)
    }
}
