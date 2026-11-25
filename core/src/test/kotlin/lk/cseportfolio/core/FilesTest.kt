package lk.cseportfolio.core

import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesTest {
    private val fees = FeeSchedule.cseDefault()
    private fun eq(expected: String, actual: BigDecimal?) { if (BigDecimal(expected).compareTo(actual!!) != 0) throw AssertionError("expected $expected but was ${actual.toPlainString()}") }

    /** Figures from a real Capital TRUST "Portfolio Gain/Loss Statement" (personal details removed). */
    private val statement = """
        Capital TRUST Securities (Private) Limited
        Portfolio Gain/Loss Statement as at 10/11/2026
        Client Name  :  SAMPLE CLIENT  Client A/C No  :  ABC-12345-LI/00
        Security Code  Quantity  Cost per share  Cost  VWA Price  Projected sales proceeds  Gain/Loss
        ACL.N0000  0  98.90  0.00  104.50  0.00  0.00
        ASIY.N0000  115,000  15.12  1,738,800.00  13.50  1,535,112.00  -203,688.00
        CHL.N0000  0  0.00  0.00  65.00  0.00  0.00
        HBS.N0000  0  21.40  0.00  22.20  0.00  0.00
        JAT.N0000  68,600  40.24  2,760,464.00  40.00  2,713,267.20  -47,196.80
        JXG.N0000  235,000  13.36  3,139,600.00  10.30  2,393,390.40  -746,209.60
        MEL.N0000  24,381  7.69  187,489.89  6.00  144,647.60  -42,842.29
        UML.N0000  77,500  40.51  3,139,525.00  23.40  1,793,188.80  -1,346,336.20
        YORK.N0000  0  20.22  0.00  8.30  0.00  0.00
        Total:  10,965,878.89  8,579,606.00  -2,386,272.89
    """.trimIndent().lines()

    private fun check(st: BrokerStatement) {
        assertEquals(LocalDate.of(2026, 11, 10), st.asAt)
        assertEquals(5, st.holdings.size)
        eq("10965878.89", st.totalCost); eq("1.12", st.impliedSellChargePct)
        val txns = StatementParser.toOpeningTxns(st)
        val r = PortfolioEngine.compute(EngineInput(txns, asOf = st.asAt!!))
        val quotes = st.lines.associate { it.symbol to Quote(it.symbol, it.price) }
        val s = Valuation.summarize(r, quotes, fees)
        // the app's own figures must reproduce the broker's totals to the cent
        eq("10965878.89", s.costOfHoldings)
        eq("8579606.00", s.holdings.map { it.projectedNet }.sum())
        eq("-2386272.89", s.netUnrealized)
        for (l in st.holdings) {
            val h = s.holdings.first { it.symbol == l.symbol }
            eq(l.cost.toPlainString(), h.position.costBasis); eq(l.projectedProceeds.toPlainString(), h.projectedNet); eq(l.gainLoss.toPlainString(), h.netUnrealized)
        }
    }

    @Test fun brokerStatementReconcilesToTheCent() {
        assertTrue(StatementParser.looksLikeGainLoss(statement))
        val st = StatementParser.parse(statement)
        assertEquals("ABC-12345-LI/00", st.account); assertEquals(9, st.lines.size)
        check(st)
    }

    /** Runs only when a real statement PDF is supplied: -Dstatement.pdf=/path/file.pdf */
    @Test fun realPdfStatement() {
        val path = System.getProperty("statement.pdf") ?: return
        val lines = PdfText.extractLines(File(path).readBytes())
        assertTrue(StatementParser.looksLikeGainLoss(lines))
        val st = StatementParser.parse(lines)
        assertEquals(16, st.lines.size)
        assertTrue(st.broker.contains("Capital TRUST"))
        check(st)
    }

    @Test fun csvImportWithFeesDuplicatesAndErrors() {
        val csv = "Statement of account\n\nTrade Date,Security,Buy/Sell,Qty,Price,Brokerage,CSE Fee,SEC Cess,CDS Fee,STL,Remarks\n" +
            "05/01/2026,HBS.N0000,Buy,\"1,000\",20.00,128.00,16.80,14.40,4.80,60.00,CN 1001\n" +
            "15-Mar-2026,HBS.N0000,S,600,25,96,12.6,10.8,3.6,45,\n" +
            "05/01/2026,HBS.N0000,Buy,1000,20.00,128.00,16.80,14.40,4.80,60.00,repeat\n" +
            "bad date,YORK.N0000,Buy,10,5,,,,,,\n" +
            "2026-04-01,YORK.N0000,Buy,100,8.30,,,,,,\n"
        val rows = Csv.parse(csv)
        val h = TxnImport.detectHeader(rows)
        assertEquals(1, h)
        val map = TxnImport.autoMap(rows[h])
        assertEquals(0, map[ImportField.DATE]); assertEquals(1, map[ImportField.SYMBOL]); assertEquals(9, map[ImportField.TAX]); assertEquals(10, map[ImportField.NOTE])
        val pv = TxnImport.parse(rows, h, map, listOf(fees), emptySet())
        assertEquals(3, pv.valid.size); assertEquals(1, pv.duplicates); assertEquals(1, pv.errors.size)
        val t = pv.valid[0].txn!!
        assertEquals(LocalDate.of(2026, 1, 5), t.date); eq("224.00", t.charges); assertEquals("CN 1001", t.note)
        assertEquals(TxnType.SELL, pv.valid[1].txn!!.type); assertEquals(LocalDate.of(2026, 3, 15), pv.valid[1].txn!!.date)
        assertTrue(pv.valid[2].feesEstimated); eq("9.30", pv.valid[2].txn!!.charges)
        // importing the same file again: everything is a duplicate
        val again = TxnImport.parse(rows, h, map, listOf(fees), pv.valid.map { TxnImport.key(it.txn!!) }.toSet())
        assertEquals(0, again.valid.size)
        // a lone net-amount column is split by the schedule so the total still matches the statement
        val rows2 = Csv.parse("Date;Symbol;Type;Quantity;Price;Net Amount\n05/01/2026;HBS;BUY;1000;20;20230.00\n")
        val t2 = TxnImport.parse(rows2, 0, TxnImport.autoMap(rows2[0]), listOf(fees), emptySet()).valid.single().txn!!
        eq("230.00", t2.charges); eq("61.61", t2.tax)
    }

    @Test fun excelRoundTrip() {
        val txns = listOf(Txn(1, LocalDate.of(2026, 1, 5), "HBS", TxnType.BUY, 1000, bd("20.00"), bd("128"), bd("16.8"), bd("14.4"), bd("4.8"), bd("60"), note = "A & B <ok>"),
            Txn(2, LocalDate.of(2026, 3, 15), "HBS", TxnType.SELL, 600, bd("25.5"), bd("96")))
        val bytes = Xlsx.write(listOf(TxnImport.exportRows(txns, mapOf("HBS" to "hSenid Business Solutions")), Reports.charges(PortfolioEngine.compute(EngineInput(txns)))))
        val rows = Xlsx.read(ByteArrayInputStream(bytes))
        val h = TxnImport.detectHeader(rows)
        assertEquals(TxnImport.TEMPLATE_HEADERS, rows[h])
        val pv = TxnImport.parse(rows, h, TxnImport.autoMap(rows[h]), listOf(fees), emptySet())
        assertEquals(2, pv.valid.size); assertEquals(0, pv.errors.size)
        val a = pv.valid[0].txn!!
        assertEquals(txns[0].copy(id = a.id), a.copy(price = a.price.setScale(2)))
        eq("25.5", pv.valid[1].txn!!.price); assertEquals("hSenid Business Solutions", pv.valid[0].company)
        assertEquals(LocalDate.of(2026, 1, 5), Dates.parse("46027"))   // Excel serial date
    }

    @Test fun jsonAndEncryptedBackup() {
        val data = mapOf("v" to 1L, "name" to "Line1\n\"quoted\" \\ ü", "list" to listOf(1L, BigDecimal("2.50"), null, true), "nested" to mapOf("a" to emptyList<Any>()))
        val text = Json.write(data)
        assertEquals(data, Json.parse(text))
        val enc = BackupCrypto.encrypt(text.toByteArray(), "pass word".toCharArray())
        assertTrue(BackupCrypto.isEncrypted(enc)); assertTrue(!BackupCrypto.isEncrypted(text.toByteArray()))
        assertEquals(text, String(BackupCrypto.decrypt(enc, "pass word".toCharArray())))
        var failed = false
        try { BackupCrypto.decrypt(enc, "wrong".toCharArray()) } catch (e: Exception) { failed = true }
        assertTrue(failed)
    }

    @Test fun reportsBuild() {
        val t = listOf(Txn(1, LocalDate.of(2026, 1, 5), "HBS", TxnType.BUY, 1000, bd("20.00"), bd("128"), tax = bd("60")),
            Txn(2, LocalDate.of(2026, 3, 15), "HBS", TxnType.SELL, 600, bd("25"), bd("96")))
        val r = PortfolioEngine.compute(EngineInput(t, asOf = LocalDate.of(2026, 3, 31)))
        val s = Valuation.summarize(r, mapOf("HBS" to Quote("HBS", bd("22"))), fees)
        val all = listOf(Reports.holdings(s), Reports.companyAnalysis(r, emptyMap(), fees), Reports.monthly(r), Reports.annual(r), Reports.charges(r),
            Reports.cashVsMargin(r), Reports.trades(r), Reports.allocation(s), Reports.ledger(r.ledger), Reports.companyStatement(r, "HBS")) +
            Reports.monthlyReport(r, java.time.YearMonth.of(2026, 3)) + Reports.annualReport(r, s, 2026)
        for (tb in all) { for (row in tb.rows) assertEquals(tb.title, tb.headers.size, row.size); tb.total?.let { assertEquals(tb.title, tb.headers.size, it.size) } }
        assertTrue(Xlsx.write(all).size > 2000)
        assertTrue(Csv.write(Csv.fromTable(all[0])).contains("HBS"))
    }

    @Test fun cseWebsiteFeedParsing() {
        // shapes as published by the CSE website; values are illustrative
        val summary = """{"reqTradeSummery":[
            {"id":1,"name":"HSENID BUSINESS SOLUTIONS PLC","symbol":"HBS.N0000","price":22.2,"previousClose":22.0,"high":22.5,"low":21.9,"change":0.2,"percentageChange":0.909090,"sharevolume":15300},
            {"id":2,"name":"YORK ARCADE HOLDINGS PLC","symbol":"york.n0000","price":8.3,"previousClose":8.4,"change":-0.1,"percentageChange":-1.19},
            {"id":3,"name":"NO PRICE PLC","symbol":"NONE.N0000","price":0.0},
            {"id":4,"name":"no symbol"}]}"""
        val q = CseFeed.parseTradeSummary(summary, 1234L)
        assertEquals(setOf("HBS.N0000", "YORK.N0000"), q.keys)
        val h = q["HBS.N0000"]!!
        eq("22.2", h.last); eq("22.0", h.prevClose); eq("0.2", h.change); eq("0.91", h.changePct)
        assertEquals(15300L, h.volume); assertEquals(1234L, h.updatedAtMillis); assertEquals("CSE website", h.source)
        eq("-0.1", q["YORK.N0000"]!!.change)
        val info = """{"reqSymbolInfo":{"symbol":"ACME.N0000","name":"ACME PRINTING & PACKAGING PLC","lastTradedPrice":6.1,"previousClose":6.0,"p12HiPrice":9.8,"p12LowPrice":4.2,"tdyShareVolume":100},"reqLogo":null}"""
        val a = CseFeed.parseCompanyInfo(info, "ACME.N0000", 5L)!!
        eq("6.1", a.last); eq("0.1", a.change); eq("1.67", a.changePct); eq("9.8", a.high52); eq("4.2", a.low52)
        assertEquals("ACME PRINTING & PACKAGING PLC", CseFeed.parseCompanyName(info))
        // nothing is invented when the reply is empty or has no price
        assertTrue(CseFeed.parseTradeSummary("", 1L).isEmpty()); assertTrue(CseFeed.parseTradeSummary("{}", 1L).isEmpty())
        assertEquals(null, CseFeed.parseCompanyInfo("{}", "X", 1L))
    }

    @Test fun marketFeedAndDirectory() {
        val summary = """{"reqTradeSummery":[{"id":7,"name":"JAT HOLDINGS PLC","symbol":"JAT.N0000","price":40.0,"previousClose":39.5,"high":40.5,"low":39.0,"change":0.5,"percentageChange":1.2658,"sharevolume":1200,"turnover":48000.0,"marketCap":20400000000},
            {"id":8,"name":"X PLC","symbol":"XX.X0000","price":5.0,"previousClose":5.0}]}"""
        val fresh = MarketFeed.parseSecurities(summary, 99L)
        assertEquals(2, fresh.size); eq("1.27", fresh[0].changePct); assertEquals("Ordinary voting", fresh[0].securityClass); assertEquals("Non-voting", fresh[1].securityClass)
        eq("0", fresh[1].change)
        val dir = MarketFeed.parseDirectory("""[{"symbol":"JAT.N0000","name":"JAT HOLDINGS PLC"},{"symbol":"NEW.N0000","name":"NEW CO PLC"},{"name":"junk"}]""")
        val merged = MarketFeed.merge(MarketFeed.merge(emptyList(), fresh), dir)
        assertEquals(listOf("JAT.N0000", "NEW.N0000", "XX.X0000"), merged.map { it.symbol })
        eq("40.0", merged[0].last)                                   // a name-only row never wipes a stored price
        assertEquals(null, merged[1].last)                           // and never invents one
        assertEquals(merged, merged.map { Security.fromMap(Json.parse(Json.write(it.toMap())) as Map<*, *>)!! })
        val ann = MarketFeed.parseAnnouncements("""{"approvedAnnouncements":[{"company":"JAT HOLDINGS PLC","symbol":"JAT.N0000","announcementCategory":"Dividend Announcement","createdDate":1794300000000,"filePath":"cmt/upload/x.pdf"},
            {"company":"X PLC","title":"Interim Financial Statements","createdDate":"10 NOV 2026","path":"https://cdn.cse.lk/y.pdf"}],"other":5}""")
        assertEquals(2, ann.size); assertTrue(ann[0].isDividend); assertEquals("https://cdn.cse.lk/cmt/upload/x.pdf", ann[0].url)
        assertTrue(ann[1].isFinancial); assertEquals("10 NOV 2026", ann[1].dateText)
        assertTrue(MarketFeed.parseAnnouncements("not json").isEmpty())
        assertEquals("End of day", MarketFeed.priceStatus(java.time.ZonedDateTime.of(2026, 11, 15, 11, 0, 0, 0, java.time.ZoneId.of("Asia/Colombo")).toInstant().toEpochMilli())) // Sunday
        assertEquals("Delayed", MarketFeed.priceStatus(java.time.ZonedDateTime.of(2026, 11, 16, 11, 0, 0, 0, java.time.ZoneId.of("Asia/Colombo")).toInstant().toEpochMilli()))    // Monday 11:00
        assertTrue(MarketFeed.flatten("""{"reqSymbolInfo":{"id":3,"symbol":"JAT.N0000","lastTradedPrice":40.0},"reqLogo":{"path":"a.png"}}""").any { it.first == "lastTradedPrice" && it.second == "40.0" })
    }

    @Test fun financialRatios() {
        // figures in LKR '000 as a report would state them; worked by hand below
        fun fy(year: Int, rev: String, np: String, eps: String) = FinPeriod("ACME.N0000", year, 0, unit = 1000, source = "Annual report", figures = mapOf(
            Fig.REVENUE to bd(rev), Fig.COST_OF_SALES to bd("6000"), Fig.OPERATING_PROFIT to bd("1500"), Fig.DEPRECIATION to bd("300"), Fig.FINANCE_COSTS to bd("250"),
            Fig.NET_PROFIT to bd(np), Fig.EPS to bd(eps), Fig.DPS to bd("1.50"), Fig.SHARES to bd("200000"), Fig.TOTAL_ASSETS to bd("12000"), Fig.CURRENT_ASSETS to bd("5000"),
            Fig.INVENTORY to bd("2000"), Fig.CASH to bd("800"), Fig.CURRENT_LIABILITIES to bd("2500"), Fig.TOTAL_DEBT to bd("3000"), Fig.EQUITY to bd("6000"), Fig.OCF to bd("1400"), Fig.CAPEX to bd("600")))
        val p25 = fy(2025, "8000", "800", "4.00"); val p26 = fy(2026, "10000", "1000", "5.00")
        val all = listOf(p26, p25)
        val r = Ratios.compute(p26, Ratios.prior(p26, all), bd("40")).associateBy { it.name }
        fun v(n: String) = r[n]!!.value
        eq("16.67", v("Return on equity (ROE)"))          // 1,000 / 6,000
        eq("8.33", v("Return on assets (ROA)"))           // 1,000 / 12,000
        eq("15.79", v("Return on capital employed (ROCE)")) // 1,500 / (12,000 - 2,500)
        eq("40.00", v("Gross profit margin"))             // (10,000 - 6,000) / 10,000, gross profit derived
        eq("10.00", v("Net profit margin")); eq("8.00", v("Price / earnings (P/E)"))
        eq("1.33", v("Price / book value (P/BV)"))        // 40 / (6,000,000 / 200,000 = 30)
        eq("5.67", v("EV / EBITDA"))                      // (8,000,000 + 3,000,000 - 800,000) / 1,800,000
        eq("0.50", v("Debt / equity")); eq("6.00", v("Interest coverage")); eq("2.00", v("Current ratio")); eq("1.20", v("Quick ratio"))
        eq("25.00", v("Revenue growth")); eq("25.00", v("EPS growth")); eq("3.75", v("Dividend yield")); eq("30.00", v("Dividend payout ratio"))
        eq("800000.00", v("Free cash flow")); eq("0.83", v("Asset turnover")); eq("3.00", v("Inventory turnover"))
        assertEquals(null, v("Net interest margin"))      // not entered, so not shown as zero
        // a quarter gets no price-based ratios, and nothing is computed from missing inputs
        val q = FinPeriod("ACME.N0000", 2026, 1, figures = mapOf(Fig.REVENUE to bd("100"), Fig.EPS to bd("1")))
        val rq = Ratios.compute(q, null, bd("40")).associateBy { it.name }
        assertEquals(null, rq["Price / earnings (P/E)"]!!.value); assertEquals(null, rq["Return on equity (ROE)"]!!.value)
        val st = Ratios.statementTable("ACME.N0000", all, true)
        assertEquals(listOf("Figure (LKR)", "FY2025", "FY2026", "Change on year"), st.headers); for (row in st.rows) assertEquals(4, row.size)
        eq("10000000", st.rows[0][2].num)                 // revenue shown in rupees
        val rt = Ratios.ratioTable("ACME.N0000", all, true, bd("40")); for (row in rt.rows) assertEquals(4, row.size)
        assertTrue(rt.rows.none { it[0].text == "Net interest margin" })
        val cmp = Ratios.comparisonTable(listOf("ACME.N0000", "HBS.N0000"), all, mapOf("ACME.N0000" to bd("40")), emptyMap())
        for (row in cmp.rows) assertEquals(3, row.size); assertEquals("not entered", cmp.rows[0][2].text)
        assertEquals(p26, FinPeriod.fromMap(Json.parse(Json.write(p26.toMap())) as Map<*, *>))
    }

    @Test fun globalQuotesRankingAndCharts() {
        val quotes = """{"quoteResponse":{"result":[
          {"symbol":"NVDA","longName":"NVIDIA Corporation","regularMarketPrice":151.57,"regularMarketChange":-3.2,"regularMarketChangePercent":-2.07,"regularMarketPreviousClose":154.77,"marketCap":3.7e12,"currency":"USD","fullExchangeName":"NasdaqGS","trailingPE":40.1,"epsTrailingTwelveMonths":3.78,"priceToBook":30.2,"trailingAnnualDividendYield":0.0003,"regularMarketTime":1795000000},
          {"symbol":"005930.KS","shortName":"SamsungElec","regularMarketPrice":70000,"regularMarketPreviousClose":69000,"marketCap":4.2e14,"currency":"KRW"},
          {"symbol":"AZN","regularMarketPrice":80,"marketCap":2.5e11,"currency":"USD"},
          {"symbol":"KRW=X","regularMarketPrice":1400.0},
          {"symbol":"LKR=X","regularMarketPrice":300.0,"regularMarketPreviousClose":299.0},
          {"symbol":"EUR=X","regularMarketPrice":0.9,"regularMarketPreviousClose":0.9},
          {"symbol":"NOPRICE"}]}}"""
        val q = YahooFeed.parseQuotes(quotes, 5L)
        assertEquals(6, q.size)
        assertEquals(3.78, q["NVDA"]!!.eps!!, 1e-9); assertEquals(0.03, q["NVDA"]!!.divYieldPct!!, 1e-9); assertEquals(1795000000000L, q["NVDA"]!!.time)
        assertEquals(1.449, q["005930.KS"]!!.changePct!!, 0.001)
        val top = YahooFeed.rankTop(q)
        assertEquals(listOf("NVDA", "005930.KS", "AZN"), top.map { it.instrument.symbol })   // Samsung: 420T KRW / 1400 = 300B USD
        assertEquals(3.0e11, top[1].marketCapUsd!!, 1.0); assertEquals(1, top[0].rank)
        assertEquals(300.0, YahooFeed.lkrRate("USD", q)!!.price!!, 1e-9)
        val eur = YahooFeed.lkrRate("EUR", q)!!                                            // 300 / 0.9
        assertEquals(333.333, eur.price!!, 0.001); assertEquals(1.111, eur.change!!, 0.001)
        assertEquals(null, YahooFeed.lkrRate("JPY", q))                                     // no leg: nothing invented
        assertEquals(2.0, YahooFeed.toUsd(200.0, "GBp", mapOf("GBP=X" to GQuote("GBP=X", price = 1.0)))!!, 1e-9)

        val chart = """{"chart":{"result":[{"meta":{"currency":"USD","symbol":"GC=F","exchangeName":"CMX","regularMarketPrice":2650.5,"chartPreviousClose":2640.0,"regularMarketTime":1795000000,"fiftyTwoWeekHigh":2800.0},
            "timestamp":[1794900000,1794986400,1795072800],"indicators":{"quote":[{"open":[2600,2610,null],"high":[2620,2630,2660],"low":[2590,2600,2640],"close":[2610,2640,2650.5],"volume":[100,200,null]}]}}],"error":null}}"""
        val c = YahooFeed.parseChart(chart, 9L)
        assertEquals(3, c.candles.size); assertEquals(2650.5, c.candles[2].o, 1e-9)        // missing open falls back to close
        assertEquals(10.5, c.quote!!.change!!, 1e-9); assertEquals(2800.0, c.quote!!.high52!!, 1e-9)
        assertTrue(YahooFeed.parseChart("{}", 1L).candles.isEmpty())
        assertEquals(q["NVDA"], GQuote.fromMap(Json.parse(Json.write(q["NVDA"]!!.toMap())) as Map<*, *>))
        assertEquals(66, GlobalCatalog.stocks.size); assertEquals(GlobalCatalog.stocks.size, GlobalCatalog.stocks.map { it.symbol }.toSet().size)
        assertEquals("33.00", NumFmt.price(33.0)); assertEquals("1.20T", NumFmt.big(1.2e12)); assertEquals("+1.50%", NumFmt.pct(1.5))
    }

    @Test fun cseChartsAndIndex() {
        val daily = """{"chartData":[{"tradeDate":"2026-11-20","high":41,"low":39,"close":40.5,"volume":1200},{"tradeDate":"2026-11-19","high":40,"low":38,"close":39,"volume":900},{"tradeDate":"bad","close":1}]}"""
        val d = CseChart.parse(daily)
        assertEquals(2, d.size); assertEquals(39.0, d[0].c, 1e-9); assertEquals(41.0, d[1].h, 1e-9); assertEquals(1200L, d[1].v)
        val intraday = """{"chartData":[{"t":1795050000000,"p":40.1,"q":100},{"t":1795050600000,"p":40.3,"q":50}]}"""
        assertEquals(2, CseChart.parse(intraday).size)
        assertEquals("297", CseChart.stockId("""{"reqSymbolInfo":{"id":297,"symbol":"JKH.N0000"}}"""))
        val idx = CseChart.parseIndex("""{"value":21833.77,"change":44.72,"percentage":0.205}""")!!
        assertEquals(21833.77, idx.first, 1e-9); assertEquals(0.205, idx.third!!, 1e-9)
        val s = sma(d, 2); assertEquals(null, s[0]); assertEquals(39.75, s[1]!!, 1e-9)
    }
}
