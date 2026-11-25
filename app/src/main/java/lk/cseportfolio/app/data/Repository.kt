package lk.cseportfolio.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import java.io.File
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import lk.cseportfolio.app.R
import lk.cseportfolio.core.BackupCrypto
import lk.cseportfolio.core.BrokerStatement
import lk.cseportfolio.core.CashEntry
import lk.cseportfolio.core.CashType
import lk.cseportfolio.core.CostMethod
import lk.cseportfolio.core.CseFeed
import lk.cseportfolio.core.Candle
import lk.cseportfolio.core.CseChart
import lk.cseportfolio.core.GQuote
import lk.cseportfolio.core.GlobalCatalog
import lk.cseportfolio.core.YahooFeed
import lk.cseportfolio.core.FinPeriod
import lk.cseportfolio.core.MarketFeed
import lk.cseportfolio.core.FeeSchedule
import lk.cseportfolio.core.Fmt
import lk.cseportfolio.core.ImportedTxn
import lk.cseportfolio.core.InterestMethod
import lk.cseportfolio.core.Json
import lk.cseportfolio.core.MarginAccount
import lk.cseportfolio.core.MarginEntry
import lk.cseportfolio.core.MarginEntryType
import lk.cseportfolio.core.PayMethod
import lk.cseportfolio.core.Quote
import lk.cseportfolio.core.Reports
import lk.cseportfolio.core.StatementParser
import lk.cseportfolio.core.Txn
import lk.cseportfolio.core.TxnType
import lk.cseportfolio.core.parseDecimal

// ---------------------------------------------------------------- mappers

fun TxnEntity.toCore() = Txn(id, date, symbol, type, quantity, price, brokerage, cseFee, secFee, cdsFee, tax, other,
    payment, marginAccountId, financedAmount, loanRepayment, splitNum, splitDen, note)

fun CashEntity.toCore() = CashEntry(id, date, type, amount, symbol, note)

fun MarginAccountEntity.toCore() = MarginAccount(id, broker, accountNo, facilityLimit, interestRate, method, startDate, repaymentDate,
    warnUtilizationPct, marginCallLtvPct, wholePortfolioCollateral, note)

fun MarginEntryEntity.toCore() = MarginEntry(id, accountId, date, type, amount, rate, note)

fun FeeEntity.toCore() = FeeSchedule(id, name, effectiveFrom, brokeragePct, csePct, secPct, cdsPct, taxPct, otherPct, minBrokerage,
    fixedBrokerage, fixedOther, tier2Threshold, t2BrokeragePct, t2CsePct, t2SecPct, t2CdsPct)

fun FeeSchedule.toEntity() = FeeEntity(id, name, effectiveFrom, brokeragePct, csePct, secPct, cdsPct, taxPct, otherPct, minBrokerage,
    fixedBrokerage, fixedOther, tier2Threshold, t2BrokeragePct, t2CsePct, t2SecPct, t2CdsPct)

fun CompanyEntity.toQuote(): Quote? = last?.let { Quote(symbol, it, prevClose, change, changePct, volume, high52, low52, priceUpdatedAt, priceSource) }

fun TxnEntity.describe(): String = when (type) {
    TxnType.SPLIT -> "Split $splitNum:$splitDen $symbol on ${date.format(Reports.DATE)}"
    TxnType.BONUS -> "Bonus ${Fmt.qty(quantity)} $symbol on ${date.format(Reports.DATE)}"
    else -> "${type.label} ${Fmt.qty(quantity)} $symbol @ ${Fmt.money(price)} on ${date.format(Reports.DATE)}"
}

// ---------------------------------------------------------------- settings

data class Settings(
    /** 0 = follow system, 1 = light, 2 = dark (default: dark, TradingView style) */
    val theme: Int = 2,
    val costMethod: CostMethod = CostMethod.WEIGHTED_AVERAGE,
    val lockEnabled: Boolean = false,
    val autoBackup: Boolean = true,
    val backupFolder: String = "",
    val lastAutoBackup: Long = 0,
    val alerts: Boolean = true,
    /** Read prices from the Colombo Stock Exchange website (unofficial source). */
    val cseSource: Boolean = true,
    /** Refresh prices automatically when the app opens. */
    val autoRefresh: Boolean = true,
    val lastPriceRefresh: Long = 0,
    // optional market-data source supplied by the user (their broker's or a licensed vendor's API)
    val priceUrl: String = "",
    val priceAuthHeader: String = "",
    val pathLast: String = "last",
    val pathPrevClose: String = "previousClose",
    val pathChange: String = "change",
    val pathChangePct: String = "changePercentage",
    val pathVolume: String = "volume",
    val pathHigh52: String = "high52",
    val pathLow52: String = "low52"
) {
    val hasPriceSource: Boolean get() = cseSource || priceUrl.isNotBlank()
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<Settings> = _flow

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            theme = prefs.getInt("theme", d.theme),
            costMethod = try { CostMethod.valueOf(prefs.getString("costMethod", d.costMethod.name)!!) } catch (e: Exception) { d.costMethod },
            lockEnabled = prefs.getBoolean("lockEnabled", d.lockEnabled),
            autoBackup = prefs.getBoolean("autoBackup", d.autoBackup),
            backupFolder = prefs.getString("backupFolder", d.backupFolder)!!,
            lastAutoBackup = prefs.getLong("lastAutoBackup", 0),
            alerts = prefs.getBoolean("alerts", d.alerts),
            cseSource = prefs.getBoolean("cseSource", d.cseSource),
            autoRefresh = prefs.getBoolean("autoRefresh", d.autoRefresh),
            lastPriceRefresh = prefs.getLong("lastPriceRefresh", 0),
            priceUrl = prefs.getString("priceUrl", d.priceUrl)!!,
            priceAuthHeader = prefs.getString("priceAuthHeader", d.priceAuthHeader)!!,
            pathLast = prefs.getString("pathLast", d.pathLast)!!,
            pathPrevClose = prefs.getString("pathPrevClose", d.pathPrevClose)!!,
            pathChange = prefs.getString("pathChange", d.pathChange)!!,
            pathChangePct = prefs.getString("pathChangePct", d.pathChangePct)!!,
            pathVolume = prefs.getString("pathVolume", d.pathVolume)!!,
            pathHigh52 = prefs.getString("pathHigh52", d.pathHigh52)!!,
            pathLow52 = prefs.getString("pathLow52", d.pathLow52)!!
        )
    }

    fun update(change: (Settings) -> Settings) {
        val s = change(_flow.value)
        prefs.edit()
            .putInt("theme", s.theme).putString("costMethod", s.costMethod.name).putBoolean("lockEnabled", s.lockEnabled)
            .putBoolean("autoBackup", s.autoBackup).putString("backupFolder", s.backupFolder).putLong("lastAutoBackup", s.lastAutoBackup)
            .putBoolean("alerts", s.alerts).putString("priceUrl", s.priceUrl).putString("priceAuthHeader", s.priceAuthHeader)
            .putString("pathLast", s.pathLast).putString("pathPrevClose", s.pathPrevClose).putString("pathChange", s.pathChange)
            .putString("pathChangePct", s.pathChangePct).putString("pathVolume", s.pathVolume).putString("pathHigh52", s.pathHigh52)
            .putString("pathLow52", s.pathLow52)
            .putBoolean("cseSource", s.cseSource).putBoolean("autoRefresh", s.autoRefresh).putLong("lastPriceRefresh", s.lastPriceRefresh).apply()
        _flow.value = s
    }
}

// ---------------------------------------------------------------- repository

class Repository(private val context: Context, val db: AppDb, val settings: SettingsStore, val market: MarketStore) {
    val dao: AppDao = db.dao()
    private fun now() = System.currentTimeMillis()

    private suspend fun audit(entity: String, id: Long, action: String, summary: String, before: Any? = null, after: Any? = null) {
        dao.putAudit(AuditEntity(timestamp = now(), entity = entity, entityId = id, action = action, summary = summary,
            before = before?.toString() ?: "", after = after?.toString() ?: ""))
    }

    suspend fun seedDefaults() {
        if (dao.feeCount() == 0) {
            val id = dao.putFee(FeeSchedule.cseDefault().toEntity())
            audit("Fee schedule", id, "Created", "Starting fee schedule: CSE published equity rates")
        }
    }

    // ---- companies & prices
    suspend fun ensureCompany(symbol: String, name: String? = null) {
        val cur = dao.company(symbol)
        if (cur == null) dao.putCompany(CompanyEntity(symbol, name ?: ""))
        else if (!name.isNullOrBlank() && name != cur.name) dao.putCompany(cur.copy(name = name))
    }

    suspend fun setQuote(q: Quote) {
        val cur = dao.company(q.symbol) ?: CompanyEntity(q.symbol)
        dao.putCompany(cur.copy(last = q.last, prevClose = q.prevClose, change = q.change, changePct = q.changePct, volume = q.volume,
            high52 = q.high52 ?: cur.high52, low52 = q.low52 ?: cur.low52, priceUpdatedAt = q.updatedAtMillis, priceSource = q.source))
        checkAlerts(q)
    }

    suspend fun deleteCompany(symbol: String) = dao.deleteCompany(symbol)

    // ---- transactions
    suspend fun saveTxn(e: TxnEntity, company: String? = null): Long {
        val before = if (e.id != 0L) dao.txn(e.id) else null
        val toSave = e.copy(createdAt = before?.createdAt ?: now(), updatedAt = now(), deleted = false)
        val id = dao.putTxn(toSave)
        ensureCompany(e.symbol, company)
        audit("Transaction", id, if (before == null) "Created" else "Edited", toSave.describe(), before, toSave.copy(id = id))
        return id
    }

    suspend fun deleteTxn(id: Long) {
        val e = dao.txn(id) ?: return
        dao.putTxn(e.copy(deleted = true, updatedAt = now()))
        audit("Transaction", id, "Deleted", e.describe(), e)
    }

    suspend fun restoreTxn(id: Long) {
        val e = dao.txn(id) ?: return
        dao.putTxn(e.copy(deleted = false, updatedAt = now()))
        audit("Transaction", id, "Restored", e.describe(), after = e)
    }

    suspend fun purgeTxn(id: Long) {
        val e = dao.txn(id) ?: return
        dao.purgeTxn(id)
        audit("Transaction", id, "Permanently deleted", e.describe(), e)
    }

    // ---- cash
    private fun CashEntity.describe() = "${type.label} ${Fmt.lkr(amount)} on ${date.format(Reports.DATE)}" + (symbol?.let { " ($it)" } ?: "")

    suspend fun saveCash(e: CashEntity): Long {
        val before = if (e.id != 0L) dao.cashEntry(e.id) else null
        val s = e.copy(updatedAt = now(), deleted = false)
        val id = dao.putCash(s)
        audit("Cash entry", id, if (before == null) "Created" else "Edited", s.describe(), before, s.copy(id = id))
        return id
    }
    suspend fun deleteCash(id: Long) { val e = dao.cashEntry(id) ?: return; dao.putCash(e.copy(deleted = true, updatedAt = now())); audit("Cash entry", id, "Deleted", e.describe(), e) }
    suspend fun restoreCash(id: Long) { val e = dao.cashEntry(id) ?: return; dao.putCash(e.copy(deleted = false, updatedAt = now())); audit("Cash entry", id, "Restored", e.describe(), after = e) }
    suspend fun purgeCash(id: Long) { val e = dao.cashEntry(id) ?: return; dao.purgeCash(id); audit("Cash entry", id, "Permanently deleted", e.describe(), e) }

    // ---- margin
    suspend fun saveAccount(e: MarginAccountEntity): Long {
        val id = dao.putAccount(e)
        audit("Margin account", id, if (e.id == 0L) "Created" else "Edited", "${e.broker} ${e.accountNo}".trim(), after = e.copy(id = id))
        return id
    }
    suspend fun deleteAccount(e: MarginAccountEntity) { dao.deleteAccount(e.id); audit("Margin account", e.id, "Deleted", "${e.broker} ${e.accountNo}".trim(), e) }

    private fun MarginEntryEntity.describe() = if (type == MarginEntryType.RATE_CHANGE) "Rate change to ${Fmt.rate(rate)} on ${date.format(Reports.DATE)}"
        else "${type.label} ${Fmt.lkr(amount)} on ${date.format(Reports.DATE)}"

    suspend fun saveMarginEntry(e: MarginEntryEntity): Long {
        val before = if (e.id != 0L) dao.marginEntry(e.id) else null
        val s = e.copy(updatedAt = now(), deleted = false)
        val id = dao.putMarginEntry(s)
        audit("Margin entry", id, if (before == null) "Created" else "Edited", s.describe(), before, s.copy(id = id))
        return id
    }
    suspend fun deleteMarginEntry(id: Long) { val e = dao.marginEntry(id) ?: return; dao.putMarginEntry(e.copy(deleted = true, updatedAt = now())); audit("Margin entry", id, "Deleted", e.describe(), e) }
    suspend fun restoreMarginEntry(id: Long) { val e = dao.marginEntry(id) ?: return; dao.putMarginEntry(e.copy(deleted = false, updatedAt = now())); audit("Margin entry", id, "Restored", e.describe(), after = e) }
    suspend fun purgeMarginEntry(id: Long) { val e = dao.marginEntry(id) ?: return; dao.purgeMarginEntry(id); audit("Margin entry", id, "Permanently deleted", e.describe(), e) }

    // ---- fees
    suspend fun saveFee(e: FeeEntity): Long {
        val id = dao.putFee(e)
        audit("Fee schedule", id, if (e.id == 0L) "Created" else "Edited", "${e.name} effective ${e.effectiveFrom.format(Reports.DATE)}", after = e.copy(id = id))
        return id
    }
    suspend fun deleteFee(e: FeeEntity) { dao.deleteFee(e.id); audit("Fee schedule", e.id, "Deleted", "${e.name} effective ${e.effectiveFrom.format(Reports.DATE)}", e) }

    // ---- watchlist
    suspend fun saveWatch(e: WatchEntity) { dao.putWatch(e); ensureCompany(e.symbol, e.name.ifBlank { null }) }
    suspend fun deleteWatch(id: Long) = dao.deleteWatch(id)

    // ---- imports
    suspend fun importTxns(items: List<ImportedTxn>, schedules: List<FeeSchedule>): Int = db.withTransaction {
        var n = 0
        for (item in items) {
            val t = item.txn ?: continue
            val e = TxnEntity(date = t.date, symbol = t.symbol, type = t.type, quantity = t.quantity, price = t.price, brokerage = t.brokerage,
                cseFee = t.cseFee, secFee = t.secFee, cdsFee = t.cdsFee, tax = t.tax, other = t.other, payment = PayMethod.CASH,
                note = t.note, feeScheduleId = FeeSchedule.forDate(schedules, t.date)?.id, createdAt = now(), updatedAt = now())
            dao.putTxn(e); ensureCompany(t.symbol, item.company); n++
        }
        audit("Import", 0, "Imported", "$n transactions imported from file")
        n
    }

    /** Imports a broker Gain/Loss statement: holdings become opening balances, every listed price is stored as a dated quote. */
    suspend fun importStatement(st: BrokerStatement, includeHoldings: Boolean): Int = db.withTransaction {
        val stamp = (st.asAt ?: LocalDate.now()).atTime(14, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val source = "Broker statement ${(st.asAt ?: LocalDate.now()).format(Reports.DATE)}"
        var n = 0
        if (includeHoldings) for (t in StatementParser.toOpeningTxns(st)) {
            dao.putTxn(TxnEntity(date = t.date, symbol = t.symbol, type = TxnType.OPENING, quantity = t.quantity, price = t.price, other = t.other,
                note = t.note, createdAt = now(), updatedAt = now()))
            n++
        }
        for (l in st.lines) {
            val cur = dao.company(l.symbol) ?: CompanyEntity(l.symbol)
            if (l.price.signum() > 0 && cur.priceUpdatedAt <= stamp) dao.putCompany(cur.copy(last = l.price, prevClose = null, change = null, changePct = null,
                priceUpdatedAt = stamp, priceSource = source)) else if (dao.company(l.symbol) == null) dao.putCompany(cur)
        }
        audit("Import", 0, "Imported", "$source: $n opening balances, ${st.lines.size} prices")
        n
    }

    // ---- price alerts
    private suspend fun checkAlerts(q: Quote) {
        if (!settings.flow.value.alerts) return
        val day = 12 * 60 * 60 * 1000L
        for (w in dao.allWatch().filter { it.symbol == q.symbol }) {
            var u = w
            if (w.targetBuy != null && q.last <= w.targetBuy && now() - w.buyNotifiedAt > day) {
                Notifier.show(context, w.id.toInt() * 2, "${w.symbol} reached your buy target",
                    "Price LKR ${Fmt.money(q.last)} is at or below your target of LKR ${Fmt.money(w.targetBuy)} (${q.source}).")
                u = u.copy(buyNotifiedAt = now())
            }
            if (w.targetSell != null && q.last >= w.targetSell && now() - w.sellNotifiedAt > day) {
                Notifier.show(context, w.id.toInt() * 2 + 1, "${w.symbol} reached your sell target",
                    "Price LKR ${Fmt.money(q.last)} is at or above your target of LKR ${Fmt.money(w.targetSell)} (${q.source}).")
                u = u.copy(sellNotifiedAt = now())
            }
            if (u != w) dao.putWatch(u)
        }
    }

    // ---- market data (only when the user has configured a source)
    suspend fun fetchQuote(symbol: String): Quote = withContext(Dispatchers.IO) {
        val s = settings.flow.value
        require(s.priceUrl.isNotBlank()) { "No market-data source is configured." }
        val url = URL(s.priceUrl.replace("{symbol}", URLEncoder.encode(symbol, "UTF-8")))
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000; conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/json")
            if (s.priceAuthHeader.contains(":")) conn.setRequestProperty(s.priceAuthHeader.substringBefore(":").trim(), s.priceAuthHeader.substringAfter(":").trim())
            if (conn.responseCode !in 200..299) throw IllegalStateException("The data source answered ${conn.responseCode} for $symbol.")
            val root = Json.parse(conn.inputStream.bufferedReader().use { it.readText() })
            fun at(path: String): BigDecimal? {
                if (path.isBlank()) return null
                var cur: Any? = root
                for (part in path.split('.')) {
                    val node = cur
                    cur = when (node) {
                        is Map<*, *> -> node[part]
                        is List<*> -> node.getOrNull(part.toIntOrNull() ?: 0)
                        else -> null
                    }
                }
                val leaf = cur
                return when (leaf) { is Number -> BigDecimal(leaf.toString()); is String -> parseDecimal(leaf); else -> null }
            }
            val last = at(s.pathLast) ?: throw IllegalStateException("The reply for $symbol has no '${s.pathLast}' price field.")
            Quote(symbol, last, at(s.pathPrevClose), at(s.pathChange), at(s.pathChangePct), at(s.pathVolume)?.toLong(), at(s.pathHigh52), at(s.pathLow52),
                now(), "API " + (url.host ?: ""))
        } finally { conn.disconnect() }
    }

    // ---- Colombo Stock Exchange website (unofficial source)
    private fun csePost(endpoint: String, form: String): String {
        val conn = URL(CseFeed.BASE + endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000; conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Origin", "https://www.cse.lk")
            conn.setRequestProperty("Referer", "https://www.cse.lk/")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) CSEPortfolioPro")
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 404) return ""
            if (code !in 200..299) throw IllegalStateException("The CSE website answered $code.")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    /**
     * Updates prices for the companies you track from the CSE website: one request for everything
     * that traded today, then one request each (at most 30) for tracked companies that did not trade.
     * Returns a short message for the user. Companies you do not track are not added.
     */
    suspend fun refreshFromCse(only: List<String>? = null): String = withContext(Dispatchers.IO) {
        val tracked = (only ?: dao.allCompanies().map { it.symbol }).map { it.uppercase() }.distinct()
        val stamp = now()
        val body = try { csePost("tradeSummary", "") }
            catch (e: java.io.IOException) { throw IllegalStateException("Could not reach the CSE website. Check your internet connection.") }
        val summary = CseFeed.parseTradeSummary(body, stamp)
        // the same reply feeds the Market Watch list; the full security list is topped up at most once a day
        val listed = market.applyTradeSummary(body, stamp)
        try { refreshIndices() } catch (e: Exception) { }
        if (stamp - market.directoryAt > 24 * 60 * 60 * 1000L) try { market.applyDirectory(csePost("allSecurityCode", ""), stamp) } catch (e: Exception) { }
        if (tracked.isEmpty()) { settings.update { it.copy(lastPriceRefresh = stamp) }
            return@withContext if (listed > 0) "Market Watch updated: $listed securities." else "The CSE website returned no prices. The market may not have opened yet." }
        var updated = 0
        val missing = ArrayList<String>()
        for (sym in tracked) { val q = summary[sym]; if (q != null) { setQuote(q); updated++ } else missing.add(sym) }
        var failed = 0
        for (sym in missing.take(30)) try {
            val body = csePost("companyInfoSummery", "symbol=" + URLEncoder.encode(sym, "UTF-8"))
            val q = CseFeed.parseCompanyInfo(body, sym, stamp)
            if (q != null) {
                setQuote(q); updated++
                val cur = dao.company(sym)
                if (cur != null && cur.name.isBlank()) CseFeed.parseCompanyName(body)?.let { dao.putCompany(cur.copy(name = it)) }
            } else failed++
        } catch (e: Exception) { failed++ }
        settings.update { it.copy(lastPriceRefresh = stamp) }
        when {
            updated == 0 && summary.isEmpty() -> "The CSE website returned no prices. The market may not have opened yet; your stored prices are unchanged."
            failed > 0 -> "Updated $updated price(s) from the CSE website. $failed symbol(s) had no price there; check the symbol spelling (for example YORK.N0000)."
            else -> "Updated $updated price(s) from the CSE website."
        }
    }

    // ---- CSE indices and charts
    suspend fun refreshIndices() = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, List<Double?>>()
        for ((name, ep) in listOf("ASPI" to "aspiData", "S&P SL20" to "snpData")) try {
            CseChart.parseIndex(csePost(ep, ""))?.let { out[name] = listOf(it.first, it.second, it.third) }
        } catch (e: Exception) { }
        if (out.isNotEmpty()) market.setIndices(out, now())
    }

    /**
     * Price history for a CSE security. 1D uses the session's trades; longer ranges use about a year of
     * daily figures. If the CSE website gives nothing, the daily prices this app has recorded are used.
     * Returns the bars and a label naming their source.
     */
    suspend fun cseChart(symbol: String, range: String): Pair<List<Candle>, String> = withContext(Dispatchers.IO) {
        var id = market.stockIds[symbol]
        if (id == null) try { CseChart.stockId(csePost("companyInfoSummery", "symbol=" + URLEncoder.encode(symbol, "UTF-8")))?.let { id = it; market.putStockId(symbol, it) } } catch (e: Exception) { }
        val stock = id
        if (stock != null) try {
            val c = CseChart.parse(csePost("companyChartDataByStock", "stockId=$stock&period=" + if (range == "1D") "1" else "5"))
            val cut = if (range == "1D") c else CseChart.cut(c, range, now())
            if (cut.size >= 2) return@withContext cut to "CSE website"
        } catch (e: Exception) { }
        val local = CseChart.cut(market.localHistory(symbol), range, now())
        local to "Prices recorded by this app on each refresh"
    }

    // ---- world markets: Yahoo Finance (unofficial, delayed)
    private val cookies = java.net.CookieManager().also { java.net.CookieHandler.setDefault(it) }
    private var crumb: String? = null
    private fun httpGet(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20000; conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept", "application/json,text/plain,*/*")
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to body
        } finally { conn.disconnect() }
    }
    private fun yahooCrumb(): String? {
        crumb?.let { return it }
        try { httpGet("https://fc.yahoo.com/") } catch (e: Exception) { }
        val (code, body) = try { httpGet("https://query2.finance.yahoo.com/v1/test/getcrumb") } catch (e: Exception) { return null }
        return if (code == 200 && body.isNotBlank() && body.length < 40 && !body.contains("<")) body.trim().also { crumb = it } else null
    }
    private fun yahooQuotes(symbols: List<String>): Map<String, GQuote> {
        val out = LinkedHashMap<String, GQuote>()
        val c = yahooCrumb()
        if (c != null) for (chunk in symbols.chunked(40)) try {
            val (code, body) = httpGet("https://query1.finance.yahoo.com/v7/finance/quote?symbols=" + URLEncoder.encode(chunk.joinToString(","), "UTF-8") + "&crumb=" + URLEncoder.encode(c, "UTF-8"))
            if (code == 401 || code == 403) { crumb = null; break }
            out += YahooFeed.parseQuotes(body, now())
        } catch (e: Exception) { }
        return out
    }
    /** Header figures from the chart service, used when the quote service is unavailable (no market cap or ratios). */
    private suspend fun chartQuotes(symbols: List<String>): Map<String, GQuote> = coroutineScope {
        symbols.chunked(8).flatMap { group -> group.map { sym -> async(Dispatchers.IO) {
            try { YahooFeed.parseChart(httpGet("https://query1.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(sym, "UTF-8") + "?range=5d&interval=1d").second, now()).quote } catch (e: Exception) { null }
        } }.awaitAll() }.filterNotNull().associateBy { it.symbol }
    }

    /** Refreshes the world top-50 candidates, commodities, crypto and rupee rates. Returns a message for the user. */
    suspend fun refreshGlobal(): String = withContext(Dispatchers.IO) {
        val symbols = (GlobalCatalog.all.map { it.symbol } + GlobalCatalog.usdLegs).distinct()
        var q = yahooQuotes(symbols)
        var partial = false
        val missing = symbols.filter { it !in q }
        if (missing.isNotEmpty()) { q = q + chartQuotes(missing); partial = q.size < symbols.size }
        if (q.isEmpty()) throw IllegalStateException("Could not reach Yahoo Finance. Check your internet connection and try again.")
        market.setGlobal(q, now())
        if (partial) "Updated ${q.size} of ${symbols.size} world prices. Market capitalisation and ratios need the full quote service; some may be missing."
        else "World markets updated: ${q.size} prices."
    }

    suspend fun globalChart(symbol: String, range: String): List<Candle> = withContext(Dispatchers.IO) {
        val (r, i) = YahooFeed.rangeParams(range)
        val (code, body) = try { httpGet("https://query1.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(symbol, "UTF-8") + "?range=$r&interval=$i") }
            catch (e: java.io.IOException) { throw IllegalStateException("Could not reach Yahoo Finance. Check your internet connection.") }
        if (code !in 200..299) throw IllegalStateException("No chart available for $symbol just now.")
        val parsed = YahooFeed.parseChart(body, now())
        parsed.quote?.let { cq -> val old = market.global.value[symbol]; market.setGlobal(mapOf(symbol to (old?.copy(price = cq.price, change = cq.change, changePct = cq.changePct, prevClose = cq.prevClose, fetchedAt = cq.fetchedAt, time = cq.time) ?: cq)), now()) }
        parsed.candles
    }

    /** Downloads today's company announcements and financial filings from the CSE website. */
    suspend fun refreshAnnouncements(): String = withContext(Dispatchers.IO) {
        val all = ArrayList<lk.cseportfolio.core.Announcement>()
        var reached = false
        for ((endpoint, category) in listOf("approvedAnnouncement" to "", "getFinancialAnnouncement" to "Financial statements")) try {
            all += MarketFeed.parseAnnouncements(csePost(endpoint, ""), category); reached = true
        } catch (e: Exception) { }
        if (!reached) throw IllegalStateException("Could not reach the CSE website. Check your internet connection.")
        val list = all.distinctBy { it.title + "|" + it.company + "|" + it.dateMillis + "|" + it.url }.sortedByDescending { it.dateMillis }
        market.setAnnouncements(list, now())
        if (list.isEmpty()) "The CSE website returned no announcements just now." else "Loaded ${list.size} announcement(s) from the CSE website."
    }

    /** Every field the CSE website publishes for one security, as label/value pairs. */
    suspend fun fetchCompanyInfo(symbol: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val body = try { csePost("companyInfoSummery", "symbol=" + URLEncoder.encode(symbol, "UTF-8")) }
            catch (e: java.io.IOException) { throw IllegalStateException("Could not reach the CSE website. Check your internet connection.") }
        val rows = MarketFeed.flatten(body)
        if (rows.isEmpty()) throw IllegalStateException("The CSE website has no details for $symbol.")
        rows
    }

    // ---- backup
    suspend fun backupBytes(password: String?): ByteArray = withContext(Dispatchers.IO) {
        val json = Json.write(Backup.export(dao) + mapOf("financials" to market.periods.value.map { it.toMap() })).toByteArray(Charsets.UTF_8)
        if (password.isNullOrEmpty()) json else BackupCrypto.encrypt(json, password.toCharArray())
    }

    /** Replaces all data with the contents of a backup file. */
    suspend fun restore(data: ByteArray, password: String?): String = withContext(Dispatchers.IO) {
        val plain = if (BackupCrypto.isEncrypted(data)) {
            if (password.isNullOrEmpty()) throw IllegalArgumentException("This backup is password-protected. Enter its password.")
            try { BackupCrypto.decrypt(data, password.toCharArray()) } catch (e: Exception) { throw IllegalArgumentException("Wrong password, or the backup file is damaged.") }
        } else data
        val root = try { Json.parse(String(plain, Charsets.UTF_8)) as Map<*, *> } catch (e: Exception) { throw IllegalArgumentException("This is not a CSE Portfolio Pro backup file.") }
        if (root["app"] != "CSEPortfolioPro") throw IllegalArgumentException("This is not a CSE Portfolio Pro backup file.")
        db.withTransaction { Backup.import(dao, root) }
        (root["financials"] as? List<*>)?.let { l -> market.replacePeriods(l.filterIsInstance<Map<*, *>>().mapNotNull { FinPeriod.fromMap(it) }) }
        audit("Backup", 0, "Restored", "All data replaced from a backup made on ${root["exportedAt"]}")
        "Backup restored."
    }

    /** Writes a dated backup to private storage (and to the chosen folder, if any). Keeps the 10 most recent private copies. */
    suspend fun autoBackup(force: Boolean = false): String? = withContext(Dispatchers.IO) {
        val s = settings.flow.value
        if (!force && (!s.autoBackup || now() - s.lastAutoBackup < 20 * 60 * 60 * 1000L)) return@withContext null
        if (dao.allTxns().isEmpty() && dao.allCash().isEmpty()) return@withContext null
        val pw = Vault.get(context, "backupPassword")
        val bytes = backupBytes(pw)
        val name = "cse-portfolio-${LocalDate.now()}.cseb"
        val dir = File(context.filesDir, "backups").apply { mkdirs() }
        File(dir, name).writeBytes(bytes)
        dir.listFiles()?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
        var where = "private app storage"
        if (s.backupFolder.isNotEmpty()) try {
            val tree = Uri.parse(s.backupFolder)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", name)
            if (doc != null) { context.contentResolver.openOutputStream(doc)?.use { it.write(bytes) }; where = "your backup folder" }
        } catch (e: Exception) { where = "private app storage (the backup folder could not be written)" }
        settings.update { it.copy(lastAutoBackup = now()) }
        "Backup saved to $where."
    }
}

// ---------------------------------------------------------------- notifications

object Notifier {
    private const val CHANNEL = "price_targets"

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun show(context: Context, id: Int, title: String, text: String) {
        if (!canNotify(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Price targets", NotificationManager.IMPORTANCE_DEFAULT))
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_chart)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).build()
        try { nm.notify(id, n) } catch (e: SecurityException) { }
    }
}

// ---------------------------------------------------------------- backup format

object Backup {
    private fun dec(v: Any?): BigDecimal? = when (v) { null -> null; is BigDecimal -> v; else -> v.toString().takeIf { it.isNotEmpty() }?.let { BigDecimal(it) } }
    private fun d0(v: Any?): BigDecimal = dec(v) ?: BigDecimal.ZERO
    private fun lng(v: Any?): Long = when (v) { is Number -> v.toLong(); null -> 0L; else -> v.toString().toLongOrNull() ?: 0L }
    private fun str(v: Any?): String = v?.toString() ?: ""
    private fun date(v: Any?): LocalDate? = v?.toString()?.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
    private fun bool(v: Any?): Boolean = v == true
    private fun s(v: BigDecimal?): String? = v?.toPlainString()
    @Suppress("UNCHECKED_CAST") private fun list(root: Map<*, *>, k: String): List<Map<*, *>> = (root[k] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

    suspend fun export(dao: AppDao): Map<String, Any?> = linkedMapOf(
        "app" to "CSEPortfolioPro", "version" to 1L, "exportedAt" to java.time.LocalDateTime.now().withNano(0).toString(),
        "companies" to dao.allCompanies().map { linkedMapOf("symbol" to it.symbol, "name" to it.name, "last" to s(it.last), "prevClose" to s(it.prevClose), "change" to s(it.change),
            "changePct" to s(it.changePct), "volume" to it.volume, "high52" to s(it.high52), "low52" to s(it.low52), "priceUpdatedAt" to it.priceUpdatedAt, "priceSource" to it.priceSource) },
        "transactions" to dao.allTxns().map { linkedMapOf("id" to it.id, "date" to it.date.toString(), "symbol" to it.symbol, "type" to it.type.name, "quantity" to it.quantity,
            "price" to s(it.price), "brokerage" to s(it.brokerage), "cseFee" to s(it.cseFee), "secFee" to s(it.secFee), "cdsFee" to s(it.cdsFee), "tax" to s(it.tax), "other" to s(it.other),
            "payment" to it.payment.name, "marginAccountId" to it.marginAccountId, "financedAmount" to s(it.financedAmount), "loanRepayment" to s(it.loanRepayment),
            "splitNum" to it.splitNum.toLong(), "splitDen" to it.splitDen.toLong(), "note" to it.note, "feeScheduleId" to it.feeScheduleId, "createdAt" to it.createdAt,
            "updatedAt" to it.updatedAt, "deleted" to it.deleted) },
        "cash" to dao.allCash().map { linkedMapOf("id" to it.id, "date" to it.date.toString(), "type" to it.type.name, "amount" to s(it.amount), "symbol" to it.symbol, "note" to it.note,
            "updatedAt" to it.updatedAt, "deleted" to it.deleted) },
        "marginAccounts" to dao.allAccounts().map { linkedMapOf("id" to it.id, "broker" to it.broker, "accountNo" to it.accountNo, "facilityLimit" to s(it.facilityLimit),
            "interestRate" to s(it.interestRate), "method" to it.method.name, "startDate" to it.startDate?.toString(), "repaymentDate" to it.repaymentDate?.toString(),
            "warnUtilizationPct" to s(it.warnUtilizationPct), "marginCallLtvPct" to s(it.marginCallLtvPct), "wholePortfolioCollateral" to it.wholePortfolioCollateral, "note" to it.note) },
        "marginEntries" to dao.allMarginEntries().map { linkedMapOf("id" to it.id, "accountId" to it.accountId, "date" to it.date.toString(), "type" to it.type.name,
            "amount" to s(it.amount), "rate" to s(it.rate), "note" to it.note, "updatedAt" to it.updatedAt, "deleted" to it.deleted) },
        "feeSchedules" to dao.allFees().map { linkedMapOf("id" to it.id, "name" to it.name, "effectiveFrom" to it.effectiveFrom.toString(), "brokeragePct" to s(it.brokeragePct),
            "csePct" to s(it.csePct), "secPct" to s(it.secPct), "cdsPct" to s(it.cdsPct), "taxPct" to s(it.taxPct), "otherPct" to s(it.otherPct), "minBrokerage" to s(it.minBrokerage),
            "fixedBrokerage" to s(it.fixedBrokerage), "fixedOther" to s(it.fixedOther), "tier2Threshold" to s(it.tier2Threshold), "t2BrokeragePct" to s(it.t2BrokeragePct),
            "t2CsePct" to s(it.t2CsePct), "t2SecPct" to s(it.t2SecPct), "t2CdsPct" to s(it.t2CdsPct)) },
        "watchlist" to dao.allWatch().map { linkedMapOf("id" to it.id, "symbol" to it.symbol, "name" to it.name, "targetBuy" to s(it.targetBuy), "targetSell" to s(it.targetSell), "notes" to it.notes) },
        "audit" to dao.allAudit().map { linkedMapOf("id" to it.id, "timestamp" to it.timestamp, "entity" to it.entity, "entityId" to it.entityId, "action" to it.action,
            "summary" to it.summary, "before" to it.before, "after" to it.after) }
    )

    suspend fun import(dao: AppDao, root: Map<*, *>) {
        dao.clearTxns(); dao.clearCash(); dao.clearMarginEntries(); dao.clearAccounts(); dao.clearFees(); dao.clearCompanies(); dao.clearWatch(); dao.clearAudit()
        dao.putCompanies(list(root, "companies").map { CompanyEntity(str(it["symbol"]), str(it["name"]), dec(it["last"]), dec(it["prevClose"]), dec(it["change"]), dec(it["changePct"]),
            (it["volume"] as? Number)?.toLong(), dec(it["high52"]), dec(it["low52"]), lng(it["priceUpdatedAt"]), str(it["priceSource"])) })
        dao.putTxns(list(root, "transactions").map { TxnEntity(lng(it["id"]), date(it["date"])!!, str(it["symbol"]), TxnType.valueOf(str(it["type"])), lng(it["quantity"]), d0(it["price"]),
            d0(it["brokerage"]), d0(it["cseFee"]), d0(it["secFee"]), d0(it["cdsFee"]), d0(it["tax"]), d0(it["other"]), PayMethod.valueOf(str(it["payment"])),
            (it["marginAccountId"] as? Number)?.toLong(), d0(it["financedAmount"]), d0(it["loanRepayment"]), lng(it["splitNum"]).toInt().coerceAtLeast(1), lng(it["splitDen"]).toInt().coerceAtLeast(1),
            str(it["note"]), (it["feeScheduleId"] as? Number)?.toLong(), lng(it["createdAt"]), lng(it["updatedAt"]), bool(it["deleted"])) })
        dao.putCashList(list(root, "cash").map { CashEntity(lng(it["id"]), date(it["date"])!!, CashType.valueOf(str(it["type"])), d0(it["amount"]), it["symbol"]?.toString(), str(it["note"]),
            lng(it["updatedAt"]), bool(it["deleted"])) })
        dao.putAccounts(list(root, "marginAccounts").map { MarginAccountEntity(lng(it["id"]), str(it["broker"]), str(it["accountNo"]), d0(it["facilityLimit"]), d0(it["interestRate"]),
            InterestMethod.valueOf(str(it["method"])), date(it["startDate"]), date(it["repaymentDate"]), d0(it["warnUtilizationPct"]), d0(it["marginCallLtvPct"]),
            bool(it["wholePortfolioCollateral"]), str(it["note"])) })
        dao.putMarginEntries(list(root, "marginEntries").map { MarginEntryEntity(lng(it["id"]), lng(it["accountId"]), date(it["date"])!!, MarginEntryType.valueOf(str(it["type"])),
            d0(it["amount"]), d0(it["rate"]), str(it["note"]), lng(it["updatedAt"]), bool(it["deleted"])) })
        dao.putFees(list(root, "feeSchedules").map { FeeEntity(lng(it["id"]), str(it["name"]), date(it["effectiveFrom"])!!, d0(it["brokeragePct"]), d0(it["csePct"]), d0(it["secPct"]),
            d0(it["cdsPct"]), d0(it["taxPct"]), d0(it["otherPct"]), d0(it["minBrokerage"]), d0(it["fixedBrokerage"]), d0(it["fixedOther"]), dec(it["tier2Threshold"]),
            d0(it["t2BrokeragePct"]), d0(it["t2CsePct"]), d0(it["t2SecPct"]), d0(it["t2CdsPct"])) })
        dao.putWatchList(list(root, "watchlist").map { WatchEntity(lng(it["id"]), str(it["symbol"]), str(it["name"]), dec(it["targetBuy"]), dec(it["targetSell"]), str(it["notes"])) })
        dao.putAuditList(list(root, "audit").map { AuditEntity(lng(it["id"]), lng(it["timestamp"]), str(it["entity"]), lng(it["entityId"]), str(it["action"]), str(it["summary"]),
            str(it["before"]), str(it["after"])) })
    }
}
