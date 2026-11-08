package team.mikcoders.neurosteno.export

import team.mikcoders.neurosteno.export.Utterance
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** All offline export renderers. JSON stays the brief's canonical export; the
 *  others are human/word-processor views. PDF/DOCX are generated without any
 *  new dependency: PDF carries WinAnsi text, DOCX is a minimal OOXML zip. */
enum class ExportFormat(val mime: String, val ext: String, val label: String) {
    JSON("application/json", "json", "JSON"),
    TXT("text/plain", "txt", "Текст"),
    SRT("text/plain", "srt", "SRT субтитры"),
    VTT("text/vtt", "vtt", "WebVTT"),
    CSV("text/csv", "csv", "CSV"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx", "Word"),
    PDF("application/pdf", "pdf", "PDF"),
}

object TranscriptExport {
    fun bytes(format: ExportFormat, rows: List<Utterance>, title: String = "Транскрипт"): ByteArray = when (format) {
        ExportFormat.JSON -> TranscriptJson.encode(team.mikcoders.neurosteno.export.TranscriptFile(rows)).toByteArray()
        ExportFormat.TXT -> txt(rows, title).toByteArray()
        ExportFormat.SRT -> srt(rows).toByteArray()
        ExportFormat.VTT -> vtt(rows).toByteArray()
        ExportFormat.CSV -> csv(rows).toByteArray()
        ExportFormat.DOCX -> docx(rows, title)
        ExportFormat.PDF -> pdf(rows, title)
    }

    private fun tsMs(ms: Long, dot: Boolean): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val c = if (dot) '.' else ','
        return "%02d:%02d:%02d%c%03d".format(s / 3600, (s / 60) % 60, s % 60, c, ms % 1000)
    }

    fun txt(rows: List<Utterance>, title: String): String = buildString {
        appendLine(title)
        appendLine("=".repeat(60))
        for (u in rows) {
            appendLine("[${tsMs(u.startMs, true)} – ${tsMs(u.endMs, true)}] ${u.speakerId}: ${u.text}")
        }
    }

    fun srt(rows: List<Utterance>): String = buildString {
        val sorted = rows.sortedBy { it.startMs }
        sorted.forEachIndexed { i, u ->
            appendLine("${i + 1}")
            appendLine("${tsMs(u.startMs, false)} --> ${tsMs(u.endMs, false)}")
            appendLine("${u.speakerId}: ${u.text}")
            appendLine()
        }
    }

    fun vtt(rows: List<Utterance>): String = buildString {
        appendLine("WEBVTT")
        appendLine()
        for (u in rows.sortedBy { it.startMs }) {
            appendLine("${tsMs(u.startMs, true)} --> ${tsMs(u.endMs, true)}")
            appendLine("<v ${u.speakerId}>${u.text}</v>")
            appendLine()
        }
    }

    fun csv(rows: List<Utterance>): String = buildString {
        appendLine("speaker_id,start_ms,end_ms,text")
        for (u in rows) {
            append("${u.speakerId},${u.startMs},${u.endMs},\"${u.text.replace("\"", "\"\"")}\"")
            appendLine()
        }
    }

    private fun xml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    fun docx(rows: List<Utterance>, title: String): ByteArray {
        val paras = StringBuilder()
        fun para(text: String, style: String? = null, size: Int = 24) {
            val styleEl = style?.let { "<w:pStyle w:val=\"$it\"/>" } ?: ""
            paras.append("<w:p><w:pPr>$styleEl</w:pPr><w:r><w:rPr><w:sz w:val=\"$size\"/></w:rPr><w:t xml:space=\"preserve\">")
                .append(xml(text)).append("</w:t></w:r></w:p>")
        }
        para(title, "Title", 40)
        for (u in rows) {
            para("[${tsMs(u.startMs, true)}] ${u.speakerId}: ${u.text}")
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${paras}<w:sectPr/></w:body></w:document>"""
        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in listOf(
                "[Content_Types].xml" to contentTypes,
                "_rels/.rels" to rels,
                "word/document.xml" to document,
            )) {
                z.putNextEntry(ZipEntry(name))
                z.write(data.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun winAnsi(s: String): String = s.map { c ->
        when {
            c.code in 32..255 -> c
            c in "«»—–…".toList() -> when (c) {
                '«' -> '«'; '»' -> '»'; '—' -> '—'; '–' -> '–'; '…' -> '…'; else -> '?'
            }
            else -> '?'
        }
    }.joinToString("")

    fun pdf(rows: List<Utterance>, title: String): ByteArray {
        val lines = ArrayList<String>()
        lines += winAnsi(title)
        lines += "=".repeat(60)
        for (u in rows) {
            val head = "[${tsMs(u.startMs, true)}] ${u.speakerId}: "
            var rest = winAnsi(u.text)
            var first = true
            while (true) {
                val prefix = if (first) head else " ".repeat(head.length)
                val avail = 92 - prefix.length
                if (rest.length <= avail) { lines += prefix + rest; break }
                var cut = rest.lastIndexOf(' ', avail.coerceAtLeast(1))
                if (cut <= 0) cut = avail
                lines += prefix + rest.substring(0, cut)
                rest = rest.substring(cut).trimStart()
                first = false
            }
        }
        val pageLines = 48
        val pages = lines.chunked(pageLines).ifEmpty { listOf(emptyList()) }
        fun esc(s: String): String = s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        val out = ByteArrayOutputStream()
        fun push(s: String) { out.write(s.toByteArray(Charsets.ISO_8859_1)) }
        push("%PDF-1.4\n")
        val offsets = ArrayList<Int>()
        var nextObj = 1
        fun obj(body: String): Int {
            offsets += out.size()
            val id = nextObj++
            push("$id 0 obj\n$body\nendobj\n")
            return id
        }
        // reserve ids 1..3 before content generation so page ids are stable
        val catalogId = 1
        val pagesId = 2
        val fontId = 3
        var nextFree = 4
        val pageIds = ArrayList<Int>()
        val contents = pages.map { page ->
            val ops = StringBuilder("BT /F1 13 Tf 56 800 Td")
            page.forEachIndexed { li, line ->
                if (li > 0) ops.append(" 0 -17 Td")
                ops.append(" (${esc(line)}) Tj")
            }
            ops.append(" ET")
            ops.toString()
        }
        pages.forEachIndexed { pi, _ ->
            val pageId = nextFree++; val contentId = nextFree++
            pageIds += pageId
            // placeholder offsets patched below by writing objects in order:
            // we must write objects sequentially; to keep ids simple emit page now
            // with a forward reference to contentId.
            val pageBody = "<< /Type /Page /Parent $pagesId 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 $fontId 0 R >> >> /Contents $contentId 0 R >>"
            // store bodies for later ordered write
            pageBodies += pageId to pageBody
            contentBodies += contentId to contents[pi]
        }
        // write objects in id order 1..max
        val all = sortedMapOf<Int, String>()
        all[catalogId] = "<< /Type /Catalog /Pages $pagesId 0 R >>"
        all[pagesId] = "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>"
        all[fontId] = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
        for ((id, body) in pageBodies) all[id] = body
        for ((id, body) in contentBodies) all[id] = "<< /Length ${body.toByteArray(Charsets.ISO_8859_1).size} >>\nstream\n$body\nendstream"
        // objects vec is indexed by id-1; pad to max id
        val byId = (1..all.keys.maxOrNull()!!).map { all[it] ?: "<<" + ">>" }
        for ((id, body) in byId.withIndex()) {
            if (id == 0) continue
            // offsets for ids 1..n must be recorded in order; we currently use obj()
            // which assigns sequentially - rebuild sequentially here instead.
        }
        // Re-emit deterministically: rebuild from scratch with obj() in id order
        out.reset()
        push("%PDF-1.4\n")
        offsets.clear()
        nextObj = 1
        for (id in 1..byId.size) {
            val body = byId[id - 1]
            offsets += out.size()
            push("$id 0 obj\n$body\nendobj\n")
        }
        val xref = out.size()
        val size = byId.size + 1
        push("xref\n0 $size\n0000000000 65535 f \n")
        for (off in offsets) push("%010d 00000 n \n".format(off))
        push("trailer\n<< /Size $size /Root $catalogId 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    private val pageBodies = ArrayList<Pair<Int, String>>()
    private val contentBodies = ArrayList<Pair<Int, String>>()
}
