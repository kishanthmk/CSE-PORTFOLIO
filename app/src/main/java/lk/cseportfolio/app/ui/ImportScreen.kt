package lk.cseportfolio.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.ByteArrayInputStream
import lk.cseportfolio.app.MainViewModel
import lk.cseportfolio.app.Nav
import lk.cseportfolio.app.UiState
import lk.cseportfolio.app.data.toCore
import lk.cseportfolio.core.BrokerStatement
import lk.cseportfolio.core.Csv
import lk.cseportfolio.core.Fmt
import lk.cseportfolio.core.ImportField
import lk.cseportfolio.core.PdfText
import lk.cseportfolio.core.ReportTable
import lk.cseportfolio.core.Reports
import lk.cseportfolio.core.StatementParser
import lk.cseportfolio.core.TxnImport
import lk.cseportfolio.core.TxnType
import lk.cseportfolio.core.Xlsx

@Composable
fun ImportScreen(st: UiState, vm: MainViewModel, nav: Nav) {
    var fileName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var statement by remember { mutableStateOf<BrokerStatement?>(null) }
    var rows by remember { mutableStateOf<List<List<String>>?>(null) }
    var headerRow by remember { mutableStateOf(0) }
    var mapping by remember { mutableStateOf<Map<ImportField, Int>>(emptyMap()) }
    var includeHoldings by remember { mutableStateOf(true) }
    val saver = rememberSaver(vm)

    val opener = rememberOpener(vm) { name, bytes ->
        fileName = name; error = ""; statement = null; rows = null
        try {
            val lower = name.lowercase()
            when {
                lower.endsWith(".pdf") || (bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "%PDF") -> {
                    val lines = PdfText.extractLines(bytes)
                    if (StatementParser.looksLikeGainLoss(lines)) statement = StatementParser.parse(lines)
                    else error = if (lines.isEmpty()) "No text could be read from this PDF. Scanned or image-only PDFs are not supported; ask your broker for an Excel or CSV export, or enter the figures by hand."
                        else "This PDF was read, but it is not a Portfolio Gain/Loss statement. For transaction histories, use the broker's Excel or CSV export."
                }
                lower.endsWith(".xls") -> error = "Old-format .xls workbooks are not supported. Open the file in Excel or Google Sheets and save it as .xlsx or .csv."
                else -> {
                    val table = if (lower.endsWith(".xlsx") || (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())) Xlsx.read(ByteArrayInputStream(bytes))
                                else Csv.parse(String(bytes, Charsets.UTF_8))
                    if (table.isEmpty()) error = "The file is empty."
                    else if (StatementParser.looksLikeGainLoss(table.map { it.joinToString(" ") })) statement = StatementParser.parse(table.map { it.joinToString(" ") })
                    else { headerRow = TxnImport.detectHeader(table); mapping = TxnImport.autoMap(table[headerRow]); rows = table }
                }
            }
        } catch (e: Exception) { error = "This file could not be read: ${e.message}" }
    }

    Screen("Import", onBack = { nav.back() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("Choose a file") {
                Text("• Broker Portfolio Gain/Loss statement (PDF): loads your current holdings as opening balances, with the statement's prices.\n" +
                     "• Excel (.xlsx) or CSV transaction history: loads individual buys and sells. Columns are matched automatically and can be adjusted.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { opener.open() }) { Text("Choose file") }
                    OutlinedButton(onClick = { saver.save("cse-transactions-template.xlsx") { Xlsx.write(listOf(ReportTable("Transactions", TxnImport.TEMPLATE_HEADERS, emptyList()))) } }) { Text("Blank template") }
                }
                if (fileName.isNotEmpty()) Text(fileName, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
            }
            if (error.isNotEmpty()) Banner(error, LocalTones.current.loss)

            statement?.let { s ->
                val already = s.holdings.filter { l -> st.txns.any { it.type == TxnType.OPENING && it.symbol == l.symbol && it.date == s.asAt && it.quantity == l.quantity } }
                Section("Portfolio Gain/Loss statement") {
                    KV("Broker", s.broker.ifEmpty { "–" }); KV("Account", s.account.ifEmpty { "–" }); KV("As at", s.asAt?.format(Reports.DATE) ?: "date not found")
                    KV("Holdings", "${s.holdings.size} of ${s.lines.size} securities listed")
                    s.totalCost?.let { KVMoney("Total cost on the statement", it) }
                    s.totalGainLoss?.let { KVMoney("Gain / loss on the statement", it, signed = true) }
                    s.impliedSellChargePct?.let { KV("Selling charges the broker assumed", Fmt.rate(it)) }
                    Spacer(Modifier.height(8.dp))
                    TableView(ReportTable("", listOf("Security", "Quantity", "Cost per share", "Cost", "Price", "Projected proceeds", "Gain / loss"),
                        s.lines.map { l -> listOf(Reports.t(l.symbol), Reports.n(l.quantity), Reports.price(l.costPerShare), Reports.m(l.cost), Reports.price(l.price), Reports.m(l.projectedProceeds), Reports.pl(l.gainLoss)) }))
                }
                if (already.isNotEmpty()) Banner("${already.size} of these holdings were already imported from a statement of the same date. Importing holdings again would double them, so holdings are switched off below.")
                LaunchedEffect(already.size) { if (already.isNotEmpty()) includeHoldings = false }
                Section {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Add the holdings as opening balances", style = MaterialTheme.typography.bodyLarge)
                            Text("Switch off to update prices only. Cost per share on the statement already includes purchase charges.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = includeHoldings, onCheckedChange = { includeHoldings = it })
                    }
                    Button(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), onClick = {
                        val holdings = includeHoldings
                        vm.run { val n = importStatement(s, holdings); vm.say(if (holdings) "Imported $n holdings and ${s.lines.size} prices." else "Updated ${s.lines.size} prices.") }
                        nav.back()
                    }) { Text(if (includeHoldings) "Import ${s.holdings.size} holdings and ${s.lines.size} prices" else "Update ${s.lines.size} prices only") }
                }
            }

            rows?.let { table ->
                val header = table.getOrElse(headerRow) { emptyList() }
                val existing = remember(st.txns) { st.txns.map { TxnImport.key(it.toCore()) }.toSet() }
                val preview = remember(table, headerRow, mapping, existing) { TxnImport.parse(table, headerRow, mapping, st.schedules, existing) }
                val missing = ImportField.values().filter { it.required && !mapping.containsKey(it) }
                Section("Match the columns") {
                    Text("Header found on row ${headerRow + 1}. Check that each item points at the right column of your file.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    ImportField.values().forEach { f ->
                        Picker(f.label + if (f.required) " *" else "", listOf<Int?>(null) + header.indices.toList(), mapping[f],
                            { i -> if (i == null) "— not in this file —" else header[i].ifEmpty { "Column ${i + 1}" } },
                            { i -> mapping = if (i == null) mapping - f else mapping + (f to i) }, Modifier.fillMaxWidth().padding(vertical = 3.dp))
                    }
                }
                if (missing.isNotEmpty()) Banner("Still needed: " + missing.joinToString(", ") { it.label } + ".", LocalTones.current.loss)
                Section("Check before importing") {
                    KV("Ready to import", preview.valid.size.toString(), bold = true)
                    KV("Duplicates skipped (already recorded, or repeated in the file)", preview.duplicates.toString())
                    KV("Rows with problems (not imported)", preview.errors.size.toString(), if (preview.errors.isEmpty()) MaterialTheme.colorScheme.onSurface else LocalTones.current.loss)
                    val est = preview.valid.count { it.feesEstimated }
                    if (est > 0) Text("$est row(s) have no charges in the file, so charges were calculated from your fee schedule for their trade date. Check them against the contract notes.",
                        style = MaterialTheme.typography.bodySmall, color = LocalTones.current.warn)
                    preview.errors.take(8).forEach { e -> Text("Row ${e.row}: ${e.error}", style = MaterialTheme.typography.labelSmall, color = LocalTones.current.loss) }
                    if (preview.errors.size > 8) Text("…and ${preview.errors.size - 8} more.", style = MaterialTheme.typography.labelSmall, color = LocalTones.current.loss)
                    Spacer(Modifier.height(8.dp))
                    TableView(ReportTable("", listOf("Date", "Symbol", "Type", "Quantity", "Price", "Charges", "Total"), preview.valid.take(25).map { v ->
                        val t = v.txn!!
                        listOf(Reports.t(t.date.format(Reports.DATE)), Reports.t(t.symbol), Reports.t(t.type.label), Reports.n(t.quantity), Reports.price(t.price), Reports.m(t.charges),
                            Reports.m(if (t.type == TxnType.SELL) t.netProceeds else t.totalCost))
                    }, note = if (preview.valid.size > 25) "First 25 of ${preview.valid.size} shown." else ""))
                    Button(enabled = preview.valid.isNotEmpty() && missing.isEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp), onClick = {
                        val items = preview.valid
                        vm.run { val n = importTxns(items, st.schedules); vm.say("Imported $n transactions.") }
                        nav.back()
                    }) { Text("Import ${preview.valid.size} transactions") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
