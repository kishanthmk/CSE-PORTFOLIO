package lk.cseportfolio.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.time.LocalDate
import lk.cseportfolio.app.MainViewModel
import lk.cseportfolio.app.Nav
import lk.cseportfolio.app.UiState
import lk.cseportfolio.app.data.MarginAccountEntity
import lk.cseportfolio.app.data.MarginEntryEntity
import lk.cseportfolio.core.Fmt
import lk.cseportfolio.core.InterestMethod
import lk.cseportfolio.core.MarginEntryType
import lk.cseportfolio.core.MarginView
import lk.cseportfolio.core.ReportTable
import lk.cseportfolio.core.Reports
import lk.cseportfolio.core.Simulator

@Composable
fun SimulatorScreen(st: UiState, vm: MainViewModel, nav: Nav, initSymbol: String) {
    val held = st.summary.holdings
    var symbol by remember { mutableStateOf(if (held.any { it.symbol == initSymbol }) initSymbol else held.firstOrNull()?.symbol ?: "") }
    val h = held.firstOrNull { it.symbol == symbol }
    var qty by remember(symbol) { mutableStateOf(h?.position?.quantity?.toString() ?: "") }
    var price by remember(symbol) { mutableStateOf(h?.quote?.last.field()) }
    var custom by remember(symbol) { mutableStateOf(listOf<BigDecimal>()) }
    var customText by remember(symbol) { mutableStateOf("") }
    val tones = LocalTones.current
    Screen("Sell price simulator", onBack = { nav.back() }) {
        if (h == null) { EmptyState("Nothing to simulate", "The simulator works on shares you currently hold. Record a purchase or an opening balance first."); return@Screen }
        val p = h.position
        val q = (qty.toLongOrNull() ?: 0L).coerceIn(0, p.quantity)
        val pr = price.dec()
        val fees = st.fees
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Banner("Estimate only. Nothing here is recorded until you save a real sale. Charges use the fee schedule “${fees.name}” (${Fmt.rate(fees.totalPct())}).", tones.info)
            Picker("Stock", held.map { it.symbol }, symbol, { s -> s + st.name(s).let { n -> if (n.isEmpty()) "" else " – $n" } }, { symbol = it }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(qty, { qty = it }, "Quantity to sell", Modifier.weight(1f), whole = true, supporting = "of ${Fmt.qty(p.quantity)} held")
                NumField(price, { price = it }, "Selling price", Modifier.weight(1f), supporting = h.quote?.let { "last ${Fmt.money(it.last)}" } ?: "no market price")
            }
            if (pr != null && pr.signum() > 0 && q > 0) {
                val s = Simulator.simulate(p, q, pr, fees, st.result.method)
                Section("If you sell ${Fmt.qty(q)} $symbol at LKR ${Fmt.money(pr)}") {
                    KV("Current share quantity", Fmt.qty(p.quantity))
                    KV("Average purchase price", Fmt.money(p.avgCost.setScale(4, java.math.RoundingMode.HALF_UP)))
                    KVMoney("Original acquisition cost of these shares", s.costOfSold)
                    KV("Proposed selling price", Fmt.money(pr))
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    FeeSummary(false, s.gross, s.fees)
                    KV("Estimated selling charges in total", Fmt.money(s.fees.total))
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Profit / loss if sold", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Fmt.lkr(s.profit).replace("LKR ", if (s.profit.signum() > 0) "+LKR " else "LKR "), style = NumStyle.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), color = toneColor(s.profit))
                    KV("Return on cost", Fmt.pct(s.returnPct), toneColor(s.returnPct), bold = true)
                    KVMoney("Gross profit before selling charges", s.grossProfit, signed = true)
                    if (!s.isMargin) KVMoney("Net cash you receive", s.netCash, bold = true)
                }
                if (s.isMargin) Section("After margin settlement") {
                    KVMoney("1. Acquisition cost of these shares", s.costOfSold)
                    KVMoney("2. Financed by the broker", s.financed)
                    KVMoney("3. Your own capital", s.ownCapital)
                    KVMoney("4. Outstanding loan on these shares", s.financed)
                    KVMoney("5. Interest charged to date", s.interestCharged)
                    KVMoney("6. Selling proceeds after fees", s.net)
                    KVMoney("7. Loan repayment (principal + unpaid interest)", s.loanRepayment)
                    KVMoney("8. Net cash payable to you", s.netCash, bold = true)
                    KVMoney("9. Your net profit / loss after interest", s.profitAfterInterest, signed = true, bold = true)
                    KV("10. Return on your own capital", Fmt.pct(s.returnOnOwnCapitalPct), toneColor(s.returnOnOwnCapitalPct), bold = true)
                    Text("Trading profit and financing cost are kept separate: profit before interest is ${Fmt.signed(s.profit)}, interest is ${Fmt.money(s.interestCharged)}.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                Button(onClick = { nav.newTxn("SELL", symbol, q.toString(), Fmt.plain(pr)) }, modifier = Modifier.fillMaxWidth()) { Text("Record this as an actual sale…") }
            }
            if (q > 0) {
                val cost = Simulator.simulate(p, q, BigDecimal.ONE, fees, st.result.method).costOfSold
                val be = Simulator.breakEven(q, cost, fees)
                val interest = Simulator.simulate(p, q, BigDecimal.ONE, fees, st.result.method).interestCharged
                Section("Break-even selling price") {
                    if (be != null) {
                        KV("Exact break-even after all charges", Fmt.money(be.setScale(4, java.math.RoundingMode.UP)), bold = true)
                        KV("Lowest tradable price without a loss", Fmt.money(Simulator.toTick(be)), tones.gain, bold = true)
                        if (interest.signum() > 0) Simulator.breakEven(q, cost.add(interest), fees)?.let { KV("…also covering margin interest", Fmt.money(Simulator.toTick(it))) }
                        Text("CSE prices move in steps of LKR 0.10, so the tradable price is rounded up to the next step.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val prices = (Simulator.defaultScenarios(p.avgCost, h.quote?.last) + custom).distinct().sorted()
                val table = ReportTable("Scenarios", listOf("Selling price", "Net proceeds", "Net profit / loss", "Return %"), prices.map { x ->
                    val s = Simulator.simulate(p, q, x, fees, st.result.method)
                    listOf(Reports.price(x), Reports.m(s.net), Reports.pl(if (s.isMargin) s.profitAfterInterest else s.profit), Reports.pc(s.returnPct))
                })
                Section("Selling price scenarios") {
                    TableView(table)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        NumField(customText, { customText = it }, "Add your own price", Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Button(enabled = (customText.dec()?.signum() ?: 0) > 0, onClick = { custom = custom + customText.dec()!!; customText = "" }) { Text("Add") }
                    }
                    if (custom.isNotEmpty()) TextButton(onClick = { custom = emptyList() }) { Text("Remove my prices") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------- margin

@Composable
fun MarginScreen(st: UiState, vm: MainViewModel, nav: Nav) {
    var editAccount by remember { mutableStateOf<MarginAccountEntity?>(null) }
    var newAccount by remember { mutableStateOf(false) }
    var editEntry by remember { mutableStateOf<MarginEntryEntity?>(null) }
    val tones = LocalTones.current
    Screen("Margin", onBack = { nav.back() }, actions = { IconButton(onClick = { newAccount = true }) { Icon(Icons.Filled.Add, contentDescription = "Add margin account") } }) {
        if (st.accounts.isEmpty()) {
            EmptyState("No margin facility", "If you trade with a margin loan, add the facility here. Purchases can then be marked as margin-funded, and the app tracks the loan, interest and collateral. Cash-only investors can ignore this tab.",
                "Add margin account") { newAccount = true }
            return@Screen
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            st.summary.margin.forEach { v -> MarginAccountCard(v, st, onEdit = { editAccount = st.accounts.firstOrNull { it.id == v.state.account.id } },
                onEntry = { editEntry = MarginEntryEntity(accountId = v.state.account.id, date = LocalDate.now(), type = it) }) }

            val funded = st.summary.holdings.filter { it.position.financed.signum() > 0 || it.position.interestCharged.signum() > 0 }
            if (funded.isNotEmpty()) Section("Margin-funded holdings at the latest price") {
                val table = ReportTable("Margin profit and loss", listOf("Symbol", "Acquisition cost", "Financed", "Own capital", "Loan outstanding", "Interest to date",
                    "Net proceeds", "Loan repayment", "Net cash to you", "Net profit / loss", "Return on own capital"),
                    funded.map { h ->
                        val s = Simulator.simulate(h.position, h.position.quantity, h.quote?.last ?: h.position.avgCost, st.fees, st.result.method)
                        listOf(Reports.t(h.symbol), Reports.m(s.costOfSold), Reports.m(s.financed), Reports.m(s.ownCapital), Reports.m(s.financed), Reports.m(s.interestCharged),
                            Reports.m(s.net), Reports.m(s.loanRepayment), Reports.m(s.netCash), Reports.pl(s.profitAfterInterest), Reports.pc(s.returnOnOwnCapitalPct))
                    })
                TableView(table)
                Text("Estimated as if each holding were sold in full at its latest price (or at cost when no price is recorded). Open the simulator for other prices or part sales.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }

            Section("Loan entries") {
                val entries = st.marginEntries.sortedByDescending { it.date }
                if (entries.isEmpty()) Text("Drawdowns, repayments, charges and rate changes you record appear here. Margin purchases and sale repayments are listed under Transactions.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                entries.forEach { e ->
                    Row(Modifier.fillMaxWidth().clickable { editEntry = e }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.type.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(e.date.format(Reports.DATE) + "  ·  " + (st.accounts.firstOrNull { it.id == e.accountId }?.broker ?: "deleted account") + (if (e.note.isNotEmpty()) "  ·  ${e.note}" else ""),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (e.type == MarginEntryType.RATE_CHANGE) Fmt.rate(e.rate) else Fmt.money(e.amount), style = NumStyle,
                            color = if (e.type == MarginEntryType.REPAYMENT) tones.gain else MaterialTheme.colorScheme.onSurface)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Spacer(Modifier.height(88.dp))
        }
    }
    if (newAccount) MarginAccountDialog(null, { newAccount = false }, { vm.run("Margin account saved.") { saveAccount(it) } }, null)
    editAccount?.let { a -> MarginAccountDialog(a, { editAccount = null }, { vm.run("Margin account saved.") { saveAccount(it) } },
        if (st.result.margin.firstOrNull { it.account.id == a.id }?.outstanding?.signum() == 0) ({ vm.run("Margin account deleted.") { deleteAccount(a) } }) else null) }
    editEntry?.let { e -> MarginEntryDialog(e, { editEntry = null }, { vm.run("Entry saved.") { saveMarginEntry(it) } },
        if (e.id != 0L) ({ vm.run("Moved to the recycle bin.") { deleteMarginEntry(e.id) } }) else null) }
}

@Composable
private fun MarginAccountCard(v: MarginView, st: UiState, onEdit: () -> Unit, onEntry: (MarginEntryType) -> Unit) {
    val s = v.state
    val a = s.account
    val tones = LocalTones.current
    var menu by remember { mutableStateOf(false) }
    Section(a.broker + (if (a.accountNo.isNotEmpty()) "  ·  ${a.accountNo}" else ""), trailing = { TextButton(onClick = onEdit) { Text("Edit") } }) {
        if (v.marginCall) Banner("Margin call level reached: the loan is ${Fmt.pct(v.ltvPct).removePrefix("+")} of collateral, above your ${Fmt.rate(a.marginCallLtvPct)} threshold.", tones.loss)
        else if (v.utilizationWarning) Banner("Exposure warning: ${Fmt.pct(s.utilizationPct).removePrefix("+")} of the facility is in use (your warning level is ${Fmt.rate(a.warnUtilizationPct)}).")
        Text("Outstanding loan", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Text(Fmt.lkr(s.outstanding), style = NumStyle.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), color = if (s.outstanding.signum() > 0) tones.loss else MaterialTheme.colorScheme.onSurface)
        val util = (s.utilizationPct?.toFloat() ?: 0f) / 100f
        LinearProgressIndicator(progress = { util.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            color = if (v.utilizationWarning) tones.loss else MaterialTheme.colorScheme.primary)
        KVMoney("Loan principal", s.principal)
        KVMoney("Interest accrued and unpaid", s.interestUnpaid)
        KVMoney("Interest charged to date", s.interestTotal)
        KVMoney("Other financing charges", s.chargesTotal)
        KVMoney("Total repaid", s.repaidTotal)
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
        KV("Interest rate", Fmt.rate(s.rate) + " p.a.")
        KV("Interest method", a.method.label)
        KV("Loan commencement", a.startDate?.format(Reports.DATE) ?: "–")
        KV("Repayment date", a.repaymentDate?.format(Reports.DATE) ?: "–")
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
        KVMoney("Margin facility limit", a.facilityLimit)
        KVMoney("Available margin facility", s.available)
        KV("Margin utilisation", Fmt.pct(s.utilizationPct).removePrefix("+"))
        KVMoney(if (a.wholePortfolioCollateral) "Collateral value (all holdings)" else "Collateral value (margin-funded stocks)", v.collateral)
        KV("Loan ÷ collateral", Fmt.pct(v.ltvPct).removePrefix("+"))
        KV("Margin call threshold", if (a.marginCallLtvPct.signum() > 0) Fmt.rate(a.marginCallLtvPct) + " of collateral" else "not set")
        KVMoney("Available margin buying power", v.buyingPower, bold = true)
        Box(Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { menu = true }) { Text("Add loan entry") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MarginEntryType.values().forEach { t -> DropdownMenuItem(text = { Text(t.label) }, onClick = { menu = false; onEntry(t) }) }
            }
        }
    }
}

@Composable
private fun MarginAccountDialog(existing: MarginAccountEntity?, onDismiss: () -> Unit, onSave: (MarginAccountEntity) -> Unit, onDelete: (() -> Unit)?) {
    var broker by remember { mutableStateOf(existing?.broker ?: "") }
    var accountNo by remember { mutableStateOf(existing?.accountNo ?: "") }
    var limit by remember { mutableStateOf(existing?.facilityLimit.field()) }
    var rate by remember { mutableStateOf(existing?.interestRate.field()) }
    var method by remember { mutableStateOf(existing?.method ?: InterestMethod.SIMPLE_DAILY_365) }
    var start by remember { mutableStateOf(existing?.startDate) }
    var due by remember { mutableStateOf(existing?.repaymentDate) }
    var warn by remember { mutableStateOf(existing?.warnUtilizationPct?.let { Fmt.plain(it) } ?: "80") }
    var call by remember { mutableStateOf(existing?.marginCallLtvPct?.let { Fmt.plain(it) } ?: "50") }
    var whole by remember { mutableStateOf(existing?.wholePortfolioCollateral ?: false) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Add margin account" else "Edit margin account") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(broker, { broker = it }, "Margin broker / provider", Modifier.fillMaxWidth())
            TextInput(accountNo, { accountNo = it }, "Margin account number", Modifier.fillMaxWidth())
            NumField(limit, { limit = it }, "Facility limit (LKR)", Modifier.fillMaxWidth())
            NumField(rate, { rate = it }, "Interest rate (% per year)", Modifier.fillMaxWidth(), supporting = if (existing != null) "To change the rate from a date, add a “rate change” entry instead." else null)
            Picker("Interest calculation method", InterestMethod.values().toList(), method, { it.label }, { method = it }, Modifier.fillMaxWidth())
            DateField(start, { start = it }, "Loan commencement date", Modifier.fillMaxWidth())
            DateField(due, { due = it }, "Repayment date", Modifier.fillMaxWidth())
            NumField(warn, { warn = it }, "Warn when utilisation exceeds (%)", Modifier.fillMaxWidth())
            NumField(call, { call = it }, "Margin call when loan exceeds (% of collateral)", Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Count all holdings as collateral", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = whole, onCheckedChange = { whole = it })
            }
            TextInput(note, { note = it }, "Note", Modifier.fillMaxWidth())
            if (onDelete != null) TextButton(onClick = { onDelete(); onDismiss() }) { Text("Delete this account", color = MaterialTheme.colorScheme.error) }
            else if (existing != null) Text("An account can be deleted once its loan balance is zero.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = { TextButton(enabled = broker.isNotBlank(), onClick = {
            onSave(MarginAccountEntity(existing?.id ?: 0, broker.trim(), accountNo.trim(), limit.decOrZero(), rate.decOrZero(), method, start, due, warn.decOrZero(), call.decOrZero(), whole, note.trim()))
            onDismiss()
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun MarginEntryDialog(e: MarginEntryEntity, onDismiss: () -> Unit, onSave: (MarginEntryEntity) -> Unit, onDelete: (() -> Unit)?) {
    var date by remember { mutableStateOf(e.date) }
    var amount by remember { mutableStateOf(e.amount.field()) }
    var rate by remember { mutableStateOf(e.rate.field()) }
    var note by remember { mutableStateOf(e.note) }
    val isRate = e.type == MarginEntryType.RATE_CHANGE
    AlertDialog(onDismissRequest = onDismiss, title = { Text(e.type.label) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (e.type) {
                MarginEntryType.DRAWDOWN -> "Cash taken from the facility (not a share purchase). It increases the loan and your cash balance."
                MarginEntryType.REPAYMENT -> "Cash paid into the facility. Unpaid interest is settled first, then principal. It reduces your cash balance."
                MarginEntryType.CHARGE -> "A fee debited to the margin account, such as a facility or documentation charge. It is added to the loan."
                MarginEntryType.RATE_CHANGE -> "The new annual rate applies from this date. Earlier interest is unaffected."
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DateField(date, { date = it }, "Date", Modifier.fillMaxWidth())
            if (isRate) NumField(rate, { rate = it }, "New interest rate (% per year)", Modifier.fillMaxWidth())
            else NumField(amount, { amount = it }, "Amount (LKR)", Modifier.fillMaxWidth())
            TextInput(note, { note = it }, "Note", Modifier.fillMaxWidth())
            if (onDelete != null) TextButton(onClick = { onDelete(); onDismiss() }) { Text("Delete this entry", color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = if (isRate) rate.dec() != null else (amount.dec()?.signum() ?: 0) > 0, onClick = {
            onSave(e.copy(date = date, amount = if (isRate) BigDecimal.ZERO else amount.decOrZero(), rate = if (isRate) rate.decOrZero() else BigDecimal.ZERO, note = note.trim()))
            onDismiss()
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
