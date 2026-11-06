package team.mikcoders.neurosteno.present

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import team.mikcoders.neurosteno.export.TranscriptFile
import team.mikcoders.neurosteno.export.Utterance

class PresentConnectProtocolTest {
    private fun start(p: PresentConnectProtocol, id: String, hint: String? = null): List<Outbound> {
        val hintJson = if (hint == null) "null" else "\"$hint\""
        return p.onText(
            """{"type":"stream.start","stream_id":"$id","sample_rate":16000,"encoding":"pcm_s16le","speaker_hint":$hintJson}""",
        )
    }

    private fun pcm(id: String, bytes: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(bytes)
        return """{"type":"audio.pcm16","stream_id":"$id","seq":1,"pcm16_b64":"$b64"}"""
    }

    @Test
    fun twentyFirstStreamEmitsOverCapacityAndDoesNotOpen() {
        val p = PresentConnectProtocol()
        repeat(20) { i -> start(p, "s%02d".format(i + 1)) }
        val out = start(p, "s21")
        val send = out.filterIsInstance<Outbound.Send>().map { it.json }
        assertTrue(send.any { it.contains("OVER_CAPACITY") && it.contains("s21") })
        assertTrue(out.any { it is Outbound.Raise && (it as Outbound.Raise).event is PresentConnectEvent.OverCapacity })
        assertEquals(20, p.registry.activeCount())
        assertFalse(p.registry.activeStreamIds().contains("s21"))
    }

    @Test
    fun pcmGoesOnlyToNamedStream() {
        val p = PresentConnectProtocol()
        start(p, "s01")
        start(p, "s02", hint = "qr-seat-2")
        p.onText(pcm("s01", ByteArray(8) { 0x11 }))
        p.onText(pcm("s02", ByteArray(4) { 0x22 }))
        val snaps = p.registry.isolatedSnapshots()
        assertEquals(8, snaps.getValue("s01").size)
        assertEquals(4, snaps.getValue("s02").size)
        assertTrue(snaps.getValue("s01").all { it == 0x11.toByte() })
        assertTrue(snaps.getValue("s02").all { it == 0x22.toByte() })
        assertEquals("qr-seat-2", p.registry.get("s02")!!.speakerHint)
    }

    @Test
    fun streamEndClosesOnlyThatStreamWithIsolatedPcm() {
        val p = PresentConnectProtocol()
        start(p, "s01")
        start(p, "s02")
        p.onText(pcm("s01", byteArrayOf(1, 2)))
        p.onText(pcm("s02", byteArrayOf(3, 4)))
        val out = p.onText("""{"type":"stream.end","stream_id":"s01"}""")
        val ended = out.filterIsInstance<Outbound.Raise>()
            .map { it.event }
            .filterIsInstance<PresentConnectEvent.StreamEnded>()
            .single()
        assertEquals("s01", ended.streamId)
        assertEquals(2, ended.pcmS16le.size)
        assertEquals(1.toByte(), ended.pcmS16le[0])
        assertEquals(1, p.registry.activeCount())
        assertEquals(2, p.registry.get("s02")!!.size())
        assertFalse(
            "stream.closed waits for the pre-ASR gate (error then closed)",
            out.filterIsInstance<Outbound.Send>().any { it.json.contains("stream.closed") },
        )
    }

    @Test
    fun pcmWithoutStartIsUnknownStreamNotAMixSlot() {
        val p = PresentConnectProtocol()
        val out = p.onText(pcm("ghost", byteArrayOf(1, 2)))
        val err = out.filterIsInstance<Outbound.Raise>().map { it.event }
            .filterIsInstance<PresentConnectEvent.Error>().single()
        assertEquals(PresentConnectCodes.UNKNOWN_STREAM, err.code)
        assertEquals(0, p.registry.activeCount())
    }

    @Test
    fun transcriptFinalJsonHasStreamIdAndBriefUtteranceKeys() {
        val frames = PresentConnectFrames.transcriptFinal(
            "s01",
            TranscriptFile(listOf(Utterance("speaker_1", 10, 20, "Привет"))),
        )
        val raw = frames.single()
        assertTrue(raw.contains("\"type\":\"transcript.final\""))
        assertTrue(raw.contains("\"stream_id\":\"s01\""))
        assertTrue(raw.contains("\"speaker_id\""))
        assertTrue(raw.contains("\"start_ms\""))
        assertTrue(raw.contains("\"end_ms\""))
        assertTrue(raw.contains("\"text\""))
        assertFalse(raw.contains("\"stream_id\":\"speaker_1\""))
    }

    @Test
    fun transcriptPartialKeepsDiarSpeakerIdSeparateFromStreamId() {
        val raw = PresentConnectFrames.transcriptPartial(
            "s07",
            Utterance("speaker_2", 0, 800, "добрый"),
        )
        assertTrue(raw.contains("\"stream_id\":\"s07\""))
        assertTrue(raw.contains("\"speaker_id\":\"speaker_2\""))
    }
}
