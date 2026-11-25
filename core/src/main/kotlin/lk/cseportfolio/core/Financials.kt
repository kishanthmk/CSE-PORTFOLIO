package lk.cseportfolio.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** A figure you can record from a published financial statement. [money] figures are scaled by the period's unit; the rest are entered as they are. */
enum class Fig(val label: String, val group: String, val money: Boolean = true) {
    REVENUE("Revenue", "Income statement"), COST_OF_SALES("Cost of sales", "Income statement"), GROSS_PROFIT("Gross profit", "Income statement"),
    OPEX("Operating expenses", "Income statement"), OPERATING_PROFIT("Operating profit", "Income statement"), DEPRECIATION("Depreciation and amortisation", "Income statement"),
    FINANCE_COSTS("Finance costs", "Income statement"), PBT("Profit before tax", "Income statement"), TAX("Income tax", "Income statement"), NET_PROFIT("Net profit", "Income statement"),
    EPS("Earnings per share (LKR)", "Per share", false), DPS("Dividend per share (LKR)", "Per share", false), SHARES("Shares in issue (number)", "Per share", false),
    TOTAL_ASSETS("Total assets", "Balance sheet"), CURRENT_ASSETS("Current assets", "Balance sheet"), INVENTORY("Inventories", "Balance sheet"), CASH("Cash and equivalents", "Balance sheet"),
    TOTAL_LIABILITIES("Total liabilities", "Balance sheet"), CURRENT_LIABILITIES("Current liabilities", "Balance sheet"), TOTAL_DEBT("Interest-bearing borrowings", "Balance sheet"),
    EQUITY("Shareholders' equity", "Balance sheet"), RETAINED("Retained earnings", "Balance sheet"),
    OCF("Operating cash flow", "Cash flow"), ICF("Investing cash flow", "Cash flow"), FCF_FIN("Financing cash flow", "Cash flow"), CAPEX("Capital expenditure", "Cash flow"),
    NIM("Net interest margin (%)", "Banks and finance", false), NPL("Non-performing loan ratio (%)", "Banks and finance", false), CAR("Capital adequacy ratio (%)", "Banks and finance", false)
}

/** One reporting period for one company, as typed in from its published report. quarter 0 = full financial year. */
data class FinPeriod(
    val symbol: String, val year: Int, val quarter: Int = 0, val periodEnd: LocalDate? = null,
    /** 1, 1000 or 1000000: the unit the report states its money figures in. */
    val unit: Long = 1000, val source: String = "", val updatedOn: LocalDate? = null,
    val figures: Map<Fig, BigDecimal> = emptyMap()
) {
    val key: String get() = "$symbol|$year|$quarter"
    val label: String get() = if (quarter == 0) "FY$year" else "Q$quarter FY$year"
    val isAnnual: Boolean get() = quarter == 0
    /** Value in rupees (money figures scaled by the unit). Gross profit is derived when only revenue and cost of sales were entered. */
    fun v(f: Fig): BigDecimal? {
        val raw = figures[f] ?: if (f == Fig.GROSS_PROFIT) { val r = figures[Fig.REVENUE]; val c = figures[Fig.COST_OF_SALES]; if (r != null && c != null) r.subtract(c.abs()) else null } else null
        return raw?.let { if (f.money) it.multiply(bd(unit)) else it }
    }
    fun toMap(): Map<String, Any?> = linkedMapOf("symbol" to symbol, "year" to year.toLong(), "quarter" to quarter.toLong(), "periodEnd" to periodEnd?.toString(), "unit" to unit,
        "source" to source, "updatedOn" to updatedOn?.toString(), "figures" to figures.entries.associate { it.key.name to it.value.toPlainString() })
    companion object {
        fun fromMap(m: Map<*, *>): FinPeriod? {
            val s = m["symbol"] as? String ?: return null
            val figs = LinkedHashMap<Fig, BigDecimal>()
            (m["figures"] as? Map<*, *>)?.forEach { (k, v) -> try { figs[Fig.valueOf(k.toString())] = BigDecimal(v.toString()) } catch (e: Exception) { } }
            return FinPeriod(s, (m["year"] as? Number)?.toInt() ?: return null, (m["quarter"] as? Number)?.toInt() ?: 0, (m["periodEnd"] as? String)?.let { LocalDate.parse(it) },
                (m["unit"] as? Number)?.toLong() ?: 1, m["source"] as? String ?: "", (m["updatedOn"] as? String)?.let { LocalDate.parse(it) }, figs)
        }
        fun sort(list: List<FinPeriod>): List<FinPeriod> = list.sortedWith(compareBy<FinPeriod> { it.year }.thenBy { if (it.quarter == 0) 5 else it.quarter })
    }
}

/** kind: "x" = times, "%" = percent, "LKR" = rupees. value is null when a needed figure has not been entered. */
data class Ratio(val name: String, val category: String, val value: BigDecimal?, val kind: String, val formula: String) {
    val text: String get() = when {
        value == null -> "–"
        kind == "%" -> Fmt.money(value) + "%"
        kind == "x" -> Fmt.money(value) + "x"
        else -> Fmt.money(value)
    }
}

object Ratios {
    private fun div(a: BigDecimal?, b: BigDecimal?): BigDecimal? = if (a == null || b == null || b.isZero()) null else a.divide(b, 6, RoundingMode.HALF_UP)
    private fun pct(a: BigDecimal?, b: BigDecimal?): BigDecimal? = div(a, b)?.multiply(HUNDRED)?.setScale(2, RoundingMode.HALF_UP)
    private fun x(a: BigDecimal?, b: BigDecimal?): BigDecimal? = div(a, b)?.setScale(2, RoundingMode.HALF_UP)
    private fun growth(now: BigDecimal?, before: BigDecimal?): BigDecimal? = if (now == null || before == null || !before.isPos()) null else pct(now.subtract(before), before)

    /** The period this one should be compared with for growth: the previous year's same period. */
    fun prior(p: FinPeriod, all: List<FinPeriod>): FinPeriod? = all.firstOrNull { it.symbol == p.symbol && it.year == p.year - 1 && it.quarter == p.quarter }

    /**
     * All ratios for one period. Price-based ratios use [price] (the latest market price) and are
     * given for full-year periods only, because a single quarter's EPS is not comparable with a price.
     */
    fun compute(p: FinPeriod, prior: FinPeriod?, price: BigDecimal?): List<Ratio> {
        val np = p.v(Fig.NET_PROFIT); val eq = p.v(Fig.EQUITY); val ta = p.v(Fig.TOTAL_ASSETS); val rev = p.v(Fig.REVENUE)
        val op = p.v(Fig.OPERATING_PROFIT); val cl = p.v(Fig.CURRENT_LIABILITIES); val ca = p.v(Fig.CURRENT_ASSETS)
        val eps = p.v(Fig.EPS); val dps = p.v(Fig.DPS); val shares = p.v(Fig.SHARES); val debt = p.v(Fig.TOTAL_DEBT)
        val annualPrice = if (p.isAnnual) price else null
        val bvps = div(eq, shares)
        val ebitda = if (op != null) op.add(p.v(Fig.DEPRECIATION) ?: ZERO) else null
        val ev = if (annualPrice != null && shares != null) annualPrice.multiply(shares).add(debt ?: ZERO).subtract(p.v(Fig.CASH) ?: ZERO) else null
        val capEmployed = if (ta != null && cl != null) ta.subtract(cl) else null
        val fcf = p.v(Fig.OCF)?.let { o -> p.v(Fig.CAPEX)?.let { o.subtract(it.abs()) } }
        val scope = if (p.isAnnual) "" else " (for the quarter, not annualised)"
        return listOf(
            Ratio("Return on equity (ROE)", "Profitability", pct(np, eq), "%", "Net profit ÷ shareholders' equity$scope"),
            Ratio("Return on assets (ROA)", "Profitability", pct(np, ta), "%", "Net profit ÷ total assets$scope"),
            Ratio("Return on capital employed (ROCE)", "Profitability", pct(op, capEmployed), "%", "Operating profit ÷ (total assets − current liabilities)$scope"),
            Ratio("Gross profit margin", "Profitability", pct(p.v(Fig.GROSS_PROFIT), rev), "%", "Gross profit ÷ revenue"),
            Ratio("Operating profit margin", "Profitability", pct(op, rev), "%", "Operating profit ÷ revenue"),
            Ratio("Net profit margin", "Profitability", pct(np, rev), "%", "Net profit ÷ revenue"),
            Ratio("Earnings per share (EPS)", "Valuation", eps?.setScale(2, RoundingMode.HALF_UP), "LKR", "As reported"),
            Ratio("Price / earnings (P/E)", "Valuation", x(annualPrice, eps?.takeIf { it.isPos() }), "x", "Latest market price ÷ annual EPS"),
            Ratio("Price / book value (P/BV)", "Valuation", x(annualPrice, bvps), "x", "Latest market price ÷ (equity ÷ shares in issue)"),
            Ratio("EV / EBITDA", "Valuation", x(ev, ebitda?.takeIf { it.isPos() }), "x", "(Market value of shares + borrowings − cash) ÷ (operating profit + depreciation)"),
            Ratio("Debt / equity", "Leverage", x(debt, eq), "x", "Interest-bearing borrowings ÷ shareholders' equity"),
            Ratio("Interest coverage", "Leverage", x(op, p.v(Fig.FINANCE_COSTS)?.abs()), "x", "Operating profit ÷ finance costs"),
            Ratio("Current ratio", "Liquidity", x(ca, cl), "x", "Current assets ÷ current liabilities"),
            Ratio("Quick ratio", "Liquidity", x(if (ca != null) ca.subtract(p.v(Fig.INVENTORY) ?: ZERO) else null, cl), "x", "(Current assets − inventories) ÷ current liabilities"),
            Ratio("Revenue growth", "Growth", growth(rev, prior?.v(Fig.REVENUE)), "%", "Change in revenue from the same period a year earlier"),
            Ratio("Net profit growth", "Growth", growth(np, prior?.v(Fig.NET_PROFIT)), "%", "Change in net profit from the same period a year earlier"),
            Ratio("EPS growth", "Growth", growth(eps, prior?.v(Fig.EPS)), "%", "Change in EPS from the same period a year earlier"),
            Ratio("Dividend yield", "Dividend", pct(if (p.isAnnual) dps else null, price), "%", "Annual dividend per share ÷ latest market price"),
            Ratio("Dividend payout ratio", "Dividend", pct(dps, eps?.takeIf { it.isPos() }), "%", "Dividend per share ÷ EPS"),
            Ratio("Free cash flow", "Cash flow", fcf?.setScale(2, RoundingMode.HALF_UP), "LKR", "Operating cash flow − capital expenditure"),
            Ratio("Asset turnover", "Efficiency", x(rev, ta), "x", "Revenue ÷ total assets$scope"),
            Ratio("Inventory turnover", "Efficiency", x(p.v(Fig.COST_OF_SALES)?.abs(), p.v(Fig.INVENTORY)), "x", "Cost of sales ÷ inventories$scope"),
            Ratio("Net interest margin", "Banks and finance", p.v(Fig.NIM), "%", "As reported by the company"),
            Ratio("Non-performing loan ratio", "Banks and finance", p.v(Fig.NPL), "%", "As reported by the company"),
            Ratio("Capital adequacy ratio", "Banks and finance", p.v(Fig.CAR), "%", "As reported by the company")
        )
    }

    private fun figCell(f: Fig, v: BigDecimal?): Cell = if (v == null) Cell("–") else if (f == Fig.SHARES) Cell(Fmt.qty(v.toLong()), v, 0, 0) else Cell(Fmt.money(v), v)
    private fun ratioCell(r: Ratio): Cell = if (r.value == null) Cell("–") else Cell(r.text, r.value, if (r.category == "Growth") 1 else 0)

    /** Statement figures, one column per period, with the change from the same period a year earlier. Values in rupees. */
    fun statementTable(symbol: String, periods: List<FinPeriod>, annual: Boolean): ReportTable {
        val ps = FinPeriod.sort(periods.filter { it.symbol == symbol && it.isAnnual == annual }).takeLast(8)
        val used = Fig.values().filter { f -> ps.any { it.v(f) != null } }
        val rows = used.map { f -> listOf(Cell(f.label)) + ps.map { figCell(f, it.v(f)) } +
            (ps.lastOrNull()?.let { last -> listOf(growth(last.v(f), prior(last, periods)?.v(f))?.let { Cell(Fmt.pct(it), it, 1) } ?: Cell("–")) } ?: emptyList()) }
        return ReportTable((if (annual) "Annual" else "Quarterly") + " financial statements – $symbol", listOf("Figure (LKR)") + ps.map { it.label } + (if (ps.isEmpty()) emptyList() else listOf("Change on year")), rows,
            note = ps.joinToString("  ·  ") { "${it.label}: ${it.source.ifEmpty { "source not recorded" }}" + (it.updatedOn?.let { d -> ", entered ${d.format(Reports.DATE)}" } ?: "") })
    }

    /** Ratios dashboard: one column per period. */
    fun ratioTable(symbol: String, periods: List<FinPeriod>, annual: Boolean, price: BigDecimal?): ReportTable {
        val ps = FinPeriod.sort(periods.filter { it.symbol == symbol && it.isAnnual == annual }).takeLast(8)
        val cols = ps.map { compute(it, prior(it, periods), price) }
        val names = cols.firstOrNull() ?: emptyList()
        val rows = names.indices.filter { i -> cols.any { it[i].value != null } }.map { i -> listOf(Cell(names[i].name), Cell(names[i].category)) + cols.map { ratioCell(it[i]) } }
        return ReportTable("Financial ratios – $symbol", listOf("Ratio", "Category") + ps.map { it.label }, rows,
            note = if (price != null) "Price-based ratios use the latest market price of LKR ${Fmt.money(price)} against full-year figures." else "No market price is stored, so price-based ratios are blank.")
    }

    /** Side-by-side comparison of up to five companies on their latest full-year figures. */
    fun comparisonTable(symbols: List<String>, periods: List<FinPeriod>, prices: Map<String, BigDecimal>, marketCaps: Map<String, BigDecimal>): ReportTable {
        val syms = symbols.take(5)
        val latest = syms.map { s -> FinPeriod.sort(periods.filter { it.symbol == s && it.isAnnual }).lastOrNull() }
        val ratios = syms.indices.map { i -> latest[i]?.let { compute(it, prior(it, periods), prices[syms[i]]) } }
        fun r(name: String) = syms.indices.map { i -> ratios[i]?.firstOrNull { it.name == name }?.let(::ratioCell) ?: Cell("–") }
        val rows = listOf(
            listOf(Cell("Latest full year")) + latest.map { Cell(it?.label ?: "not entered") },
            listOf(Cell("Market price (LKR)")) + syms.map { s -> prices[s]?.let { Cell(Fmt.money(it), it) } ?: Cell("–") },
            listOf(Cell("Market capitalisation (LKR)")) + syms.indices.map { i -> (marketCaps[syms[i]] ?: latest[i]?.v(Fig.SHARES)?.let { sh -> prices[syms[i]]?.multiply(sh) })?.let { Cell(Fmt.compact(it), it) } ?: Cell("–") },
            listOf(Cell("Revenue (LKR)")) + latest.map { p -> p?.v(Fig.REVENUE)?.let { Cell(Fmt.compact(it), it) } ?: Cell("–") },
            listOf(Cell("Net profit (LKR)")) + latest.map { p -> p?.v(Fig.NET_PROFIT)?.let { Cell(Fmt.compact(it), it, 1) } ?: Cell("–") },
            listOf(Cell("Revenue growth")) + r("Revenue growth"), listOf(Cell("EPS (LKR)")) + r("Earnings per share (EPS)"),
            listOf(Cell("ROE")) + r("Return on equity (ROE)"), listOf(Cell("ROA")) + r("Return on assets (ROA)"),
            listOf(Cell("P/E")) + r("Price / earnings (P/E)"), listOf(Cell("P/BV")) + r("Price / book value (P/BV)"),
            listOf(Cell("Dividend yield")) + r("Dividend yield"), listOf(Cell("Debt / equity")) + r("Debt / equity"))
        return ReportTable("Company comparison", listOf("Measure") + syms, rows, note = "Financial measures use each company's latest full-year figures as entered from its published report.")
    }
}
