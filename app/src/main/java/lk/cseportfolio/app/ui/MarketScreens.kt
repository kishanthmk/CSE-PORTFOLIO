package lk.cseportfolio.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.launch
import lk.cseportfolio.app.MainViewModel
import lk.cseportfolio.app.Nav
import lk.cseportfolio.app.UiState
import lk.cseportfolio.app.data.WatchEntity
import lk.cseportfolio.core.Announcement
import lk.cseportfolio.core.Candle
import lk.cseportfolio.core.NumFmt
import lk.cseportfolio.core.Fig
import lk.cseportfolio.core.FinPeriod
import lk.cseportfolio.core.Fmt
import lk.cseportfolio.core.MarketFeed
import lk.cseportfolio.core.ReportTable
import lk.cseportfolio.core.Ratios
import lk.cseportfolio.core.Reports
import lk.cseportfolio.core.Security
import lk.cseportfolio.core.pctOf

fun openUrl(context: Context, url: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
}

/** Where a market price came from and how fresh it is. Never described as live. */
@Composable
private fun PriceStamp(at: Long, modifier: Modifier = Modifier) {
    Text(if (at <= 0) "No prices downloaded yet. Tap refresh." else "${MarketFeed.priceStatus(at)} prices · CSE website · updated ${stamp(at)}",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** Latest known price for a symbol: the Market Watch figure if there is one, otherwise the price stored for your portfolio. */
private fun priceOf(symbol: String, secs: Map<String, Security>, st: UiState): BigDecimal? = secs[symbol]?.last ?: st.quotes[symbol]?.last

@Composable
fun MarketScreen(st: UiState, vm: MainViewModel, nav: Nav, initialTab: Int) {
    val market = vm.repo.market
    val secs by market.securities.collectAsStateWithLifecycle()
    val pricesAt by market.pricesAt.collectAsStateWithLifecycle()
    val anns by market.announcements.collectAsStateWithLifecycle()
    val annsAt by market.announcementsAt.collectAsStateWithLifecycle()
    val periods by market.periods.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(initialTab.coerceIn(0, 5)) }
    val bySymbol = remember(secs) { secs.associateBy { it.symbol } }
    Screen("CSE market", onBack = { nav.back() }, actions = {
        IconButton(onClick = { if (tab == 2 || tab == 3) vm.run { vm.say(refreshAnnouncements()) } else vm.refreshPrices(emptyList()) }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
    }) {
        ScrollableTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, edgePadding = 8.dp) {
            listOf("Market watch", "Directory", "Announcements", "Dividends", "Ratios", "Compare").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> SecurityList(secs, pricesAt, false, st) { nav.company(it) }
            1 -> SecurityList(secs, pricesAt, true, st) { nav.company(it) }
            2 -> AnnouncementList(anns, annsAt, st, bySymbol, null)
            3 -> DividendTab(anns, annsAt, st, bySymbol, periods, nav)
            4 -> RatiosTab(periods, bySymbol, st, nav)
            else -> CompareTab(secs, periods, bySymbol, st)
        }
    }
}

@Composable
internal fun SecurityList(secs: List<Security>, pricesAt: Long, directory: Boolean, st: UiState, onOpen: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(if (directory) "Name" else "Gainers") }
    val held = st.summary.holdings.map { it.symbol }.toSet()
    val shown = remember(secs, query, sort, directory) {
        val q = query.trim()
        val f = secs.filter { (directory || it.last != null) && (q.isEmpty() || it.symbol.contains(q, true) || it.name.contains(q, true)) }
        when (sort) {
            "Gainers" -> f.sortedByDescending { it.changePct ?: BigDecimal("-9999") }
            "Losers" -> f.sortedBy { it.changePct ?: BigDecimal("9999") }
            "Volume" -> f.sortedByDescending { it.volume ?: 0 }
            "Code" -> f.sortedBy { it.symbol }
            else -> f.sortedBy { it.name.ifEmpty { it.symbol } }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search company name or trading code") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Chips(if (directory) listOf("Name", "Code") else listOf("Gainers", "Losers", "Volume", "Name"), sort, { it }, { sort = it })
        PriceStamp(pricesAt)
        if (secs.isEmpty()) EmptyState("No market data yet", "Tap the refresh button at the top with internet on to download the list of securities and their prices from the CSE website.")
        else {
            Text(if (directory) "${shown.size} securities. The list is built from the CSE website and grows as securities trade; one that has not traded since you installed the app may be missing until it does."
                 else "${shown.size} securities with a price.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it.symbol }) { s ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(s.symbol) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(if (directory) s.name.ifEmpty { s.symbol } else s.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((if (directory) s.symbol + "  ·  " + s.securityClass else s.name) + (if (s.symbol in held) "  ·  in your portfolio" else ""),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(s.last?.let { Fmt.money(it) } ?: "–", style = NumStyle.copy(fontWeight = FontWeight.SemiBold))
                            if (s.change != null) Text(Fmt.signed(s.change!!) + "  " + Fmt.pct(s.changePct), style = NumStyle.copy(fontSize = 11.sp), color = toneColor(s.change))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

private fun matches(a: Announcement, symbol: String, name: String): Boolean =
    (a.symbol.isNotEmpty() && a.symbol == symbol) || (name.length > 3 && a.company.isNotEmpty() && (a.company.contains(name, true) || name.contains(a.company, true)))

@Composable
private fun AnnouncementRow(a: Announcement) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().clickable(enabled = a.url.isNotEmpty()) { openUrl(context, a.url) }.padding(vertical = 8.dp)) {
        Row {
            Text(a.company.ifEmpty { a.symbol.ifEmpty { "CSE" } }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (a.dateMillis > 0) stamp(a.dateMillis) else a.dateText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(a.title, style = MaterialTheme.typography.bodyMedium)
        if (a.url.isNotEmpty()) Text("Open the document", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** [only] = a single company (symbol to name) for the company page; null = the market-wide list with filters. */
@Composable
private fun AnnouncementList(anns: List<Announcement>, at: Long, st: UiState, secs: Map<String, Security>, only: Pair<String, String>?) {
    var filter by remember { mutableStateOf("All") }
    val heldPairs = st.summary.holdings.map { it.symbol to (secs[it.symbol]?.name ?: st.name(it.symbol)) }
    val shown = anns.filter { a -> when {
        only != null -> matches(a, only.first, only.second)
        filter == "My holdings" -> heldPairs.any { matches(a, it.first, it.second) }
        filter == "Financial" -> a.isFinancial
        filter == "Dividends" -> a.isDividend
        else -> true } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (only == null) Chips(listOf("All", "My holdings", "Financial", "Dividends"), filter, { it }, { filter = it }, Modifier.padding(top = 8.dp))
        Text(if (at <= 0) "Not downloaded yet. Tap refresh at the top." else "From the CSE website · downloaded ${stamp(at)}. The CSE lists recent announcements only; open the company on cse.lk for the full history.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
        if (shown.isEmpty()) Text(if (at <= 0) "" else "No announcements match.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) { items(shown) { a -> AnnouncementRow(a) } }
    }
}

@Composable
private fun DividendTab(anns: List<Announcement>, at: Long, st: UiState, secs: Map<String, Security>, periods: List<FinPeriod>, nav: Nav) {
    val div = anns.filter { it.isDividend }
    val received = st.result.positions.filter { it.dividends.signum() > 0 }
    val yields = st.summary.holdings.mapNotNull { h ->
        val p = FinPeriod.sort(periods.filter { it.symbol == h.symbol && it.isAnnual }).lastOrNull { it.v(Fig.DPS) != null } ?: return@mapNotNull null
        Triple(h, p, priceOf(h.symbol, secs, st))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Dividend announcements") {
            Text(if (at <= 0) "Tap refresh at the top to download announcements from the CSE website." else "Recent announcements that mention a dividend · downloaded ${stamp(at)}. XD, record and payment dates are in the linked document.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (at > 0 && div.isEmpty()) Text("None among the announcements the CSE currently lists.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            div.forEach { AnnouncementRow(it) }
        }
        Section("Dividend yield on your holdings") {
            if (yields.isEmpty()) Text("Enter a company's annual dividend per share (open the company ▸ Financials ▸ Add reporting period) to see its yield here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else TableView(ReportTable("", listOf("Symbol", "Year", "Dividend per share", "Price", "Yield on price", "Yield on your cost", "Dividend on your shares"), yields.map { (h, p, price) ->
                val dps = p.v(Fig.DPS)!!
                listOf(Reports.t(h.symbol), Reports.t(p.label), Reports.price(dps), Reports.price(price), Reports.pc(price?.let { pctOf(dps, it) }).copy(tone = 0),
                    Reports.pc(pctOf(dps, h.position.avgCost)).copy(tone = 0), Reports.m(dps.multiply(BigDecimal.valueOf(h.position.quantity))))
            }))
        }
        Section("Dividends you have recorded", trailing = { TextButton(onClick = { nav.cash() }) { Text("Add") } }) {
            if (received.isEmpty()) Text("Record dividends you receive with + ▸ Deposit, withdrawal or dividend.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else TableView(ReportTable("", listOf("Symbol", "Dividends received"), received.map { listOf(Reports.t(it.symbol), Reports.m(it.dividends)) },
                listOf(Reports.t("Total"), Reports.m(st.result.dividends))))
        }
    }
}

@Composable
private fun RatioFormulas() {
    val sample = remember { Ratios.compute(FinPeriod("", 0), null, null) }
    Section("How each ratio is calculated") {
        sample.forEach { r -> Text("${r.name}: ${r.formula}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp)) }
    }
}

@Composable
private fun RatiosTab(periods: List<FinPeriod>, secs: Map<String, Security>, st: UiState, nav: Nav) {
    val symbols = periods.map { it.symbol }.distinct().sorted()
    var symbol by remember(symbols) { mutableStateOf(symbols.firstOrNull() ?: "") }
    var annual by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (symbols.isEmpty()) {
            EmptyState("No financial figures entered yet", "The CSE publishes financial statements as PDF reports only. Open a company from Market watch, choose Financials, and enter the key figures from its report. The app then works out every ratio, growth rate and comparison.")
            RatioFormulas()
        } else {
            Picker("Company", symbols, symbol, { it + (secs[it]?.name ?: st.name(it)).let { n -> if (n.isEmpty()) "" else " – $n" } }, { symbol = it }, Modifier.fillMaxWidth())
            Chips(listOf(true, false), annual, { if (it) "Annual" else "Quarterly" }, { annual = it })
            val table = remember(periods, symbol, annual, secs) { Ratios.ratioTable(symbol, periods, annual, priceOf(symbol, secs, st)) }
            Section(table.title, trailing = { TextButton(onClick = { nav.company(symbol) }) { Text("Company") } }) {
                if (table.rows.isEmpty()) Text("No ${if (annual) "annual" else "quarterly"} figures entered for this company.", style = MaterialTheme.typography.bodySmall) else TableView(table)
            }
            Text("Calculated ${LocalDate.now().format(Reports.DATE)} from figures entered from published reports; each period's source is listed under the company's Financials tab.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RatioFormulas()
        }
    }
}

@Composable
private fun SymbolAdder(secs: List<Security>, extra: List<String>, label: String, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val all = remember(secs, extra) { (secs.map { it.symbol to it.name } + extra.map { it to "" }).distinctBy { it.first } }
    val matches = if (text.length < 2) emptyList() else all.filter { it.first.startsWith(text, true) || it.second.contains(text, true) }.take(6)
    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { matches.forEach { m -> AssistChip(onClick = { onAdd(m.first); text = "" }, label = { Text(m.first) }) } }
}

@Composable
private fun CompareTab(secs: List<Security>, periods: List<FinPeriod>, bySymbol: Map<String, Security>, st: UiState) {
    var chosen by remember { mutableStateOf(st.summary.holdings.take(3).map { it.symbol }) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Choose up to five companies.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { chosen.forEach { s -> FilterChip(selected = true, onClick = { chosen = chosen - s }, label = { Text("$s  ×") }) } }
        if (chosen.size < 5) SymbolAdder(secs, st.symbols, "Add a company by name or code") { s -> if (s !in chosen) chosen = chosen + s }
        if (chosen.isNotEmpty()) {
            val table = remember(chosen, periods, bySymbol) {
                Ratios.comparisonTable(chosen, periods, chosen.mapNotNull { s -> priceOf(s, bySymbol, st)?.let { s to it } }.toMap(), chosen.mapNotNull { s -> bySymbol[s]?.marketCap?.let { s to it } }.toMap())
            }
            Section("Comparison") { TableView(table) }
        }
    }
}

// ---------------------------------------------------------------- one company

@Composable
fun CompanyScreen(st: UiState, vm: MainViewModel, nav: Nav, symbol: String) {
    val market = vm.repo.market
    val secs by market.securities.collectAsStateWithLifecycle()
    val pricesAt by market.pricesAt.collectAsStateWithLifecycle()
    val anns by market.announcements.collectAsStateWithLifecycle()
    val annsAt by market.announcementsAt.collectAsStateWithLifecycle()
    val periods by market.periods.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bySymbol = remember(secs) { secs.associateBy { it.symbol } }
    val sec = bySymbol[symbol]
    val name = sec?.name?.ifEmpty { null } ?: st.name(symbol)
    val quote = st.quotes[symbol]
    val price = priceOf(symbol, bySymbol, st)
    val pos = st.result.position(symbol)?.takeIf { it.isOpen }
    var tab by remember { mutableStateOf(0) }
    var annual by remember { mutableStateOf(true) }
    var details by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var loading by remember { mutableStateOf(false) }
    val mine = periods.filter { it.symbol == symbol }
    Screen(symbol, onBack = { nav.back() }, actions = {
        IconButton(onClick = { if (tab == 3) vm.run { vm.say(refreshAnnouncements()) } else vm.refreshPrices(listOf(symbol)) }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
    }) {
        if (name.isNotEmpty()) Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        ScrollableTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, edgePadding = 8.dp) {
            listOf("Market data", "Financials", "Ratios", "Announcements", "Reports").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        if (tab == 3) { AnnouncementList(anns, annsAt, st, bySymbol, symbol to name); return@Screen }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (tab) {
                0 -> {
                    CseChartSection(vm, symbol)
                    Section("Market information") {
                        if (price == null) Text("No price stored for this security. Tap refresh at the top.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("LKR " + Fmt.money(price), style = NumStyle.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold))
                                Spacer(Modifier.width(10.dp))
                                val chg = sec?.change ?: quote?.change
                                if (chg != null) Text(Fmt.signed(chg) + "  " + Fmt.pct(sec?.changePct ?: quote?.changePct), style = NumStyle, color = toneColor(chg), modifier = Modifier.padding(bottom = 4.dp))
                            }
                            if (sec?.last != null) PriceStamp(sec.updatedAt) else if (quote != null) Text("Updated ${stamp(quote.updatedAtMillis)} · ${quote.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            KV("Security class", sec?.securityClass?.ifEmpty { "–" } ?: "–")
                            KV("Previous close", (sec?.prevClose ?: quote?.prevClose)?.let { Fmt.money(it) } ?: "–")
                            KV("Day high / low", (sec?.high?.let { Fmt.money(it) } ?: "–") + " / " + (sec?.low?.let { Fmt.money(it) } ?: "–"))
                            KV("Volume", (sec?.volume ?: quote?.volume)?.let { Fmt.qty(it) } ?: "–")
                            KV("Turnover", sec?.turnover?.let { Fmt.money(it) } ?: "–")
                            KV("Market capitalisation", sec?.marketCap?.let { Fmt.compact(it) } ?: "–")
                            KV("52-week high / low", (quote?.high52?.let { Fmt.money(it) } ?: "–") + " / " + (quote?.low52?.let { Fmt.money(it) } ?: "–"))
                        }
                    }
                    if (pos != null) Section("Your holding", trailing = { TextButton(onClick = { nav.stock(symbol) }) { Text("Open") } }) {
                        KV("Shares held", Fmt.qty(pos.quantity)); KV("Average cost", Fmt.money(pos.avgCost.setScale(4, java.math.RoundingMode.HALF_UP))); KVMoney("Cost", pos.costBasis)
                        if (price != null) { val mv = price.multiply(BigDecimal.valueOf(pos.quantity)); KVMoney("Market value", mv); KVMoney("Unrealized profit / loss", mv.subtract(pos.costBasis), signed = true, bold = true) }
                        if (pos.financed.signum() > 0) { KVMoney("Margin loan on this stock", pos.financed); KVMoney("Unpaid margin interest", pos.interestUnpaid) }
                        Button(onClick = { nav.simulator(symbol) }, modifier = Modifier.padding(top = 6.dp)) { Text("Calculate selling profit") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (pos == null) OutlinedButton(onClick = { nav.newTxn("BUY", symbol, "", price?.let { Fmt.plain(it) } ?: "") }) { Text("Buy") }
                        OutlinedButton(onClick = { vm.run("Added to your watchlist.") { saveWatch(WatchEntity(symbol = symbol, name = name)) } }) { Text("Add to watchlist") }
                    }
                    Section("Everything the CSE publishes for this security") {
                        val d = details
                        if (d == null) Button(enabled = !loading, onClick = {
                            loading = true
                            scope.launch { try { details = vm.repo.fetchCompanyInfo(symbol) } catch (e: Exception) { vm.say(e.message ?: "Could not load details.") }; loading = false }
                        }) { Text(if (loading) "Loading…" else "Load details from the CSE website") }
                        else { d.forEach { (k, v) -> KV(k, v.take(40)) }; Text("Shown exactly as the CSE website sends it · loaded just now.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                1 -> {
                    QuarterSnapshot(symbol, mine, periods, price, anns.filter { it.isFinancial && matches(it, symbol, name) }.firstOrNull())
                    Chips(listOf(true, false), annual, { if (it) "Annual" else "Quarterly" }, { annual = it })
                    val table = remember(periods, symbol, annual) { Ratios.statementTable(symbol, periods, annual) }
                    if (table.rows.isEmpty()) Text("No ${if (annual) "annual" else "quarterly"} figures entered yet. The CSE publishes statements as PDF reports, so the figures are typed in from the report; the app does the analysis.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Section(table.title) { TableView(table) }
                    Button(onClick = { nav.financials(symbol) }, modifier = Modifier.fillMaxWidth()) { Text("Add reporting period") }
                    if (mine.isNotEmpty()) Section("Entered periods") {
                        FinPeriod.sort(mine).reversed().forEach { p ->
                            Row(Modifier.fillMaxWidth().clickable { nav.financials(symbol, p.key) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text((p.source.ifEmpty { "Source not recorded" }) + (p.updatedOn?.let { "  ·  entered ${it.format(Reports.DATE)}" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("Edit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                2 -> {
                    Chips(listOf(true, false), annual, { if (it) "Annual" else "Quarterly" }, { annual = it })
                    val table = remember(periods, symbol, annual, price) { Ratios.ratioTable(symbol, periods, annual, price) }
                    if (table.rows.isEmpty()) Text("Enter figures under Financials to see ratios here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Section(table.title) { TableView(table) }
                    RatioFormulas()
                }
                else -> {
                    Section("Quarterly and annual reports") {
                        Text("The original interim financial statements, annual reports and the full announcement history are on the company's page on the CSE website.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { openUrl(context, MarketFeed.companyPageUrl(symbol)) }, modifier = Modifier.padding(top = 8.dp)) { Text("Open $symbol on cse.lk") }
                    }
                    val fin = anns.filter { it.isFinancial && matches(it, symbol, name) }
                    Section("Recent financial filings") {
                        if (fin.isEmpty()) Text(if (annsAt <= 0) "Tap refresh on the Announcements tab to download recent filings." else "None for this company among the filings the CSE currently lists.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        fin.forEach { AnnouncementRow(it) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------- entering figures

@Composable
fun FinancialEditorScreen(st: UiState, vm: MainViewModel, nav: Nav, symbol: String, key: String) {
    val market = vm.repo.market
    val existing = remember(key) { market.periods.value.firstOrNull { it.key == key } }
    var year by remember { mutableStateOf((existing?.year ?: LocalDate.now().year).toString()) }
    var quarter by remember { mutableStateOf(existing?.quarter ?: 0) }
    var end by remember { mutableStateOf(existing?.periodEnd) }
    var unit by remember { mutableStateOf(existing?.unit ?: 1000L) }
    var source by remember { mutableStateOf(existing?.source ?: "") }
    val values = remember { mutableStateMapOf<Fig, String>().apply { existing?.figures?.forEach { (f, v) -> put(f, v.toPlainString()) } } }
    var confirmDelete by remember { mutableStateOf(false) }

    fun save() {
        val y = year.toIntOrNull()
        if (y == null || y < 1990 || y > 2100) { vm.say("Enter the financial year, for example 2026 for the year ending in 2026."); return }
        val figs = LinkedHashMap<Fig, BigDecimal>()
        for (f in Fig.values()) values[f]?.trim()?.takeIf { it.isNotEmpty() && it != "-" }?.let { t -> try { figs[f] = BigDecimal(t) } catch (e: Exception) { } }
        if (figs.isEmpty()) { vm.say("Enter at least one figure."); return }
        val p = FinPeriod(symbol, y, quarter, end, unit, source.trim(), LocalDate.now(), figs)
        if (existing == null && market.periods.value.any { it.key == p.key }) { vm.say("${p.label} is already entered for $symbol. Open it from the Financials tab to edit."); return }
        market.savePeriod(p, existing?.key)
        vm.say("${p.label} saved.")
        nav.back()
    }

    Screen((if (existing == null) "Add figures – " else "Edit figures – ") + symbol, onBack = { nav.back() }, actions = {
        if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        TextButton(onClick = { save() }) { Text("Save") }
    }) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Banner("Type the figures from the company's published report. Leave anything you don't need blank; ratios that depend on a blank figure simply show a dash. Use a minus sign for losses.", LocalTones.current.info)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(year, { year = it }, "Financial year (ending)", Modifier.weight(1f), whole = true)
                DateField(end, { end = it }, "Period end date", Modifier.weight(1f))
            }
            Chips(listOf(0, 1, 2, 3, 4), quarter, { if (it == 0) "Full year" else "Q$it" }, { quarter = it })
            Text("Money figures in the report are stated in", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Chips(listOf(1L, 1000L, 1000000L), unit, { when (it) { 1L -> "Rupees"; 1000L -> "Rs. '000"; else -> "Rs. million" } }, { unit = it })
            TextInput(source, { source = it }, "Source, e.g. Annual report 2025/26, page 112", Modifier.fillMaxWidth())
            Fig.values().groupBy { it.group }.forEach { (group, figs) ->
                Section(group) {
                    figs.forEach { f -> NumField(values[f] ?: "", { values[f] = it }, f.label, Modifier.fillMaxWidth().padding(vertical = 3.dp), signed = true) }
                }
            }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Save figures") }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmDelete && existing != null) ConfirmDialog("Delete ${existing.label}?", "The figures entered for this period will be removed.", "Delete",
        { market.deletePeriod(existing.key); vm.say("Deleted."); nav.back() }, { confirmDelete = false })
}


@Composable
private fun CseChartSection(vm: MainViewModel, symbol: String) {
    var range by remember { mutableStateOf("6M") }
    var candles by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var source by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(symbol, range) {
        loading = true
        try { val (c, src) = vm.repo.cseChart(symbol, range); candles = c; source = src } catch (e: Exception) { candles = emptyList(); source = "" }
        loading = false
    }
    Section("Price chart") {
        RangeChips(listOf("1D", "1M", "3M", "6M", "1Y"), range) { range = it }
        Spacer(Modifier.height(6.dp))
        if (loading && candles.isEmpty()) Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else PriceChart(candles, range == "1D", 260)
        if (source.isNotEmpty()) Text("Source: $source · delayed or end of day, never live.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The latest quarter entered for a company, with change on the same quarter last year and the key ratios. */
@Composable
private fun QuarterSnapshot(symbol: String, mine: List<FinPeriod>, all: List<FinPeriod>, price: BigDecimal?, latestFiling: Announcement?) {
    val context = LocalContext.current
    val q = FinPeriod.sort(mine.filter { !it.isAnnual }).lastOrNull()
    Section("Latest quarter snapshot") {
        if (q == null) {
            Text("No quarter entered yet. The CSE publishes quarterly results as PDF reports only, so the figures can't be filled in automatically: open the report, then tap Add reporting period below and type the key figures. The app does all the ratios and comparisons.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val prior = Ratios.prior(q, all)
            Text(q.label + (q.periodEnd?.let { " · ended ${it.format(Reports.DATE)}" } ?: "") + " · " + q.source.ifEmpty { "source not recorded" },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (f in listOf(Fig.REVENUE, Fig.GROSS_PROFIT, Fig.OPERATING_PROFIT, Fig.PBT, Fig.NET_PROFIT, Fig.EPS, Fig.OCF)) {
                val v = q.v(f) ?: continue
                val was = prior?.v(f)
                val g = if (was != null && was.signum() > 0) lk.cseportfolio.core.pctOf(v.subtract(was), was) else null
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(if (f.money) Fmt.compact(v) else Fmt.money(v), style = NumStyle)
                    Text("  " + (g?.let { Fmt.pct(it) } ?: "–"), style = NumStyle.copy(fontSize = 11.sp), color = toneColor(g), modifier = Modifier.width(76.dp))
                }
            }
            Text("Right-hand column: change on the same quarter a year earlier.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val r = Ratios.compute(q, prior, price).filter { it.value != null && it.category != "Valuation" }.take(8)
            if (r.isNotEmpty()) { HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant); r.forEach { KV(it.name, it.text) } }
        }
        if (latestFiling != null) TextButton(onClick = { openUrl(context, latestFiling.url) }, enabled = latestFiling.url.isNotEmpty()) { Text("Open the latest filing: " + latestFiling.title.take(40)) }
        else TextButton(onClick = { openUrl(context, MarketFeed.companyPageUrl(symbol)) }) { Text("Find the quarterly report on cse.lk") }
    }
}
