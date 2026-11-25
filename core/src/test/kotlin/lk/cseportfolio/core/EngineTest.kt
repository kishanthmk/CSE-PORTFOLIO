package lk.cseportfolio.core

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reconciliation tests using sample HBS, YORK and ACME transactions, worked by hand in the comments. */
class EngineTest {
    private val fees = FeeSchedule.cseDefault()
    private fun d(s: String) = LocalDate.parse(s)
    private fun eq(expected: String, actual: BigDecimal?) = assertEquals(0, BigDecimal(expected).compareTo(actual!!).also {
        if (it != 0) throw AssertionError("expected $expected but was ${actual.toPlainString()}") })

    private fun buy(id: Long, date: String, sym: String, qty: Long, price: String, pay: PayMethod = PayMethod.CASH, acct: Long? = null, financed: String = "0"): Txn {
        val f = fees.compute(bd(price).multiply(bd(qty)).r2())
        return Txn(id, d(date), sym, TxnType.BUY, qty, bd(price), f.brokerage, f.cse, f.sec, f.cds, f.tax, f.other, pay, acct, bd(financed))
    }
    private fun sell(id: Long, date: String, sym: String, qty: Long, price: String, acct: Long? = null, repay: String = "0"): Txn {
        val f = fees.compute(bd(price).multiply(bd(qty)).r2())
        return Txn(id, d(date), sym, TxnType.SELL, qty, bd(price), f.brokerage, f.cse, f.sec, f.cds, f.tax, f.other, PayMethod.CASH, acct, loanRepayment = bd(repay))
    }

    @Test fun feeBreakdownMatchesPublishedRates() {
        val f = fees.compute(bd("20000"))
        eq("128.00", f.brokerage); eq("16.80", f.cse); eq("14.40", f.sec); eq("4.80", f.cds); eq("60.00", f.tax); eq("224.00", f.total)
        // Broker statement check: 115,000 ASIY at 13.50 -> projected proceeds 1,535,112.00
        val g = bd("13.50").multiply(bd(115000))
        eq("1535112.00", g.subtract(fees.compute(g).total))
        // second tier above Rs. 100 million
        val big = fees.compute(bd("200000000"))
        eq("400000.00", big.brokerage); eq("105000.00", big.cse); eq("90000.00", big.sec); eq("30000.00", big.cds); eq("600000.00", big.tax)
    }

    @Test fun historicalScheduleIsSelectedByDate() {
        val old = FeeSchedule(1, "old", d("2015-01-01"), brokeragePct = bd("1.0"))
        val cur = FeeSchedule(2, "new", d("2024-01-01"), brokeragePct = bd("0.5"))
        assertEquals(1L, FeeSchedule.forDate(listOf(cur, old), d("2020-06-01"))!!.id)
        assertEquals(2L, FeeSchedule.forDate(listOf(cur, old), d("2024-01-01"))!!.id)
        assertEquals(1L, FeeSchedule.forDate(listOf(cur, old), d("2010-01-01"))!!.id)
    }

    /** HBS: two purchases at different prices, then a partial sale. */
    private fun hbs() = listOf(
        buy(1, "2026-01-05", "HBS", 1000, "20.00"),      // 20,000.00 + 224.00 = 20,224.00
        buy(2, "2026-02-10", "HBS", 500, "23.00"),       // 11,500.00 + 128.80 = 11,628.80
        sell(3, "2026-03-15", "HBS", 600, "25.00"))      // 15,000.00 - 168.00 = 14,832.00

    @Test fun hbsWeightedAverage() {
        val r = PortfolioEngine.compute(EngineInput(hbs(), listOf(CashEntry(1, d("2026-01-01"), CashType.DEPOSIT, bd("50000"))), asOf = d("2026-04-01")))
        val p = r.position("HBS")!!
        assertEquals(900L, p.quantity); assertEquals(1500L, p.boughtQty); assertEquals(600L, p.soldQty)
        // pooled cost 31,852.80 over 1,500 shares = 21.2352; cost of 600 sold = 12,741.12
        eq("19111.68", p.costBasis); eq("21.2352", p.avgCost)
        eq("2090.88", p.realized)                         // 14,832.00 - 12,741.12
        eq("352.80", p.buyCharges); eq("168.00", p.sellCharges)
        eq("32979.20", r.cash)                            // 50,000 - 20,224 - 11,628.80 + 14,832
        val s = r.sells.single()
        eq("12741.12", s.costOfSold); eq("2258.88", s.grossProfit); eq("16.41", s.returnPct)
        assertEquals(69L, s.holdingDays)                  // FIFO lot of 5 Jan -> 15 Mar
        eq("520.80", r.feeTotals.total)
        eq("2090.88", r.monthly[java.time.YearMonth.of(2026, 3)]!!.realized)
        assertTrue(r.issues.isEmpty())
        // identity: invested - cost still held + realized = net proceeds received
        eq(r.positions.map { it.totalNetProceeds }.sum().toPlainString(), r.totalInvested.subtract(r.costOfHoldings).add(r.realized))
        eq(r.cash.toPlainString(), r.ledger.last().cashAfter)
    }

    @Test fun hbsFifoAlternative() {
        val r = PortfolioEngine.compute(EngineInput(hbs(), method = CostMethod.FIFO, asOf = d("2026-04-01")))
        val p = r.position("HBS")!!
        eq("2697.60", p.realized)                         // 14,832.00 - 20,224.00 * 600/1000
        eq("19718.40", p.costBasis)                       // 8,089.60 + 11,628.80
    }

    /** YORK: the example in the specification. */
    @Test fun yorkSellSimulation() {
        val open = Txn(1, d("2026-01-01"), "YORK", TxnType.OPENING, 10000, bd("6.80"))
        val r = PortfolioEngine.compute(EngineInput(listOf(open), asOf = d("2026-06-01")))
        val p = r.position("YORK")!!
        eq("68000.00", p.costBasis)
        val s = Simulator.simulate(p, 10000, bd("12.70"), fees)
        eq("127000.00", s.gross); eq("1422.40", s.fees.total); eq("125577.60", s.net)
        eq("57577.60", s.profit); eq("84.67", s.returnPct); eq("125577.60", s.netCash)
        // partial: 4,000 shares
        val h = Simulator.simulate(p, 4000, bd("12.70"), fees)
        eq("27200.00", h.costOfSold); eq("50231.04", h.net); eq("23031.04", h.profit)
        // break-even after selling charges: 68,000 / (10,000 x 0.9888) = 6.8770..., next tick 6.90
        val be = Simulator.breakEven(10000, p.costBasis, fees)!!
        assertTrue(be > bd("6.8770") && be < bd("6.8772"))
        eq("6.90", Simulator.toTick(be))
        assertTrue(Simulator.simulate(p, 10000, bd("6.90"), fees).profit.isPos())
        assertTrue(Simulator.simulate(p, 10000, bd("6.80"), fees).profit.isNeg())
        // nothing recorded: the simulator never changes the portfolio
        assertEquals(10000L, p.quantity); assertTrue(r.sells.isEmpty())
    }

    private val acct = MarginAccount(7, "Sample Broker", "M-1", facilityLimit = bd("100000"), interestRate = bd("18"))

    /** ACME: margin-financed purchase, interest accrual, sale and loan settlement. */
    @Test fun acmeMarginRoundTrip() {
        val b = buy(1, "2026-01-01", "ACME", 5000, "10.00", PayMethod.MARGIN, 7, "30000")   // cost 50,560.00; own capital 20,560.00
        val before = PortfolioEngine.compute(EngineInput(listOf(b), accounts = listOf(acct), asOf = d("2026-03-02")))
        val st = before.margin.single()
        // 30,000 x 18% / 365: Jan 31d 458.63 + Feb 28d 414.25 + 1d 14.79 = 887.67
        eq("30000.00", st.principal); eq("887.67", st.interestUnpaid); eq("30887.67", st.outstanding)
        eq("-20560.00", before.cash)
        val p = before.position("ACME")!!
        eq("30000.00", p.financed); eq("887.67", p.interestCharged); eq("20560.00", p.ownCapital)
        val sim = Simulator.simulate(p, 5000, bd("12.00"), fees)
        eq("59328.00", sim.net); eq("8768.00", sim.profit)
        eq("30887.67", sim.loanRepayment); eq("28440.33", sim.netCash)
        eq("7880.33", sim.profitAfterInterest); eq("38.33", sim.returnOnOwnCapitalPct)
        // record the sale exactly as simulated
        val s = sell(2, "2026-03-02", "ACME", 5000, "12.00", 7, "30887.67")
        val after = PortfolioEngine.compute(EngineInput(listOf(b, s), accounts = listOf(acct), asOf = d("2026-03-31")))
        eq("0.00", after.loanOutstanding); eq("887.67", after.interestTotal)
        eq("8768.00", after.realized)
        eq("7880.33", after.cash)                         // = trading profit - margin interest: cash reconciles
        eq(after.realized.subtract(after.interestTotal).toPlainString(), after.cash)
        val sum = Valuation.summarize(after, emptyMap(), fees)
        eq("7880.33", sum.totalProfit); eq("7880.33", sum.netEquity)
        eq("30000.00", after.marginFunded); eq("20560.00", after.cashFunded)
    }

    @Test fun marginPartialSaleAndPartialRepayment() {
        val b = buy(1, "2026-01-01", "ACME", 5000, "10.00", PayMethod.MARGIN, 7, "30000")
        val rep = MarginEntry(1, 7, d("2026-01-31"), MarginEntryType.REPAYMENT, bd("10000"))
        // 30 days on 30,000 = 443.84 interest; repayment pays it first, then 9,556.16 of principal
        val s = sell(2, "2026-02-10", "ACME", 2000, "11.00", 7, "8000")
        val r = PortfolioEngine.compute(EngineInput(listOf(b, s), accounts = listOf(acct), marginEntries = listOf(rep),
            cash = listOf(CashEntry(1, d("2026-01-01"), CashType.DEPOSIT, bd("40000"))), asOf = d("2026-02-10")))
        val st = r.margin.single()
        // after repayment principal 20,443.84; 31 Jan->1 Feb 1d = 10.08, 1 Feb->10 Feb 9d = 90.74; total interest 544.66
        eq("544.66", st.interestTotal)
        // sale applies 8,000: 100.82 unpaid interest first, then 7,899.18 principal
        eq("12544.66", st.principal); eq("0.00", st.interestUnpaid)
        val p = r.position("ACME")!!
        assertEquals(3000L, p.quantity); eq("30336.00", p.costBasis); eq("12544.66", p.financed)
        // cash: 40,000 - 20,560 - 10,000 + (22,000 - 246.40 - 8,000) = 23,193.60
        eq("23193.60", r.cash)
        eq("1529.60", p.realized)                         // 21,753.60 - 20,224.00
        val v = Valuation.summarize(r, mapOf("ACME" to Quote("ACME", bd("11.00"))), fees).margin.single()
        eq("33000.00", v.collateral); eq("38.01", v.ltvPct); assertTrue(!v.marginCall && !v.utilizationWarning)
        // an over-large repayment is capped and reported
        val r2 = PortfolioEngine.compute(EngineInput(listOf(b), accounts = listOf(acct),
            marginEntries = listOf(MarginEntry(1, 7, d("2026-01-01"), MarginEntryType.REPAYMENT, bd("99999"))), asOf = d("2026-01-01")))
        eq("0.00", r2.loanOutstanding); assertEquals(1, r2.issues.size); eq("-50560.00", r2.cash)
    }

    @Test fun marginRateChangeCompoundingAndWarnings() {
        val a = acct.copy(method = InterestMethod.MONTHLY_COMPOUND_365, facilityLimit = bd("35000"), warnUtilizationPct = bd("80"), marginCallLtvPct = bd("50"))
        val b = buy(1, "2026-01-01", "ACME", 5000, "10.00", PayMethod.MARGIN, 7, "30000")
        val rc = MarginEntry(1, 7, d("2026-02-01"), MarginEntryType.RATE_CHANGE, rate = bd("24"))
        val r = PortfolioEngine.compute(EngineInput(listOf(b), accounts = listOf(a), marginEntries = listOf(rc), asOf = d("2026-03-01")))
        val st = r.margin.single()
        // Jan: 458.63 capitalised -> 30,458.63; Feb at 24%: 30,458.63 x 24% x 28/365 = 560.77, capitalised on 1 Mar
        eq("31019.40", st.principal); eq("1019.40", st.interestTotal); eq("31019.40", r.position("ACME")!!.financed)
        val v = Valuation.summarize(r, mapOf("ACME" to Quote("ACME", bd("9.00"))), fees).margin.single()
        assertTrue(v.utilizationWarning)                  // 88.6% of the facility
        assertTrue(v.marginCall)                          // 31,019 / 45,000 = 68.9% > 50%
        eq("0.00", v.buyingPower)
    }

    @Test fun corporateActions() {
        val t = listOf(
            Txn(1, d("2026-01-01"), "HBS", TxnType.OPENING, 1000, bd("10.00")),
            Txn(2, d("2026-02-01"), "HBS", TxnType.BONUS, 250),                         // 1 for 4
            Txn(3, d("2026-03-01"), "HBS", TxnType.SPLIT, splitNum = 2, splitDen = 1),
            Txn(4, d("2026-04-01"), "HBS", TxnType.RIGHTS, 500, bd("3.00")),            // rights at 3.00, no charges
            sell(5, "2026-05-01", "HBS", 1000, "5.00"))
        val r = PortfolioEngine.compute(EngineInput(t, asOf = d("2026-06-01")))
        val p = r.position("HBS")!!
        // 1,000 -> 1,250 -> 2,500 -> 3,000 shares costing 11,500.00 (avg 3.8333); sell 1,000: cost 3,833.33
        assertEquals(2000L, p.quantity); eq("7666.67", p.costBasis)
        eq("1110.67", p.realized)                         // 5,000 - 56.00 - 3,833.33
        assertEquals(2000L, p.lots.sumOf { it.qty })
    }

    @Test fun oversellIsReportedNotHidden() {
        val r = PortfolioEngine.compute(EngineInput(listOf(buy(1, "2026-01-01", "HBS", 100, "10"), sell(2, "2026-01-02", "HBS", 150, "10")), asOf = d("2026-02-01")))
        assertEquals(1, r.issues.size); assertEquals(0L, r.position("HBS")!!.quantity)
    }

    @Test fun dashboardSummary() {
        val t = hbs() + Txn(10, d("2026-01-01"), "YORK", TxnType.OPENING, 10000, bd("6.80"))
        val r = PortfolioEngine.compute(EngineInput(t, listOf(CashEntry(1, d("2026-01-01"), CashType.DEPOSIT, bd("50000")),
            CashEntry(2, d("2026-03-20"), CashType.DIVIDEND, bd("900"), "HBS")), asOf = d("2026-03-31")))
        val q = mapOf("HBS" to Quote("HBS", bd("22.20"), prevClose = bd("22.00")), "YORK" to Quote("YORK", bd("8.30"), change = bd("-0.10")))
        val s = Valuation.summarize(r, q, fees)
        eq("102980.00", s.marketValue)                    // 900 x 22.20 + 10,000 x 8.30
        eq("87111.68", s.costOfHoldings); eq("15868.32", s.unrealized)
        eq("14714.94", s.netUnrealized)                   // after 1.12% selling charges (223.78 + 929.60)
        eq("33879.20", s.cash); eq("136859.20", s.netEquity)
        eq("-820.00", s.dayChange)                        // 900 x 0.20 - 10,000 x 0.10
        eq("2990.88", s.monthPL); eq("2990.88", s.ytdPL)  // realized 2,090.88 + dividend 900
        eq("18859.20", s.totalProfit)
        assertEquals("YORK", s.best!!.symbol); assertEquals("HBS", s.worst!!.symbol)
        assertEquals(2, s.companiesHeld); assertEquals(0, s.unpricedCount)
        assertNotNull(Reports.averageHoldingDays(r))
        val f = LedgerFilter(symbol = "HBS", side = "SELL", profitable = true)
        assertEquals(1, r.ledger.count { f.matches(it) })
        assertEquals(0, r.ledger.count { f.copy(profitable = false).matches(it) })
        assertNull(Valuation.summarize(r, emptyMap(), fees).dayChange)
    }
}
