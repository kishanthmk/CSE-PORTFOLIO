package lk.cseportfolio.app.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import lk.cseportfolio.core.Announcement
import lk.cseportfolio.core.Candle
import lk.cseportfolio.core.GQuote
import lk.cseportfolio.core.FinPeriod
import lk.cseportfolio.core.Json
import lk.cseportfolio.core.MarketFeed
import lk.cseportfolio.core.Security

/**
 * Market research data kept as plain files beside the encrypted portfolio database:
 * the CSE security list and announcements (re-downloadable caches), and the financial
 * statement figures you type in (also included in every backup file).
 */
class MarketStore(context: Context) {
    private val dir = File(context.filesDir, "market").apply { mkdirs() }
    private val marketFile = File(dir, "market.json")
    private val newsFile = File(dir, "announcements.json")
    private val finFile = File(dir, "financials.json")

    private fun readList(f: File): List<Map<*, *>> = try {
        if (!f.exists()) emptyList() else ((Json.parse(f.readText()) as? Map<*, *>)?.get("items") as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
    } catch (e: Exception) { emptyList() }
    private fun readStamp(f: File, key: String): Long = try {
        if (!f.exists()) 0 else ((Json.parse(f.readText()) as? Map<*, *>)?.get(key) as? Number)?.toLong() ?: 0
    } catch (e: Exception) { 0 }
    private fun write(f: File, items: List<Map<String, Any?>>, extra: Map<String, Any?> = emptyMap()) {
        try { val tmp = File(f.path + ".tmp"); tmp.writeText(Json.write(extra + mapOf("items" to items))); tmp.renameTo(f) } catch (e: Exception) { }
    }

    val securities = MutableStateFlow(readList(marketFile).mapNotNull { Security.fromMap(it) })
    val pricesAt = MutableStateFlow(readStamp(marketFile, "pricesAt"))
    var directoryAt: Long = readStamp(marketFile, "directoryAt")
        private set
    val announcements = MutableStateFlow(readList(newsFile).map { Announcement.fromMap(it) })
    val announcementsAt = MutableStateFlow(readStamp(newsFile, "at"))
    val periods = MutableStateFlow(readList(finFile).mapNotNull { FinPeriod.fromMap(it) })

    // ---- world markets (Yahoo Finance), cached so the last figures show offline
    private val globalFile = File(dir, "global.json")
    val global = MutableStateFlow(readList(globalFile).mapNotNull { GQuote.fromMap(it) }.associateBy { it.symbol })
    val globalAt = MutableStateFlow(readStamp(globalFile, "at"))
    fun setGlobal(q: Map<String, GQuote>, now: Long) {
        val merged = global.value + q
        global.value = merged; globalAt.value = now
        write(globalFile, merged.values.map { it.toMap() }, mapOf("at" to now))
    }

    // ---- CSE indices
    private val indexFile = File(dir, "indices.json")
    val indices = MutableStateFlow(readList(indexFile).mapNotNull { m -> (m["name"] as? String)?.let { n -> n to listOf((m["value"] as? Number)?.toDouble(), (m["change"] as? Number)?.toDouble(), (m["pct"] as? Number)?.toDouble()) } }.toMap())
    val indicesAt = MutableStateFlow(readStamp(indexFile, "at"))
    fun setIndices(v: Map<String, List<Double?>>, now: Long) {
        indices.value = v; indicesAt.value = now
        write(indexFile, v.map { (k, x) -> linkedMapOf("name" to k, "value" to x.getOrNull(0), "change" to x.getOrNull(1), "pct" to x.getOrNull(2)) }, mapOf("at" to now))
    }

    // ---- CSE stock ids for chart requests, and a daily-close history kept from every refresh
    private val idFile = File(dir, "stockids.json")
    val stockIds: MutableMap<String, String> = try { ((Json.parse(idFile.readText()) as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap()).toMutableMap() } catch (e: Exception) { HashMap() }
    fun putStockId(symbol: String, id: String) { stockIds[symbol] = id; try { idFile.writeText(Json.write(stockIds)) } catch (e: Exception) { } }

    private val histFile = File(dir, "history.json")
    /** symbol → (yyyy-MM-dd → last price seen that day). Never overwritten by later days. */
    private val history: MutableMap<String, MutableMap<String, Double>> = try {
        ((Json.parse(histFile.readText()) as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to ((v as? Map<*, *>)?.entries?.associate { it.key.toString() to (it.value as Number).toDouble() }?.toMutableMap() ?: HashMap()) } ?: emptyMap()).toMutableMap()
    } catch (e: Exception) { HashMap() }
    private fun recordHistory(now: Long) {
        val day = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Asia/Colombo")).toLocalDate().toString()
        securities.value.forEach { s -> s.last?.let { history.getOrPut(s.symbol) { HashMap() }[day] = it.toDouble() } }
        try { histFile.writeText(Json.write(history)) } catch (e: Exception) { }
    }
    fun localHistory(symbol: String): List<Candle> = history[symbol]?.entries?.mapNotNull { (d, p) ->
        try { Candle(java.time.LocalDate.parse(d).atStartOfDay(java.time.ZoneId.of("Asia/Colombo")).toInstant().toEpochMilli(), p, p, p, p) } catch (e: Exception) { null } }?.sortedBy { it.t } ?: emptyList()

    private fun saveMarket() = write(marketFile, securities.value.map { it.toMap() }, mapOf("pricesAt" to pricesAt.value, "directoryAt" to directoryAt))

    /** Returns how many securities carried a price in this reply. */
    fun applyTradeSummary(body: String, now: Long): Int {
        val fresh = MarketFeed.parseSecurities(body, now)
        if (fresh.isEmpty()) return 0
        securities.value = MarketFeed.merge(securities.value, fresh)
        pricesAt.value = now
        saveMarket()
        recordHistory(now)
        return fresh.count { it.last != null }
    }

    fun applyDirectory(body: String, now: Long) {
        val names = MarketFeed.parseDirectory(body)
        if (names.isEmpty()) return
        securities.value = MarketFeed.merge(securities.value, names)
        directoryAt = now
        saveMarket()
    }

    fun setAnnouncements(list: List<Announcement>, now: Long) {
        announcements.value = list; announcementsAt.value = now
        write(newsFile, list.map { it.toMap() }, mapOf("at" to now))
    }

    fun savePeriod(p: FinPeriod, replacingKey: String? = null) {
        periods.value = FinPeriod.sort(periods.value.filter { it.key != p.key && it.key != replacingKey } + p)
        write(finFile, periods.value.map { it.toMap() })
    }
    fun deletePeriod(key: String) { periods.value = periods.value.filter { it.key != key }; write(finFile, periods.value.map { it.toMap() }) }
    fun replacePeriods(list: List<FinPeriod>) { periods.value = FinPeriod.sort(list); write(finFile, periods.value.map { it.toMap() }) }
}
