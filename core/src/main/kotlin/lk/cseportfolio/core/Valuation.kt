package lk.cseportfolio.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** A holding valued at the latest known price. */
data class Holding(
    val position: Position,
    val quote: Quote?,
    val marketValue: BigDecimal,
    /** Market value − cost basis (before selling charges). */
    val unrealized: BigDecimal,
    val unrealizedPct: BigDecimal?,
    /** What the broker would pay out if everything were sold at the latest price, after selling charges. */
    val projectedNet: BigDecimal,
    /** Projected net proceeds − cost basis: the figure a broker's Gain/Loss statement shows. */
    val netUnrealized: BigDecimal,
    val dayChange: BigDecimal?,
    /** (realized + unrealized) ÷ everything ever invested in this stock. */
    val totalReturnPct: BigDecimal?
) {
    val symbol get() = position.symbol
    val priced get() = quote != null
}

data class MarginView(
    val state: MarginState,
    val collateral: BigDecimal,
    /** Loan ÷ collateral, percent. */
    val ltvPct: BigDecimal?,
    val buyingPower: BigDecimal,
    val utilizationWarning: Boolean,
    val marginCall: Boolean
)

data class Summary(
    val holdings: List<Holding>,
    val marketValue: BigDecimal,
    val costOfHoldings: BigDecimal,
    val realized: BigDecimal,
    val unrealized: BigDecimal,
    val netUnrealized: BigDecimal,
    val charges: BigDecimal,
    val cash: BigDecimal,
    val loan: BigDecimal,
    val buyingPower: BigDecimal,
    val netEquity: BigDecimal,
    val returnPct: BigDecimal?,
    val totalProfit: BigDecimal,
    val dayChange: BigDecimal?,
    val monthPL: BigDecimal,
    val ytdPL: BigDecimal,
    val best: Holding?,
    val worst: Holding?,
    val companiesHeld: Int,
    val unpricedCount: Int,
    val margin: List<MarginView>
)

object Valuation {
    fun holding(p: Position, q: Quote?, fees: FeeSchedule?): Holding {
        val price = q?.last
        // Without a price we carry the holding at cost, clearly flagged as unpriced by the UI.
        val mv = if (price != null) price.multiply(bd(p.quantity)).r2() else p.costBasis
        val unreal = mv.subtract(p.costBasis)
        val sellFees = if (price != null && fees != null) fees.compute(mv).total else ZERO
        val net = mv.subtract(sellFees)
        val day = if (q?.change != null) q.change.multiply(bd(p.quantity)).r2()
                  else if (q?.prevClose != null) q.last.subtract(q.prevClose).multiply(bd(p.quantity)).r2() else null
        return Holding(p, q, mv, unreal, pctOf(unreal, p.costBasis), net, net.subtract(p.costBasis), day,
            pctOf(p.realized.add(if (p.isOpen) unreal else ZERO), p.totalInvested))
    }

    fun summarize(r: PortfolioResult, quotes: Map<String, Quote>, fees: FeeSchedule?): Summary {
        val holdings = r.open.map { holding(it, quotes[it.symbol], fees) }
        val mv = holdings.map { it.marketValue }.sum()
        val unreal = holdings.map { it.unrealized }.sum()
        val netUnreal = holdings.map { it.netUnrealized }.sum()
        val financingCost = r.interestTotal.add(r.financingCharges)
        val totalProfit = r.realized.add(unreal).add(r.dividends).subtract(financingCost)
        val days = holdings.mapNotNull { it.dayChange }
        val today = r.asOf
        val month = r.stats(today.withDayOfMonth(1), today).net
        val ytd = r.stats(LocalDate.of(today.year, 1, 1), today).net
        val ranked = r.positions.filter { it.totalInvested.isPos() }.map { p ->
            holdings.firstOrNull { it.symbol == p.symbol } ?: holding(p, quotes[p.symbol], fees)
        }.filter { it.totalReturnPct != null }
        val margin = r.margin.map { st ->
            val collateral = holdings.filter { st.account.wholePortfolioCollateral || st.financed.containsKey(it.symbol) }
                .map { it.marketValue }.sum()
            val ltv = pctOf(st.outstanding, collateral)
            val call = st.account.marginCallLtvPct
            var power = st.available
            if (call.isPos()) power = power.min(collateral.multiply(call).divide(HUNDRED, 2, RoundingMode.HALF_UP).subtract(st.outstanding).max(ZERO))
            val util = st.utilizationPct
            MarginView(st, collateral, ltv, power,
                utilizationWarning = util != null && st.account.warnUtilizationPct.isPos() && util >= st.account.warnUtilizationPct,
                marginCall = st.outstanding.isPos() && call.isPos() && (ltv == null || ltv >= call))
        }
        val buyingPower = r.cash.max(ZERO).add(margin.map { it.buyingPower }.sum())
        return Summary(
            holdings = holdings.sortedByDescending { it.marketValue },
            marketValue = mv, costOfHoldings = r.costOfHoldings, realized = r.realized, unrealized = unreal,
            netUnrealized = netUnreal, charges = r.feeTotals.total, cash = r.cash, loan = r.loanOutstanding,
            buyingPower = buyingPower, netEquity = mv.add(r.cash).subtract(r.loanOutstanding),
            returnPct = pctOf(totalProfit, r.totalInvested), totalProfit = totalProfit,
            dayChange = if (days.isEmpty()) null else days.sum(), monthPL = month, ytdPL = ytd,
            best = ranked.maxByOrNull { it.totalReturnPct!! }, worst = ranked.minByOrNull { it.totalReturnPct!! },
            companiesHeld = holdings.size, unpricedCount = holdings.count { !it.priced }, margin = margin
        )
    }
}

// ---------------- Sell price simulator ----------------

data class SimResult(
    val symbol: String,
    val quantity: Long,
    val heldQuantity: Long,
    val avgCost: BigDecimal,
    val price: BigDecimal,
    val costOfSold: BigDecimal,
    val gross: BigDecimal,
    val fees: Fees,
    val net: BigDecimal,
    /** Net proceeds − cost of the shares sold. */
    val profit: BigDecimal,
    val grossProfit: BigDecimal,
    val returnPct: BigDecimal?,
    // margin settlement
    val financed: BigDecimal,
    val ownCapital: BigDecimal,
    val interestDue: BigDecimal,
    val interestCharged: BigDecimal,
    val loanRepayment: BigDecimal,
    val netCash: BigDecimal,
    /** Profit after the margin interest attributable to the shares sold. */
    val profitAfterInterest: BigDecimal,
    val returnOnOwnCapitalPct: BigDecimal?
) {
    val isMargin get() = financed.isPos() || interestCharged.isPos()
}

object Simulator {
    /** CSE price tick. */
    val TICK: BigDecimal = bd("0.10")

    /**
     * Estimates the outcome of selling [qty] shares of [p] at [price] using [fees].
     * This is an estimate only — it never touches recorded transactions.
     */
    fun simulate(p: Position, qty: Long, price: BigDecimal, fees: FeeSchedule, method: CostMethod = CostMethod.WEIGHTED_AVERAGE): SimResult {
        val q = qty.coerceIn(0, p.quantity)
        val all = q == p.quantity
        val frac: (BigDecimal) -> BigDecimal = { v ->
            if (all || p.quantity == 0L) v else v.multiply(bd(q)).divide(bd(p.quantity), 2, RoundingMode.HALF_UP)
        }
        val cost = when {
            all -> p.costBasis
            method == CostMethod.FIFO -> fifoCost(p, q)
            else -> frac(p.costBasis)
        }
        val gross = price.multiply(bd(q)).r2()
        val f = fees.compute(gross)
        val net = gross.subtract(f.total)
        val profit = net.subtract(cost)
        val financed = frac(p.financed)
        val interestDue = frac(p.interestUnpaid)
        val interestCharged = frac(p.interestCharged)
        val repay = financed.add(interestDue).min(net.max(ZERO))
        val own = cost.subtract(financed)
        val afterInt = profit.subtract(interestCharged)
        return SimResult(p.symbol, q, p.quantity, p.avgCost, price, cost, gross, f, net, profit, gross.subtract(cost),
            pctOf(profit, cost), financed, own, interestDue, interestCharged, repay, net.subtract(repay), afterInt,
            if (own.isPos()) pctOf(afterInt, own) else null)
    }

    private fun fifoCost(p: Position, qty: Long): BigDecimal {
        var rem = qty; var c = ZERO
        for (lot in p.lots) {
            if (rem <= 0) break
            val take = minOf(rem, lot.qty)
            c = c.add(if (take == lot.qty) lot.cost else lot.cost.multiply(bd(take)).divide(bd(lot.qty), 2, RoundingMode.HALF_UP))
            rem -= take
        }
        return c
    }

    /**
     * Lowest selling price at which net proceeds cover [target] (cost, or cost + interest).
     * Returns the exact price (4dp, rounded up) — use [toTick] for a tradable price.
     */
    fun breakEven(qty: Long, target: BigDecimal, fees: FeeSchedule): BigDecimal? {
        if (qty <= 0) return null
        if (!target.isPos()) return ZERO
        var lo = ZERO
        var hi = target.divide(bd(qty), 6, RoundingMode.UP).multiply(bd("2")).add(bd("1"))
        fun net(pr: BigDecimal): BigDecimal { val g = pr.multiply(bd(qty)).r2(); return g.subtract(fees.compute(g).total) }
        var guard = 0
        while (net(hi) < target && guard++ < 60) hi = hi.multiply(bd("2"))
        repeat(60) {
            val mid = lo.add(hi).divide(bd("2"), 6, RoundingMode.HALF_UP)
            if (net(mid) >= target) hi = mid else lo = mid
        }
        return hi.setScale(4, RoundingMode.UP)
    }

    fun toTick(price: BigDecimal): BigDecimal = price.divide(TICK, 0, RoundingMode.UP).multiply(TICK).setScale(2)

    /** Scenario ladder around a reference price: the fixed marks from the spec, scaled to the stock's price level. */
    fun defaultScenarios(avgCost: BigDecimal, last: BigDecimal?): List<BigDecimal> {
        val ref = (last ?: avgCost).max(bd("0.10"))
        val steps = listOf("0.8", "0.9", "1.0", "1.1", "1.25", "1.5", "2.0")
        return steps.map { toTick(ref.multiply(bd(it))) }.distinct()
    }
}
