package team.mikcoders.neurosteno.present

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.mikcoders.neurosteno.export.Utterance
import team.mikcoders.neurosteno.pipeline.PcmAudio

class PathBSessionTest {
    private val itemKeys = setOf("speaker_id", "start_ms", "end_ms", "text")

    @Test
    fun twoStreamsForceOracleSpeakerAndStripStreamIdFromBrief() = runBlocking {
        val asr = PathBAsr { streamId, audio ->
            listOf(
                Utterance(
                    speakerId = "cluster_wrong",
                    startMs = 10,
                    endMs = 50,
                    text = "from $streamId n=${audio.samples.size}",
                ),
            )
        }
        val session = PathBSession(asr = asr)
        session.bindStart("s01")
        session.bindStart("s05")
        val pcm = ByteArray(320) // 10 ms @ 16 kHz s16le mono
        val a = session.finish("s01", pcm)
        val b = session.finish("s05", pcm)
        assertEquals("speaker_1", a.utterances.single().speakerId)
        assertEquals("speaker_5", b.utterances.single().speakerId)
        assertTrue(a.wireJson.single().contains("\"stream_id\":\"s01\""))
        assertTrue(a.wireJson.single().contains("\"type\":\"transcript.final\""))
        assertTrue(b.wireJson.single().contains("\"stream_id\":\"s05\""))

        val brief = session.brief()
        assertEquals(2, brief.transcript.size)
        assertEquals("speaker_1", brief.transcript[0].speakerId)
        assertEquals("speaker_5", brief.transcript[1].speakerId)
        val encoded = PresentConnectWire.briefJson(brief.transcript)
        val root = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("transcript"), root.keys)
        for (el in root["transcript"]!!.jsonArray) {
            val o = el.jsonObject
            assertEquals(itemKeys, o.keys)
            assertFalse(o.containsKey("stream_id"))
            assertFalse(o.containsKey("type"))
        }
    }

    @Test
    fun cancelDoesNotAbsorb() = runBlocking {
        val session = PathBSession(
            asr = PathBAsr { _, _ -> listOf(Utterance("x", 0, 1, "nope")) },
        )
        session.bindStart("s01")
        session.unbind("s01")
        assertTrue(session.brief().transcript.isEmpty())
    }

    @Test
    fun clockOffsetShiftsTimestampsWhenStreamStartSaysSo() = runBlocking {
        val session = PathBSession(
            asr = PathBAsr { _, _ -> listOf(Utterance("cluster", 100, 200, "hi")) },
        )
        session.bindStart("s03", clockOffsetMs = 180)
        val out = session.finish("s03", ByteArray(32))
        assertEquals(280L, out.utterances.single().startMs)
        assertEquals(380L, out.utterances.single().endMs)
        assertEquals("speaker_3", out.utterances.single().speakerId)
    }
}
