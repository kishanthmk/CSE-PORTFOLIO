package lk.cseportfolio.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.TreeMap

data class EngineInput(
    val txns: List<Txn>,
    val cash: List<CashEntry> = emptyList(),
    val accounts: List<MarginAccount> = emptyList(),
    val marginEntries: List<MarginEntry> = emptyList(),
    val method: CostMethod = CostMethod.WEIGHTED_AVERAGE,
    val asOf: LocalDate = LocalDate.now()
)

class Lot(val date: LocalDate, var qty: Long, var cost: BigDecimal)

class Position(val symbol: String) {
    var quantity: Long = 0
    /** Cost of the shares still held, including allocated acquisition charges. */
    var costBasis: BigDecimal = ZERO
    var boughtQty: Long = 0
    var soldQty: Long = 0
    /** Every rupee ever paid to acquire this stock (gross + charges). */
    var totalInvested: BigDecimal = ZERO
    var totalNetProceeds: BigDecimal = ZERO
    var realized: BigDecimal = ZERO
    var buyCharges: BigDecimal = ZERO
    var sellCharges: BigDecimal = ZERO
    var dividends: BigDecimal = ZERO
    /** Margin loan principal currently attributed to this stock (all margin accounts). */
    var financed: BigDecimal = ZERO
    /** Margin interest charged to date that is attributable to this stock. */
    var interestCharged: BigDecimal = ZERO
    /** Unpaid part of [interestCharged]. */
    var interestUnpaid: BigDecimal = ZERO
    var cashFunded: BigDecimal = ZERO
    var marginFunded: BigDecimal = ZERO
    var firstAcquired: LocalDate? = null
    val lots: MutableList<Lot> = mutableListOf()

    val charges: BigDecimal get() = buyCharges.add(sellCharges)
    val avgCost: BigDecimal get() = if (quantity == 0L) ZERO else costBasis.divide(bd(quantity), 4, RoundingMode.HALF_UP)
    val isOpen: Boolean get() = quantity > 0
    val ownCapital: BigDecimal get() = costBasis.subtract(financed)
}

data class SellResult(
    val txnId: Long, val date: LocalDate, val symbol: String, val quantity: Long, val price: BigDecimal,
    val gross: BigDecimal, val charges: BigDecimal, val net: BigDecimal, val costOfSold: BigDecimal,
    /** Net realized profit: net proceeds − cost of shares sold (buy and sell charges both included once). */
    val realized: BigDecimal,
    /** Gross trading profit before any charges on the sale: gross − cost of shares sold. */
    val grossProfit: BigDecimal,
    val returnPct: BigDecimal?, val holdingDays: Long, val loanRepaid: BigDecimal
)

enum class LedgerSource { TXN, CASH, MARGIN }

data class LedgerRow(
    val source: LedgerSource, val refId: Long, val date: LocalDate, val symbol: String?, val kind: String,
    val quantity: Long?, val price: BigDecimal?, val gross: BigDecimal?, val brokerage: BigDecimal?,
    val otherCharges: BigDecimal?, val total: BigDecimal?, val cashFlow: BigDecimal, val realized: BigDecimal?,
    val payment: PayMethod?, val cashAfter: BigDecimal, val loanAfter: BigDecimal, val note: String
)

class MarginState(val account: MarginAccount) {
    var principal: BigDecimal = ZERO
    var interestUnpaid: BigDecimal = ZERO
    var interestTotal: BigDecimal = ZERO
    var chargesTotal: BigDecimal = ZERO
    var drawnTotal: BigDecimal = ZERO
    var repaidTotal: BigDecimal = ZERO
    var rate: BigDecimal = account.interestRate
    val financed: MutableMap<String, BigDecimal> = TreeMap()
    val symUnpaid: MutableMap<String, BigDecimal> = TreeMap()
    internal var last: LocalDate? = null
    val outstanding: BigDecimal get() = principal.add(interestUnpaid)
    val available: BigDecimal get() = account.facilityLimit.subtract(outstanding).max(ZERO)
    val utilizationPct: BigDecimal? get() = pctOf(outstanding, account.facilityLimit)
}

class PeriodStats {
    var realized: BigDecimal = ZERO
    var charges: BigDecimal = ZERO
    var buyValue: BigDecimal = ZERO
    var sellValue: BigDecimal = ZERO
    var dividends: BigDecimal = ZERO
    var interest: BigDecimal = ZERO
    var financingCharges: BigDecimal = ZERO
    var trades: Int = 0
    /** Realized trading profit plus dividends, less margin interest and financing charges. */
    val net: BigDecimal get() = realized.add(dividends).subtract(interest).subtract(financingCharges)
    fun add(o: PeriodStats) {
        realized = realized.add(o.realized); charges = charges.add(o.charges); buyValue = buyValue.add(o.buyValue)
        sellValue = sellValue.add(o.sellValue); dividends = dividends.add(o.dividends); interest = interest.add(o.interest)
        financingCharges = financingCharges.add(o.financingCharges); trades += o.trades
    }
}

class PortfolioResult(
    val asOf: LocalDate,
    val method: CostMethod,
    val positions: List<Position>,
    val sells: List<SellResult>,
    val ledger: List<LedgerRow>,
    val margin: List<MarginState>,
    val monthly: TreeMap<YearMonth, PeriodStats>,
    val cash: BigDecimal,
    val deposits: BigDecimal,
    val withdrawals: BigDecimal,
    val feeTotals: Fees,
    val issues: List<String>
) {
    val open: List<Position> get() = positions.filter { it.isOpen }
    val realized: BigDecimal get() = positions.map { it.realized }.sum()
    val dividends: BigDecimal get() = positions.map { it.dividends }.sum().add(unallocatedDividends)
    var unallocatedDividends: BigDecimal = ZERO
    val costOfHoldings: BigDecimal get() = open.map { it.costBasis }.sum()
    val totalInvested: BigDecimal get() = positions.map { it.totalInvested }.sum()
    val loanOutstanding: BigDecimal get() = margin.map { it.outstanding }.sum()
    val interestTotal: BigDecimal get() = margin.map { it.interestTotal }.sum()
    val financingCharges: BigDecimal get() = margin.map { it.chargesTotal }.sum()
    val cashFunded: BigDecimal get() = positions.map { it.cashFunded }.sum()
    val marginFunded: BigDecimal get() = positions.map { it.marginFunded }.sum()
    fun position(symbol: String): Position? = positions.firstOrNull { it.symbol == symbol }
    fun stats(from: LocalDate, to: LocalDate): PeriodStats {
        val s = PeriodStats()
        monthly.subMap(YearMonth.from(from), true, YearMonth.from(to), true).values.forEach { s.add(it) }
        return s
    }
    fun yearly(): TreeMap<Int, PeriodStats> {
        val m = TreeMap<Int, PeriodStats>()
        monthly.forEach { (k, v) -> m.getOrPut(k.year) { PeriodStats() }.add(v) }
        return m
    }
}

/**
 * The single source of truth for every figure in the app. It replays all recorded events in date
 * order and derives holdings, cost basis, realized profit, cash, margin loans and interest.
 * Nothing is stored pre-aggregated, so edits and deletions always reconcile.
 */
object PortfolioEngine {

    private class Ev(val date: LocalDate, val order: Int, val id: Long, val txn: Txn? = null,
                     val cash: CashEntry? = null, val margin: MarginEntry? = null)

    fun compute(input: EngineInput): PortfolioResult {
        val issues = mutableListOf<String>()
        val positions = TreeMap<String, Position>()
        val states = LinkedHashMap<Long, MarginState>()
        input.accounts.forEach { states[it.id] = MarginState(it) }
        val monthly = TreeMap<YearMonth, PeriodStats>()
        val sells = mutableListOf<SellResult>()
        val ledger = mutableListOf<LedgerRow>()
        var cash = ZERO
        var deposits = ZERO
        var withdrawals = ZERO
        var unallocDiv = ZERO
        var fb = ZERO; var fc = ZERO; var fs = ZERO; var fd = ZERO; var ft = ZERO; var fo = ZERO

        fun pos(sym: String) = positions.getOrPut(sym) { Position(sym) }
        fun period(d: LocalDate) = monthly.getOrPut(YearMonth.from(d)) { PeriodStats() }
        fun loanTotal() = states.values.map { it.outstanding }.sum()

        val evs = ArrayList<Ev>()
        input.txns.forEach { evs += Ev(it.date, if (it.type == TxnType.SELL) 2 else 1, it.id, txn = it) }
        input.cash.forEach { evs += Ev(it.date, 0, it.id, cash = it) }
        input.marginEntries.forEach { evs += Ev(it.date, if (it.type == MarginEntryType.REPAYMENT) 3 else 0, it.id, margin = it) }
        evs.sortWith(compareBy<Ev> { it.date }.thenBy { it.order }.thenBy { it.id })

        // ----- margin helpers -----
        fun accrue(st: MarginState, to: LocalDate) {
            var d = st.last ?: run { st.last = to; return }
            if (!to.isAfter(d)) return
            val m = st.account.method
            while (d.isBefore(to)) {
                if (st.principal.isZero() && st.interestUnpaid.isZero()) { d = to; break }
                val nextMonth = d.withDayOfMonth(1).plusMonths(1)
                val end = if (nextMonth.isBefore(to)) nextMonth else to
                val days = ChronoUnit.DAYS.between(d, end)
                if (days > 0 && st.principal.isPos() && st.rate.isPos()) {
                    val interest = st.principal.multiply(st.rate).multiply(bd(days))
                        .divide(bd(100L * m.basis), 2, RoundingMode.HALF_UP)
                    if (interest.isPos()) {
                        st.interestUnpaid = st.interestUnpaid.add(interest)
                        st.interestTotal = st.interestTotal.add(interest)
                        period(d).interest = period(d).interest.add(interest)
                        for ((sym, fin) in st.financed) {
                            if (!fin.isPos()) continue
                            val share = interest.multiply(fin).divide(st.principal, 2, RoundingMode.HALF_UP)
                            st.symUnpaid[sym] = (st.symUnpaid[sym] ?: ZERO).add(share)
                            val p = pos(sym)
                            p.interestCharged = p.interestCharged.add(share)
                            p.interestUnpaid = p.interestUnpaid.add(share)
                        }
                    }
                }
                d = end
                if (m.compoundMonthly && d == nextMonth && st.interestUnpaid.isPos()) {
                    // capitalise: unpaid interest becomes principal
                    st.principal = st.principal.add(st.interestUnpaid)
                    st.interestUnpaid = ZERO
                    for ((sym, u) in st.symUnpaid) {
                        if (u.isPos()) {
                            st.financed[sym] = (st.financed[sym] ?: ZERO).add(u)
                            val p = pos(sym); p.financed = p.financed.add(u); p.interestUnpaid = p.interestUnpaid.subtract(u).max(ZERO)
                        }
                    }
                    st.symUnpaid.clear()
                }
            }
            st.last = to
        }
        fun accrueAll(to: LocalDate) = states.values.forEach { accrue(it, to) }

        fun reduceSymUnpaid(st: MarginState, sym: String, amt: BigDecimal) {
            val cur = st.symUnpaid[sym] ?: ZERO
            val take = amt.min(cur)
            if (!take.isPos()) return
            st.symUnpaid[sym] = cur.subtract(take)
            val p = pos(sym); p.interestUnpaid = p.interestUnpaid.subtract(take).max(ZERO)
        }
        fun reduceFinanced(st: MarginState, sym: String, amt: BigDecimal) {
            val cur = st.financed[sym] ?: ZERO
            val take = amt.min(cur)
            if (!take.isPos()) return
            val left = cur.subtract(take)
            if (left.isPos()) st.financed[sym] = left else st.financed.remove(sym)
            val p = pos(sym); p.financed = p.financed.subtract(take).max(ZERO)
        }
        fun payInterest(st: MarginState, amt: BigDecimal) {
            if (!amt.isPos()) return
            val before = st.interestUnpaid
            for (sym in st.symUnpaid.keys.toList()) {
                val u = st.symUnpaid[sym] ?: ZERO
                if (u.isPos() && before.isPos()) reduceSymUnpaid(st, sym, u.multiply(amt).divide(before, 2, RoundingMode.HALF_UP))
            }
            st.interestUnpaid = before.subtract(amt).max(ZERO)
        }
        fun payPrincipal(st: MarginState, amt: BigDecimal) {
            if (!amt.isPos()) return
            val before = st.principal
            for (sym in st.financed.keys.toList()) {
                val f = st.financed[sym] ?: ZERO
                if (f.isPos() && before.isPos()) reduceFinanced(st, sym, f.multiply(amt).divide(before, 2, RoundingMode.HALF_UP))
            }
            st.principal = before.subtract(amt).max(ZERO)
            if (st.principal.isZero()) {
                for (sym in st.financed.keys.toList()) reduceFinanced(st, sym, st.financed[sym] ?: ZERO)
            }
        }
        /** Applies a repayment; returns the amount actually applied (never more than is owed). */
        fun repay(st: MarginState, amount: BigDecimal, symbol: String?): BigDecimal {
            val applied = amount.min(st.outstanding)
            if (!applied.isPos()) return ZERO
            var rem = applied
            if (symbol != null) {
                // a sale settles its own stock's interest and principal first
                val ownInt = (st.symUnpaid[symbol] ?: ZERO).min(rem)
                if (ownInt.isPos()) { reduceSymUnpaid(st, symbol, ownInt); st.interestUnpaid = st.interestUnpaid.subtract(ownInt).max(ZERO); rem = rem.subtract(ownInt) }
                val ownPrin = (st.financed[symbol] ?: ZERO).min(rem)
                if (ownPrin.isPos()) { reduceFinanced(st, symbol, ownPrin); st.principal = st.principal.subtract(ownPrin).max(ZERO); rem = rem.subtract(ownPrin) }
            }
            val toInt = rem.min(st.interestUnpaid)
            payInterest(st, toInt); rem = rem.subtract(toInt)
            payPrincipal(st, rem)
            st.repaidTotal = st.repaidTotal.add(applied)
            return applied
        }

        // ----- replay -----
        for (ev in evs) {
            accrueAll(ev.date)
            ev.cash?.let { c ->
                val flow = if (c.type.sign > 0) c.amount else c.amount.negate()
                cash = cash.add(flow)
                when (c.type) {
                    CashType.DEPOSIT -> deposits = deposits.add(c.amount)
                    CashType.WITHDRAWAL -> withdrawals = withdrawals.add(c.amount)
                    CashType.DIVIDEND -> {
                        period(c.date).dividends = period(c.date).dividends.add(c.amount)
                        if (c.symbol.isNullOrBlank()) unallocDiv = unallocDiv.add(c.amount)
                        else pos(c.symbol).let { it.dividends = it.dividends.add(c.amount) }
                    }
                    else -> {}
                }
                ledger += LedgerRow(LedgerSource.CASH, c.id, c.date, c.symbol, c.type.label, null, null, null, null, null,
                    c.amount, flow, null, null, cash, loanTotal(), c.note)
            }
            ev.margin?.let { e ->
                val st = states[e.accountId]
                if (st == null) { issues += "Margin entry on ${e.date} refers to a deleted margin account."; return@let }
                var flow = ZERO
                when (e.type) {
                    MarginEntryType.DRAWDOWN -> { st.principal = st.principal.add(e.amount); st.drawnTotal = st.drawnTotal.add(e.amount); flow = e.amount }
                    MarginEntryType.REPAYMENT -> {
                        val applied = repay(st, e.amount, null)
                        if (applied < e.amount) issues += "Repayment of ${Fmt.money(e.amount)} on ${e.date} exceeds the loan balance; only ${Fmt.money(applied)} was applied."
                        flow = applied.negate()
                    }
                    MarginEntryType.CHARGE -> {
                        st.principal = st.principal.add(e.amount); st.chargesTotal = st.chargesTotal.add(e.amount)
                        period(e.date).financingCharges = period(e.date).financingCharges.add(e.amount)
                    }
                    MarginEntryType.RATE_CHANGE -> st.rate = e.rate
                }
                cash = cash.add(flow)
                ledger += LedgerRow(LedgerSource.MARGIN, e.id, e.date, null, e.type.label + " – " + st.account.broker, null, null, null, null, null,
                    if (e.type == MarginEntryType.RATE_CHANGE) null else e.amount, flow, null, PayMethod.MARGIN, cash, loanTotal(),
                    if (e.type == MarginEntryType.RATE_CHANGE) "New rate ${Fmt.rate(e.rate)} p.a. ${e.note}".trim() else e.note)
            }
            ev.txn?.let { t ->
                val p = pos(t.symbol)
                var flow = ZERO
                var realizedCell: BigDecimal? = null
                when (t.type) {
                    TxnType.BUY, TxnType.RIGHTS, TxnType.OPENING -> {
                        val total = t.totalCost
                        p.quantity += t.quantity; p.boughtQty += t.quantity
                        p.costBasis = p.costBasis.add(total)
                        p.totalInvested = p.totalInvested.add(total)
                        p.buyCharges = p.buyCharges.add(t.charges)
                        p.lots += Lot(t.date, t.quantity, total)
                        if (p.firstAcquired == null) p.firstAcquired = t.date
                        var financedAmt = ZERO
                        if (t.type != TxnType.OPENING) {
                            if (t.payment == PayMethod.MARGIN && t.financedAmount.isPos()) {
                                val st = t.marginAccountId?.let { states[it] }
                                if (st == null) issues += "${t.symbol} ${t.type.label.lowercase()} on ${t.date} is marked Margin but has no margin account; treated as cash."
                                else {
                                    financedAmt = t.financedAmount.min(total)
                                    if (st.last == null) st.last = t.date
                                    st.principal = st.principal.add(financedAmt); st.drawnTotal = st.drawnTotal.add(financedAmt)
                                    st.financed[t.symbol] = (st.financed[t.symbol] ?: ZERO).add(financedAmt)
                                    p.financed = p.financed.add(financedAmt)
                                }
                            }
                            flow = total.subtract(financedAmt).negate()
                            p.cashFunded = p.cashFunded.add(total.subtract(financedAmt))
                            p.marginFunded = p.marginFunded.add(financedAmt)
                            period(t.date).let { it.buyValue = it.buyValue.add(t.gross); it.charges = it.charges.add(t.charges); it.trades++ }
                            fb = fb.add(t.brokerage); fc = fc.add(t.cseFee); fs = fs.add(t.secFee); fd = fd.add(t.cdsFee); ft = ft.add(t.tax); fo = fo.add(t.other)
                        } else {
                            p.cashFunded = p.cashFunded.add(total)
                        }
                    }
                    TxnType.BONUS -> {
                        val held = p.quantity
                        if (held <= 0) issues += "Bonus issue for ${t.symbol} on ${t.date} recorded with no shares held."
                        var left = t.quantity
                        if (held > 0) for (lot in p.lots) { val add = t.quantity * lot.qty / held; lot.qty += add; left -= add }
                        if (left > 0) { if (p.lots.isEmpty()) p.lots += Lot(t.date, left, ZERO) else p.lots[0].qty += left }
                        p.quantity += t.quantity
                    }
                    TxnType.SPLIT -> {
                        if (t.splitNum <= 0 || t.splitDen <= 0) issues += "Invalid split ratio for ${t.symbol} on ${t.date}."
                        else {
                            val newQty = p.quantity * t.splitNum / t.splitDen
                            var assigned = 0L
                            for (lot in p.lots) { lot.qty = lot.qty * t.splitNum / t.splitDen; assigned += lot.qty }
                            if (p.lots.isNotEmpty()) p.lots[p.lots.size - 1].qty += newQty - assigned
                            p.quantity = newQty
                            p.lots.removeAll { it.qty <= 0 }
                        }
                    }
                    TxnType.SELL -> {
                        var q = t.quantity
                        if (q > p.quantity) {
                            issues += "Sale of ${Fmt.qty(q)} ${t.symbol} on ${t.date} exceeds the ${Fmt.qty(p.quantity)} shares held. Check for a missing purchase."
                            q = p.quantity
                        }
                        val net = t.netProceeds
                        // consume FIFO lots (used for holding period always, and for cost under FIFO)
                        var rem = q; var fifoCost = ZERO; var dayWeight = 0L
                        val iter = p.lots.iterator()
                        while (rem > 0 && iter.hasNext()) {
                            val lot = iter.next()
                            val take = minOf(rem, lot.qty)
                            val c = if (take == lot.qty) lot.cost else lot.cost.multiply(bd(take)).divide(bd(lot.qty), 2, RoundingMode.HALF_UP)
                            fifoCost = fifoCost.add(c); lot.cost = lot.cost.subtract(c); lot.qty -= take; rem -= take
                            dayWeight += take * ChronoUnit.DAYS.between(lot.date, t.date)
                            if (lot.qty == 0L) iter.remove()
                        }
                        val costOfSold = when {
                            q == 0L -> ZERO
                            q == p.quantity -> p.costBasis
                            input.method == CostMethod.FIFO -> fifoCost
                            else -> p.costBasis.multiply(bd(q)).divide(bd(p.quantity), 2, RoundingMode.HALF_UP)
                        }
                        p.costBasis = p.costBasis.subtract(costOfSold)
                        p.quantity -= q; p.soldQty += t.quantity
                        val realized = net.subtract(costOfSold)
                        p.realized = p.realized.add(realized)
                        p.sellCharges = p.sellCharges.add(t.charges)
                        p.totalNetProceeds = p.totalNetProceeds.add(net)
                        var applied = ZERO
                        if (t.loanRepayment.isPos()) {
                            val st = t.marginAccountId?.let { states[it] }
                            if (st == null) issues += "${t.symbol} sale on ${t.date} has a loan repayment but no margin account; proceeds kept as cash."
                            else {
                                applied = repay(st, t.loanRepayment.min(net.max(ZERO)), t.symbol)
                                if (applied < t.loanRepayment) issues += "Loan repayment on the ${t.symbol} sale of ${t.date} was capped at ${Fmt.money(applied)} (loan balance or proceeds were lower)."
                            }
                        }
                        if (p.quantity == 0L) {
                            // position closed: any loan still attributed to it becomes unattributed pool debt
                            p.lots.clear()
                            for (st in states.values) { st.financed.remove(t.symbol); st.symUnpaid.remove(t.symbol) }
                            p.financed = ZERO; p.interestUnpaid = ZERO
                        }
                        flow = net.subtract(applied)
                        realizedCell = realized
                        sells += SellResult(t.id, t.date, t.symbol, t.quantity, t.price, t.gross, t.charges, net, costOfSold, realized,
                            t.gross.subtract(costOfSold), pctOf(realized, costOfSold), if (q > 0) dayWeight / q else 0, applied)
                        period(t.date).let { it.realized = it.realized.add(realized); it.sellValue = it.sellValue.add(t.gross); it.charges = it.charges.add(t.charges); it.trades++ }
                        fb = fb.add(t.brokerage); fc = fc.add(t.cseFee); fs = fs.add(t.secFee); fd = fd.add(t.cdsFee); ft = ft.add(t.tax); fo = fo.add(t.other)
                    }
                }
                cash = cash.add(flow)
                val money = t.type == TxnType.BUY || t.type == TxnType.SELL || t.type == TxnType.RIGHTS || t.type == TxnType.OPENING
                ledger += LedgerRow(LedgerSource.TXN, t.id, t.date, t.symbol,
                    if (t.type == TxnType.SPLIT) "Split ${t.splitNum}:${t.splitDen}" else t.type.label,
                    if (t.type == TxnType.SPLIT) null else t.quantity,
                    if (money) t.price else null, if (money) t.gross else null,
                    if (money) t.brokerage else null, if (money) t.charges.subtract(t.brokerage) else null,
                    if (!money) null else if (t.type == TxnType.SELL) t.netProceeds else t.totalCost,
                    flow, realizedCell, if (t.type == TxnType.BUY || t.type == TxnType.RIGHTS || t.type == TxnType.SELL) t.payment else null,
                    cash, loanTotal(), t.note)
            }
        }
        accrueAll(input.asOf)

        val res = PortfolioResult(input.asOf, input.method, positions.values.toList(), sells, ledger, states.values.toList(),
            monthly, cash, deposits, withdrawals, Fees(fb, fc, fs, fd, ft, fo), issues)
        res.unallocatedDividends = unallocDiv
        return res
    }
}
