package lk.cseportfolio.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import lk.cseportfolio.core.Candle
import lk.cseportfolio.core.NumFmt
import lk.cseportfolio.core.sma

private val TV_GRID = Color(0xFF1E222D)
private val MA_FAST = Color(0xFF2962FF)
private val MA_SLOW = Color(0xFFFF9800)

private fun label(t: Long, intraday: Boolean): String =
    Instant.ofEpochMilli(t).atZone(ZoneId.of("Asia/Colombo")).format(DateTimeFormatter.ofPattern(if (intraday) "dd MMM HH:mm" else "dd MMM yyyy", Locale.ENGLISH))

/**
 * TradingView-style price chart: candlesticks or a line, optional 20/50 moving averages, a price
 * scale on the right and a crosshair that follows your finger and shows the bar's figures.
 */
@Composable
fun PriceChart(candles: List<Candle>, intraday: Boolean, height: Int = 280) {
    var candleMode by remember { mutableStateOf(true) }
    var showMa by remember { mutableStateOf(true) }
    var sel by remember(candles) { mutableStateOf(-1) }
    val tones = LocalTones.current
    if (candles.size < 2) {
        Box(Modifier.fillMaxWidth().height(height.dp), contentAlignment = Alignment.Center) {
            Text("Not enough price history for this range.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val lineOnly = candles.all { it.o == it.c && it.h == it.c && it.l == it.c }
    val maFast = remember(candles) { sma(candles, 20) }
    val maSlow = remember(candles) { sma(candles, 50) }
    val hi = candles.maxOf { it.h }; val lo = candles.minOf { it.l }
    val pad = ((hi - lo) * 0.06).let { if (it == 0.0) hi * 0.01 + 0.01 else it }
    val top = hi + pad; val bottom = lo - pad
    val shown = if (sel in candles.indices) candles[sel] else candles.last()
    val first = candles.first().c
    val up = candles.last().c >= first

    // header: the bar under the crosshair, or the latest bar
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label(shown.t, intraday), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        val c = if (shown.c >= shown.o) tones.gain else tones.loss
        Text(if (lineOnly) NumFmt.price(shown.c) else "O ${NumFmt.price(shown.o)}  H ${NumFmt.price(shown.h)}  L ${NumFmt.price(shown.l)}  C ${NumFmt.price(shown.c)}",
            style = NumStyle.copy(fontSize = 11.sp), color = c)
    }
    Row(Modifier.fillMaxWidth().height(height.dp)) {
        Canvas(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer)
            .pointerInput(candles) {
                detectTapGestures(onTap = { o -> sel = ((o.x / size.width) * candles.size).toInt().coerceIn(0, candles.size - 1) })
            }
            .pointerInput(candles) {
                detectDragGestures(onDragStart = { o -> sel = ((o.x / size.width) * candles.size).toInt().coerceIn(0, candles.size - 1) },
                    onDragEnd = { sel = -1 }, onDragCancel = { sel = -1 },
                    onDrag = { change, _ -> sel = ((change.position.x / size.width) * candles.size).toInt().coerceIn(0, candles.size - 1) })
            }) {
            val w = size.width; val h = size.height
            fun y(v: Double) = ((top - v) / (top - bottom) * h).toFloat()
            for (k in 1..4) { val gy = h * k / 5f; drawLine(TV_GRID, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f) }
            val step = w / candles.size
            if (candleMode && !lineOnly) {
                val body = (step * 0.7f).coerceAtLeast(1f)
                candles.forEachIndexed { i, c ->
                    val x = i * step + step / 2
                    val col = if (c.c >= c.o) tones.gain else tones.loss
                    drawLine(col, Offset(x, y(c.h)), Offset(x, y(c.l)), strokeWidth = 1.5f)
                    val t = y(maxOf(c.o, c.c)); val b = y(minOf(c.o, c.c))
                    drawRect(col, topLeft = Offset(x - body / 2, t), size = Size(body, (b - t).coerceAtLeast(1.5f)))
                }
            } else {
                val path = Path()
                candles.forEachIndexed { i, c -> val x = i * step + step / 2; if (i == 0) path.moveTo(x, y(c.c)) else path.lineTo(x, y(c.c)) }
                drawPath(path, if (up) tones.gain else tones.loss, style = Stroke(width = 2.5f))
            }
            if (showMa) for ((series, col) in listOf(maFast to MA_FAST, maSlow to MA_SLOW)) {
                val path = Path(); var started = false
                series.forEachIndexed { i, v -> if (v != null) { val x = i * step + step / 2; if (!started) { path.moveTo(x, y(v)); started = true } else path.lineTo(x, y(v)) } }
                if (started) drawPath(path, col, style = Stroke(width = 1.5f))
            }
            val last = candles.last().c
            drawLine(if (up) tones.gain else tones.loss, Offset(0f, y(last)), Offset(w, y(last)), strokeWidth = 1f)
            if (sel in candles.indices) {
                val x = sel * step + step / 2
                drawLine(Color(0xFF9598A1), Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                drawLine(Color(0xFF9598A1), Offset(0f, y(candles[sel].c)), Offset(w, y(candles[sel].c)), strokeWidth = 1f)
            }
        }
        Column(Modifier.width(64.dp).fillMaxHeight().padding(start = 4.dp), verticalArrangement = Arrangement.SpaceBetween) {
            for (k in 0..5) Text(NumFmt.price(top - (top - bottom) * k / 5), style = NumStyle.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(label(candles.first().t, intraday), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(label(candles.last().t, intraday), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        if (!lineOnly) { FilterChip(selected = candleMode, onClick = { candleMode = !candleMode }, label = { Text(if (candleMode) "Candles" else "Line") }); Spacer(Modifier.width(8.dp)) }
        FilterChip(selected = showMa, onClick = { showMa = !showMa }, label = { Text("MA 20 / 50") })
        Spacer(Modifier.width(8.dp))
        if (showMa) Text("■ 20", color = MA_FAST, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        if (showMa) Text("  ■ 50", color = MA_SLOW, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
    val change = candles.last().c - first
    Text("Change over this range: ${NumFmt.signed(change)} (${NumFmt.pct(if (first != 0.0) change / first * 100 else null)}). Hold and drag on the chart to read any bar.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
}

/** Small sparkline for list rows and cards. */
@Composable
fun Sparkline(points: List<Double>, modifier: Modifier = Modifier) {
    if (points.size < 2) return
    val tones = LocalTones.current
    val up = points.last() >= points.first()
    Canvas(modifier) {
        val hi = points.max(); val lo = points.min(); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val path = Path()
        points.forEachIndexed { i, v -> val x = size.width * i / (points.size - 1); val y = (size.height * (hi - v) / span).toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        drawPath(path, if (up) tones.gain else tones.loss, style = Stroke(width = 2f))
    }
}

@Composable
fun RangeChips(ranges: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ranges.forEach { r ->
            val on = r == selected
            Box(Modifier.background(if (on) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent, RoundedCornerShape(6.dp))
                .pointerInput(r) { detectTapGestures(onTap = { onSelect(r) }) }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(r, style = MaterialTheme.typography.labelMedium, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
