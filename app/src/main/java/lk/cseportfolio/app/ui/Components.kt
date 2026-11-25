package lk.cseportfolio.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import lk.cseportfolio.core.Cell
import lk.cseportfolio.core.Fmt
import lk.cseportfolio.core.ReportTable
import lk.cseportfolio.core.Reports

// ---------------------------------------------------------------- theme

class Tones(val gain: Color, val loss: Color, val info: Color, val warn: Color)

val LocalTones = staticCompositionLocalOf { Tones(Color(0xFF16A34A), Color(0xFFDC2626), Color(0xFF2563EB), Color(0xFFD97706)) }

/** TradingView-inspired dark palette: black canvas, charcoal cards, blue accent, teal-green and red for moves. */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF2962FF), onPrimary = Color.White, primaryContainer = Color(0xFF142A66), onPrimaryContainer = Color(0xFFD6E2FF),
    secondary = Color(0xFF9598A1), onSecondary = Color(0xFF000000), secondaryContainer = Color(0xFF2A2E39), onSecondaryContainer = Color(0xFFD1D4DC),
    background = Color(0xFF000000), onBackground = Color(0xFFF0F3FA), surface = Color(0xFF000000), onSurface = Color(0xFFF0F3FA),
    surfaceVariant = Color(0xFF1E222D), onSurfaceVariant = Color(0xFF9598A1), outline = Color(0xFF434651), outlineVariant = Color(0xFF2A2E39),
    surfaceContainer = Color(0xFF131722), surfaceContainerHigh = Color(0xFF1E222D), surfaceContainerHighest = Color(0xFF2A2E39),
    surfaceContainerLow = Color(0xFF0C0E15), surfaceContainerLowest = Color(0xFF000000),
    error = Color(0xFFF23645), onError = Color.White, errorContainer = Color(0xFF4C1418), onErrorContainer = Color(0xFFFFCDD2)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1D4ED8), onPrimary = Color.White, primaryContainer = Color(0xFFDBE7FF), onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = Color(0xFF475569), onSecondary = Color.White, secondaryContainer = Color(0xFFE2E8F0), onSecondaryContainer = Color(0xFF1E293B),
    background = Color(0xFFF4F6FA), onBackground = Color(0xFF0F172A), surface = Color(0xFFF4F6FA), onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE8ECF3), onSurfaceVariant = Color(0xFF55627A), outline = Color(0xFFB6C0D1), outlineVariant = Color(0xFFDCE2EC),
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerHighest = Color(0xFFEDF1F7),
    surfaceContainerLow = Color.White, surfaceContainerLowest = Color.White,
    error = Color(0xFFB91C1C), onError = Color.White, errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF7F1D1D)
)

@Composable
fun AppTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val tones = if (dark) Tones(Color(0xFF089981), Color(0xFFF23645), Color(0xFF2962FF), Color(0xFFFF9800))
                else Tones(Color(0xFF15803D), Color(0xFFDC2626), Color(0xFF1D4ED8), Color(0xFFB45309))
    CompositionLocalProvider(LocalTones provides tones) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}

/** Figures use tabular monospace digits so columns of numbers line up. */
val NumStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

@Composable
fun toneColor(v: BigDecimal?): Color {
    val t = LocalTones.current
    return when { v == null || v.signum() == 0 -> MaterialTheme.colorScheme.onSurface; v.signum() > 0 -> t.gain; else -> t.loss }
}

// ---------------------------------------------------------------- scaffolding

@Composable
fun Screen(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) },
            navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        content()
    }
}

@Composable
fun Section(title: String? = null, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (trailing != null) trailing()
                }
            }
            content()
        }
    }
}

@Composable
fun KV(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Text(value, style = NumStyle.copy(fontSize = 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal), color = color, textAlign = TextAlign.End)
    }
}

@Composable
fun KVMoney(label: String, v: BigDecimal?, signed: Boolean = false, bold: Boolean = false) {
    if (v == null) KV(label, "–") else KV(label, if (signed) Fmt.signed(v) else Fmt.money(v), if (signed) toneColor(v) else MaterialTheme.colorScheme.onSurface, bold)
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null, color: Color = MaterialTheme.colorScheme.onSurface) {
    Card(modifier = modifier, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(value, style = NumStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun Banner(text: String, color: Color = LocalTones.current.warn, icon: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.14f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { icon(); Spacer(Modifier.width(10.dp)) }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun EmptyState(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(16.dp)); Button(onClick = onAction) { Text(action) } }
    }
}

// ---------------------------------------------------------------- inputs

@Composable
fun NumField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, whole: Boolean = false, enabled: Boolean = true, supporting: String? = null, signed: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { s -> onChange(s.filterIndexed { i, c -> c.isDigit() || (!whole && c == '.') || (signed && i == 0 && c == '-') }) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        textStyle = NumStyle.copy(fontSize = 15.sp),
        keyboardOptions = KeyboardOptions(keyboardType = if (signed) KeyboardType.Text else if (whole) KeyboardType.Number else KeyboardType.Decimal),
        supportingText = if (supporting != null) { { Text(supporting) } } else null,
        modifier = modifier
    )
}

@Composable
fun TextInput(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, singleLine: Boolean = true, caps: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(if (caps) it.uppercase() else it) }, label = { Text(label) },
        singleLine = singleLine, modifier = modifier
    )
}

@Composable
fun DateField(date: LocalDate?, onChange: (LocalDate) -> Unit, label: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = date?.format(Reports.DATE) ?: "", onValueChange = {}, readOnly = true, label = { Text(label) }, singleLine = true,
            trailingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null) }, modifier = Modifier.fillMaxWidth()
        )
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
}

@Composable
fun <T> Picker(label: String, options: List<T>, selected: T?, text: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    // a null selection is shown as blank unless null is itself one of the options (e.g. "All companies")
    @Suppress("UNCHECKED_CAST")
    val shown = if (selected != null || options.any { it == null }) text(selected as T) else ""
    Box(modifier) {
        OutlinedTextField(
            value = shown, onValueChange = {}, readOnly = true, label = { Text(label) }, singleLine = true,
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) }, modifier = Modifier.fillMaxWidth()
        )
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { onSelect(o); open = false }) }
        }
    }
}

@Composable
fun <T> Chips(options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(text(o)) }) }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------- tables

@Composable
private fun CellText(c: Cell, width: Int, numeric: Boolean, bold: Boolean) {
    Text(
        c.text,
        style = if (numeric) NumStyle.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
                else MaterialTheme.typography.bodySmall.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
        color = if (c.tone == 1) toneColor(c.num) else MaterialTheme.colorScheme.onSurface,
        textAlign = if (numeric) TextAlign.End else TextAlign.Start,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width.dp).padding(horizontal = 6.dp, vertical = 7.dp)
    )
}

/** Renders a report table with horizontal scrolling. Long tables are capped on screen; exports always contain every row. */
@Composable
fun TableView(table: ReportTable, maxRows: Int = 150) {
    val widths = remember(table) {
        table.headers.indices.map { c ->
            val longest = maxOf(table.headers[c].length, table.rows.maxOfOrNull { it.getOrNull(c)?.text?.length ?: 0 } ?: 0, table.total?.getOrNull(c)?.text?.length ?: 0)
            (longest * 8 + 22).coerceIn(64, 240)
        }
    }
    val numeric = remember(table) { table.headers.indices.map { table.isNumeric(it) } }
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))) {
            table.headers.forEachIndexed { c, h ->
                Text(h, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = if (numeric[c]) TextAlign.End else TextAlign.Start, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(widths[c].dp).padding(horizontal = 6.dp, vertical = 8.dp))
            }
        }
        if (table.rows.isEmpty()) Text("No entries", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        table.rows.take(maxRows).forEach { r ->
            Row { r.forEachIndexed { c, cell -> CellText(cell, widths.getOrElse(c) { 90 }, numeric.getOrElse(c) { false }, false) } }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        table.total?.let { t -> Row { t.forEachIndexed { c, cell -> CellText(cell, widths.getOrElse(c) { 90 }, numeric.getOrElse(c) { false }, true) } } }
    }
    if (table.rows.size > maxRows) Text("Showing the first $maxRows of ${table.rows.size} rows. Export to Excel or PDF for the full table.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    if (table.note.isNotEmpty()) Text(table.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

// ---------------------------------------------------------------- charts

val ChartPalette = listOf(Color(0xFF3B82F6), Color(0xFF14B8A6), Color(0xFFF59E0B), Color(0xFFA855F7), Color(0xFFEC4899),
    Color(0xFF22C55E), Color(0xFFF97316), Color(0xFF06B6D4), Color(0xFF84CC16), Color(0xFF64748B))

class Slice(val label: String, val value: Float, val detail: String)

/** Donut with a tappable legend: tap an entry to highlight its slice. */
@Composable
fun DonutChart(slices: List<Slice>, centerLabel: String, centerValue: String) {
    val total = slices.sumOf { it.value.toDouble() }.toFloat()
    if (slices.isEmpty() || total <= 0f) { Text("Nothing to chart yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall); return }
    var sel by remember(slices.size) { mutableStateOf(-1) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                val base = 22.dp.toPx()
                drawArc(track, 0f, 360f, false, style = Stroke(base), topLeft = Offset(base / 2, base / 2), size = Size(size.width - base, size.height - base))
                var start = -90f
                slices.forEachIndexed { i, s ->
                    val sweep = s.value / total * 360f
                    val w = if (i == sel) base * 1.35f else base
                    drawArc(ChartPalette[i % ChartPalette.size].copy(alpha = if (sel == -1 || sel == i) 1f else 0.35f), start + 1f, (sweep - 2f).coerceAtLeast(0.5f), false,
                        style = Stroke(w), topLeft = Offset(base / 2, base / 2), size = Size(size.width - base, size.height - base))
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (sel >= 0) slices[sel].label else centerLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(if (sel >= 0) "%.1f%%".format(slices[sel].value / total * 100f) else centerValue, style = NumStyle.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            slices.forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { sel = if (sel == i) -1 else i }
                    .background(if (sel == i) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent).padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(ChartPalette[i % ChartPalette.size]))
                    Spacer(Modifier.width(8.dp))
                    Text(s.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.detail, style = NumStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

class Bar(val label: String, val value: BigDecimal)

/** Signed bar chart (green above zero, red below). Tap a bar to read its value. */
@Composable
fun BarChart(bars: List<Bar>, height: Int = 150) {
    if (bars.isEmpty()) { Text("Nothing to chart yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall); return }
    val tones = LocalTones.current
    var sel by remember(bars.size) { mutableStateOf(-1) }
    val max = bars.maxOf { it.value.abs() }.toFloat().coerceAtLeast(0.01f)
    val hasNeg = bars.any { it.value.signum() < 0 }
    val hasPos = bars.any { it.value.signum() > 0 }
    val posH = if (hasPos && hasNeg) height / 2 else if (hasPos) height else 0
    val negH = if (hasPos && hasNeg) height / 2 else if (hasNeg) height else 0
    Text(if (sel in bars.indices) "${bars[sel].label}: LKR ${Fmt.signed(bars[sel].value)}" else "Tap a bar for its value",
        style = MaterialTheme.typography.labelMedium, color = if (sel in bars.indices) toneColor(bars[sel].value) else MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.Top) {
        bars.forEachIndexed { i, b ->
            val frac = b.value.abs().toFloat() / max
            Column(Modifier.width(44.dp).clip(RoundedCornerShape(6.dp)).clickable { sel = if (sel == i) -1 else i }
                .background(if (sel == i) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(posH.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    if (b.value.signum() > 0) Box(Modifier.width(22.dp).height((posH * frac).coerceAtLeast(2f).dp).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(tones.gain))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Box(Modifier.height(negH.dp).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    if (b.value.signum() < 0) Box(Modifier.width(22.dp).height((negH * frac).coerceAtLeast(2f).dp).clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)).background(tones.loss))
                }
                Text(b.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Line chart of a running total, with its low, high and latest values labelled. */
@Composable
fun LineChart(points: List<BigDecimal>, firstLabel: String, lastLabel: String) {
    if (points.size < 2) { Text("At least two periods are needed for a trend line.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall); return }
    val tones = LocalTones.current
    val line = if (points.last().signum() >= 0) tones.gain else tones.loss
    val grid = MaterialTheme.colorScheme.outlineVariant
    val min = points.minOf { it }.min(BigDecimal.ZERO)
    val max = points.maxOf { it }.max(BigDecimal.ZERO)
    val span = max.subtract(min).toFloat().coerceAtLeast(0.01f)
    Row(Modifier.fillMaxWidth()) {
        Text("High ${Fmt.compact(points.maxOf { it })}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("Latest ${Fmt.compact(points.last())}", style = MaterialTheme.typography.labelSmall, color = line)
    }
    Canvas(Modifier.fillMaxWidth().height(140.dp).padding(vertical = 8.dp)) {
        fun y(v: BigDecimal) = size.height - (v.subtract(min).toFloat() / span) * size.height
        val zero = y(BigDecimal.ZERO)
        drawLine(grid, Offset(0f, zero), Offset(size.width, zero), strokeWidth = 1.dp.toPx())
        val step = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(0f, y(p)) else path.lineTo(i * step, y(p)) }
        drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(line, 4.dp.toPx(), Offset(size.width, y(points.last())))
    }
    Row(Modifier.fillMaxWidth()) {
        Text(firstLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(lastLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("Low ${Fmt.compact(points.minOf { it })}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
