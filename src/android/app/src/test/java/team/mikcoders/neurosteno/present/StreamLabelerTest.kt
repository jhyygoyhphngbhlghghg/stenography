package team.mikcoders.neurosteno.present

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StreamLabelerTest {
    private val labeler = StreamLabeler.oracle()

    @Test
    fun oracleMapsS01ToS20() {
        for (n in 1..20) {
            val sid = "s%02d".format(n)
            val label = labeler.bind(sid)
            assertEquals("speaker_$n", label.speakerId)
            assertEquals(sid, label.streamId)
            if (n == 1) {
                assertTrue(label.isMain)
                assertEquals(StreamLabel.ROLE_MAIN, label.role)
            } else {
                assertFalse(label.isMain)
                assertEquals(StreamLabel.ROLE_BABBLE, label.role)
            }
        }
        assertEquals("speaker_1", labeler.speakerId("s01"))
        assertEquals("speaker_5", labeler.speakerId("s05"))
        assertEquals("speaker_20", labeler.speakerId("s20"))
    }

    @Test
    fun oddIdsParseSnn() {
        assertEquals("speaker_21", labeler.bind("s21").speakerId)
        assertEquals("speaker_7", labeler.bind("s7").speakerId)
        assertEquals("speaker_99", StreamLabeler.speakerFromStreamId("s99"))
        assertEquals("speaker_unknown", StreamLabeler.speakerFromStreamId("mic-a"))
    }

    @Test
    fun speakerHintWinsOverTable() {
        val hinted = labeler.bind("s02", speakerHint = "speaker_9")
        assertEquals("speaker_9", hinted.speakerId)
        assertEquals("speaker_4", labeler.bind("s02", speakerHint = "4").speakerId)
    }

    @Test
    fun channelsJsonRoundTrip() {
        val raw = """
            {"channels":[
              {"stream_id":"s01","speaker_id":"speaker_1","offset_ms":0,"role":"main"},
              {"stream_id":"s05","speaker_id":"speaker_5","offset_ms":360,"role":"babble"}
            ]}
        """.trimIndent()
        val fromFile = StreamLabeler.fromChannelsJson(raw)
        assertEquals("speaker_1", fromFile.speakerId("s01"))
        assertEquals("speaker_5", fromFile.speakerId("s05"))
        assertEquals(360L, fromFile.bind("s05").evalOffsetMs)
    }
}

class PathBGoldenBriefTest {
    private val itemKeys = setOf("speaker_id", "start_ms", "end_ms", "text")

    @Test
    fun goldenBriefFourFieldsSortedWithMain() {
        val raw = loadGolden()
        val root = Json.parseToJsonElement(raw).jsonObject
        assertEquals(setOf("transcript"), root.keys)
        val rows = root["transcript"]!!.jsonArray
        assertEquals(8, rows.size)
        var prev = Long.MIN_VALUE
        val speakers = LinkedHashSet<String>()
        for (el in rows) {
            val o = el.jsonObject
            assertEquals(itemKeys, o.keys)
            assertFalse(o.containsKey("stream_id"))
            assertFalse(o.containsKey("type"))
            val start = o["start_ms"]!!.jsonPrimitive.long
            assertTrue(start >= prev)
            prev = start
            speakers += o["speaker_id"]!!.jsonPrimitive.content
        }
        assertTrue(speakers.contains("speaker_1"))
        assertTrue(speakers.size >= 3)
        assertEquals(7, speakers.size)
    }

    @Test
    fun labelerMatchesEvalMapUsedByGoldenSpeakers() {
        val labeler = StreamLabeler.oracle()
        assertEquals("speaker_1", labeler.bind("s01").speakerId)
        assertEquals("speaker_5", labeler.bind("s05").speakerId)
        assertEquals("speaker_20", labeler.bind("s20").speakerId)
        val goldenSpeakers = setOf(
            "speaker_1", "speaker_3", "speaker_4", "speaker_5",
            "speaker_9", "speaker_10", "speaker_20",
        )
        for (sp in goldenSpeakers) {
            val n = sp.removePrefix("speaker_").toInt()
            assertEquals(sp, labeler.bind("s%02d".format(n)).speakerId)
        }
    }

    private fun loadGolden(): String {
        val fromResource = javaClass.classLoader.getResourceAsStream("golden_path_b_brief.json")
        if (fromResource != null) return fromResource.bufferedReader().readText()
        val candidates = listOf(
            File("src/test/resources/golden_path_b_brief.json"),
            File("../../artifacts/test-results/golden_path_b_brief.json"),
            File("../../../artifacts/test-results/golden_path_b_brief.json"),
        )
        return candidates.first { it.isFile }.readText()
    }
}
