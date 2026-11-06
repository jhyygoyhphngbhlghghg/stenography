package team.mikcoders.neurosteno.present

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
