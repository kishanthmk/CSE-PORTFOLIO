package lk.cseportfolio.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** tone: 0 neutral, 1 = colour by sign (green/red). */
data class Cell(val text: String, val num: BigDecimal? = null, val tone: Int = 0, val decimals: Int = 2)

data class ReportTable(
    val title: String,
    val headers: List<String>,
    val rows: List<List<Cell>>,
    val total: List<Cell>? = null,
    val note: String = ""
) {
    fun isNumeric(col: Int): Boolean = rows.any { it.getOrNull(col)?.num != null } || total?.getOrNull(col)?.num != null
}

data class LedgerFilter(
    val symbol: String? = null, val from: LocalDate? = null, val to: LocalDate? = null,
    /** null = all, otherwise "BUY" or "SELL". */
    val side: String? = null,
    val payment: PayMethod? = null,
    /** null = all, true = profitable sales only, false = loss-making sales only. */
    val profitable: Boolean? = null
) {
    fun matches(r: LedgerRow): Boolean {
        if (symbol != null && r.symbol != symbol) return false
        if (from != null && r.date.isBefore(from)) return false
        if (to != null && r.date.isAfter(to)) return false
        if (side == "BUY" && !(r.source == LedgerSource.TXN && (r.kind == TxnType.BUY.label || r.kind == TxnType.RIGHTS.label || r.kind == TxnType.OPENING.label))) return false
        if (side == "SELL" && !(r.source == LedgerSource.TXN && r.kind == TxnType.SELL.label)) return false
        if (payment != null && r.payment != payment) return false
        if (profitable != null) { val x = r.realized ?: return false; if (profitable != x.isPos()) return false }
        return true
    }
    val active: Boolean get() = symbol != null || from != null || to != null || side != null || payment != null || profitable != null
}

object Reports {
    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

    fun t(s: String?) = Cell(s ?: "")
    fun m(v: BigDecimal?) = if (v == null) Cell("") else Cell(Fmt.money(v), v)
    fun pl(v: BigDecimal?) = if (v == null) Cell("") else Cell(Fmt.signed(v), v, 1)
    fun n(v: Long?) = if (v == null) Cell("") else Cell(Fmt.qty(v), bd(v), 0, 0)
    fun pc(v: BigDecimal?) = if (v == null) Cell("–") else Cell(Fmt.pct(v), v, 1)
    fun price(v: BigDecimal?) = if (v == null) Cell("–") else Cell(Fmt.money(v), v)

    fun holdings(s: Summary): ReportTable = ReportTable(
        "Current holdings",
        listOf("Symbol", "Quantity", "Avg cost", "Cost", "Price", "Market value", "Unrealized P/L", "Return %", "Net if sold", "Net P/L if sold"),
        s.holdings.map { h -> listOf(t(h.symbol), n(h.position.quantity), Cell(Fmt.money(h.position.avgCost), h.position.avgCost), m(h.position.costBasis),
            price(h.quote?.last), m(h.marketValue), pl(h.unrealized), pc(h.unrealizedPct), m(h.projectedNet), pl(h.netUnrealized)) },
        listOf(t("Total"), t(""), t(""), m(s.costOfHoldings), t(""), m(s.marketValue), pl(s.unrealized), pc(pctOf(s.unrealized, s.costOfHoldings)),
            m(s.holdings.map { it.projectedNet }.sum()), pl(s.netUnrealized)),
        if (s.unpricedCount > 0) "${s.unpricedCount} holding(s) have no market price and are carried at cost." else ""
    )

    fun companyAnalysis(r: PortfolioResult, quotes: Map<String, Quote>, fees: FeeSchedule?): ReportTable {
        val hs = r.positions.map { Valuation.holding(it, quotes[it.symbol], fees) }
        return ReportTable("Company-wise investment analysis",
            listOf("Symbol", "Bought", "Sold", "Held", "Total invested", "Cost of holding", "Market value", "Realized P/L", "Unrealized P/L", "Dividends", "Charges", "Total return %"),
            hs.map { h -> val p = h.position
                listOf(t(p.symbol), n(p.boughtQty), n(p.soldQty), n(p.quantity), m(p.totalInvested), m(p.costBasis),
                    if (p.isOpen) m(h.marketValue) else t("–"), pl(p.realized), if (p.isOpen) pl(h.unrealized) else t("–"), m(p.dividends), m(p.charges), pc(h.totalReturnPct)) },
            listOf(t("Total"), t(""), t(""), t(""), m(r.totalInvested), m(r.costOfHoldings), m(hs.filter { it.position.isOpen }.map { it.marketValue }.sum()),
                pl(r.realized), pl(hs.filter { it.position.isOpen }.map { it.unrealized }.sum()), m(r.dividends), m(r.feeTotals.total), t("")))
    }

    private fun periodRow(label: String, s: PeriodStats) = listOf(t(label), n(s.trades.toLong()), m(s.buyValue), m(s.sellValue), m(s.charges),
        pl(s.realized), m(s.dividends), m(s.interest.add(s.financingCharges)), pl(s.net))
    private val periodHeaders = listOf("Trades", "Purchases", "Sales", "Charges", "Realized P/L", "Dividends", "Margin cost", "Net P/L")

    fun monthly(r: PortfolioResult, year: Int? = null): ReportTable {
        val rows = r.monthly.filter { year == null || it.key.year == year }
        val tot = PeriodStats().also { t -> rows.values.forEach { t.add(it) } }
        return ReportTable("Monthly trading performance" + (year?.let { " $it" } ?: ""), listOf("Month") + periodHeaders,
            rows.map { (k, v) -> periodRow(k.format(MONTH), v) }, periodRow("Total", tot))
    }

    fun annual(r: PortfolioResult): ReportTable {
        val y = r.yearly()
        val tot = PeriodStats().also { t -> y.values.forEach { t.add(it) } }
        return ReportTable("Annual investment performance", listOf("Year") + periodHeaders,
            y.map { (k, v) -> periodRow(k.toString(), v) }, periodRow("Total", tot))
    }

    fun charges(r: PortfolioResult): ReportTable {
        val f = r.feeTotals
        val turnover = r.monthly.values.map { it.buyValue.add(it.sellValue) }.sum()
        fun row(name: String, v: BigDecimal) = listOf(t(name), m(v), pc(pctOf(v, turnover)).copy(tone = 0))
        return ReportTable("Brokerage, taxes and exchange charges", listOf("Charge", "Amount (LKR)", "% of turnover"),
            listOf(row("Brokerage", f.brokerage), row("CSE fees", f.cse), row("SEC levies", f.sec), row("CDS charges", f.cds),
                row("Taxes / levies", f.tax), row("Other charges", f.other)),
            row("Total transaction charges", f.total), "Turnover (purchases + sales): LKR ${Fmt.money(turnover)}")
    }

    fun cashVsMargin(r: PortfolioResult): ReportTable {
        val tot = r.cashFunded.add(r.marginFunded)
        return ReportTable("Cash versus margin investment", listOf("Funding", "Amount invested (LKR)", "Share %"),
            listOf(listOf(t("Own cash"), m(r.cashFunded), pc(pctOf(r.cashFunded, tot)).copy(tone = 0)),
                   listOf(t("Margin loan"), m(r.marginFunded), pc(pctOf(r.marginFunded, tot)).copy(tone = 0))),
            listOf(t("Total"), m(tot), t("")),
            "Margin interest to date: LKR ${Fmt.money(r.interestTotal)}; other financing charges: LKR ${Fmt.money(r.financingCharges)}; loan outstanding: LKR ${Fmt.money(r.loanOutstanding)}")
    }

    fun trades(r: PortfolioResult, list: List<SellResult> = r.sells, title: String = "Closed trades"): ReportTable = ReportTable(title,
        listOf("Date", "Symbol", "Quantity", "Price", "Net proceeds", "Cost of shares", "Realized P/L", "Return %", "Days held"),
        list.map { s -> listOf(t(s.date.format(DATE)), t(s.symbol), n(s.quantity), price(s.price), m(s.net), m(s.costOfSold), pl(s.realized), pc(s.returnPct), n(s.holdingDays)) },
        listOf(t("Total"), t(""), t(""), t(""), m(list.map { it.net }.sum()), m(list.map { it.costOfSold }.sum()), pl(list.map { it.realized }.sum()), t(""), t("")))

    fun allocation(s: Summary): ReportTable = ReportTable("Investment allocation by company", listOf("Symbol", "Market value", "Allocation %", "Cost", "Cost allocation %"),
        s.holdings.map { h -> listOf(t(h.symbol), m(h.marketValue), pc(pctOf(h.marketValue, s.marketValue)).copy(tone = 0), m(h.position.costBasis),
            pc(pctOf(h.position.costBasis, s.costOfHoldings)).copy(tone = 0)) },
        listOf(t("Total"), m(s.marketValue), t(""), m(s.costOfHoldings), t("")), concentrationNote(s))

    fun concentrationNote(s: Summary): String {
        if (s.holdings.isEmpty() || !s.marketValue.isPos()) return ""
        val w = s.holdings.map { it.marketValue.divide(s.marketValue, 6, RoundingMode.HALF_UP) }
        val top = w.first().multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP)
        val top3 = w.take(3).sum().multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP)
        val hhi = w.map { it.multiply(it) }.sum()
        val eff = if (hhi.isPos()) BigDecimal.ONE.divide(hhi, 1, RoundingMode.HALF_UP) else ZERO
        return "Concentration: largest holding ${s.holdings.first().symbol} $top%, top three $top3%, equivalent to $eff equally weighted stocks."
    }

    fun averageHoldingDays(r: PortfolioResult): Long? {
        val q = r.sells.sumOf { it.quantity }
        return if (q == 0L) null else r.sells.sumOf { it.quantity * it.holdingDays } / q
    }

    fun ledger(rows: List<LedgerRow>, title: String = "Transaction ledger"): ReportTable = ReportTable(title,
        listOf("Date", "Symbol", "Type", "Pay", "Quantity", "Price", "Gross value", "Brokerage", "Other charges", "Total value", "Net cash flow", "Realized P/L", "Cash balance", "Loan balance", "Note"),
        rows.map { r -> listOf(t(r.date.format(DATE)), t(r.symbol), t(r.kind), t(r.payment?.label), n(r.quantity), price(r.price).let { if (r.price == null) t("") else it },
            m(r.gross), m(r.brokerage), m(r.otherCharges), m(r.total), pl(r.cashFlow), pl(r.realized), m(r.cashAfter), m(r.loanAfter), t(r.note)) },
        listOf(t("Total"), t(""), t(""), t(""), t(""), t(""), m(rows.mapNotNull { it.gross }.sum()), m(rows.mapNotNull { it.brokerage }.sum()),
            m(rows.mapNotNull { it.otherCharges }.sum()), t(""), pl(rows.map { it.cashFlow }.sum()), pl(rows.mapNotNull { it.realized }.sum()), t(""), t(""), t("")))

    fun companyStatement(r: PortfolioResult, symbol: String): ReportTable =
        ledger(r.ledger.filter { it.symbol == symbol }, "Transaction statement – $symbol")

    fun marginSummary(views: List<MarginView>): ReportTable = ReportTable("Margin facilities",
        listOf("Broker", "Account", "Rate % p.a.", "Facility limit", "Principal", "Unpaid interest", "Outstanding", "Interest to date", "Collateral", "Utilisation %", "Loan / collateral %"),
        views.map { v -> val st = v.state; listOf(t(st.account.broker), t(st.account.accountNo), Cell(Fmt.plain(st.rate), st.rate), m(st.account.facilityLimit), m(st.principal),
            m(st.interestUnpaid), m(st.outstanding), m(st.interestTotal), m(v.collateral), pc(st.utilizationPct).copy(tone = 0), pc(v.ltvPct).copy(tone = 0)) })

    /** Tables for the monthly profit/loss PDF. */
    fun monthlyReport(r: PortfolioResult, ym: YearMonth): List<ReportTable> {
        val s = r.monthly[ym] ?: PeriodStats()
        val summary = ReportTable("Profit and loss – ${ym.format(MONTH)}", listOf("Item", "Amount (LKR)"), listOf(
            listOf(t("Purchases (gross)"), m(s.buyValue)), listOf(t("Sales (gross)"), m(s.sellValue)),
            listOf(t("Transaction charges paid"), m(s.charges)), listOf(t("Realized trading profit / loss (net of charges)"), pl(s.realized)),
            listOf(t("Dividends received"), m(s.dividends)), listOf(t("Margin interest"), m(s.interest)), listOf(t("Other financing charges"), m(s.financingCharges))),
            listOf(t("Net profit / loss for the month"), pl(s.net)))
        val sells = r.sells.filter { YearMonth.from(it.date) == ym }
        val rows = r.ledger.filter { YearMonth.from(it.date) == ym }
        return listOf(summary, trades(r, sells, "Sales closed in ${ym.format(MONTH)}"), ledger(rows, "All entries in ${ym.format(MONTH)}"))
    }

    /** Tables for the annual investment summary. */
    fun annualReport(r: PortfolioResult, s: Summary, year: Int): List<ReportTable> {
        val st = r.yearly()[year] ?: PeriodStats()
        val head = ReportTable("Annual investment summary – $year", listOf("Item", "Amount (LKR)"), listOf(
            listOf(t("Purchases (gross)"), m(st.buyValue)), listOf(t("Sales (gross)"), m(st.sellValue)),
            listOf(t("Transaction charges paid"), m(st.charges)), listOf(t("Realized trading profit / loss"), pl(st.realized)),
            listOf(t("Dividends received"), m(st.dividends)), listOf(t("Margin interest and financing charges"), m(st.interest.add(st.financingCharges)))),
            listOf(t("Net profit / loss for the year"), pl(st.net)))
        return listOf(head, monthly(r, year), trades(r, r.sells.filter { it.date.year == year }, "Sales closed in $year"), holdings(s).copy(title = "Holdings as at ${r.asOf.format(DATE)}"))
    }
}
