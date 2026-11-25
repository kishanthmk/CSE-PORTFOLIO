package lk.cseportfolio.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.util.Locale

// ---------- Money helpers ----------

val ZERO: BigDecimal = BigDecimal.ZERO
val HUNDRED: BigDecimal = BigDecimal(100)

fun bd(s: String): BigDecimal = BigDecimal(s)
fun bd(l: Long): BigDecimal = BigDecimal.valueOf(l)

/** Round to cents, half-up: the convention used on CSE contract notes. */
fun BigDecimal.r2(): BigDecimal = setScale(2, RoundingMode.HALF_UP)
fun BigDecimal.isZero(): Boolean = signum() == 0
fun BigDecimal.isPos(): Boolean = signum() > 0
fun BigDecimal.isNeg(): Boolean = signum() < 0

/** a / b * 100 rounded to 2dp, or null when b is zero. */
fun pctOf(a: BigDecimal, b: BigDecimal): BigDecimal? =
    if (b.isZero()) null else a.multiply(HUNDRED).divide(b, 2, RoundingMode.HALF_UP)

fun Iterable<BigDecimal>.sum(): BigDecimal = fold(ZERO) { a, b -> a.add(b) }

/** Lenient parser for user/imported numbers: "1,234.50", "(200.00)", "LKR 5", "" -> null. */
fun parseDecimal(raw: String?): BigDecimal? {
    if (raw == null) return null
    var s = raw.trim()
    if (s.isEmpty() || s == "-") return null
    var neg = false
    if (s.startsWith("(") && s.endsWith(")")) { neg = true; s = s.substring(1, s.length - 1) }
    s = s.replace(",", "").replace("LKR", "", true).replace("Rs.", "", true).replace("Rs", "", true).replace(" ", "")
    if (s.endsWith("-")) { neg = true; s = s.dropLast(1) }
    if (s.endsWith("%")) s = s.dropLast(1)
    return try { BigDecimal(s).let { if (neg) it.negate() else it } } catch (e: NumberFormatException) { null }
}

object Fmt {
    private val sym = DecimalFormatSymbols(Locale.US)
    private fun df(p: String) = DecimalFormat(p, sym).apply { roundingMode = RoundingMode.HALF_UP }
    fun money(v: BigDecimal): String = df("#,##0.00").format(v)
    fun lkr(v: BigDecimal): String = (if (v.isNeg()) "-LKR " else "LKR ") + df("#,##0.00").format(v.abs())
    fun signed(v: BigDecimal): String = (if (v.isPos()) "+" else "") + df("#,##0.00").format(v)
    fun qty(v: Long): String = df("#,##0").format(v)
    fun pct(v: BigDecimal?): String = if (v == null) "–" else (if (v.isPos()) "+" else "") + df("#,##0.00").format(v) + "%"
    fun rate(v: BigDecimal): String = v.stripTrailingZeros().toPlainString() + "%"
    fun plain(v: BigDecimal): String = v.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
    /** Compact: 1.25M, 340.5K */
    fun compact(v: BigDecimal): String {
        val a = v.abs()
        val s = when {
            a >= bd("1000000000") -> df("#,##0.00").format(v.divide(bd("1000000000"))) + "B"
            a >= bd("1000000") -> df("#,##0.00").format(v.divide(bd("1000000"))) + "M"
            a >= bd("1000") -> df("#,##0.0").format(v.divide(bd("1000"))) + "K"
            else -> df("#,##0.00").format(v)
        }
        return s
    }
}

// ---------- Domain ----------

enum class TxnType(val label: String) {
    BUY("Buy"), SELL("Sell"), OPENING("Opening balance"),
    BONUS("Bonus issue"), RIGHTS("Rights issue"), SPLIT("Share split / consolidation");
    val isAcquisition get() = this == BUY || this == OPENING || this == RIGHTS
}

enum class PayMethod(val label: String) { CASH("Cash"), MARGIN("Margin") }

enum class CostMethod(val label: String) { WEIGHTED_AVERAGE("Weighted average cost"), FIFO("First in, first out (FIFO)") }

/**
 * One share transaction. Fee fields hold the amounts actually charged (already computed from the
 * fee schedule in force on the trade date, or typed from the contract note), so later changes to
 * the fee schedule never alter historical transactions.
 */
data class Txn(
    val id: Long,
    val date: LocalDate,
    val symbol: String,
    val type: TxnType,
    val quantity: Long = 0,
    val price: BigDecimal = ZERO,
    val brokerage: BigDecimal = ZERO,
    val cseFee: BigDecimal = ZERO,
    val secFee: BigDecimal = ZERO,
    val cdsFee: BigDecimal = ZERO,
    val tax: BigDecimal = ZERO,
    val other: BigDecimal = ZERO,
    val payment: PayMethod = PayMethod.CASH,
    val marginAccountId: Long? = null,
    /** BUY/RIGHTS on margin: part of the total cost funded by the margin loan. */
    val financedAmount: BigDecimal = ZERO,
    /** SELL: part of the net proceeds applied to the margin loan. */
    val loanRepayment: BigDecimal = ZERO,
    /** SPLIT: [splitNum] new shares for every [splitDen] old shares. */
    val splitNum: Int = 1,
    val splitDen: Int = 1,
    val note: String = ""
) {
    val gross: BigDecimal get() = price.multiply(bd(quantity)).r2()
    val exchangeCharges: BigDecimal get() = cseFee.add(secFee).add(cdsFee)
    val charges: BigDecimal get() = brokerage.add(cseFee).add(secFee).add(cdsFee).add(tax).add(other)
    /** Total cost for acquisitions (gross + charges). */
    val totalCost: BigDecimal get() = gross.add(charges)
    /** Net proceeds for sales (gross − charges). */
    val netProceeds: BigDecimal get() = gross.subtract(charges)
}

enum class CashType(val label: String, val sign: Int) {
    DEPOSIT("Deposit", 1), WITHDRAWAL("Withdrawal", -1), DIVIDEND("Dividend", 1),
    OTHER_INCOME("Other income", 1), OTHER_EXPENSE("Other expense", -1)
}

data class CashEntry(
    val id: Long, val date: LocalDate, val type: CashType, val amount: BigDecimal,
    val symbol: String? = null, val note: String = ""
)

enum class InterestMethod(val label: String, val basis: Int, val compoundMonthly: Boolean) {
    SIMPLE_DAILY_365("Simple, daily balance / 365", 365, false),
    SIMPLE_DAILY_360("Simple, daily balance / 360", 360, false),
    MONTHLY_COMPOUND_365("Daily balance / 365, capitalised monthly", 365, true)
}

data class MarginAccount(
    val id: Long,
    val broker: String,
    val accountNo: String = "",
    val facilityLimit: BigDecimal = ZERO,
    /** Annual interest rate in percent, applying from [startDate] until a RATE_CHANGE entry. */
    val interestRate: BigDecimal = ZERO,
    val method: InterestMethod = InterestMethod.SIMPLE_DAILY_365,
    val startDate: LocalDate? = null,
    val repaymentDate: LocalDate? = null,
    /** Warn when loan / facility limit exceeds this percent. */
    val warnUtilizationPct: BigDecimal = bd("80"),
    /** Margin call when loan / collateral value exceeds this percent (0 = not set). */
    val marginCallLtvPct: BigDecimal = bd("50"),
    /** true: all holdings count as collateral; false: only stocks bought with this facility. */
    val wholePortfolioCollateral: Boolean = false,
    val note: String = ""
)

enum class MarginEntryType(val label: String) {
    DRAWDOWN("Cash drawdown"), REPAYMENT("Repayment"), CHARGE("Financing charge"), RATE_CHANGE("Interest rate change")
}

data class MarginEntry(
    val id: Long, val accountId: Long, val date: LocalDate, val type: MarginEntryType,
    val amount: BigDecimal = ZERO,
    /** RATE_CHANGE: new annual rate in percent. */
    val rate: BigDecimal = ZERO,
    val note: String = ""
)

/** Latest known market data for a symbol. Never fabricated: comes from manual entry, an import, or a configured API. */
data class Quote(
    val symbol: String,
    val last: BigDecimal,
    val prevClose: BigDecimal? = null,
    val change: BigDecimal? = null,
    val changePct: BigDecimal? = null,
    val volume: Long? = null,
    val high52: BigDecimal? = null,
    val low52: BigDecimal? = null,
    val updatedAtMillis: Long = 0,
    val source: String = "Manual"
)
