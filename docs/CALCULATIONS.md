# How CSE Portfolio Pro calculates

Every figure in the app comes from one calculation engine (`core/.../Engine.kt`) that replays all
recorded entries in date order. Nothing is stored pre-added, so an edit or deletion always flows
through to every balance. Amounts use exact decimal arithmetic, rounded half-up to the cent.

## Charges

For a trade of gross value *G* under the fee schedule in force on the trade date:

- CSE, SEC, CDS, tax / levy and "other" are each *G × rate*, rounded to the cent.
- The total percentage charge is *G × (sum of all rates)*, rounded to the cent; brokerage is that
  total less the other components, so the parts always add up to the total.
- A minimum brokerage and fixed per-trade amounts are applied if the schedule has them.
- Above the schedule's large-trade threshold, the second-tier rates apply to the whole trade.

**Purchase:** Gross + Brokerage + Exchange charges + Other charges = Total purchase cost.
**Sale:** Gross − Brokerage − Exchange charges − Other charges = Net selling proceeds.

The computed amounts are saved on the transaction. Later changes to fee schedules do not alter them.

## Cost basis and profit

- **Acquisition cost** of a purchase is gross value plus all its charges. It is added to the
  stock's cost pool, so buying charges are part of cost and are never deducted a second time.
- **Weighted average (default).** Average cost = cost pool ÷ shares held. A sale of *q* out of *Q*
  shares removes *pool × q ÷ Q* from the pool (the whole pool when everything is sold).
- **FIFO (optional, in Settings).** A sale consumes the oldest purchase lots first.
- **Realized profit / loss** = net selling proceeds − cost of the shares sold. This is net profit:
  buying charges are inside the cost, selling charges are inside the proceeds.
  **Gross profit** (shown separately) = gross selling value − cost of the shares sold.
- **Unrealized profit / loss** = market value − cost of shares held. "If sold today, after
  charges" additionally deducts estimated selling charges, which is the figure a broker's
  Gain/Loss statement shows.
- **Bonus issue:** shares increase, cost pool unchanged. **Split / consolidation:** shares are
  multiplied by the ratio, cost pool unchanged. **Rights:** treated as a purchase at the rights price.
- **Percentage return** of a stock = (realized + unrealized) ÷ everything ever invested in it.
  Portfolio return = (realized + unrealized + dividends − margin interest − financing charges) ÷
  everything ever invested.

## Cash

Cash starts at zero and moves with: deposits (+), withdrawals (−), dividends (+), purchases
(− the part not financed by margin), sales (+ net proceeds less any loan repayment), margin cash
drawdowns (+) and margin repayments (−). Opening balances do not move cash.

## Margin

Each margin account is a pooled loan.

- A margin purchase adds its financed amount to the loan and tags that amount to the stock.
- Interest accrues daily on principal at the rate in force: *principal × rate × days ÷ 365* (or 360),
  split at month ends and rate changes. With the "capitalised monthly" method, unpaid interest is
  added to principal on the first of each month. Interest is attributed to stocks in proportion to
  their financed amounts and is reported separately from trading profit.
- A repayment settles unpaid interest first, then principal. A repayment made from a sale settles
  that stock's own interest and principal first.
- Collateral is the market value of the stocks bought with the facility (or of all holdings, if you
  choose that for the account). Utilisation = loan ÷ facility limit. The margin-call test is
  loan ÷ collateral against your threshold. Buying power is the lower of the unused facility and the
  headroom under the margin-call threshold.

For a margin-funded holding the simulator reports: (1) acquisition cost, (2) amount financed,
(3) your own capital, (4) loan outstanding, (5) interest to date, (6) net selling proceeds,
(7) loan repayment, (8) net cash to you, (9) net profit after interest, (10) return on own capital.

## Worked examples (these are the automated tests)

**HBS, weighted average.** Buy 1,000 @ 20.00 → 20,000.00 + 224.00 = 20,224.00. Buy 500 @ 23.00 →
11,500.00 + 128.80 = 11,628.80. Pool 31,852.80 over 1,500 shares, average 21.2352.
Sell 600 @ 25.00 → 15,000.00 − 168.00 = 14,832.00. Cost of shares sold 12,741.12.
Realized profit 2,090.88. Remaining 900 shares cost 19,111.68. With a 50,000 deposit, cash is 32,979.20.
Under FIFO the same sale realizes 2,697.60.

**YORK, simulator.** 10,000 shares at an average 6.80 (cost 68,000.00), sold at 12.70:
gross 127,000.00, charges 1,422.40, net proceeds 125,577.60, profit 57,577.60, return 84.67 %.
Break-even after charges is 6.8771, so the lowest tradable price without a loss is 6.90.

**ACME, margin.** Buy 5,000 @ 10.00 on 1 January: cost 50,560.00, of which 30,000.00 financed at
18 % and 20,560.00 own capital. By 2 March interest is 458.63 + 414.25 + 14.79 = 887.67.
Sell 5,000 @ 12.00: net proceeds 59,328.00, trading profit 8,768.00. Loan repayment 30,887.67,
net cash to you 28,440.33, profit after interest 7,880.33, return on own capital 38.33 %.
After the sale the loan is zero and the cash balance equals that 7,880.33.

**Broker statement.** A broker's Portfolio Gain/Loss statement, imported and valued at the
statement's own prices, must reproduce the statement's total cost, projected sales proceeds and
gain / loss to the cent. The test does this with figures from a real statement.
