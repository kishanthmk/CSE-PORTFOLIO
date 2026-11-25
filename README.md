# CSE Portfolio Pro

A private Android app for managing investments and trading on the Colombo Stock Exchange:
cash and margin trades, full fee calculation, realized and unrealized profit, a sell-price
simulator, margin loan tracking, reports, and Excel / CSV / PDF import and export. All amounts are in LKR.

Kotlin · Jetpack Compose · Material 3 · Room (SQLCipher-encrypted) · MVVM · works fully offline.

---

## 1. Build the APK (no software to install)

The project builds itself on GitHub and hands you an APK to download.

1. Create a free account at github.com and sign in.
2. Create a **new repository**. Name it anything (for example `cse-portfolio-pro`) and choose
   **Private**. Private matters: the project contains the key that signs your app.
3. On the new repository page choose **uploading an existing file**. Unzip the project on your
   computer, open the unzipped `cse-portfolio-pro` folder, select **everything inside it**
   (including the `.github` folder; switch on "show hidden files" if you cannot see it) and drag it
   into the browser. Press **Commit changes**.
4. Open the **Actions** tab. A run called **Build APK** starts by itself and takes about 10 minutes.
   If it does not start, open *Build APK* on the left and press **Run workflow**.
5. When the run shows a green tick, open it and download **CSE-Portfolio-Pro-APK** at the bottom
   of the page. Unzip it; inside is `app-release.apk`.
6. Copy the APK to your phone and open it. Android will ask you to allow installing from that
   source the first time.

If the run shows a red cross, open it, click the failed step, and copy the lines marked `e:` or
`error:`. Those lines say exactly what to correct.

**Updating later:** upload changed files to the same repository, download the new APK and install
it over the old one. Your data stays, because every build is signed with the same key
(`signing/personal.jks`). Never uninstall the app to update it: uninstalling erases its data.

**Building on a computer instead:** open the folder in Android Studio (Ladybug or newer) and run
the `app` configuration, or run `./gradlew :app:assembleRelease` with JDK 17 and the Android SDK.

## 2. First steps in the app

1. **Check your fee rates.** *Dashboard ▸ ⚙ Settings ▸ Fee schedules.* The app starts with the CSE's
   published equity rates (brokerage 0.64 %, CSE 0.084 %, SEC 0.072 %, CDS 0.024 %, share
   transaction levy 0.30 %; 1.12 % in total, with the lower tier above Rs. 100 million). Compare
   them with a contract note from your broker and edit if yours differ.
2. **Load your holdings.** *Settings ▸ Import ▸ Choose file* and pick your broker's
   *Portfolio Gain/Loss Statement* PDF. The app shows what it read, then adds each holding as an
   opening balance at the statement's cost and stores the statement's prices with its date.
3. **Record your deposits** (+ ▸ *Deposit, withdrawal or dividend*) if you want the cash balance to
   match your broker account. Opening balances do not touch cash.
4. **Turn on App lock** in Settings if you want fingerprint / screen-lock protection.
5. From then on, record each trade with the **+** button, or import them from Excel / CSV.

## 3. Using the app

**Dashboard.** Net equity (market value + cash − margin loan), investment cost, realized and
unrealized profit, charges paid, cash, margin loan, buying power, overall return, today / month /
year-to-date results, best and worst stock, allocation and monthly charts. Warnings appear here
when a margin limit is crossed or a record does not reconcile (for example a sale larger than the
holding).

**Portfolio.** One page per company: shares bought, sold and held, average cost, acquisition cost,
market price and value, unrealized and realized profit, charges and percentage return, plus that
company's transaction statement (PDF / Excel). *Update* sets the price by hand.

**Recording a trade (+).** Enter date, symbol, quantity and price. Charges are calculated from the
fee schedule in force on the trade date and shown as
*Gross + Brokerage + Exchange charges + Other charges = Total purchase cost* (or, for a sale,
*Gross − … = Net selling proceeds*). Switch **Auto** off to type the exact amounts from the
contract note. For a purchase choose **Cash** or **Margin**; for margin, pick the account and the
amount financed. For a sale you see the cost of the shares sold and the realized profit before you
save, and you can apply part of the proceeds to the margin loan.

**Bonus, rights, split, opening balance.** Also under **+**. Bonus shares come in at no cost;
splits and consolidations keep total cost and adjust the average; rights are bought at the rights price.

**Sell price simulator** (calculator icon). Pick a holding, a quantity and a price to see gross
value, each charge, net proceeds, profit and return, and, for margin-funded shares, the loan
repayment, the net cash you receive and the return on your own capital. It shows the break-even
price after all charges (exact, and rounded up to the next LKR 0.10 step) and a table of scenario
prices to which you can add your own. Everything here is labelled an estimate; nothing is recorded
until you press *Record this as an actual sale*.

**Margin.** Add a facility (broker, account, limit, interest rate and method, dates, warning and
margin-call levels). The app then tracks principal, accrued interest, charges, repayments,
collateral value, utilisation, loan-to-collateral and available buying power, and shows the
ten-line margin profit calculation for each margin-funded holding. *Add loan entry* records cash
drawdowns, repayments, financing charges and rate changes.

**Transactions.** A broker-style ledger with running cash and loan balances. Filter by company,
dates, buy / sell, cash / margin and profitable / loss-making. Tap an entry to edit or delete it.
Deleted entries go to the **Recycle bin** and every change is listed in **Audit history**.

**Reports.** Charts (allocation, realized and unrealized by stock, monthly and annual results,
cumulative result, cash versus margin, where charges went), all report tables, and exports: a
complete Excel workbook, the monthly profit / loss PDF, the annual investment summary and
company statements.

**Markets tab** (second button at the bottom). A dark, TradingView-style market screen with five
sections: *Sri Lanka* (ASPI and S&P SL20, advancing/declining stocks, top 10 gainers, losers, most
active and highest turnover, and every traded CSE security), *World top 50* (ranked by market
capitalisation in US dollars from a list of 66 of the world's largest companies, re-ranked on each
refresh), *Commodities* (gold, silver, platinum, palladium, Brent, WTI, natural gas, with gold in
rupees per 8 g), *Crypto*, and *Forex* (12 major currencies against the rupee). Tap anything for a
candlestick chart with 1D–5Y ranges, 20/50-day moving averages and a crosshair, plus P/E, EPS,
P/BV, dividend yield and 52-week range where the source has them. World prices come from Yahoo
Finance, an unofficial and delayed source that can change or stop without notice; exchange rates
there are market indicative, not CBSL rates. Margin moved to the bank icon at the top of the Dashboard.

**CSE market and company research** (search icon on the Dashboard, or the chips under it).
*Market watch* and *Directory* list the securities the CSE website publishes, with search and
sorting; tap one for its company page. *Announcements* and *Dividends* show the CSE's recent
disclosures and open the original documents. Each company page has Market data, Financials,
Ratios, Announcements and Reports tabs and shows your own holding alongside. The CSE publishes
financial statements only as PDF reports, so under *Financials ▸ Add reporting period* you type
the key figures from the report (annual or quarterly); the app then calculates every ratio,
growth rate and the five-company comparison, and lists the formula for each. Figures you enter
are included in backup files. Prices here are labelled Delayed or End of day, never live.

**Watchlist** (star icon). Target buy and sell prices, expected upside and notes. You are notified
when a price you enter, import or fetch crosses a target.

**Importing Excel / CSV.** The app finds the header row, matches columns by name (date, symbol,
buy / sell, quantity, price, and any fee columns) and lets you correct the matching. It previews
what will be imported, skips duplicates (same date, symbol, type, quantity and price) and lists
rows it could not read. Old `.xls` files must first be saved as `.xlsx`. *Blank template* gives you
the expected layout; the app's own transaction export uses the same layout.

**Backups.** *Settings ▸ Backup.* A daily backup is written automatically when you open the app.
Choose a **backup folder** to have a copy placed there too (pick a folder your phone syncs to
Google Drive if you want an off-phone copy), set an optional **password** to encrypt backup files,
and use *Save backup file* / *Restore* to move data to a new phone.

## 4. How the figures are calculated

See [docs/CALCULATIONS.md](docs/CALCULATIONS.md) for the method and worked examples, and
[docs/DATABASE.md](docs/DATABASE.md) for the database structure.

## 5. What you should know

- **Market prices are never invented.** By default the app reads prices from the Colombo Stock
  Exchange website (cse.lk) when it opens and when you tap refresh. That source is unofficial and
  undocumented, so it can stop working if the CSE changes its site; switch it off under
  *Settings ▸ Market prices* if you prefer. Prices can also be typed in, imported from a broker
  statement, or read from another web address you configure. Every price shows when it was updated
  and where it came from, and a holding without a price is valued at cost and flagged.
- **"Today's gain / loss"** needs a previous close, which the CSE website supplies. Prices refresh
  when you open the app or tap refresh, not tick by tick.
- **Cloud sync** is done through backup files rather than a live server: nothing leaves your phone
  unless you put a backup somewhere. There is no account to create and no service to pay for.
- **Fee rounding.** The total charge is the combined percentage of the trade value rounded to the
  cent, which reproduces the figures on a Capital TRUST statement exactly. A contract note can
  still differ by a cent on an individual component; switch Auto off to enter its exact figures.
- **Broker statement import** reads text-based PDFs in the layout of the Capital TRUST
  *Portfolio Gain/Loss Statement*. Scanned PDFs, and other brokers' layouts, need the Excel / CSV route.
- Estimates are not financial advice. The contract note from your broker is the authority.

## 6. Project layout

```
core/   Pure Kotlin, no Android: calculation engine, fee calculator, simulator, reports,
        Excel / CSV / PDF-text readers and writers, backup encryption, and the tests.
app/    Android app: Room database, repository, view model, Compose screens, PDF report writer.
docs/   Calculation method and database structure.
.github/workflows/build-apk.yml   Builds and tests on GitHub.
signing/personal.jks              The key that signs your builds. Keep the repository private.
```

## 7. Tests

`core/src/test` holds the reconciliation tests required by the specification, using sample HBS,
YORK and ACME transactions: fee breakdowns, weighted-average and FIFO cost, partial sales, bonus /
rights / split, margin interest (simple, monthly-capitalised, rate changes), partial repayments,
loan settlement, cash reconciliation, the sell simulator and break-even, the dashboard totals, and
the file importers. The GitHub build runs them before it builds the APK; run them yourself with
`./gradlew :core:test`. Add `-Pstatement=/path/to/statement.pdf` to check a real broker statement
end to end.
