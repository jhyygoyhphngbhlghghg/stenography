package team.mikcoders.neurosteno.export

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Multi-format transcript export: SRT, TXT, CSV, DOCX.
 *
 * DOCX is built in-memory as a minimal OOXML zip — no external dependencies.
 * All text formats use the brief's four fields (speaker_id, start_ms, end_ms, text).
 */
object TranscriptExport {

    /* ---------------------------------------------------------------- SRT */

    fun toSrt(transcript: List<Utterance>): String {
        val sb = StringBuilder()
        transcript.forEachIndexed { i, u ->
            sb.append(i + 1).append('\n')
            sb.append(srtTime(u.startMs)).append(" --> ").append(srtTime(u.endMs)).append('\n')
            sb.append('[').append(u.speakerId).append("] ").append(u.text).append('\n')
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun srtTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1_000
        val frac = ms % 1_000
        return "%02d:%02d:%02d,%03d".format(h, m, s, frac)
    }

    /* ---------------------------------------------------------------- TXT */

    fun toTxt(transcript: List<Utterance>): String {
        val sb = StringBuilder()
        sb.append("НЕЙРО-СТЕНОГРАФИСТ — протокол диалога\n")
        sb.append("=".repeat(44)).append('\n\n')
        for (u in transcript) {
            sb.append('[').append(u.speakerId).append("] ")
            sb.append(formatClock(u.startMs)).append(" – ")
            sb.append(u.text).append('\n')
        }
        return sb.toString()
    }

    private fun formatClock(ms: Long): String {
        val s = ms / 1000
        val m = s / 60
        val rem = s % 60
        return "%d:%02d".format(m, rem)
    }

    /* ---------------------------------------------------------------- CSV */

    fun toCsv(transcript: List<Utterance>): String {
        val sb = StringBuilder()
        sb.append("speaker_id,start_ms,end_ms,text\n")
        for (u in transcript) {
            sb.append(csvField(u.speakerId)).append(',')
            sb.append(u.startMs).append(',')
            sb.append(u.endMs).append(',')
            sb.append(csvField(u.text)).append('\n')
        }
        return sb.toString()
    }

    private fun csvField(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            '"' + value.replace("\"", "\"\"") + '"'
        } else {
            value
        }
    }

    /* --------------------------------------------------------------- DOCX */

    fun toDocx(transcript: List<Utterance>): ByteArray {
        val documentXml = buildDocumentXml(transcript)
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(CONTENT_TYPES.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(RELS.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            zip.write(DOC_RELS.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun buildDocumentXml(transcript: List<Utterance>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
        sb.append("<w:body>")

        // Title
        sb.append("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>")
        sb.append("<w:r><w:rPr><w:b/><w:sz w:val=\"32\"/></w:rPr>")
        sb.append("<w:t>Нейро-стенографист — протокол диалога</w:t></w:r></w:p>")
        sb.append("<w:p/>")

        for (u in transcript) {
            sb.append("<w:p>")
            // Speaker + timestamp (bold)
            sb.append("<w:r><w:rPr><w:b/></w:rPr>")
            sb.append("<w:t xml:space=\"preserve\">[${xmlEscape(u.speakerId)}] ${formatClock(u.startMs)} – </w:t></w:r>")
            // Text
            sb.append("<w:r><w:t xml:space=\"preserve\">${xmlEscape(u.text)}</w:t></w:r>")
            sb.append("</w:p>")
        }

        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private const val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
</Relationships>"""
}
