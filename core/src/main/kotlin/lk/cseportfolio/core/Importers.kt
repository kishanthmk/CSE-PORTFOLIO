package lk.cseportfolio.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

object Dates {
    private val patterns = listOf("d/M/uuuu", "uuuu-M-d", "d-M-uuuu", "d.M.uuuu", "d-MMM-uuuu", "d MMM uuuu", "d/MMM/uuuu", "uuuu/M/d", "d-MMM-uu", "d/M/uu", "MMM d, uuuu")
        .map { DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH) }

    /** Accepts common Sri Lankan statement formats (day first) and Excel serial dates. */
    fun parse(raw: String?): LocalDate? {
        val s = raw?.trim()?.substringBefore('T')?.let { if (it.contains(' ') && it.contains(':')) it.substringBefore(' ') else it } ?: return null
        if (s.isEmpty()) return null
        s.toBigDecimalOrNull()?.let { n -> val d = n.toInt(); if (d in 20000..80000) return LocalDate.of(1899, 12, 30).plusDays(d.toLong()) }
        for (p in patterns) try { return LocalDate.parse(s, p) } catch (e: Exception) { }
        return null
    }
    fun iso(d: LocalDate): String = d.toString()
}

// ---------------- Broker "Portfolio Gain/Loss" statement ----------------

data class StatementLine(
    val symbol: String, val quantity: Long, val costPerShare: BigDecimal, val cost: BigDecimal,
    val price: BigDecimal, val projectedProceeds: BigDecimal, val gainLoss: BigDecimal
)

data class BrokerStatement(
    val broker: String, val account: String, val asAt: LocalDate?,
    val lines: List<StatementLine>, val totalCost: BigDecimal?, val totalProceeds: BigDecimal?, val totalGainLoss: BigDecimal?
) {
    val holdings: List<StatementLine> get() = lines.filter { it.quantity > 0 }
    /** Selling-charge rate the broker applied when projecting proceeds (e.g. 1.12), if it can be derived. */
    val impliedSellChargePct: BigDecimal? get() {
        val h = holdings.maxByOrNull { it.cost } ?: return null
        val gross = h.price.multiply(bd(h.quantity))
        return if (gross.isPos()) pctOf(gross.subtract(h.projectedProceeds), gross) else null
    }
}

object StatementParser {
    private val NUM = "-?\\(?[\\d,]+(?:\\.\\d+)?\\)?"
    private val LINE = Regex("^([A-Z0-9]{2,10}\\.[A-Z]\\d{4})\\s+($NUM)\\s+($NUM)\\s+($NUM)\\s+($NUM)\\s+($NUM)\\s+($NUM)$")
    private val TOTAL = Regex("Total\\s*:?\\s+($NUM)\\s+($NUM)\\s+($NUM)")
    private val ASAT = Regex("as\\s+at\\s+(\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4})", RegexOption.IGNORE_CASE)
    private val ACC = Regex("A/C\\s*No\\s*:?\\s*([A-Z0-9/-]+)", RegexOption.IGNORE_CASE)

    fun looksLikeGainLoss(lines: List<String>): Boolean = lines.any { it.contains("Gain/Loss", true) } && lines.any { LINE.matches(norm(it)) }

    private fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ")

    fun parse(lines: List<String>): BrokerStatement {
        val out = ArrayList<StatementLine>()
        var asAt: LocalDate? = null; var acc = ""; var tc: BigDecimal? = null; var tp: BigDecimal? = null; var tg: BigDecimal? = null
        val broker = lines.firstOrNull { it.contains("Securities", true) || it.contains("Stock Brokers", true) || it.contains("Limited", true) }?.let(::norm) ?: ""
        for (raw in lines) {
            val l = norm(raw)
            if (asAt == null) ASAT.find(l)?.let { asAt = Dates.parse(it.groupValues[1]) }
            if (acc.isEmpty()) ACC.find(l)?.let { acc = it.groupValues[1] }
            val m = LINE.find(l)
            if (m != null) {
                val g = m.groupValues
                out += StatementLine(g[1], parseDecimal(g[2])?.toLong() ?: 0, parseDecimal(g[3]) ?: ZERO, parseDecimal(g[4]) ?: ZERO,
                    parseDecimal(g[5]) ?: ZERO, parseDecimal(g[6]) ?: ZERO, parseDecimal(g[7]) ?: ZERO)
            } else TOTAL.find(l)?.let { t -> tc = parseDecimal(t.groupValues[1]); tp = parseDecimal(t.groupValues[2]); tg = parseDecimal(t.groupValues[3]) }
        }
        return BrokerStatement(broker, acc, asAt, out, tc, tp, tg)
    }

    /** Opening-balance transactions for the holdings on the statement, with ids starting at [firstId]. */
    fun toOpeningTxns(st: BrokerStatement, firstId: Long = 1): List<Txn> {
        val date = st.asAt ?: LocalDate.now()
        return st.holdings.mapIndexed { i, l ->
            Txn(firstId + i, date, l.symbol, TxnType.OPENING, l.quantity, l.costPerShare,
                // keep the statement's cost to the cent even when cost-per-share was rounded
                other = l.cost.subtract(l.costPerShare.multiply(bd(l.quantity)).r2()),
                note = "Opening balance from ${st.broker.ifEmpty { "broker" }} statement as at ${date.format(Reports.DATE)}")
        }
    }
}

// ---------------- Generic transaction table import (Excel / CSV) ----------------

enum class ImportField(val label: String, val required: Boolean, vararg val keys: String) {
    DATE("Date", true, "trade date", "transaction date", "txn date", "date", "settlement date"),
    SYMBOL("Symbol", true, "security code", "symbol", "security", "stock code", "stock", "script", "scrip", "code", "ticker"),
    COMPANY("Company name", false, "company name", "company", "security name", "name", "description"),
    TYPE("Buy / Sell", true, "buy/sell", "b/s", "transaction type", "txn type", "trans type", "type", "side", "action"),
    QUANTITY("Quantity", true, "quantity", "qty", "no of shares", "no. of shares", "shares", "units"),
    PRICE("Price", true, "price per share", "avg price", "average price", "price", "rate"),
    BROKERAGE("Brokerage", false, "brokerage fee", "brokerage", "broker commission", "commission"),
    CSE("CSE fee", false, "cse fees", "cse fee", "cse"),
    SEC("SEC levy", false, "sec cess", "sec levy", "sec fee", "sec"),
    CDS("CDS fee", false, "cds fees", "cds fee", "cds"),
    TAX("Tax / levy", false, "share transaction levy", "stl", "levy", "tax", "govt cess", "government cess"),
    OTHER("Other charges", false, "other charges", "other fees", "other"),
    TOTAL_CHARGES("Total charges", false, "total charges", "total fees", "charges", "fees", "total commission"),
    NET("Net amount", false, "net amount", "net value", "net proceeds", "settlement amount", "net", "amount", "total"),
    PAYMENT("Cash / Margin", false, "payment method", "payment", "funding", "cash/margin"),
    NOTE("Note", false, "note", "notes", "remarks", "remark", "contract no", "contract note", "reference")
}

data class ImportedTxn(
    val row: Int, val txn: Txn?, val company: String?, val duplicate: Boolean, val feesEstimated: Boolean, val error: String?
) { val ok: Boolean get() = txn != null && error == null }

data class ImportPreview(val items: List<ImportedTxn>) {
    val valid get() = items.filter { it.ok && !it.duplicate }
    val duplicates get() = items.count { it.ok && it.duplicate }
    val errors get() = items.filter { !it.ok }
}

object TxnImport {
    private fun clean(s: String) = s.lowercase().replace(Regex("[^a-z0-9/. ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Index of the header row: the first of the top 30 rows that names at least three known columns. */
    fun detectHeader(rows: List<List<String>>): Int {
        for (i in 0 until minOf(rows.size, 30)) { if (autoMap(rows[i]).size >= 3) return i }
        return 0
    }

    fun autoMap(header: List<String>): Map<ImportField, Int> {
        val h = header.map(::clean)
        val map = LinkedHashMap<ImportField, Int>()
        val taken = HashSet<Int>()
        // exact matches first, then "contains", so "Net amount" is not swallowed by "amount" etc.
        for (pass in 0..1) for (f in ImportField.values()) {
            if (map.containsKey(f)) continue
            for (k in f.keys) {
                val idx = h.indices.firstOrNull { it !in taken && (if (pass == 0) h[it] == k else h[it].contains(k)) }
                if (idx != null) { map[f] = idx; taken.add(idx); break }
            }
        }
        return map
    }

    fun key(date: LocalDate, symbol: String, type: TxnType, qty: Long, price: BigDecimal): String =
        "$date|${symbol.uppercase()}|${type.name}|$qty|${price.stripTrailingZeros().toPlainString()}"

    fun key(t: Txn): String = key(t.date, t.symbol, t.type, t.quantity, t.price)

    fun parseType(raw: String): TxnType? {
        val s = raw.trim().uppercase()
        return when {
            s == "B" || s.startsWith("BUY") || s.startsWith("BOUGHT") || s.startsWith("PUR") -> TxnType.BUY
            s == "S" || s.startsWith("SELL") || s.startsWith("SALE") || s.startsWith("SOLD") -> TxnType.SELL
            s.startsWith("BONUS") -> TxnType.BONUS
            s.startsWith("RIGHT") -> TxnType.RIGHTS
            s.startsWith("OPEN") -> TxnType.OPENING
            else -> null
        }
    }

    /**
     * Converts table rows to transactions. Fees come from the file when present; a lone
     * "total charges" or "net amount" column is split across the fee components in the proportions
     * of the fee schedule in force on the trade date; with no fee data at all the schedule is applied
     * and the row is flagged as estimated.
     */
    fun parse(rows: List<List<String>>, headerRow: Int, mapping: Map<ImportField, Int>, schedules: List<FeeSchedule>,
              existingKeys: Set<String>, firstId: Long = 1): ImportPreview {
        val items = ArrayList<ImportedTxn>()
        val seen = HashSet<String>(existingKeys)
        var id = firstId
        fun cell(r: List<String>, f: ImportField): String? = mapping[f]?.let { r.getOrNull(it) }?.takeIf { it.isNotBlank() }
        for (ri in headerRow + 1 until rows.size) {
            val r = rows[ri]
            if (r.all { it.isBlank() }) continue
            val rowNo = ri + 1
            val date = Dates.parse(cell(r, ImportField.DATE))
            val symbol = cell(r, ImportField.SYMBOL)?.trim()?.uppercase()
            val type = cell(r, ImportField.TYPE)?.let(::parseType)
            val qtyRaw = parseDecimal(cell(r, ImportField.QUANTITY))
            val price = parseDecimal(cell(r, ImportField.PRICE))
            if (date == null && symbol == null && qtyRaw == null) continue      // decorative / total row
            val err = when {
                date == null -> "Date missing or not recognised"
                symbol.isNullOrEmpty() -> "Symbol missing"
                type == null -> "Buy/Sell type missing or not recognised"
                qtyRaw == null || qtyRaw.isZero() -> "Quantity missing"
                qtyRaw.stripTrailingZeros().scale() > 0 -> "Quantity is not a whole number"
                type != TxnType.BONUS && (price == null || price.isNeg()) -> "Price missing"
                else -> null
            }
            if (err != null) { items += ImportedTxn(rowNo, null, null, false, false, err); continue }
            val qty = qtyRaw!!.abs().toLong()
            val p = price ?: ZERO
            val gross = p.multiply(bd(qty)).r2()
            val sched = FeeSchedule.forDate(schedules, date!!)
            val est = sched?.compute(gross) ?: Fees()
            val explicit = listOf(ImportField.BROKERAGE, ImportField.CSE, ImportField.SEC, ImportField.CDS, ImportField.TAX, ImportField.OTHER)
                .map { parseDecimal(cell(r, it))?.abs() }
            var fees: Fees; var estimated = false
            val totalCharges = parseDecimal(cell(r, ImportField.TOTAL_CHARGES))?.abs()
                ?: parseDecimal(cell(r, ImportField.NET))?.abs()?.let { net -> net.subtract(gross).abs() }
            if (type == TxnType.BONUS || type == TxnType.OPENING) fees = Fees()
            else if (explicit.any { it != null }) {
                fees = Fees(explicit[0] ?: ZERO, explicit[1] ?: ZERO, explicit[2] ?: ZERO, explicit[3] ?: ZERO, explicit[4] ?: ZERO, explicit[5] ?: ZERO)
                if (totalCharges != null && totalCharges > fees.total) fees = fees.copy(other = fees.other.add(totalCharges.subtract(fees.total)))
            } else if (totalCharges != null) {
                fees = splitTotal(totalCharges, est)
            } else { fees = est; estimated = true }
            val pay = if (cell(r, ImportField.PAYMENT)?.contains("margin", true) == true) PayMethod.MARGIN else PayMethod.CASH
            val t = Txn(id++, date, symbol!!, type!!, qty, p, fees.brokerage, fees.cse, fees.sec, fees.cds, fees.tax, fees.other,
                payment = pay, note = cell(r, ImportField.NOTE) ?: "")
            val k = key(t)
            val dup = !seen.add(k)
            items += ImportedTxn(rowNo, t, cell(r, ImportField.COMPANY), dup, estimated, null)
        }
        return ImportPreview(items)
    }

    /** Splits a known total across components in the schedule's proportions; any rounding remainder goes to brokerage. */
    fun splitTotal(total: BigDecimal, pattern: Fees): Fees {
        if (!pattern.total.isPos()) return Fees(brokerage = total)
        fun part(x: BigDecimal) = total.multiply(x).divide(pattern.total, 2, java.math.RoundingMode.HALF_UP)
        val f = Fees(ZERO, part(pattern.cse), part(pattern.sec), part(pattern.cds), part(pattern.tax), part(pattern.other))
        return f.copy(brokerage = total.subtract(f.total))
    }

    /** Template the app offers for download, and the layout its own export uses, so exports re-import cleanly. */
    val TEMPLATE_HEADERS = listOf("Date", "Symbol", "Company", "Type", "Quantity", "Price", "Brokerage", "CSE Fee", "SEC Levy", "CDS Fee", "Tax", "Other Charges", "Payment", "Note")

    fun exportRows(txns: List<Txn>, names: Map<String, String>): ReportTable = ReportTable("Transactions", TEMPLATE_HEADERS,
        txns.sortedWith(compareBy<Txn> { it.date }.thenBy { it.id }).filter { it.type != TxnType.SPLIT }.map { t ->
            listOf(Reports.t(t.date.format(Reports.DATE)), Reports.t(t.symbol), Reports.t(names[t.symbol] ?: ""), Reports.t(t.type.name), Reports.n(t.quantity),
                Cell(Fmt.plain(t.price), t.price), Reports.m(t.brokerage), Reports.m(t.cseFee), Reports.m(t.secFee), Reports.m(t.cdsFee), Reports.m(t.tax), Reports.m(t.other),
                Reports.t(t.payment.label), Reports.t(t.note)) })
}
