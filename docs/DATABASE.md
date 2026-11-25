# Database structure

The app keeps one SQLite database, `portfolio.db`, encrypted with SQLCipher. The key is generated on
the phone and wrapped by a key in the Android Keystore, so the file cannot be opened elsewhere.
Room generates the exact schema as JSON in `app/schemas/` on every build.

Amounts are stored as plain decimal text (`"1738800.00"`), never as floating point, and dates as
ISO text (`2026-11-10`). Nothing aggregated is stored: holdings, cost basis, profit, cash and loan
balances are always recomputed from these records by the calculation engine.

| Table | One row is | Main columns |
|---|---|---|
| `companies` | A CSE company you track, with its latest known price | `symbol` (key), `name`, `last`, `prevClose`, `change`, `changePct`, `volume`, `high52`, `low52`, `priceUpdatedAt`, `priceSource` |
| `transactions` | A buy, sell, opening balance, bonus, rights issue or split | `id`, `date`, `symbol`, `type`, `quantity`, `price`, `brokerage`, `cseFee`, `secFee`, `cdsFee`, `tax`, `other`, `payment` (CASH/MARGIN), `marginAccountId`, `financedAmount`, `loanRepayment`, `splitNum`, `splitDen`, `note`, `feeScheduleId`, `createdAt`, `updatedAt`, `deleted` |
| `cash_entries` | A deposit, withdrawal, dividend or other cash movement | `id`, `date`, `type`, `amount`, `symbol`, `note`, `updatedAt`, `deleted` |
| `margin_accounts` | A margin facility | `id`, `broker`, `accountNo`, `facilityLimit`, `interestRate`, `method`, `startDate`, `repaymentDate`, `warnUtilizationPct`, `marginCallLtvPct`, `wholePortfolioCollateral`, `note` |
| `margin_entries` | A cash drawdown, repayment, financing charge or rate change | `id`, `accountId`, `date`, `type`, `amount`, `rate`, `note`, `updatedAt`, `deleted` |
| `fee_schedules` | A dated set of fee rates | `id`, `name`, `effectiveFrom`, `brokeragePct`, `csePct`, `secPct`, `cdsPct`, `taxPct`, `otherPct`, `minBrokerage`, `fixedBrokerage`, `fixedOther`, `tier2Threshold`, `t2BrokeragePct`, `t2CsePct`, `t2SecPct`, `t2CdsPct` |
| `watchlist` | A company you are watching | `id`, `symbol`, `name`, `targetBuy`, `targetSell`, `notes`, `buyNotifiedAt`, `sellNotifiedAt` |
| `audit_log` | One change to any record | `id`, `timestamp`, `entity`, `entityId`, `action`, `summary`, `before`, `after` |

Notes

- **Fees are stored on the transaction.** `feeScheduleId` only records which schedule produced
  them. Editing or adding schedules therefore never changes a trade already recorded.
- **Deleting is soft.** `deleted = 1` moves a row to the recycle bin; it can be restored. Permanent
  deletion is a separate, confirmed step. Every create, edit, delete, restore and import writes an
  `audit_log` row holding the record before and after.
- **Margin purchases and sale repayments live on the transaction** (`financedAmount`,
  `loanRepayment`); `margin_entries` holds everything else that moves the loan.
