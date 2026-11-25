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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.math.BigDecimal
import lk.cseportfolio.app.MainViewModel
import lk.cseportfolio.app.Nav
import lk.cseportfolio.app.UiState
import lk.cseportfolio.core.AssetGroup
import lk.cseportfolio.core.Candle
import lk.cseportfolio.core.Fmt
import lk.cseportfolio.core.GQuote
import lk.cseportfolio.core.GlobalCatalog
import lk.cseportfolio.core.Instrument
import lk.cseportfolio.core.MarketFeed
import lk.cseportfolio.core.NumFmt
import lk.cseportfolio.core.Security
import lk.cseportfolio.core.YahooFeed

private const val GRAMS_PER_OUNCE = 31.1034768

fun marketStateText(q: GQuote?): String = when (q?.marketState) {
    "REGULAR" -> "Market open"; "PRE", "PREPRE" -> "Pre-market"; "POST", "POSTPOST" -> "Post-market"; "CLOSED" -> "Closed"; else -> ""
}

/** Source and freshness line for world prices. Always "delayed": this is not a licensed real-time feed. */
@Composable
fun WorldStamp(at: Long) {
    Text(if (at <= 0) "No world prices downloaded yet. Tap refresh." else "Delayed prices · ${YahooFeed.SOURCE} (unofficial) · updated ${NumFmt.time(at)}",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MoveText(change: Double?, pct: Double?) {
    val tones = LocalTones.current
    val c = when { change == null -> MaterialTheme.colorScheme.onSurfaceVariant; change > 0 -> tones.gain; change < 0 -> tones.loss; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Text(NumFmt.signed(change) + "  " + NumFmt.pct(pct), style = NumStyle.copy(fontSize = 12.sp), color = c)
}

@Composable
private fun QuoteRow(lead: String?, title: String, sub: String, q: GQuote?, extra: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (lead != null) Text(lead, style = NumStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(30.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(NumFmt.price(q?.price), style = NumStyle.copy(fontWeight = FontWeight.SemiBold))
            MoveText(q?.change, q?.changePct)
            if (extra != null) Text(extra, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun MarketsHomeScreen(st: UiState, vm: MainViewModel, nav: Nav) {
    val market = vm.repo.market
    var tab by rememberSaveableTab()
    val global by market.global.collectAsStateWithLifecycle()
    val globalAt by market.globalAt.collectAsStateWithLifecycle()
    Screen("Markets", actions = {
        IconButton(onClick = { nav.market(1) }) { Icon(Icons.Filled.Search, contentDescription = "Search CSE companies") }
        IconButton(onClick = { if (tab == 0) vm.refreshPrices(emptyList()) else vm.run { vm.say(refreshGlobal()) } }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
    }) {
        ScrollableTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, edgePadding = 8.dp) {
            listOf("Sri Lanka", "World top 50", "Commodities", "Crypto", "Forex").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> SriLankaTab(st, vm, nav)
            1 -> WorldTab(global, globalAt, nav)
            2 -> CommoditiesTab(global, globalAt, nav)
            3 -> CryptoTab(global, globalAt, nav)
            else -> ForexTab(global, globalAt, nav)
        }
    }
}

@Composable
private fun rememberSaveableTab(): MutableState<Int> = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }

@Composable
private fun SriLankaTab(st: UiState, vm: MainViewModel, nav: Nav) {
    val market = vm.repo.market
    val secs by market.securities.collectAsStateWithLifecycle()
    val pricesAt by market.pricesAt.collectAsStateWithLifecycle()
    val indices by market.indices.collectAsStateWithLifecycle()
    var view by remember { mutableStateOf("Gainers") }
    var query by remember { mutableStateOf("") }
    val priced = remember(secs) { secs.filter { it.last != null } }
    val list = remember(priced, view, query) {
        val q = query.trim()
        val f = priced.filter { q.isEmpty() || it.symbol.contains(q, true) || it.name.contains(q, true) }
        when (view) {
            "Gainers" -> f.filter { (it.changePct ?: BigDecimal.ZERO).signum() > 0 }.sortedByDescending { it.changePct }.take(10)
            "Losers" -> f.filter { (it.changePct ?: BigDecimal.ZERO).signum() < 0 }.sortedBy { it.changePct }.take(10)
            "Most active" -> f.sortedByDescending { it.volume ?: 0 }.take(10)
            "Turnover" -> f.sortedByDescending { it.turnover ?: BigDecimal.ZERO }.take(10)
            else -> f.sortedBy { it.symbol }
        }
    }
    val held = st.summary.holdings.map { it.symbol }.toSet()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (name in listOf("ASPI", "S&P SL20")) {
                    val v = indices[name]
                    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(NumFmt.price(v?.getOrNull(0)), style = NumStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold))
                            MoveText(v?.getOrNull(1), v?.getOrNull(2))
                        }
                    }
                }
            }
            val adv = priced.count { (it.change ?: BigDecimal.ZERO).signum() > 0 }; val dec = priced.count { (it.change ?: BigDecimal.ZERO).signum() < 0 }
            val turnover = priced.mapNotNull { it.turnover }.fold(BigDecimal.ZERO) { a, b -> a.add(b) }
            val volume = priced.sumOf { it.volume ?: 0L }
            Section(modifier = Modifier.padding(top = 10.dp)) {
                Row { Text("Advancing ", style = MaterialTheme.typography.bodySmall); Text("$adv", color = LocalTones.current.gain, fontWeight = FontWeight.Bold)
                    Text("   Declining ", style = MaterialTheme.typography.bodySmall); Text("$dec", color = LocalTones.current.loss, fontWeight = FontWeight.Bold)
                    Text("   Unchanged ${priced.size - adv - dec}", style = MaterialTheme.typography.bodySmall) }
                if (priced.isNotEmpty()) LinearProgressIndicator(progress = { if (adv + dec == 0) 0.5f else adv.toFloat() / (adv + dec) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    color = LocalTones.current.gain, trackColor = LocalTones.current.loss)
                KV("Turnover (traded securities)", "LKR " + Fmt.compact(turnover)); KV("Shares traded", Fmt.qty(volume))
                Text(if (pricesAt <= 0) "No CSE data yet. Tap refresh." else "${MarketFeed.priceStatus(pricesAt)} · CSE website (unofficial) · updated ${NumFmt.time(pricesAt)}. Totals cover the securities in the CSE trade summary.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                listOf("Directory" to 1, "Announcements" to 2, "Dividends" to 3, "Ratios" to 4, "Compare" to 5).forEach { (l, t) -> AssistChip(onClick = { nav.market(t) }, label = { Text(l) }) }
            }
            Chips(listOf("Gainers", "Losers", "Most active", "Turnover", "All"), view, { if (it == "Gainers" || it == "Losers") "Top $it".lowercase().replaceFirstChar { c -> c.uppercase() } else it }, { view = it }, Modifier.padding(top = 6.dp))
            if (view == "All") OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search company or code") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            if (priced.isEmpty()) EmptyState("No CSE prices yet", "Tap the refresh button at the top with internet on.")
        }
        items(list, key = { it.symbol }) { s -> CseRow(s, s.symbol in held) { nav.company(s.symbol) } }
    }
}

@Composable
private fun CseRow(s: Security, held: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(s.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(s.name + (if (held) "  ·  in your portfolio" else ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(s.last?.let { Fmt.money(it) } ?: "–", style = NumStyle.copy(fontWeight = FontWeight.SemiBold))
            MoveText(s.change?.toDouble(), s.changePct?.toDouble())
            if (s.volume != null) Text("Vol " + Fmt.qty(s.volume!!), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun WorldTab(global: Map<String, GQuote>, at: Long, nav: Nav) {
    val ranked = remember(global) { YahooFeed.rankTop(global) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
        item {
            WorldStamp(at)
            Text("Ranked by market capitalisation in US dollars from ${GlobalCatalog.stocks.size} of the world's largest listed companies, re-ranked on every refresh. Ranking as at ${NumFmt.time(at)}.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            if (at > 0 && ranked.isEmpty()) Banner("The source returned prices but no market capitalisation, so the ranking can't be built just now. Prices are listed below in catalogue order.")
            if (at <= 0) EmptyState("No world prices yet", "Tap the refresh button at the top with internet on.")
        }
        if (ranked.isNotEmpty()) items(ranked, key = { it.instrument.symbol }) { r ->
            QuoteRow("${r.rank}", r.instrument.name, "${r.instrument.symbol} · ${r.quote?.exchange?.ifEmpty { r.instrument.country } ?: r.instrument.country}", r.quote,
                "Cap $" + NumFmt.big(r.marketCapUsd) + (r.quote?.currency?.let { if (it.isNotEmpty() && it != "USD") " · price in $it" else "" } ?: "")) { nav.instrument(r.instrument.symbol) }
        } else if (at > 0) items(GlobalCatalog.stocks.filter { global[it.symbol] != null }, key = { it.symbol }) { i ->
            QuoteRow(null, i.name, i.symbol, global[i.symbol]) { nav.instrument(i.symbol) }
        }
    }
}

private fun lkr(global: Map<String, GQuote>): Double? = global["LKR=X"]?.price

@Composable
private fun CommoditiesTab(global: Map<String, GQuote>, at: Long, nav: Nav) {
    val usdLkr = lkr(global)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
        item { WorldStamp(at); Text("Futures prices in US dollars; LKR figures are converted at the USD/LKR rate shown under Forex and exclude local taxes and dealer margins.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) }
        for (group in listOf(AssetGroup.METAL, AssetGroup.ENERGY)) {
            item { Text(group.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
            items(GlobalCatalog.commodities.filter { it.group == group }, key = { it.symbol }) { i ->
                val q = global[i.symbol]
                val px = q?.price
                val lkrText = if (px != null && usdLkr != null) {
                    if (group == AssetGroup.METAL) "LKR ${NumFmt.price(px * usdLkr / GRAMS_PER_OUNCE * 8)} per 8 g (pawn)" else "LKR ${NumFmt.price(px * usdLkr)} per unit"
                } else null
                QuoteRow(null, i.name, i.unit, q, lkrText) { nav.instrument(i.symbol) }
            }
        }
    }
}

@Composable
private fun CryptoTab(global: Map<String, GQuote>, at: Long, nav: Nav) {
    val usdLkr = lkr(global)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
        item { WorldStamp(at); Text("Crypto trades around the clock; it is kept separate from stock exchanges. Prices in US dollars.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) }
        items(GlobalCatalog.crypto.sortedByDescending { global[it.symbol]?.marketCap ?: 0.0 }, key = { it.symbol }) { i ->
            val q = global[i.symbol]
            val px = q?.price
            QuoteRow(null, i.name, i.symbol.removeSuffix("-USD") + (q?.marketCap?.let { " · cap $" + NumFmt.big(it) } ?: ""), q,
                listOfNotNull(q?.volume?.let { "24h vol " + NumFmt.big(it.toDouble()) }, if (px != null && usdLkr != null) "LKR " + NumFmt.price(px * usdLkr) else null).joinToString(" · ").ifEmpty { null }) { nav.instrument(i.symbol) }
        }
    }
}

@Composable
private fun ForexTab(global: Map<String, GQuote>, at: Long, nav: Nav) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
        item {
            WorldStamp(at)
            Banner("Market indicative rates, not Central Bank of Sri Lanka reference rates or bank buying and selling rates. Check with your bank before exchanging money.", LocalTones.current.info)
            Text("Rupees for one unit of each currency. This is a fixed list of widely used currencies, not a ranking by transaction volume.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
        }
        items(GlobalCatalog.fxCurrencies, key = { it.first }) { (code, name) ->
            val q = YahooFeed.lkrRate(code, global)
            QuoteRow(null, "$code / LKR", name + if (q?.exchange?.startsWith("Worked") == true) " · worked out from USD rates" else "", q) { nav.instrument(if (code == "USD") "LKR=X" else "${code}LKR=X") }
        }
    }
}

// ---------------------------------------------------------------- one instrument

@Composable
fun InstrumentScreen(st: UiState, vm: MainViewModel, nav: Nav, symbol: String) {
    val global by vm.repo.market.global.collectAsStateWithLifecycle()
    val inst = GlobalCatalog.find(symbol) ?: Instrument(symbol, symbol, AssetGroup.STOCK)
    val fxCode = if (symbol == "LKR=X") "USD" else symbol.removeSuffix("LKR=X")
    val q = if (inst.group == AssetGroup.FX) YahooFeed.lkrRate(fxCode, global) ?: global[symbol] else global[symbol]
    val ranges = if (inst.group == AssetGroup.FX) listOf("5D", "1M", "3M", "1Y", "5Y") else listOf("1D", "5D", "1M", "6M", "1Y", "5Y")
    var range by remember { mutableStateOf(if (inst.group == AssetGroup.FX) "1M" else "6M") }
    var candles by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(symbol, range) {
        loading = true; error = ""
        try { candles = vm.repo.globalChart(symbol, range) } catch (e: Exception) { error = e.message ?: "Could not load the chart."; candles = emptyList() }
        loading = false
    }
    val usdLkr = global["LKR=X"]?.price
    Screen(inst.name, onBack = { nav.back() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(listOf(symbol, q?.exchange ?: "", inst.country, inst.sector).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(NumFmt.price(q?.price), style = NumStyle.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold))
                Text("  " + (q?.currency ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
                Spacer(Modifier.width(10.dp)); Box(Modifier.padding(bottom = 4.dp)) { MoveText(q?.change, q?.changePct) }
            }
            Text(listOf(marketStateText(q), "Delayed", YahooFeed.SOURCE + " (unofficial)", if ((q?.time ?: 0) > 0) "last trade " + NumFmt.time(q!!.time) else "").filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RangeChips(ranges, range) { range = it }
            if (loading && candles.isEmpty()) Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (error.isNotEmpty()) Banner(error, LocalTones.current.loss)
            else PriceChart(candles, range == "1D" || range == "5D")
            Section("Key figures") {
                if (inst.group == AssetGroup.STOCK) {
                    KV("Market capitalisation", q?.marketCap?.let { NumFmt.big(it) + " " + q.currency } ?: "–")
                    q?.marketCap?.let { YahooFeed.toUsd(it, q.currency, global) }?.let { if (q.currency != "USD") KV("…in US dollars", "$" + NumFmt.big(it)) }
                    KV("P/E (trailing)", NumFmt.price(q?.pe)); KV("P/E (forward)", NumFmt.price(q?.forwardPe)); KV("EPS (trailing 12 months)", NumFmt.price(q?.eps))
                    KV("Price / book", NumFmt.price(q?.pb)); KV("Dividend yield", q?.divYieldPct?.let { String.format(java.util.Locale.US, "%.2f%%", it) } ?: "–")
                }
                if (inst.group == AssetGroup.CRYPTO) KV("Market capitalisation", q?.marketCap?.let { "$" + NumFmt.big(it) } ?: "–")
                KV("Previous close", NumFmt.price(q?.prevClose))
                KV("Day high / low", NumFmt.price(q?.dayHigh) + " / " + NumFmt.price(q?.dayLow))
                KV("52-week high / low", NumFmt.price(q?.high52) + " / " + NumFmt.price(q?.low52))
                KV(if (inst.group == AssetGroup.CRYPTO) "24-hour volume" else "Volume", q?.volume?.let { NumFmt.big(it.toDouble()) } ?: "–")
                if (inst.unit.isNotEmpty()) KV("Quoted in", inst.unit)
                val px = q?.price
                if ((inst.group == AssetGroup.METAL || inst.group == AssetGroup.ENERGY || inst.group == AssetGroup.CRYPTO) && px != null && usdLkr != null) {
                    KV("In rupees", "LKR " + NumFmt.price(px * usdLkr))
                    if (inst.group == AssetGroup.METAL) { KV("…per gram", "LKR " + NumFmt.price(px * usdLkr / GRAMS_PER_OUNCE)); KV("…per 8 g (pawn)", "LKR " + NumFmt.price(px * usdLkr / GRAMS_PER_OUNCE * 8)) }
                }
                Text("Figures as received from ${YahooFeed.SOURCE}; a dash means the source gave none. Updated ${NumFmt.time(q?.fetchedAt ?: 0)}.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            if (inst.group == AssetGroup.STOCK) Text("Quarterly and annual results, news and announcements for international companies are not included; this source does not supply them.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}
