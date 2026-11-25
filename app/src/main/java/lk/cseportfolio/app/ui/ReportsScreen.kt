package lk.cseportfolio.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import lk.cseportfolio.app.MainViewModel
import lk.cseportfolio.app.Nav
import lk.cseportfolio.app.UiState
import lk.cseportfolio.app.data.toCore
import lk.cseportfolio.app.export.PdfReport
import lk.cseportfolio.core.Csv
import lk.cseportfolio.core.Fmt
import lk.cseportfolio.core.ReportTable
import lk.cseportfolio.core.Reports
import lk.cseportfolio.core.TxnImport
import lk.cseportfolio.core.Xlsx
import lk.cseportfolio.core.pctOf

private fun allTables(st: UiState): List<ReportTable> {
    val r = st.result
    val s = st.summary
    return listOf(
        Reports.holdings(s), Reports.companyAnalysis(r, st.quotes, st.fees), Reports.monthly(r), Reports.annual(r), Reports.charges(r),
        Reports.cashVsMargin(r), Reports.trades(r, r.sells.sortedByDescending { it.realized }, "Best and worst trades"), Reports.allocation(s),
        Reports.marginSummary(s.margin), Reports.ledger(r.ledger)
    )
}

@Composable
fun ReportsScreen(st: UiState, vm: MainViewModel, nav: Nav) {
    var tab by remember { mutableStateOf(0) }
    Screen("Reports") {
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf("Charts", "Tables", "Export").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (tab) { 0 -> ChartsTab(st); 1 -> TablesTab(st); else -> ExportTab(st, vm) }
            Spacer(Modifier.height(88.dp))
        }
    }
}

@Composable
private fun ChartsTab(st: UiState) {
    val r = st.result
    val s = st.summary
    if (st.txns.isEmpty()) { EmptyState("No data yet", "Charts appear once you have recorded transactions."); return }
    val short: (String) -> String = { it.substringBefore('.') }
    Section("Key figures") {
        KV("Average holding period of shares sold", Reports.averageHoldingDays(r)?.let { "$it days" } ?: "–")
        val best = r.sells.maxByOrNull { it.realized }
        val worst = r.sells.minByOrNull { it.realized }
        KV("Best trade", best?.let { "${short(it.symbol)} ${Fmt.signed(it.realized)}" } ?: "–", toneColor(best?.realized))
        KV("Worst trade", worst?.let { "${short(it.symbol)} ${Fmt.signed(it.realized)}" } ?: "–", toneColor(worst?.realized))
        KVMoney("Total brokerage paid", r.feeTotals.brokerage)
        KVMoney("Total taxes and exchange charges", r.feeTotals.total.subtract(r.feeTotals.brokerage))
        val note = Reports.concentrationNote(s)
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
    if (s.holdings.isNotEmpty()) Section("Investment allocation by company (market value)") {
        DonutChart(s.holdings.take(9).map { Slice(short(it.symbol), it.marketValue.toFloat(), Fmt.pct(pctOf(it.marketValue, s.marketValue)).removePrefix("+")) }, "Holdings", Fmt.compact(s.marketValue))
    }
    val realized = r.positions.filter { it.realized.signum() != 0 }
    if (realized.isNotEmpty()) Section("Realized profit / loss by stock") { BarChart(realized.map { Bar(short(it.symbol), it.realized) }) }
    val unreal = s.holdings.filter { it.priced }
    if (unreal.isNotEmpty()) Section("Unrealized profit / loss by stock") { BarChart(unreal.map { Bar(short(it.symbol), it.unrealized) }) }
    if (r.monthly.isNotEmpty()) {
        val fmt = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH)
        Section("Monthly net profit / loss") { BarChart(r.monthly.entries.toList().takeLast(24).map { Bar(it.key.format(fmt), it.value.net) }) }
        Section("Cumulative realized result") {
            var run = BigDecimal.ZERO
            val pts = r.monthly.values.map { run = run.add(it.net); run }
            LineChart(pts, r.monthly.firstKey().format(fmt), r.monthly.lastKey().format(fmt))
        }
        val years = r.yearly()
        if (years.size > 1) Section("Annual net profit / loss") { BarChart(years.map { Bar(it.key.toString(), it.value.net) }) }
    }
    val funded = r.cashFunded.add(r.marginFunded)
    if (r.marginFunded.signum() > 0) Section("Cash versus margin funding") {
        DonutChart(listOf(Slice("Own cash", r.cashFunded.toFloat(), Fmt.pct(pctOf(r.cashFunded, funded)).removePrefix("+")),
            Slice("Margin loan", r.marginFunded.toFloat(), Fmt.pct(pctOf(r.marginFunded, funded)).removePrefix("+"))), "Invested", Fmt.compact(funded))
    }
    val f = r.feeTotals
    if (f.total.signum() > 0) Section("Where the charges went") {
        DonutChart(listOf("Brokerage" to f.brokerage, "CSE" to f.cse, "SEC" to f.sec, "CDS" to f.cds, "Levy / tax" to f.tax, "Other" to f.other)
            .filter { it.second.signum() > 0 }.map { Slice(it.first, it.second.toFloat(), Fmt.compact(it.second)) }, "Charges", Fmt.compact(f.total))
    }
}

@Composable
private fun TablesTab(st: UiState) {
    val tables = remember(st.result, st.summary) { allTables(st) }
    var selected by remember { mutableStateOf(0) }
    Picker("Report", tables.indices.toList(), selected, { tables[it].title }, { selected = it }, Modifier.fillMaxWidth())
    Section(tables[selected.coerceIn(tables.indices)].title) { TableView(tables[selected.coerceIn(tables.indices)]) }
}

@Composable
private fun ExportTab(st: UiState, vm: MainViewModel) {
    val r = st.result
    val saver = rememberSaver(vm)
    val asAt = "As at ${r.asOf.format(Reports.DATE)}"
    val names = st.companies.mapValues { it.value.name }
    val fmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

    Section("Complete portfolio") {
        Text("Every report as a separate sheet, plus a transactions sheet that can be imported back into the app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { saver.save("cse-portfolio-${r.asOf}.xlsx") { Xlsx.write(allTables(st) + TxnImport.exportRows(st.txns.map { it.toCore() }, names)) } }) { Text("Excel workbook") }
            OutlinedButton(onClick = { saver.save("cse-portfolio-${r.asOf}.pdf") { PdfReport.render("Portfolio report", asAt, allTables(st).dropLast(1)) } }) { Text("PDF") }
        }
        TextButton(onClick = { saver.save("transactions.csv") { Csv.write(Csv.fromTable(TxnImport.exportRows(st.txns.map { it.toCore() }, names))).toByteArray() } }) { Text("Transactions as CSV") }
    }

    val months = r.monthly.keys.toList().reversed()
    if (months.isNotEmpty()) Section("Monthly profit / loss report") {
        var ym by remember { mutableStateOf(months.first()) }
        Picker("Month", months, ym, { it.format(fmt) }, { ym = it }, Modifier.fillMaxWidth())
        Button(onClick = { saver.save("profit-loss-$ym.pdf") { PdfReport.render("Monthly profit and loss report", ym.format(fmt), Reports.monthlyReport(r, ym)) } }, modifier = Modifier.padding(top = 8.dp)) { Text("Download PDF") }
    }

    val years = r.yearly().keys.toList().reversed()
    if (years.isNotEmpty()) Section("Annual investment summary") {
        var year by remember { mutableStateOf(years.first()) }
        Picker("Year", years, year, { it.toString() }, { year = it }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { saver.save("annual-summary-$year.pdf") { PdfReport.render("Annual investment summary", year.toString(), Reports.annualReport(r, st.summary, year)) } }) { Text("PDF") }
            OutlinedButton(onClick = { saver.save("annual-summary-$year.xlsx") { Xlsx.write(Reports.annualReport(r, st.summary, year)) } }) { Text("Excel") }
        }
    }

    val traded = r.positions.map { it.symbol }
    if (traded.isNotEmpty()) Section("Company transaction statement") {
        var sym by remember { mutableStateOf(traded.first()) }
        Picker("Company", traded, sym, { it + st.name(it).let { n -> if (n.isEmpty()) "" else " – $n" } }, { sym = it }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { saver.save("$sym-statement.pdf") { PdfReport.render("Transaction statement – $sym", st.name(sym), listOf(Reports.companyStatement(r, sym))) } }) { Text("PDF") }
            OutlinedButton(onClick = { saver.save("$sym-statement.xlsx") { Xlsx.write(listOf(Reports.companyStatement(r, sym))) } }) { Text("Excel") }
        }
    }
}
