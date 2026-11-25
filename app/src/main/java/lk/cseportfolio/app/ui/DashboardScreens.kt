package lk.cseportfolio.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import lk.cseportfolio.app.MainViewModel
import lk.cseportfolio.app.Nav
import lk.cseportfolio.app.UiState
import lk.cseportfolio.app.data.CompanyEntity
import lk.cseportfolio.app.export.PdfReport
import lk.cseportfolio.core.Fmt
import lk.cseportfolio.core.Holding
import lk.cseportfolio.core.Quote
import lk.cseportfolio.core.Reports
import lk.cseportfolio.core.Valuation
import lk.cseportfolio.core.Xlsx
import lk.cseportfolio.core.pctOf

fun stamp(millis: Long): String = if (millis <= 0) "never" else SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ENGLISH).format(Date(millis))

@Composable
fun TilePair(a: @Composable (Modifier) -> Unit, b: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { a(Modifier.weight(1f)); b(Modifier.weight(1f)) }
}

@Composable
fun DashboardScreen(st: UiState, vm: MainViewModel, nav: Nav) {
    val s = st.summary
    val r = st.result
    val tones = LocalTones.current
    Screen("CSE Portfolio Pro", actions = {
        IconButton(onClick = { nav.go("margin") }) { Icon(Icons.Filled.AccountBalance, contentDescription = "Margin") }
        if (st.settings.hasPriceSource) IconButton(onClick = { vm.refreshPrices(emptyList()) }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh prices") }
        IconButton(onClick = { nav.go("watchlist") }) { Icon(Icons.Filled.Star, contentDescription = "Watchlist") }
        IconButton(onClick = { nav.simulator() }) { Icon(Icons.Filled.Calculate, contentDescription = "Sell price simulator") }
        IconButton(onClick = { nav.go("settings") }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
    }) {
        if (st.txns.isEmpty() && st.cash.isEmpty()) {
            EmptyState("Welcome", "Record your first purchase with the + button, or import your broker's statement to load your current holdings.",
                "Import a statement") { nav.go("import") }
            TextButton(onClick = { nav.market() }, modifier = Modifier.padding(horizontal = 32.dp)) { Text("Browse the CSE market and company research") }
            return@Screen
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section {
                Text("Net portfolio equity", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Fmt.lkr(s.netEquity), style = NumStyle.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Total profit / loss  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Fmt.signed(s.totalProfit) + "  (" + Fmt.pct(s.returnPct) + ")", style = NumStyle.copy(fontWeight = FontWeight.SemiBold), color = toneColor(s.totalProfit))
                }
                Text("Market value + cash − margin loan. Return is measured on everything you have invested.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            r.issues.take(3).forEach { Banner(it, tones.loss) }
            s.margin.forEach { m ->
                if (m.marginCall) Banner("Margin call level reached on ${m.state.account.broker}: the loan is ${Fmt.pct(m.ltvPct).removePrefix("+")} of collateral (limit ${Fmt.rate(m.state.account.marginCallLtvPct)}).", tones.loss)
                else if (m.utilizationWarning) Banner("${m.state.account.broker}: margin utilisation is ${Fmt.pct(m.state.utilizationPct).removePrefix("+")} of the facility (warning level ${Fmt.rate(m.state.account.warnUtilizationPct)}).")
            }
            if (s.unpricedCount > 0) Banner("${s.unpricedCount} holding(s) have no market price yet and are shown at cost. Open the stock to enter a price.", tones.info)

            TilePair({ StatTile("Market value", Fmt.money(s.marketValue), it) }, { StatTile("Investment cost", Fmt.money(s.costOfHoldings), it) })
            TilePair({ StatTile("Unrealized P/L", Fmt.signed(s.unrealized), it, Fmt.pct(pctOf(s.unrealized, s.costOfHoldings)), toneColor(s.unrealized)) },
                { StatTile("Realized P/L", Fmt.signed(s.realized), it, "net of all charges", toneColor(s.realized)) })
            TilePair({ StatTile("Cash balance", Fmt.money(s.cash), it, color = if (s.cash.signum() < 0) tones.loss else MaterialTheme.colorScheme.onSurface) },
                { StatTile("Margin loan", Fmt.money(s.loan), it, if (s.loan.signum() > 0) "incl. unpaid interest" else null, if (s.loan.signum() > 0) tones.loss else MaterialTheme.colorScheme.onSurface) })
            TilePair({ StatTile("Buying power", Fmt.money(s.buyingPower), it, "cash + unused margin") }, { StatTile("Charges paid", Fmt.money(s.charges), it, "brokerage, fees, levies") })
            if (s.cash.signum() < 0) Text("Cash is negative because purchases were recorded without a deposit. Add your deposits with + ▸ Deposit to reconcile cash.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Section("Performance") {
                if (s.dayChange != null) KVMoney("Today's gain / loss", s.dayChange, signed = true) else KV("Today's gain / loss", "needs previous close")
                KVMoney("This month (realized, dividends, interest)", s.monthPL, signed = true)
                KVMoney("Year to date", s.ytdPL, signed = true)
                KV("Total investment return", Fmt.pct(s.returnPct), toneColor(s.returnPct))
                KV("If sold today, after charges", Fmt.signed(s.netUnrealized), toneColor(s.netUnrealized))
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                KV("Best-performing stock", s.best?.let { "${it.symbol}  ${Fmt.pct(it.totalReturnPct)}" } ?: "–", toneColor(s.best?.totalReturnPct))
                KV("Worst-performing stock", s.worst?.let { "${it.symbol}  ${Fmt.pct(it.totalReturnPct)}" } ?: "–", toneColor(s.worst?.totalReturnPct))
                KV("Companies held", s.companiesHeld.toString())
            }

            if (s.holdings.isNotEmpty()) Section("Allocation by market value") {
                val top = s.holdings.take(8)
                val rest = s.holdings.drop(8).map { it.marketValue }.fold(BigDecimal.ZERO) { a, b -> a.add(b) }
                val slices = top.map { Slice(it.symbol.substringBefore('.'), it.marketValue.toFloat(), Fmt.pct(pctOf(it.marketValue, s.marketValue)).removePrefix("+")) } +
                    (if (rest.signum() > 0) listOf(Slice("Others", rest.toFloat(), Fmt.pct(pctOf(rest, s.marketValue)).removePrefix("+"))) else emptyList())
                DonutChart(slices, "Holdings", Fmt.compact(s.marketValue))
            }

            val months = r.monthly.entries.toList().takeLast(12)
            if (months.isNotEmpty()) Section("Monthly net profit / loss") {
                BarChart(months.map { Bar(it.key.format(DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH)), it.value.net) })
            }

            MarketsTodayCard(vm, nav)
            Section("CSE market and company research") {
                Text("Market Watch, the company directory, announcements, dividends, financial ratios and company comparison.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    listOf("Market watch" to 0, "Announcements" to 2, "Dividends" to 3, "Ratios" to 4, "Compare" to 5).forEach { (label, tab) -> AssistChip(onClick = { nav.market(tab) }, label = { Text(label) }) }
                }
            }
            if (s.holdings.isNotEmpty()) Section("Holdings", trailing = { TextButton(onClick = { nav.tab("portfolio") }) { Text("View all") } }) {
                s.holdings.take(6).forEach { h -> HoldingRow(h, st.name(h.symbol)) { nav.stock(h.symbol) } }
            }
            Spacer(Modifier.height(88.dp))
        }
    }
}

@Composable
fun HoldingRow(h: Holding, name: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(h.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(if (name.isNotEmpty()) name else "${Fmt.qty(h.position.quantity)} shares", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${Fmt.qty(h.position.quantity)} @ ${Fmt.money(h.position.avgCost.setScale(2, java.math.RoundingMode.HALF_UP))}" + (h.quote?.let { "  ·  now ${Fmt.money(it.last)}" } ?: "  ·  no price"),
                style = NumStyle.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.money(h.marketValue), style = NumStyle.copy(fontWeight = FontWeight.SemiBold))
            if (h.priced) Text(Fmt.signed(h.unrealized) + "  " + Fmt.pct(h.unrealizedPct), style = NumStyle.copy(fontSize = 11.sp), color = toneColor(h.unrealized))
            else Text("at cost", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun PortfolioScreen(st: UiState, vm: MainViewModel, nav: Nav) {
    var showAll by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val held = st.summary.holdings
    val heldSymbols = held.map { it.symbol }.toSet()
    val others = st.companies.values.filter { it.symbol !in heldSymbols }.sortedBy { it.symbol }
    Screen("Portfolio", actions = {
        if (st.settings.hasPriceSource) IconButton(onClick = { vm.refreshPrices(emptyList()) }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh prices") }
        IconButton(onClick = { adding = true }) { Icon(Icons.Filled.AddBusiness, contentDescription = "Add company") }
    }) {
        Chips(listOf(false, true), showAll, { if (it) "All companies (${st.companies.size})" else "Held (${held.size})" }, { showAll = it }, Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp)) {
            if (held.isEmpty() && !showAll) item { EmptyState("No holdings", "Shares you buy appear here with their cost, market value and profit or loss.") }
            items(held, key = { it.symbol }) { h -> HoldingRow(h, st.name(h.symbol)) { nav.stock(h.symbol) } }
            if (held.isNotEmpty()) item {
                Section(modifier = Modifier.padding(top = 12.dp)) {
                    KVMoney("Total cost", st.summary.costOfHoldings); KVMoney("Total market value", st.summary.marketValue)
                    KVMoney("Unrealized profit / loss", st.summary.unrealized, signed = true, bold = true)
                    KVMoney("If sold today, after charges", st.summary.netUnrealized, signed = true)
                }
            }
            if (showAll) {
                item { Text("Not currently held", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                items(others, key = { "c-" + it.symbol }) { c -> CompanyRow(c, st.result.position(c.symbol)?.realized) { nav.stock(c.symbol) } }
            }
        }
    }
    if (adding) CompanyDialog(onDismiss = { adding = false }) { symbol, name -> vm.run("Company added.") { ensureCompany(symbol, name) }; nav.stock(symbol) }
}

@Composable
private fun CompanyRow(c: CompanyEntity, realized: BigDecimal?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(c.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (c.name.isNotEmpty()) Text(c.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(c.last?.let { Fmt.money(it) } ?: "no price", style = NumStyle)
            if (realized != null && realized.signum() != 0) Text("realized " + Fmt.signed(realized), style = NumStyle.copy(fontSize = 11.sp), color = toneColor(realized))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun CompanyDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var symbol by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add a CSE company") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextInput(symbol, { symbol = it.trim() }, "Trading symbol, e.g. YORK.N0000", Modifier.fillMaxWidth(), caps = true)
            TextInput(name, { name = it }, "Company name", Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = symbol.isNotBlank(), onClick = { onSave(symbol.trim(), name.trim()); onDismiss() }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun PriceDialog(symbol: String, q: Quote?, onDismiss: () -> Unit, onSave: (Quote) -> Unit) {
    var last by remember { mutableStateOf(q?.last.field()) }
    var prev by remember { mutableStateOf(q?.prevClose.field()) }
    var volume by remember { mutableStateOf(q?.volume?.toString() ?: "") }
    var hi by remember { mutableStateOf(q?.high52.field()) }
    var lo by remember { mutableStateOf(q?.low52.field()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Update price – $symbol") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enter the figures from the CSE or your broker's platform. They are stored with today's date and time and marked as manually entered.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NumField(last, { last = it }, "Latest price (LKR)", Modifier.fillMaxWidth())
            NumField(prev, { prev = it }, "Previous close (optional)", Modifier.fillMaxWidth())
            NumField(volume, { volume = it }, "Volume (optional)", Modifier.fillMaxWidth(), whole = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(hi, { hi = it }, "52-week high", Modifier.weight(1f)); NumField(lo, { lo = it }, "52-week low", Modifier.weight(1f))
            }
        } },
        confirmButton = { TextButton(enabled = (last.dec()?.signum() ?: 0) > 0, onClick = {
            val l = last.dec()!!; val p = prev.dec()
            val chg = p?.let { l.subtract(it) }
            onSave(Quote(symbol, l, p, chg, if (p != null && chg != null) pctOf(chg, p) else null, volume.toLongOrNull(), hi.dec(), lo.dec(), System.currentTimeMillis(), "Manual"))
            onDismiss()
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun StockScreen(st: UiState, vm: MainViewModel, nav: Nav, symbol: String) {
    val p = st.result.position(symbol)
    val q = st.quotes[symbol]
    val c = st.companies[symbol]
    val h = p?.let { Valuation.holding(it, q, st.fees) }
    var pricing by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val saver = rememberSaver(vm)
    val statement = remember(st.result, symbol) { Reports.companyStatement(st.result, symbol) }
    Screen(symbol, onBack = { nav.back() }, actions = {
        if (p != null && p.isOpen) IconButton(onClick = { nav.simulator(symbol) }) { Icon(Icons.Filled.Calculate, contentDescription = "Sell price simulator") }
        if (p == null) IconButton(onClick = { confirmRemove = true }) { Icon(Icons.Filled.Delete, contentDescription = "Remove company") }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!c?.name.isNullOrEmpty()) Text(c!!.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Section("Market price", trailing = {
                Row {
                    if (st.settings.hasPriceSource) TextButton(onClick = { vm.refreshPrices(listOf(symbol)) }) { Text("Fetch") }
                    TextButton(onClick = { pricing = true }) { Text("Update") }
                }
            }) {
                if (q == null) Text("No price recorded. Tap Update to enter the latest price.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("LKR " + Fmt.money(q.last), style = NumStyle.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold))
                        Spacer(Modifier.width(10.dp))
                        if (q.change != null) Text(Fmt.signed(q.change!!) + "  " + Fmt.pct(q.changePct), style = NumStyle, color = toneColor(q.change), modifier = Modifier.padding(bottom = 4.dp))
                    }
                    Text("Last updated ${stamp(q.updatedAtMillis)} · ${q.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    KV("Previous close", q.prevClose?.let { Fmt.money(it) } ?: "–")
                    KV("Volume", q.volume?.let { Fmt.qty(it) } ?: "–")
                    KV("52-week high / low", (q.high52?.let { Fmt.money(it) } ?: "–") + " / " + (q.low52?.let { Fmt.money(it) } ?: "–"))
                }
            }
            if (p == null || h == null) {
                EmptyState("No transactions yet", "Record a purchase to start tracking this company.", "Buy shares") { nav.newTxn("BUY", symbol) }
            } else {
                Section("Position") {
                    KV("Total shares purchased", Fmt.qty(p.boughtQty))
                    KV("Total shares sold", Fmt.qty(p.soldQty))
                    KV("Current share quantity", Fmt.qty(p.quantity), bold = true)
                    KV("Average purchase price", Fmt.money(p.avgCost.setScale(4, java.math.RoundingMode.HALF_UP)))
                    KVMoney("Total acquisition cost (held)", p.costBasis)
                    KV("Current market price", q?.let { Fmt.money(it.last) } ?: "–")
                    KV("Current market value", if (p.isOpen) Fmt.money(h.marketValue) + (if (h.priced) "" else " (at cost)") else "–")
                    if (p.isOpen) KVMoney("Unrealized profit / loss", h.unrealized, signed = true, bold = true)
                    if (p.isOpen && h.priced) KVMoney("…after selling charges", h.netUnrealized, signed = true)
                    KVMoney("Realized profit / loss", p.realized, signed = true, bold = true)
                    KVMoney("Dividends received", p.dividends)
                    KVMoney("Total transaction charges", p.charges)
                    KV("Percentage return", Fmt.pct(h.totalReturnPct), toneColor(h.totalReturnPct), bold = true)
                    Text("Return = (realized + unrealized) ÷ total invested (LKR ${Fmt.money(p.totalInvested)}). Cost method: ${st.result.method.label}.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                if (p.financed.signum() > 0 || p.interestCharged.signum() > 0) Section("Margin funding") {
                    KVMoney("Loan attributed to this stock", p.financed); KVMoney("Your own capital", p.ownCapital)
                    KVMoney("Margin interest to date", p.interestCharged); KVMoney("…of which unpaid", p.interestUnpaid)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { nav.newTxn("BUY", symbol) }, modifier = Modifier.weight(1f)) { Text("Buy") }
                    OutlinedButton(onClick = { nav.newTxn("SELL", symbol) }, enabled = p.isOpen, modifier = Modifier.weight(1f)) { Text("Sell") }
                    OutlinedButton(onClick = { nav.simulator(symbol) }, enabled = p.isOpen, modifier = Modifier.weight(1f)) { Text("Simulate") }
                }
                OutlinedButton(onClick = { nav.company(symbol) }, modifier = Modifier.fillMaxWidth()) { Text("Company research: financials, ratios, announcements") }
                Section("Transactions", trailing = {
                    Row {
                        TextButton(onClick = { saver.save("$symbol-statement.pdf") { PdfReport.render("Transaction statement – $symbol", st.name(symbol), listOf(statement)) } }) { Text("PDF") }
                        TextButton(onClick = { saver.save("$symbol-statement.xlsx") { Xlsx.write(listOf(statement)) } }) { Text("Excel") }
                    }
                }) { TableView(statement) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (pricing) PriceDialog(symbol, q, { pricing = false }) { quote -> vm.run("Price saved.") { setQuote(quote) } }
    if (confirmRemove) ConfirmDialog("Remove $symbol?", "This removes the company and its stored price from your list. It has no transactions.", "Remove",
        { vm.run { deleteCompany(symbol) }; nav.back() }, { confirmRemove = false })
}


@Composable
private fun MarketsTodayCard(vm: MainViewModel, nav: Nav) {
    val global by vm.repo.market.global.collectAsStateWithLifecycle()
    val indices by vm.repo.market.indices.collectAsStateWithLifecycle()
    val globalAt by vm.repo.market.globalAt.collectAsStateWithLifecycle()
    val tones = LocalTones.current
    Section("Markets today", trailing = { TextButton(onClick = { nav.tab("markets") }) { Text("Open") } }) {
        val rows = listOf(Triple("ASPI", indices["ASPI"]?.getOrNull(0), indices["ASPI"]?.getOrNull(2)),
            Triple("Gold (USD/oz)", global["GC=F"]?.price, global["GC=F"]?.changePct), Triple("Brent oil (USD)", global["BZ=F"]?.price, global["BZ=F"]?.changePct),
            Triple("Bitcoin (USD)", global["BTC-USD"]?.price, global["BTC-USD"]?.changePct), Triple("USD / LKR", global["LKR=X"]?.price, global["LKR=X"]?.changePct))
        rows.forEach { (label, v, pct) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(lk.cseportfolio.core.NumFmt.price(v), style = NumStyle)
                Text("  " + lk.cseportfolio.core.NumFmt.pct(pct), style = NumStyle.copy(fontSize = 11.sp), modifier = Modifier.width(72.dp),
                    color = when { pct == null -> MaterialTheme.colorScheme.onSurfaceVariant; pct > 0 -> tones.gain; pct < 0 -> tones.loss; else -> MaterialTheme.colorScheme.onSurfaceVariant })
            }
        }
        WorldStamp(globalAt)
    }
}
