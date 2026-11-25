package lk.cseportfolio.app.export

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import lk.cseportfolio.core.ReportTable

/** Draws report tables onto A4 landscape pages using Android's built-in PDF writer. */
object PdfReport {
    private const val W = 842
    private const val H = 595
    private const val M = 30f

    fun render(title: String, subtitle: String, tables: List<ReportTable>): ByteArray {
        val doc = PdfDocument()
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); color = 0xFF0B1F3A.toInt() }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = 0xFF55627A.toInt() }
        val tablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); color = 0xFF0F172A.toInt() }
        val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); color = 0xFF334155.toInt() }
        val cell = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F172A.toInt() }
        val fill = Paint().apply { color = 0xFFE8ECF3.toInt() }
        val rule = Paint().apply { color = 0xFFDCE2EC.toInt(); strokeWidth = 0.5f }
        val stamp = "Generated " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) + " · CSE Portfolio Pro · figures in LKR"

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = M

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            page = p; canvas = p.canvas
            val c = p.canvas
            c.drawText(title, M, M + 12f, titlePaint)
            c.drawText(subtitle, M, M + 26f, subPaint)
            subPaint.textAlign = Paint.Align.RIGHT
            c.drawText("Page $pageNo", W - M, M + 12f, subPaint)
            subPaint.textAlign = Paint.Align.LEFT
            c.drawText(stamp, M, H - M + 10f, subPaint)
            y = M + 44f
        }
        newPage()

        for (tb in tables) {
            val cols = tb.headers.size
            if (cols == 0) continue
            val avail = W - 2 * M
            // natural widths at 8pt, then scale the font down if the table is wider than the page
            cell.textSize = 8f; head.textSize = 8f
            val natural = FloatArray(cols) { c ->
                var w = head.measureText(tb.headers[c])
                for (r in tb.rows) w = maxOf(w, cell.measureText(r.getOrNull(c)?.text ?: ""))
                tb.total?.getOrNull(c)?.let { w = maxOf(w, head.measureText(it.text)) }
                minOf(w, 230f) + 10f
            }
            val scale = minOf(1f, avail / natural.sum())
            val size = maxOf(4.5f, 8f * scale)
            cell.textSize = size; head.textSize = size
            val widths = FloatArray(cols) { natural[it] * scale }
            val rowH = size * 1.75f
            val numeric = BooleanArray(cols) { tb.isNumeric(it) }

            fun text(c: Canvas, s: String, col: Int, x: Float, paint: Paint) {
                var t = s
                val max = widths[col] - 6f * scale
                if (paint.measureText(t) > max) { val n = paint.breakText(t, true, max, null); t = t.take(maxOf(1, n - 1)) + "…" }
                if (numeric[col]) { paint.textAlign = Paint.Align.RIGHT; c.drawText(t, x + widths[col] - 3f * scale, y + rowH * 0.7f, paint) }
                else { paint.textAlign = Paint.Align.LEFT; c.drawText(t, x + 3f * scale, y + rowH * 0.7f, paint) }
                paint.textAlign = Paint.Align.LEFT
            }
            fun headerRow() {
                val c = canvas ?: return
                c.drawRect(M, y, M + widths.sum(), y + rowH, fill)
                var x = M
                for (i in 0 until cols) { text(c, tb.headers[i], i, x, head); x += widths[i] }
                y += rowH
            }

            if (y + 22f + rowH * 3 > H - M - 6f) newPage()
            canvas?.drawText(tb.title, M, y + 11f, tablePaint)
            y += 18f
            headerRow()
            for (r in tb.rows) {
                if (y + rowH > H - M - 6f) { newPage(); headerRow() }
                val c = canvas ?: break
                var x = M
                for (i in 0 until cols) {
                    val cl = r.getOrNull(i)
                    if (cl != null) {
                        cell.color = if (cl.tone == 1 && cl.num != null) (if (cl.num!!.signum() > 0) 0xFF15803D.toInt() else if (cl.num!!.signum() < 0) 0xFFB91C1C.toInt() else 0xFF0F172A.toInt()) else 0xFF0F172A.toInt()
                        text(c, cl.text, i, x, cell)
                    }
                    x += widths[i]
                }
                y += rowH
                c.drawLine(M, y, M + widths.sum(), y, rule)
            }
            tb.total?.let { t ->
                if (y + rowH > H - M - 6f) { newPage(); headerRow() }
                val c = canvas ?: return@let
                var x = M
                for (i in 0 until cols) { t.getOrNull(i)?.let { text(c, it.text, i, x, head) }; x += widths[i] }
                y += rowH
            }
            if (tb.note.isNotEmpty()) {
                if (y + 14f > H - M - 6f) newPage()
                canvas?.drawText(tb.note.take(190), M, y + 10f, subPaint)
                y += 14f
            }
            y += 16f
        }
        page?.let { doc.finishPage(it) }
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }
}
