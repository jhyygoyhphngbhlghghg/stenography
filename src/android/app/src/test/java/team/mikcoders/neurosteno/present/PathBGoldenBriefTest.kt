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

/**
 * Eval Path B golden brief (exact cut from mix_20talker_per_stream.json).
 * Brief keys only; no stream_id; sorted by start_ms.
 */
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
        assertEquals("speaker_5", rows[0].jsonObject["speaker_id"]!!.jsonPrimitive.content)
        assertEquals(959L, rows[0].jsonObject["start_ms"]!!.jsonPrimitive.long)
        assertEquals("у нас полгорода в трауре", rows[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("speaker_1", rows[1].jsonObject["speaker_id"]!!.jsonPrimitive.content)
        assertEquals("speaker_1", rows.last().jsonObject["speaker_id"]!!.jsonPrimitive.content)
        assertEquals(15252L, rows.last().jsonObject["start_ms"]!!.jsonPrimitive.long)
    }

    @Test
    fun labelerMatchesEvalMapUsedByGoldenSpeakers() {
        val labeler = StreamLabeler.oracle()
        assertEquals("speaker_1", labeler.bind("s01").speakerId)
        assertEquals("speaker_5", labeler.bind("s05").speakerId)
        assertEquals("speaker_20", labeler.bind("s20").speakerId)
        assertTrue(labeler.bind("s01").isMain)
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
        val fromResource = javaClass.classLoader?.getResourceAsStream("golden_path_b_brief.json")
        if (fromResource != null) return fromResource.bufferedReader().readText()
        val candidates = listOf(
            File("src/test/resources/golden_path_b_brief.json"),
            File("../../artifacts/test-results/golden_path_b_brief.json"),
            File("../../../artifacts/test-results/golden_path_b_brief.json"),
            File("../../samples/demo-stress/golden_path_b_brief.json"),
        )
        return candidates.first { it.isFile }.readText()
    }
}
