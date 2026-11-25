package lk.cseportfolio.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

// ---------------- CSV ----------------

object Csv {
    fun parse(text: String): List<List<String>> {
        val src = text.removePrefix("﻿")
        val firstLine = src.lineSequence().firstOrNull() ?: ""
        val delim = listOf(',', ';', '\t').maxByOrNull { d -> firstLine.count { it == d } } ?: ','
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cur = StringBuilder()
        var q = false
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (q) {
                if (c == '"') { if (i + 1 < src.length && src[i + 1] == '"') { cur.append('"'); i++ } else q = false }
                else cur.append(c)
            } else when (c) {
                '"' -> q = true
                delim -> { row.add(cur.toString().trim()); cur.setLength(0) }
                '\r' -> {}
                '\n' -> { row.add(cur.toString().trim()); cur.setLength(0); if (row.any { it.isNotEmpty() }) rows.add(row); row = ArrayList() }
                else -> cur.append(c)
            }
            i++
        }
        row.add(cur.toString().trim())
        if (row.any { it.isNotEmpty() }) rows.add(row)
        return rows
    }

    fun write(rows: List<List<String>>): String = rows.joinToString("\r\n") { r ->
        r.joinToString(",") { v -> if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v }
    }

    fun fromTable(tb: ReportTable): List<List<String>> {
        fun cells(r: List<Cell>) = r.map { it.num?.toPlainString() ?: it.text }
        return listOf(tb.headers) + tb.rows.map(::cells) + (tb.total?.let { listOf(cells(it)) } ?: emptyList())
    }
}

// ---------------- XLSX (minimal, dependency-free) ----------------

object Xlsx {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it >= ' ' }

    private fun colName(i: Int): String { var n = i; val sb = StringBuilder(); while (true) { sb.insert(0, 'A' + n % 26); n = n / 26 - 1; if (n < 0) break }; return sb.toString() }

    private fun sheetName(raw: String, used: MutableSet<String>): String {
        var base = raw.replace(Regex("[\\[\\]*?/\\\\:]"), " ").trim().take(28).ifEmpty { "Sheet" }
        var name = base; var k = 2
        while (!used.add(name.lowercase())) { name = "$base $k"; k++ }
        return name
    }

    /** Writes one worksheet per table. Numbers are stored as real numeric cells so Excel can total them. */
    fun write(tables: List<ReportTable>): ByteArray {
        val bos = ByteArrayOutputStream()
        val zip = ZipOutputStream(bos)
        fun put(name: String, content: String) { zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
        val used = HashSet<String>()
        val names = tables.map { sheetName(it.title, used) }
        val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        put("[Content_Types].xml", head + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            tables.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } +
            "</Types>")
        put("_rels/.rels", head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
        put("xl/workbook.xml", head + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
            names.mapIndexed { i, n -> "<sheet name=\"${esc(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets></workbook>")
        put("xl/_rels/workbook.xml.rels", head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            tables.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
            "<Relationship Id=\"rId${tables.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>")
        // styles: 0 text, 1 bold, 2 money, 3 bold money, 4 integer, 5 bold integer, 6 title
        put("xl/styles.xml", head + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.00\"/><numFmt numFmtId=\"165\" formatCode=\"#,##0\"/></numFmts>" +
            "<fonts count=\"3\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"14\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"7\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
            "<xf numFmtId=\"164\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\"/>" +
            "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
            "<xf numFmtId=\"165\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            "</cellXfs></styleSheet>")
        tables.forEachIndexed { idx, tb ->
            val sb = StringBuilder(head)
            sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            sb.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"3\" topLeftCell=\"A4\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
            sb.append("<cols>")
            for (c in tb.headers.indices) {
                val w = (listOf(tb.headers[c].length) + tb.rows.map { it.getOrNull(c)?.text?.length ?: 0 }).max().coerceIn(8, 48) + 3
                sb.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"$w\" customWidth=\"1\"/>")
            }
            sb.append("</cols><sheetData>")
            var r = 1
            fun text(c: Int, s: String, style: Int) { if (s.isNotEmpty()) sb.append("<c r=\"${colName(c)}$r\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(s)}</t></is></c>") }
            fun row(cells: List<Cell>, bold: Boolean) {
                sb.append("<row r=\"$r\">")
                cells.forEachIndexed { c, cell ->
                    val n = cell.num
                    if (n != null) sb.append("<c r=\"${colName(c)}$r\" s=\"${(if (cell.decimals == 0) 4 else 2) + (if (bold) 1 else 0)}\"><v>${n.toPlainString()}</v></c>")
                    else text(c, cell.text, if (bold) 1 else 0)
                }
                sb.append("</row>"); r++
            }
            sb.append("<row r=\"1\">"); text(0, tb.title, 6); sb.append("</row>"); r = 3
            sb.append("<row r=\"3\">"); tb.headers.forEachIndexed { c, h -> text(c, h, 1) }; sb.append("</row>"); r = 4
            tb.rows.forEach { row(it, false) }
            tb.total?.let { row(it, true) }
            if (tb.note.isNotEmpty()) { r++; sb.append("<row r=\"$r\">"); text(0, tb.note, 0); sb.append("</row>") }
            sb.append("</sheetData></worksheet>")
            put("xl/worksheets/sheet${idx + 1}.xml", sb.toString())
        }
        zip.close()
        return bos.toByteArray()
    }

    private fun nm(local: String?, q: String?): String = (if (q.isNullOrEmpty()) local ?: "" else q).substringAfter(':')

    /** Reads the first worksheet of an .xlsx file as rows of strings (numbers keep their plain value). */
    fun read(input: InputStream): List<List<String>> {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet")) parts[e.name] = z.readBytes()
            }
        }
        val sheetKey = parts.keys.filter { it.startsWith("xl/worksheets/sheet") }
            .minByOrNull { it.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: Int.MAX_VALUE }
            ?: throw IllegalArgumentException("This file has no worksheet. Only .xlsx workbooks are supported (not the old .xls format).")
        val factory = SAXParserFactory.newInstance()
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (e: Exception) { }
        val shared = ArrayList<String>()
        parts["xl/sharedStrings.xml"]?.let { bytes ->
            factory.newSAXParser().parse(ByteArrayInputStream(bytes), object : DefaultHandler() {
                val sb = StringBuilder(); var inT = false
                override fun startElement(u: String?, l: String?, qn: String?, a: Attributes?) { val q = nm(l, qn); if (q == "si") sb.setLength(0); if (q == "t") inT = true }
                override fun endElement(u: String?, l: String?, qn: String?) { val q = nm(l, qn); if (q == "t") inT = false; if (q == "si") shared.add(sb.toString()) }
                override fun characters(ch: CharArray, s: Int, len: Int) { if (inT) sb.append(ch, s, len) }
            })
        }
        val rows = ArrayList<MutableList<String>>()
        factory.newSAXParser().parse(ByteArrayInputStream(parts[sheetKey]), object : DefaultHandler() {
            var row: MutableList<String>? = null; var col = 0; var type = ""; val sb = StringBuilder(); var capture = false
            override fun startElement(u: String?, l: String?, qn: String?, a: Attributes?) {
                when (nm(l, qn)) {
                    "row" -> row = ArrayList()
                    "c" -> {
                        val ref = a?.getValue("r") ?: ""
                        type = a?.getValue("t") ?: ""
                        val letters = ref.takeWhile { it.isLetter() }
                        col = if (letters.isEmpty()) (row?.size ?: 0) else letters.fold(0) { acc, ch -> acc * 26 + (ch.uppercaseChar() - 'A' + 1) } - 1
                        sb.setLength(0)
                    }
                    "v", "t" -> capture = true
                }
            }
            override fun endElement(u: String?, l: String?, qn: String?) {
                when (nm(l, qn)) {
                    "v", "t" -> capture = false
                    "c" -> {
                        val raw = sb.toString()
                        val v = when (type) {
                            "s" -> shared.getOrNull(raw.trim().toIntOrNull() ?: -1) ?: ""
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            "inlineStr", "str", "e" -> raw
                            else -> try { if (raw.isBlank()) "" else BigDecimal(raw.trim()).stripTrailingZeros().toPlainString() } catch (e: NumberFormatException) { raw }
                        }
                        val r = row ?: return
                        while (r.size < col) r.add("")
                        if (r.size == col) r.add(v.trim()) else r[col] = v.trim()
                    }
                    "row" -> { row?.let { if (it.any { c -> c.isNotEmpty() }) rows.add(it) }; row = null }
                }
            }
            override fun characters(ch: CharArray, s: Int, len: Int) { if (capture) sb.append(ch, s, len) }
        })
        return rows
    }
}

// ---------------- PDF text (for broker statements) ----------------

/**
 * Minimal text extractor for simple, text-based PDFs such as the JasperReports/iText statements
 * issued by CSE brokers. It reads Flate-compressed content streams and returns the text grouped
 * into lines, left to right. Scanned (image) PDFs and PDFs with embedded subset fonts are not supported.
 */
object PdfText {
    private class Piece(val x: Double, val y: Double, val s: String)

    fun extractLines(pdf: ByteArray): List<String> {
        val lines = ArrayList<String>()
        for (stream in streams(pdf)) {
            val pieces = parseContent(stream)
            if (pieces.isEmpty()) continue
            val byLine = pieces.groupBy { Math.round(it.y / 2.0) }.toSortedMap(compareByDescending { it })
            for ((_, ps) in byLine) {
                val text = ps.sortedBy { it.x }.joinToString("  ") { it.s.trim() }.trim()
                if (text.isNotEmpty()) lines.add(text)
            }
        }
        return lines
    }

    private fun indexOf(d: ByteArray, pat: ByteArray, from: Int): Int {
        var i = from
        outer@ while (i <= d.size - pat.size) { for (j in pat.indices) if (d[i + j] != pat[j]) { i++; continue@outer }; return i }
        return -1
    }

    private fun streams(d: ByteArray): List<String> {
        val out = ArrayList<String>()
        val sTok = "stream".toByteArray(); val eTok = "endstream".toByteArray()
        var pos = 0
        while (true) {
            val s = indexOf(d, sTok, pos); if (s < 0) break
            if (s >= 3 && String(d, s - 3, 3, Charsets.ISO_8859_1) == "end") { pos = s + 6; continue }
            var start = s + 6
            if (start < d.size && d[start] == '\r'.code.toByte()) start++
            if (start < d.size && d[start] == '\n'.code.toByte()) start++
            val e = indexOf(d, eTok, start); if (e < 0) break
            val dictStart = maxOf(0, s - 600)
            val dict = String(d, dictStart, s - dictStart, Charsets.ISO_8859_1).substringAfterLast("obj")
            val raw = d.copyOfRange(start, e)
            val bytes = if (dict.contains("FlateDecode")) inflate(raw) else if (dict.contains("/Filter")) null else raw
            if (bytes != null) { val txt = String(bytes, Charsets.ISO_8859_1); if (txt.contains("BT") && (txt.contains("Tj") || txt.contains("TJ"))) out.add(txt) }
            pos = e + 9
        }
        return out
    }

    private fun inflate(raw: ByteArray): ByteArray? = try {
        val inf = Inflater(); inf.setInput(raw)
        val bos = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; bos.write(buf, 0, n) }
        inf.end(); bos.toByteArray()
    } catch (e: Exception) { null }

    private fun parseContent(c: String): List<Piece> {
        val out = ArrayList<Piece>()
        val stack = ArrayList<Any>()   // numbers (Double), strings (String), arrays (List)
        var x = 0.0; var y = 0.0; var lx = 0.0; var ly = 0.0; var leading = 0.0
        var i = 0
        fun num(k: Int): Double = (stack.getOrNull(stack.size - k) as? Double) ?: 0.0
        fun readString(): String {
            val sb = StringBuilder(); var depth = 1; i++
            while (i < c.length && depth > 0) {
                val ch = c[i]
                when (ch) {
                    '\\' -> { i++; if (i < c.length) { val e = c[i]
                        when (e) { 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t'); 'b', 'f' -> {}
                            in '0'..'7' -> { var v = 0; var k = 0; while (k < 3 && i < c.length && c[i] in '0'..'7') { v = v * 8 + (c[i] - '0'); i++; k++ }; i--; sb.append(v.toChar()) }
                            '\n' -> {}; '\r' -> {}
                            else -> sb.append(e) } } }
                    '(' -> { depth++; sb.append(ch) }
                    ')' -> { depth--; if (depth > 0) sb.append(ch) }
                    else -> sb.append(ch)
                }
                i++
            }
            return sb.toString()
        }
        fun readHex(): String { val sb = StringBuilder(); i++; val hex = StringBuilder()
            while (i < c.length && c[i] != '>') { if (!c[i].isWhitespace()) hex.append(c[i]); i++ }; i++
            var k = 0; while (k + 1 < hex.length) { sb.append(hex.substring(k, k + 2).toInt(16).toChar()); k += 2 }; return sb.toString() }
        while (i < c.length) {
            val ch = c[i]
            when {
                ch.isWhitespace() -> i++
                ch == '(' -> stack.add(readString())
                ch == '<' && i + 1 < c.length && c[i + 1] != '<' -> stack.add(readHex())
                ch == '[' -> { i++; val sb = StringBuilder()
                    while (i < c.length && c[i] != ']') {
                        when {
                            c[i] == '(' -> sb.append(readString())
                            c[i] == '<' -> sb.append(readHex())
                            else -> { val st = i; while (i < c.length && c[i] != '(' && c[i] != '<' && c[i] != ']' && !c[i].isWhitespace()) i++
                                val t = c.substring(st, i).toDoubleOrNull(); if (t != null && t < -200) sb.append(' '); if (i == st) i++ }
                        }
                    }
                    i++; stack.add(listOf(sb.toString())) }
                ch == '/' || ch == '<' || ch == '>' -> { i++; while (i < c.length && !c[i].isWhitespace() && c[i] !in "/[(<>") i++; stack.add("/") }
                ch == '-' || ch == '+' || ch == '.' || ch.isDigit() -> { val st = i; i++
                    while (i < c.length && (c[i].isDigit() || c[i] == '.' || c[i] == '-')) i++
                    stack.add(c.substring(st, i).toDoubleOrNull() ?: 0.0) }
                else -> { val st = i; while (i < c.length && !c[i].isWhitespace() && c[i] !in "([</") i++
                    if (i == st) { i++; continue }
                    when (c.substring(st, i)) {
                        "BT" -> { x = 0.0; y = 0.0; lx = 0.0; ly = 0.0 }
                        "Tm" -> { x = num(2); y = num(1); lx = x; ly = y }
                        "Td" -> { lx += num(2); ly += num(1); x = lx; y = ly }
                        "TD" -> { leading = -num(1); lx += num(2); ly += num(1); x = lx; y = ly }
                        "TL" -> leading = num(1)
                        "T*" -> { ly -= leading; x = lx; y = ly }
                        "Tj" -> (stack.lastOrNull() as? String)?.let { if (it != "/") out.add(Piece(x, y, it)) }
                        "TJ" -> (stack.lastOrNull() as? List<*>)?.let { out.add(Piece(x, y, it.firstOrNull()?.toString() ?: "")) }
                        "'" -> { ly -= leading; x = lx; y = ly; (stack.lastOrNull() as? String)?.let { out.add(Piece(x, y, it)) } }
                    }
                    stack.clear() }
            }
        }
        return out
    }
}
