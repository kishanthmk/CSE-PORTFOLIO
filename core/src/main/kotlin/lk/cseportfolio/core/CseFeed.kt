package lk.cseportfolio.core

import java.math.BigDecimal

/**
 * Reads the JSON the Colombo Stock Exchange website (cse.lk) serves to its own pages.
 * This is an unofficial, undocumented source: field names are matched leniently so that small
 * changes on the CSE side do not break the app, and nothing is ever guessed when a price is absent.
 */
object CseFeed {
    const val BASE = "https://www.cse.lk/api/"
    const val SOURCE = "CSE website"

    private fun num(m: Map<*, *>, vararg keys: String): BigDecimal? {
        for (k in keys) when (val v = m[k]) {
            is Number -> return BigDecimal(v.toString())
            is String -> parseDecimal(v)?.let { return it }
            else -> {}
        }
        return null
    }

    private fun quote(m: Map<*, *>, symbol: String, now: Long): Quote? {
        val last = num(m, "price", "lastTradedPrice", "closingPrice")?.takeIf { it.isPos() } ?: return null
        val prev = num(m, "previousClose", "previousClosePrice")?.takeIf { it.isPos() }
        val change = num(m, "change") ?: prev?.let { last.subtract(it) }
        val pct = num(m, "percentageChange", "changePercentage")?.setScale(2, java.math.RoundingMode.HALF_UP)
            ?: if (change != null && prev != null) pctOf(change, prev) else null
        return Quote(symbol, last, prev, change, pct,
            num(m, "sharevolume", "shareVolume", "tdyShareVolume", "quantity")?.toLong(),
            num(m, "p12HiPrice", "wtdHiPrice52", "high52")?.takeIf { it.isPos() },
            num(m, "p12LowPrice", "wtdLowPrice52", "low52")?.takeIf { it.isPos() },
            now, SOURCE)
    }

    /** `POST tradeSummary`: one row per security that traded in the session. Returns quotes keyed by symbol. */
    fun parseTradeSummary(json: String, now: Long): Map<String, Quote> {
        if (json.isBlank()) return emptyMap()
        val root = Json.parse(json)
        val rows: List<*> = when (root) {
            is List<*> -> root
            is Map<*, *> -> (root["reqTradeSummery"] ?: root["reqTradeSummary"] ?: root.values.firstOrNull { it is List<*> }) as? List<*> ?: emptyList<Any>()
            else -> emptyList<Any>()
        }
        val out = LinkedHashMap<String, Quote>()
        for (r in rows) {
            val m = r as? Map<*, *> ?: continue
            val sym = (m["symbol"] as? String)?.trim()?.uppercase() ?: continue
            quote(m, sym, now)?.let { out[sym] = it }
        }
        return out
    }

    /** `POST companyInfoSummery` with `symbol=…`: used for holdings that did not trade in the session. */
    fun parseCompanyInfo(json: String, symbol: String, now: Long): Quote? {
        if (json.isBlank()) return null
        val root = Json.parse(json) as? Map<*, *> ?: return null
        val info = root["reqSymbolInfo"] as? Map<*, *> ?: root
        return quote(info, symbol, now)
    }

    /** Company name from a companyInfoSummery reply, if present. */
    fun parseCompanyName(json: String): String? =
        try { ((Json.parse(json) as? Map<*, *>)?.get("reqSymbolInfo") as? Map<*, *>)?.get("name") as? String } catch (e: Exception) { null }
}
