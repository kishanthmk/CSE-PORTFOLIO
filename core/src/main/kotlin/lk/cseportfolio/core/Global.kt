package lk.cseportfolio.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One price bar. t = epoch milliseconds. A line-only series has o = h = l = c. */
data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Long? = null)

enum class AssetGroup(val label: String) { STOCK("World top 50"), METAL("Precious metals"), ENERGY("Energy"), CRYPTO("Crypto"), FX("Sri Lankan rupee") }

/** A tradable instrument followed through the Yahoo Finance quote service. */
data class Instrument(val symbol: String, val name: String, val group: AssetGroup, val country: String = "", val sector: String = "", val unit: String = "")

/** Latest quote for an instrument, exactly as received; null where the source gave nothing. */
data class GQuote(
    val symbol: String, val name: String = "", val price: Double? = null, val change: Double? = null, val changePct: Double? = null,
    val prevClose: Double? = null, val dayHigh: Double? = null, val dayLow: Double? = null, val volume: Long? = null,
    val marketCap: Double? = null, val currency: String = "", val exchange: String = "", val high52: Double? = null, val low52: Double? = null,
    val pe: Double? = null, val forwardPe: Double? = null, val eps: Double? = null, val pb: Double? = null, val divYieldPct: Double? = null,
    val marketState: String = "", val time: Long = 0, val fetchedAt: Long = 0
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("symbol" to symbol, "name" to name, "price" to price, "change" to change, "changePct" to changePct, "prevClose" to prevClose,
        "dayHigh" to dayHigh, "dayLow" to dayLow, "volume" to volume, "marketCap" to marketCap, "currency" to currency, "exchange" to exchange, "high52" to high52,
        "low52" to low52, "pe" to pe, "forwardPe" to forwardPe, "eps" to eps, "pb" to pb, "divYieldPct" to divYieldPct, "marketState" to marketState, "time" to time, "fetchedAt" to fetchedAt)
    companion object {
        private fun d(m: Map<*, *>, k: String): Double? = (m[k] as? Number)?.toDouble()
        fun fromMap(m: Map<*, *>): GQuote? {
            val s = m["symbol"] as? String ?: return null
            return GQuote(s, m["name"] as? String ?: "", d(m, "price"), d(m, "change"), d(m, "changePct"), d(m, "prevClose"), d(m, "dayHigh"), d(m, "dayLow"),
                (m["volume"] as? Number)?.toLong(), d(m, "marketCap"), m["currency"] as? String ?: "", m["exchange"] as? String ?: "", d(m, "high52"), d(m, "low52"),
                d(m, "pe"), d(m, "forwardPe"), d(m, "eps"), d(m, "pb"), d(m, "divYieldPct"), m["marketState"] as? String ?: "", (m["time"] as? Number)?.toLong() ?: 0,
                (m["fetchedAt"] as? Number)?.toLong() ?: 0)
        }
    }
}

data class RankedStock(val rank: Int, val instrument: Instrument, val quote: GQuote?, val marketCapUsd: Double?)

object GlobalCatalog {
    private fun s(sym: String, name: String, country: String, sector: String) = Instrument(sym, name, AssetGroup.STOCK, country, sector)

    /**
     * Candidate universe for the world top 50. Selection method: the largest listed companies by market
     * capitalisation, using each company's primary or most liquid listing (a US listing where one exists).
     * The app ranks these candidates by the market capitalisation received on every refresh and shows the
     * top 50, so the order changes with the market. Companies outside this list cannot enter the ranking;
     * edit this list to change the universe.
     */
    val stocks: List<Instrument> = listOf(
        s("NVDA", "NVIDIA", "United States", "Semiconductors"), s("MSFT", "Microsoft", "United States", "Software"), s("AAPL", "Apple", "United States", "Technology hardware"),
        s("GOOGL", "Alphabet (Google)", "United States", "Internet"), s("AMZN", "Amazon", "United States", "Retail and cloud"), s("META", "Meta Platforms", "United States", "Internet"),
        s("AVGO", "Broadcom", "United States", "Semiconductors"), s("TSM", "TSMC", "Taiwan", "Semiconductors"), s("2222.SR", "Saudi Aramco", "Saudi Arabia", "Oil and gas"),
        s("TSLA", "Tesla", "United States", "Automobiles"), s("BRK-B", "Berkshire Hathaway", "United States", "Diversified"), s("JPM", "JPMorgan Chase", "United States", "Banks"),
        s("WMT", "Walmart", "United States", "Retail"), s("LLY", "Eli Lilly", "United States", "Pharmaceuticals"), s("ORCL", "Oracle", "United States", "Software"),
        s("V", "Visa", "United States", "Payments"), s("MA", "Mastercard", "United States", "Payments"), s("NFLX", "Netflix", "United States", "Media"),
        s("XOM", "Exxon Mobil", "United States", "Oil and gas"), s("COST", "Costco", "United States", "Retail"), s("005930.KS", "Samsung Electronics", "South Korea", "Technology hardware"),
        s("ASML", "ASML", "Netherlands", "Semiconductor equipment"), s("JNJ", "Johnson & Johnson", "United States", "Pharmaceuticals"), s("PG", "Procter & Gamble", "United States", "Consumer goods"),
        s("HD", "Home Depot", "United States", "Retail"), s("BAC", "Bank of America", "United States", "Banks"), s("ABBV", "AbbVie", "United States", "Pharmaceuticals"),
        s("PLTR", "Palantir", "United States", "Software"), s("0700.HK", "Tencent", "China", "Internet"), s("AMD", "AMD", "United States", "Semiconductors"),
        s("KO", "Coca-Cola", "United States", "Beverages"), s("SAP", "SAP", "Germany", "Software"), s("UNH", "UnitedHealth", "United States", "Health care"),
        s("CSCO", "Cisco", "United States", "Networking"), s("BABA", "Alibaba", "China", "Internet"), s("ROG.SW", "Roche", "Switzerland", "Pharmaceuticals"),
        s("NESN.SW", "Nestlé", "Switzerland", "Food"), s("CVX", "Chevron", "United States", "Oil and gas"), s("WFC", "Wells Fargo", "United States", "Banks"),
        s("GE", "GE Aerospace", "United States", "Aerospace"), s("IBM", "IBM", "United States", "Software and services"), s("MS", "Morgan Stanley", "United States", "Capital markets"),
        s("PM", "Philip Morris", "United States", "Tobacco"), s("AZN", "AstraZeneca", "United Kingdom", "Pharmaceuticals"), s("MC.PA", "LVMH", "France", "Luxury goods"),
        s("TM", "Toyota", "Japan", "Automobiles"), s("1398.HK", "ICBC", "China", "Banks"), s("NVO", "Novo Nordisk", "Denmark", "Pharmaceuticals"),
        s("HSBC", "HSBC", "United Kingdom", "Banks"), s("CRM", "Salesforce", "United States", "Software"), s("LIN", "Linde", "United States", "Chemicals"),
        s("ABT", "Abbott", "United States", "Health care"), s("MCD", "McDonald's", "United States", "Restaurants"), s("600519.SS", "Kweichow Moutai", "China", "Beverages"),
        s("300750.SZ", "CATL", "China", "Batteries"), s("RELIANCE.NS", "Reliance Industries", "India", "Conglomerate"), s("HDB", "HDFC Bank", "India", "Banks"),
        s("GS", "Goldman Sachs", "United States", "Capital markets"), s("MRK", "Merck & Co.", "United States", "Pharmaceuticals"), s("AXP", "American Express", "United States", "Payments"),
        s("INTU", "Intuit", "United States", "Software"), s("RTX", "RTX", "United States", "Aerospace"), s("T", "AT&T", "United States", "Telecoms"),
        s("SHEL", "Shell", "United Kingdom", "Oil and gas"), s("MU", "Micron", "United States", "Semiconductors"), s("RY", "Royal Bank of Canada", "Canada", "Banks")
    )

    val commodities: List<Instrument> = listOf(
        Instrument("GC=F", "Gold", AssetGroup.METAL, unit = "USD per troy ounce"), Instrument("SI=F", "Silver", AssetGroup.METAL, unit = "USD per troy ounce"),
        Instrument("PL=F", "Platinum", AssetGroup.METAL, unit = "USD per troy ounce"), Instrument("PA=F", "Palladium", AssetGroup.METAL, unit = "USD per troy ounce"),
        Instrument("BZ=F", "Brent crude oil", AssetGroup.ENERGY, unit = "USD per barrel"), Instrument("CL=F", "WTI crude oil", AssetGroup.ENERGY, unit = "USD per barrel"),
        Instrument("NG=F", "Natural gas", AssetGroup.ENERGY, unit = "USD per MMBtu")
    )

    val crypto: List<Instrument> = listOf("BTC" to "Bitcoin", "ETH" to "Ethereum", "BNB" to "BNB", "SOL" to "Solana", "XRP" to "XRP", "USDT" to "Tether",
        "DOGE" to "Dogecoin", "ADA" to "Cardano", "TRX" to "TRON", "AVAX" to "Avalanche").map { (c, n) -> Instrument("$c-USD", n, AssetGroup.CRYPTO, unit = "USD") }

    /** Major currencies against the rupee. A fixed list of widely used currencies, not a ranking by transaction volume. */
    val fxCurrencies = listOf("USD" to "US dollar", "EUR" to "Euro", "GBP" to "British pound", "INR" to "Indian rupee", "JPY" to "Japanese yen", "AUD" to "Australian dollar",
        "SGD" to "Singapore dollar", "CNY" to "Chinese yuan", "AED" to "UAE dirham", "SAR" to "Saudi riyal", "CHF" to "Swiss franc", "CAD" to "Canadian dollar")
    val fx: List<Instrument> = fxCurrencies.map { (c, n) -> Instrument(if (c == "USD") "LKR=X" else "${c}LKR=X", "$c/LKR · $n", AssetGroup.FX, unit = "LKR per $c") }
    /** USD legs used to work out rupee crosses the source does not quote directly, and to convert market caps to USD. */
    val usdLegs = listOf("EUR", "GBP", "INR", "JPY", "AUD", "SGD", "CNY", "AED", "SAR", "CHF", "CAD", "KRW", "HKD", "TWD", "DKK").map { "$it=X" }

    val all: List<Instrument> get() = stocks + commodities + crypto + fx
    fun find(symbol: String): Instrument? = all.firstOrNull { it.symbol == symbol }
}

object YahooFeed {
    const val SOURCE = "Yahoo Finance"

    private fun d(m: Map<*, *>, vararg keys: String): Double? {
        for (k in keys) when (val v = m[k]) {
            is Number -> v.toDouble().takeIf { !it.isNaN() }?.let { return it }
            is Map<*, *> -> (v["raw"] as? Number)?.toDouble()?.let { return it }
            else -> {}
        }
        return null
    }
    private fun str(m: Map<*, *>, vararg keys: String): String { for (k in keys) (m[k] as? String)?.takeIf { it.isNotBlank() }?.let { return it }; return "" }

    /** v7 quote reply → quotes by symbol. */
    fun parseQuotes(json: String, now: Long): Map<String, GQuote> {
        val root = try { Json.parse(json) as? Map<*, *> } catch (e: Exception) { null } ?: return emptyMap()
        val list = ((root["quoteResponse"] as? Map<*, *>)?.get("result") as? List<*>)?.filterIsInstance<Map<*, *>>() ?: return emptyMap()
        val out = LinkedHashMap<String, GQuote>()
        for (m in list) {
            val sym = str(m, "symbol")
            if (sym.isEmpty()) continue
            val price = d(m, "regularMarketPrice") ?: continue
            val prev = d(m, "regularMarketPreviousClose")
            val chg = d(m, "regularMarketChange") ?: prev?.let { price - it }
            val div = d(m, "trailingAnnualDividendYield")?.let { it * 100 } ?: d(m, "dividendYield")
            out[sym] = GQuote(sym, str(m, "longName", "shortName", "displayName"), price, chg, d(m, "regularMarketChangePercent") ?: if (prev != null && prev != 0.0 && chg != null) chg / prev * 100 else null,
                prev, d(m, "regularMarketDayHigh"), d(m, "regularMarketDayLow"), d(m, "regularMarketVolume")?.toLong(), d(m, "marketCap"), str(m, "currency"),
                str(m, "fullExchangeName", "exchange"), d(m, "fiftyTwoWeekHigh"), d(m, "fiftyTwoWeekLow"), d(m, "trailingPE"), d(m, "forwardPE"),
                d(m, "epsTrailingTwelveMonths"), d(m, "priceToBook"), div, str(m, "marketState"), ((d(m, "regularMarketTime") ?: 0.0) * 1000).toLong(), now)
        }
        return out
    }

    class Chart(val candles: List<Candle>, val quote: GQuote?)

    /** v8 chart reply → bars (incomplete bars are skipped) and a quote built from its header. */
    fun parseChart(json: String, now: Long): Chart {
        val root = try { Json.parse(json) as? Map<*, *> } catch (e: Exception) { null } ?: return Chart(emptyList(), null)
        val res = ((root["chart"] as? Map<*, *>)?.get("result") as? List<*>)?.firstOrNull() as? Map<*, *> ?: return Chart(emptyList(), null)
        val meta = res["meta"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val ts = (res["timestamp"] as? List<*>) ?: emptyList<Any>()
        val q = (((res["indicators"] as? Map<*, *>)?.get("quote") as? List<*>)?.firstOrNull() as? Map<*, *>) ?: emptyMap<Any, Any>()
        fun col(k: String) = (q[k] as? List<*>) ?: emptyList<Any>()
        val o = col("open"); val h = col("high"); val l = col("low"); val c = col("close"); val v = col("volume")
        val candles = ArrayList<Candle>()
        for (i in ts.indices) {
            val t = (ts[i] as? Number)?.toLong() ?: continue
            val close = (c.getOrNull(i) as? Number)?.toDouble() ?: continue
            val open = (o.getOrNull(i) as? Number)?.toDouble() ?: close
            candles += Candle(t * 1000, open, (h.getOrNull(i) as? Number)?.toDouble() ?: maxOf(open, close), (l.getOrNull(i) as? Number)?.toDouble() ?: minOf(open, close), close, (v.getOrNull(i) as? Number)?.toLong())
        }
        val sym = str(meta, "symbol")
        val price = d(meta, "regularMarketPrice")
        val prev = d(meta, "previousClose", "chartPreviousClose")
        val quote = if (sym.isEmpty() || price == null) null else GQuote(sym, str(meta, "longName", "shortName"), price, prev?.let { price - it },
            if (prev != null && prev != 0.0) (price - prev) / prev * 100 else null, prev, d(meta, "regularMarketDayHigh"), d(meta, "regularMarketDayLow"),
            d(meta, "regularMarketVolume")?.toLong(), null, str(meta, "currency"), str(meta, "fullExchangeName", "exchangeName"), d(meta, "fiftyTwoWeekHigh"),
            d(meta, "fiftyTwoWeekLow"), time = ((d(meta, "regularMarketTime") ?: 0.0) * 1000).toLong(), fetchedAt = now)
        return Chart(candles, quote)
    }

    /** Range button → Yahoo range and bar interval. */
    fun rangeParams(range: String): Pair<String, String> = when (range) {
        "1D" -> "1d" to "5m"; "5D" -> "5d" to "30m"; "1M" -> "1mo" to "1d"; "3M" -> "3mo" to "1d"; "6M" -> "6mo" to "1d"; "1Y" -> "1y" to "1d"; "5Y" -> "5y" to "1wk"; else -> "1y" to "1d"
    }

    /** Units of [currency] per US dollar from the USD legs; "GBp" (pence) is handled. */
    fun toUsd(amount: Double, currency: String, quotes: Map<String, GQuote>): Double? {
        if (currency.isEmpty() || currency == "USD") return amount
        if (currency == "GBp" || currency == "GBX") return toUsd(amount / 100, "GBP", quotes)
        val perUsd = quotes["$currency=X"]?.price ?: return null
        return if (perUsd == 0.0) null else amount / perUsd
    }

    /** Ranks the candidate universe by market capitalisation in USD, largest first, and keeps the top [n]. Candidates without a market cap are left out of the ranking. */
    fun rankTop(quotes: Map<String, GQuote>, n: Int = 50): List<RankedStock> =
        GlobalCatalog.stocks.mapNotNull { inst -> val q = quotes[inst.symbol]; val cap = q?.marketCap?.let { toUsd(it, q.currency, quotes) }; if (cap == null) null else inst to (q to cap) }
            .sortedByDescending { it.second.second }.take(n).mapIndexed { i, (inst, qc) -> RankedStock(i + 1, inst, qc.first, qc.second) }

    /** Rupee cross for a currency: the direct quote if the source has one, else worked out from the USD legs. */
    fun lkrRate(code: String, quotes: Map<String, GQuote>): GQuote? {
        val direct = quotes[if (code == "USD") "LKR=X" else "${code}LKR=X"]
        if (direct?.price != null) return direct
        val usdLkr = quotes["LKR=X"] ?: return null
        val leg = quotes["$code=X"] ?: return null
        val p = usdLkr.price ?: return null; val l = leg.price ?: return null
        if (l == 0.0) return null
        val prev = if (usdLkr.prevClose != null && leg.prevClose != null && leg.prevClose != 0.0) usdLkr.prevClose / leg.prevClose else null
        val price = p / l
        return GQuote("${code}LKR=X", "$code/LKR (via USD)", price, prev?.let { price - it }, prev?.let { (price - it) / it * 100 }, prev, currency = "LKR",
            exchange = "Worked out from USD rates", time = minOf(usdLkr.time, leg.time), fetchedAt = usdLkr.fetchedAt)
    }
}

/** CSE website chart replies, matched leniently because the field names are not documented. */
object CseChart {
    private fun num(m: Map<*, *>, vararg keys: String): Double? { for (k in keys) when (val v = m[k]) { is Number -> return v.toDouble(); is String -> v.replace(",", "").toDoubleOrNull()?.let { return it }; else -> {} }; return null }
    private val zone = ZoneId.of("Asia/Colombo")
    private val formats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd", "dd/MM/yyyy", "dd-MMM-yyyy", "MMM d, yyyy").map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }
    private fun time(v: Any?): Long? = when (v) {
        is Number -> v.toLong().let { if (it in 1..99999999999L) it * 1000 else it }
        is String -> v.trim().toLongOrNull()?.let { if (it in 1..99999999999L) it * 1000 else it } ?: formats.firstNotNullOfOrNull { f ->
            try { java.time.LocalDateTime.parse(v.trim().take(19), f).atZone(zone).toInstant().toEpochMilli() } catch (e: Exception) {
                try { java.time.LocalDate.parse(v.trim().take(11).trim(), f).atStartOfDay(zone).toInstant().toEpochMilli() } catch (e2: Exception) { null } } }
        else -> null
    }

    fun parse(json: String): List<Candle> {
        val root = try { Json.parse(json) } catch (e: Exception) { return emptyList() }
        val rows: List<Map<*, *>> = when (root) {
            is List<*> -> root.filterIsInstance<Map<*, *>>()
            is Map<*, *> -> ((root["chartData"] ?: root.values.firstOrNull { it is List<*> }) as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
            else -> emptyList()
        }
        return rows.mapNotNull { m ->
            val t = time(m["t"] ?: m["time"] ?: m["tradeDate"] ?: m["date"] ?: m["d"] ?: m["x"] ?: m["timestamp"]) ?: return@mapNotNull null
            val c = num(m, "close", "closingPrice", "c", "price", "p", "y", "value", "lastTradedPrice") ?: return@mapNotNull null
            val o = num(m, "open", "o", "openPrice") ?: c
            Candle(t, o, num(m, "high", "h", "hiTrade", "highPrice") ?: maxOf(o, c), num(m, "low", "l", "lowTrade", "lowPrice") ?: minOf(o, c), c,
                num(m, "volume", "v", "qty", "quantity", "sharevolume", "shareVolume")?.toLong())
        }.filter { it.c > 0 }.sortedBy { it.t }
    }

    /** stockId for the chart call: the id inside the companyInfoSummery reply. */
    fun stockId(companyInfoJson: String): String? = try {
        ((Json.parse(companyInfoJson) as? Map<*, *>)?.get("reqSymbolInfo") as? Map<*, *>)?.get("id")?.let { if (it is Number) it.toLong().toString() else it.toString() }
    } catch (e: Exception) { null }

    /** Index reply such as aspiData: {value, change, percentage} or similar. */
    fun parseIndex(json: String): Triple<Double, Double?, Double?>? {
        val m = try { Json.parse(json) as? Map<*, *> } catch (e: Exception) { null } ?: return null
        val v = num(m, "value", "indexValue", "price") ?: return null
        return Triple(v, num(m, "change"), num(m, "percentage", "percentageChange", "changePercentage"))
    }

    /** Keeps the bars inside the chosen range for a daily history. */
    fun cut(candles: List<Candle>, range: String, now: Long): List<Candle> {
        val day = 86_400_000L
        val from = when (range) { "5D" -> now - 7 * day; "1M" -> now - 31 * day; "3M" -> now - 92 * day; "6M" -> now - 183 * day; "1Y" -> now - 366 * day; else -> 0L }
        return candles.filter { it.t >= from }
    }
}

/** Simple moving average of closes; null until enough bars. */
fun sma(c: List<Candle>, n: Int): List<Double?> = c.indices.map { i -> if (i + 1 < n) null else (i + 1 - n..i).sumOf { c[it].c } / n }

object NumFmt {
    fun price(v: Double?): String = when {
        v == null -> "–"
        kotlin.math.abs(v) >= 1000 -> String.format(Locale.US, "%,.2f", v)
        kotlin.math.abs(v) >= 1 -> String.format(Locale.US, "%,.2f", v)
        else -> String.format(Locale.US, "%.4f", v)
    }
    fun signed(v: Double?): String = if (v == null) "–" else (if (v > 0) "+" else "") + price(v)
    fun pct(v: Double?): String = if (v == null) "–" else (if (v > 0) "+" else "") + String.format(Locale.US, "%.2f%%", v)
    fun big(v: Double?): String {
        if (v == null) return "–"
        val a = kotlin.math.abs(v)
        return when { a >= 1e12 -> String.format(Locale.US, "%.2fT", v / 1e12); a >= 1e9 -> String.format(Locale.US, "%.2fB", v / 1e9)
            a >= 1e6 -> String.format(Locale.US, "%.2fM", v / 1e6); a >= 1e3 -> String.format(Locale.US, "%.1fK", v / 1e3); else -> String.format(Locale.US, "%.0f", v) }
    }
    fun bd(v: Double?): BigDecimal? = v?.let { BigDecimal(it).setScale(4, RoundingMode.HALF_UP) }
    fun time(ms: Long): String = if (ms <= 0) "–" else Instant.ofEpochMilli(ms).atZone(ZoneId.of("Asia/Colombo")).format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH))
}
