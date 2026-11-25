package lk.cseportfolio.core

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** One listed security as last seen on the CSE website. Every field is as published; missing fields stay null. */
data class Security(
    val symbol: String, val name: String = "", val last: BigDecimal? = null, val prevClose: BigDecimal? = null,
    val change: BigDecimal? = null, val changePct: BigDecimal? = null, val high: BigDecimal? = null, val low: BigDecimal? = null,
    val volume: Long? = null, val turnover: BigDecimal? = null, val marketCap: BigDecimal? = null, val updatedAt: Long = 0
) {
    /** Security class from the CSE code suffix, e.g. JKH.N0000 → voting ordinary share. */
    val securityClass: String get() = when (symbol.substringAfter('.', "").firstOrNull()) {
        'N' -> "Ordinary voting"; 'X' -> "Non-voting"; 'R' -> "Rights"; 'W' -> "Warrant"; 'U' -> "Unit / fund"
        'P' -> "Preference"; 'D' -> "Debenture"; 'B' -> "Bond"; null -> ""; else -> "Other"
    }
    fun toMap(): Map<String, Any?> = linkedMapOf("symbol" to symbol, "name" to name, "last" to last?.toPlainString(), "prevClose" to prevClose?.toPlainString(),
        "change" to change?.toPlainString(), "changePct" to changePct?.toPlainString(), "high" to high?.toPlainString(), "low" to low?.toPlainString(),
        "volume" to volume, "turnover" to turnover?.toPlainString(), "marketCap" to marketCap?.toPlainString(), "updatedAt" to updatedAt)
    companion object {
        fun fromMap(m: Map<*, *>): Security? {
            val s = m["symbol"] as? String ?: return null
            fun d(k: String) = (m[k] as? String)?.let { parseDecimal(it) }
            return Security(s, m["name"] as? String ?: "", d("last"), d("prevClose"), d("change"), d("changePct"), d("high"), d("low"),
                (m["volume"] as? Number)?.toLong(), d("turnover"), d("marketCap"), (m["updatedAt"] as? Number)?.toLong() ?: 0)
        }
    }
}

data class Announcement(val title: String, val company: String, val symbol: String, val category: String, val dateMillis: Long, val dateText: String, val url: String) {
    val isDividend: Boolean get() = (title + " " + category).contains("dividend", true)
    val isFinancial: Boolean get() = (title + " " + category).let { it.contains("financial", true) || it.contains("interim", true) || it.contains("annual report", true) || it.contains("quarter", true) }
    fun toMap(): Map<String, Any?> = linkedMapOf("title" to title, "company" to company, "symbol" to symbol, "category" to category, "dateMillis" to dateMillis, "dateText" to dateText, "url" to url)
    companion object {
        fun fromMap(m: Map<*, *>) = Announcement(m["title"] as? String ?: "", m["company"] as? String ?: "", m["symbol"] as? String ?: "", m["category"] as? String ?: "",
            (m["dateMillis"] as? Number)?.toLong() ?: 0, m["dateText"] as? String ?: "", m["url"] as? String ?: "")
    }
}

object MarketFeed {
    private val COLOMBO: ZoneId = ZoneId.of("Asia/Colombo")

    /** "Delayed" while the CSE is trading (Mon–Fri 09:30–14:30 Colombo time), otherwise "End of day". Never "live": this is not a licensed real-time feed. */
    fun priceStatus(fetchedAtMillis: Long): String {
        if (fetchedAtMillis <= 0) return "No data"
        val t = Instant.ofEpochMilli(fetchedAtMillis).atZone(COLOMBO)
        val trading = t.dayOfWeek != DayOfWeek.SATURDAY && t.dayOfWeek != DayOfWeek.SUNDAY &&
            !t.toLocalTime().isBefore(LocalTime.of(9, 30)) && t.toLocalTime().isBefore(LocalTime.of(14, 35))
        return if (trading) "Delayed" else "End of day"
    }

    private fun num(m: Map<*, *>, vararg keys: String): BigDecimal? {
        for (k in keys) when (val v = m[k]) { is Number -> return BigDecimal(v.toString()); is String -> parseDecimal(v)?.let { return it }; else -> {} }
        return null
    }
    private fun str(m: Map<*, *>, vararg keys: String): String {
        for (k in keys) { val v = m[k]; if (v is String && v.isNotBlank()) return v.trim() }
        return ""
    }
    /** All lists of objects found at the top level of a reply, whatever they are called. */
    private fun rows(json: String): List<Map<*, *>> {
        if (json.isBlank()) return emptyList()
        val root = try { Json.parse(json) } catch (e: Exception) { return emptyList() }
        val lists: List<List<*>> = when (root) { is List<*> -> listOf(root); is Map<*, *> -> root.values.filterIsInstance<List<*>>(); else -> emptyList() }
        return lists.flatten().filterIsInstance<Map<*, *>>()
    }

    /** Trade summary → securities with prices. */
    fun parseSecurities(json: String, now: Long): List<Security> = rows(json).mapNotNull { m ->
        val sym = str(m, "symbol").uppercase().ifEmpty { return@mapNotNull null }
        val last = num(m, "price", "lastTradedPrice", "closingPrice")?.takeIf { it.isPos() }
        val prev = num(m, "previousClose")?.takeIf { it.isPos() }
        val change = num(m, "change") ?: if (last != null && prev != null) last.subtract(prev) else null
        Security(sym, str(m, "name", "companyName"), last, prev, change,
            (num(m, "percentageChange", "changePercentage") ?: if (change != null && prev != null) pctOf(change, prev) else null)?.setScale(2, java.math.RoundingMode.HALF_UP),
            num(m, "high", "hiTrade")?.takeIf { it.isPos() }, num(m, "low", "lowTrade")?.takeIf { it.isPos() },
            num(m, "sharevolume", "shareVolume", "tdyShareVolume", "quantity")?.toLong(), num(m, "turnover", "tdyTurnover"), num(m, "marketCap"), now)
    }

    /** Security master list → symbols and names only (no prices). */
    fun parseDirectory(json: String): List<Security> = rows(json).mapNotNull { m ->
        val sym = str(m, "symbol", "securityCode", "code").uppercase()
        if (!sym.contains('.')) null else Security(sym, str(m, "name", "companyName", "securityName"))
    }

    /** Merges fresh rows into the stored list: new prices replace old, securities that did not trade keep their last stored figures. */
    fun merge(stored: List<Security>, fresh: List<Security>): List<Security> {
        val map = LinkedHashMap<String, Security>()
        stored.forEach { map[it.symbol] = it }
        for (f in fresh) {
            val old = map[f.symbol]
            map[f.symbol] = if (old == null) f else if (f.last == null) old.copy(name = f.name.ifEmpty { old.name }) else f.copy(name = f.name.ifEmpty { old.name }, marketCap = f.marketCap ?: old.marketCap)
        }
        return map.values.sortedBy { it.symbol }
    }

    /** Announcement replies vary by endpoint, so fields are matched leniently and anything unrecognised is left blank, never invented. */
    fun parseAnnouncements(json: String, defaultCategory: String = ""): List<Announcement> = rows(json).mapNotNull { m ->
        val company = str(m, "company", "companyName", "name")
        val category = str(m, "announcementCategory", "category", "type").ifEmpty { defaultCategory }
        val title = str(m, "title", "heading", "fileText", "remarks", "description", "announcementCategory").ifEmpty { category }
        if (title.isEmpty() && company.isEmpty()) return@mapNotNull null
        val rawDate = m["createdDate"] ?: m["dateOfAnnouncement"] ?: m["manualDate"] ?: m["uploadedDate"] ?: m["date"]
        val millis = when (rawDate) { is Number -> rawDate.toLong().let { if (it in 1..99999999999L) it * 1000 else it }; else -> 0L }
        val text = when (rawDate) { is String -> rawDate; else -> "" }
        var url = str(m, "filePath", "path", "attachment", "fileUrl", "url")
        if (url.isNotEmpty() && !url.startsWith("http")) url = "https://cdn.cse.lk/" + url.trimStart('/')
        Announcement(title, company, str(m, "symbol").uppercase(), category, millis, text, url)
    }.sortedByDescending { it.dateMillis }

    /** Flattens a company-info reply into label/value pairs so every field the CSE sends can be shown as-is. */
    fun flatten(json: String): List<Pair<String, String>> {
        val root = try { Json.parse(json) as? Map<*, *> } catch (e: Exception) { null } ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        fun walk(prefix: String, v: Any?) {
            when (v) {
                is Map<*, *> -> v.forEach { (k, x) -> walk(k.toString(), x) }
                is List<*> -> {}
                null -> {}
                else -> { val s = v.toString(); if (s.isNotBlank() && !prefix.equals("id", true) && !prefix.contains("logo", true) && !prefix.contains("path", true)) out.add(prefix to s) }
            }
        }
        walk("", root)
        return out
    }

    fun companyPageUrl(symbol: String): String = "https://www.cse.lk/pages/company-profile/company-profile.component.html?symbol=" + symbol
}
