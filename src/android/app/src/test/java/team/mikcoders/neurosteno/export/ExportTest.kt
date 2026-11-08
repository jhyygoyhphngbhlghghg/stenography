package team.mikcoders.neurosteno.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

class ExportTest {
    private val rows = listOf(
        Utterance("speaker_1", 0, 2500, "Привет, как дела \"тест\""),
        Utterance("speaker_2", 2500, 5100, "Хорошо — спасибо, а у тебя?"),
    )

    @Test fun jsonShapeUnchanged() {
        val bytes = TranscriptExport.bytes(ExportFormat.JSON, rows)
        assertTrue(TranscriptJson.matchesBriefShape(bytes.toString(Charsets.UTF_8)))
    }

    @Test fun txtKeepsSpeakerAndTime() {
        val s = TranscriptExport.bytes(ExportFormat.TXT, rows).toString(Charsets.UTF_8)
        assertTrue(s.contains("speaker_1:"))
        assertTrue(s.contains("00:00:00.000"))
    }

    @Test fun srtNumbersAndArrow() {
        val s = TranscriptExport.bytes(ExportFormat.SRT, rows).toString(Charsets.UTF_8)
        assertTrue(s.contains("1\n"))
        assertTrue(s.contains("-->"))
        assertTrue(s.contains("00:00:02,500"))
    }

    @Test fun vttHeaderAndVoice() {
        val s = TranscriptExport.bytes(ExportFormat.VTT, rows).toString(Charsets.UTF_8)
        assertTrue(s.startsWith("WEBVTT"))
        assertTrue(s.contains("<v speaker_2>"))
    }

    @Test fun csvEscapesQuotes() {
        val s = TranscriptExport.bytes(ExportFormat.CSV, rows).toString(Charsets.UTF_8)
        assertTrue(s.contains("\"Привет, как дела \"\"тест\"\"\""))
    }

    @Test fun docxIsZipWithXml() {
        val bytes = TranscriptExport.bytes(ExportFormat.DOCX, rows, "Демо")
        val names = ArrayList<String>()
        ZipInputStream(bytes.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) { names += e.name; e = z.nextEntry }
        }
        assertTrue(names.contains("word/document.xml"))
        assertTrue(names.contains("[Content_Types].xml"))
    }

    @Test fun pdfHeaderAndEof() {
        val bytes = TranscriptExport.bytes(ExportFormat.PDF, rows, "Демо")
        val s = bytes.toString(Charsets.ISO_8859_1)
        assertTrue(s.startsWith("%PDF-"))
        assertTrue(s.contains("%%EOF"))
        assertTrue(bytes.size > 200)
    }
}
