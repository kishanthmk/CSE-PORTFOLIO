package lk.cseportfolio.core

import java.math.BigDecimal
import java.time.LocalDate

data class Fees(
    val brokerage: BigDecimal = ZERO, val cse: BigDecimal = ZERO, val sec: BigDecimal = ZERO,
    val cds: BigDecimal = ZERO, val tax: BigDecimal = ZERO, val other: BigDecimal = ZERO
) {
    val exchange: BigDecimal get() = cse.add(sec).add(cds)
    val total: BigDecimal get() = brokerage.add(cse).add(sec).add(cds).add(tax).add(other)
}

/**
 * A dated fee schedule. All rates are percentages of gross transaction value and are fully
 * user-editable. Several schedules can exist; the one with the latest [effectiveFrom] on or before
 * the trade date is used when a transaction is entered.
 */
data class FeeSchedule(
    val id: Long = 0,
    val name: String = "",
    val effectiveFrom: LocalDate,
    val brokeragePct: BigDecimal = ZERO,
    val csePct: BigDecimal = ZERO,
    val secPct: BigDecimal = ZERO,
    val cdsPct: BigDecimal = ZERO,
    /** Government levy / taxes (e.g. Share Transaction Levy). */
    val taxPct: BigDecimal = ZERO,
    val otherPct: BigDecimal = ZERO,
    val minBrokerage: BigDecimal = ZERO,
    val fixedBrokerage: BigDecimal = ZERO,
    val fixedOther: BigDecimal = ZERO,
    /** Optional second tier: when gross value exceeds this, the tier-2 rates apply to the whole trade. */
    val tier2Threshold: BigDecimal? = null,
    val t2BrokeragePct: BigDecimal = ZERO,
    val t2CsePct: BigDecimal = ZERO,
    val t2SecPct: BigDecimal = ZERO,
    val t2CdsPct: BigDecimal = ZERO
) {
    private fun usesTier2(gross: BigDecimal) = tier2Threshold != null && tier2Threshold.isPos() && gross > tier2Threshold

    /** Total percentage rate that applies to a trade of this size (fixed amounts excluded). */
    fun totalPct(gross: BigDecimal = ZERO): BigDecimal =
        if (usesTier2(gross)) t2BrokeragePct.add(t2CsePct).add(t2SecPct).add(t2CdsPct).add(taxPct).add(otherPct)
        else brokeragePct.add(csePct).add(secPct).add(cdsPct).add(taxPct).add(otherPct)

    fun compute(gross: BigDecimal): Fees {
        if (!gross.isPos()) return Fees()
        val t2 = usesTier2(gross)
        fun part(pct: BigDecimal) = gross.multiply(pct).divide(HUNDRED).r2()
        val cse = part(if (t2) t2CsePct else csePct)
        val sec = part(if (t2) t2SecPct else secPct)
        val cds = part(if (t2) t2CdsPct else cdsPct)
        val tax = part(taxPct)
        val otherPart = part(otherPct)
        // Brokers charge the combined percentage on the trade value and round once, so brokerage
        // absorbs any one-cent rounding difference between the components and that total.
        val pctTotal = part(totalPct(gross))
        var brokerage = pctTotal.subtract(cse).subtract(sec).subtract(cds).subtract(tax).subtract(otherPart).max(ZERO).add(fixedBrokerage)
        if (brokerage < minBrokerage) brokerage = minBrokerage
        return Fees(brokerage = brokerage.r2(), cse = cse, sec = sec, cds = cds, tax = tax, other = otherPart.add(fixedOther).r2())
    }

    companion object {
        /**
         * Starting values taken from the CSE's published equity transaction-cost table
         * (in force since 27 June 2017; checked November 2026). They are only a first-run
         * default — edit them in Settings ▸ Fee schedules to match your broker's contract notes.
         */
        fun cseDefault(): FeeSchedule = FeeSchedule(
            name = "CSE published equity rates",
            effectiveFrom = LocalDate.of(2017, 6, 27),
            brokeragePct = bd("0.64"), csePct = bd("0.084"), secPct = bd("0.072"), cdsPct = bd("0.024"),
            taxPct = bd("0.30"),
            tier2Threshold = bd("100000000"),
            t2BrokeragePct = bd("0.20"), t2CsePct = bd("0.0525"), t2SecPct = bd("0.045"), t2CdsPct = bd("0.015")
        )

        /** The schedule in force on [date]: latest effectiveFrom ≤ date, else the earliest one. */
        fun forDate(all: List<FeeSchedule>, date: LocalDate): FeeSchedule? {
            if (all.isEmpty()) return null
            val sorted = all.sortedBy { it.effectiveFrom }
            return sorted.lastOrNull { !it.effectiveFrom.isAfter(date) } ?: sorted.first()
        }
    }
}
