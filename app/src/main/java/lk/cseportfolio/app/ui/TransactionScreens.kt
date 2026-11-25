package lk.cseportfolio.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.time.LocalDate
import lk.cseportfolio.app.MainViewModel
import lk.cseportfolio.app.Nav
import lk.cseportfolio.app.UiState
import lk.cseportfolio.app.data.CashEntity
import lk.cseportfolio.app.data.MarginAccountEntity
import lk.cseportfolio.app.data.TxnEntity
import lk.cseportfolio.app.export.PdfReport
import lk.cseportfolio.core.CashType
import lk.cseportfolio.core.Fees
import lk.cseportfolio.core.Fmt
import lk.cseportfolio.core.LedgerFilter
import lk.cseportfolio.core.LedgerRow
import lk.cseportfolio.core.LedgerSource
import lk.cseportfolio.core.PayMethod
import lk.cseportfolio.core.Reports
import lk.cseportfolio.core.Simulator
import lk.cseportfolio.core.TxnImport
import lk.cseportfolio.core.TxnType
import lk.cseportfolio.core.Xlsx
import lk.cseportfolio.core.r2

@Composable
fun TransactionsScreen(st: UiState, vm: MainViewModel, nav: Nav) {
    var filter by remember { mutableStateOf(LedgerFilter()) }
    var showFilters by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val saver = rememberSaver(vm)
    val rows = remember(st.result, filter) { st.result.ledger.filter { filter.matches(it) }.asReversed() }
    val names = remember(st.companies) { st.companies.mapValues { it.value.name } }
    Screen("Transactions", actions = {
        IconButton(onClick = { showFilters = !showFilters }) {
            Icon(Icons.Filled.FilterList, contentDescription = "Filters", tint = if (filter.active) MaterialTheme.colorScheme.primary else LocalContentColor.current)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Import from Excel, CSV or statement") }, onClick = { menu = false; nav.go("import") })
                DropdownMenuItem(text = { Text("Export this ledger to Excel") }, onClick = { menu = false
                    saver.save("ledger.xlsx") { Xlsx.write(listOf(Reports.ledger(rows.asReversed()))) } })
                DropdownMenuItem(text = { Text("Export this ledger to PDF") }, onClick = { menu = false
                    saver.save("ledger.pdf") { PdfReport.render("Transaction ledger", "As at ${st.result.asOf.format(Reports.DATE)}", listOf(Reports.ledger(rows.asReversed()))) } })
                DropdownMenuItem(text = { Text("Export transactions (re-importable)") }, onClick = { menu = false
                    saver.save("transactions.xlsx") { Xlsx.write(listOf(TxnImport.exportRows(st.txns.map { it.toCoreTxn() }, names))) } })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Recycle bin") }, onClick = { menu = false; nav.go("bin") })
                DropdownMenuItem(text = { Text("Audit history") }, onClick = { menu = false; nav.go("audit") })
            }
        }
    }) {
        if (showFilters) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chips(listOf(null, "BUY", "SELL"), filter.side, { when (it) { null -> "Buy & sell"; "BUY" -> "Buy"; else -> "Sell" } }, { filter = filter.copy(side = it) })
            Chips(listOf(null, PayMethod.CASH, PayMethod.MARGIN), filter.payment, { it?.label ?: "Cash & margin" }, { filter = filter.copy(payment = it) })
            Chips(listOf(null, true, false), filter.profitable, { when (it) { null -> "All results"; true -> "Profitable"; else -> "Loss-making" } }, { filter = filter.copy(profitable = it) })
            Picker("Company", listOf<String?>(null) + st.symbols, filter.symbol, { it ?: "All companies" }, { filter = filter.copy(symbol = it) }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(filter.from, { filter = filter.copy(from = it) }, "From", Modifier.weight(1f))
                DateField(filter.to, { filter = filter.copy(to = it) }, "To", Modifier.weight(1f))
            }
            if (filter.active) TextButton(onClick = { filter = LedgerFilter() }) { Text("Clear filters") }
        }
        if (rows.isEmpty()) EmptyState(if (filter.active) "Nothing matches these filters" else "No transactions yet",
            if (filter.active) "Change or clear the filters to see more entries." else "Use the + button to record a purchase or sale, or import your records from Excel or CSV.")
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
            item {
                val realized = rows.mapNotNull { it.realized }.fold(BigDecimal.ZERO) { a, b -> a.add(b) }
                Text("${rows.size} entries · realized ${Fmt.signed(realized)} · net cash flow ${Fmt.signed(rows.map { it.cashFlow }.fold(BigDecimal.ZERO) { a, b -> a.add(b) })}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            }
            items(rows) { r ->
                LedgerItem(r) {
                    when (r.source) { LedgerSource.TXN -> nav.editTxn(r.refId); LedgerSource.CASH -> nav.cash(r.refId); LedgerSource.MARGIN -> nav.tab("margin") }
                }
            }
        }
    }
}

private fun TxnEntity.toCoreTxn() = lk.cseportfolio.core.Txn(id, date, symbol, type, quantity, price, brokerage, cseFee, secFee, cdsFee, tax, other,
    payment, marginAccountId, financedAmount, loanRepayment, splitNum, splitDen, note)

@Composable
private fun LedgerItem(r: LedgerRow, onClick: () -> Unit) {
    val tones = LocalTones.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val kindColor = when (r.kind) { TxnType.BUY.label -> tones.info; TxnType.SELL.label -> tones.warn; else -> MaterialTheme.colorScheme.onSurfaceVariant }
            Text(r.kind.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = kindColor)
            if (r.payment == PayMethod.MARGIN) Text("  MARGIN", style = MaterialTheme.typography.labelSmall, color = tones.loss)
            Spacer(Modifier.weight(1f))
            Text(r.date.format(Reports.DATE), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.symbol ?: r.note.ifEmpty { "Cash" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = buildString {
                    if (r.quantity != null) append(Fmt.qty(r.quantity!!))
                    if (r.price != null) append(" @ " + Fmt.money(r.price!!))
                    if (r.brokerage != null && r.otherCharges != null) append("  ·  charges " + Fmt.money(r.brokerage!!.add(r.otherCharges!!)))
                }
                if (detail.isNotEmpty()) Text(detail, style = NumStyle.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.signed(r.cashFlow), style = NumStyle.copy(fontWeight = FontWeight.SemiBold), color = toneColor(r.cashFlow))
                if (r.realized != null) Text("P/L " + Fmt.signed(r.realized!!), style = NumStyle.copy(fontSize = 11.sp), color = toneColor(r.realized))
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** The fee calculation laid out the way a contract note reads. */
@Composable
fun FeeSummary(buy: Boolean, gross: BigDecimal, f: Fees) {
    val sign = if (buy) "+" else "−"
    KVMoney(if (buy) "Gross purchase value" else "Gross selling value", gross)
    KV("$sign Brokerage charges", Fmt.money(f.brokerage))
    KV("$sign Exchange charges (CSE, SEC, CDS)", Fmt.money(f.exchange))
    KV("$sign Other applicable charges (levy, other)", Fmt.money(f.tax.add(f.other)))
    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline)
    KVMoney(if (buy) "= Total purchase cost" else "= Net selling proceeds", if (buy) gross.add(f.total) else gross.subtract(f.total), bold = true)
}

@Composable
fun TxnEditorScreen(st: UiState, vm: MainViewModel, nav: Nav, id: Long, initType: String, initSymbol: String, initQty: String, initPrice: String) {
    val existing = remember(id) { st.txns.firstOrNull { it.id == id } }
    var type by remember { mutableStateOf(existing?.type ?: try { TxnType.valueOf(initType) } catch (e: Exception) { TxnType.BUY }) }
    var date by remember { mutableStateOf(existing?.date ?: LocalDate.now()) }
    var symbol by remember { mutableStateOf(existing?.symbol ?: initSymbol) }
    var company by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf(existing?.quantity?.takeIf { it > 0 }?.toString() ?: initQty) }
    var price by remember { mutableStateOf(existing?.price.field().ifEmpty { initPrice }) }
    var auto by remember { mutableStateOf(existing == null) }
    var brokerage by remember { mutableStateOf(existing?.brokerage.field()) }
    var cse by remember { mutableStateOf(existing?.cseFee.field()) }
    var sec by remember { mutableStateOf(existing?.secFee.field()) }
    var cds by remember { mutableStateOf(existing?.cdsFee.field()) }
    var tax by remember { mutableStateOf(existing?.tax.field()) }
    var other by remember { mutableStateOf(existing?.other.field()) }
    var payment by remember { mutableStateOf(existing?.payment ?: PayMethod.CASH) }
    var account by remember { mutableStateOf(st.accounts.firstOrNull { it.id == existing?.marginAccountId }) }
    var financed by remember { mutableStateOf(existing?.financedAmount.field()) }
    var repay by remember { mutableStateOf(existing?.loanRepayment.field()) }
    var splitNum by remember { mutableStateOf(existing?.splitNum?.toString() ?: "2") }
    var splitDen by remember { mutableStateOf(existing?.splitDen?.toString() ?: "1") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    val hasMoney = type == TxnType.BUY || type == TxnType.SELL || type == TxnType.RIGHTS
    val q = qty.toLongOrNull() ?: 0L
    val pr = price.decOrZero()
    val gross = pr.multiply(BigDecimal.valueOf(q)).r2()
    val schedule = st.feesFor(date)

    LaunchedEffect(q, pr, date, auto, type) {
        if (auto && (type == TxnType.BUY || type == TxnType.SELL)) {
            val f = schedule.compute(gross)
            brokerage = f.brokerage.field(); cse = f.cse.field(); sec = f.sec.field(); cds = f.cds.field(); tax = f.tax.field(); other = f.other.field()
        }
    }
    val fees = if (hasMoney) Fees(brokerage.decOrZero(), cse.decOrZero(), sec.decOrZero(), cds.decOrZero(), tax.decOrZero(), other.decOrZero()) else Fees()
    val total = gross.add(fees.total)
    val net = gross.subtract(fees.total)
    val pos = st.result.position(symbol)
    val heldSymbols = st.summary.holdings.map { it.symbol }

    fun save() {
        val sym = symbol.trim().uppercase()
        val err = when {
            sym.isEmpty() -> "Enter the trading symbol."
            type == TxnType.SPLIT && ((splitNum.toIntOrNull() ?: 0) <= 0 || (splitDen.toIntOrNull() ?: 0) <= 0) -> "Enter the split ratio, for example 2 new for 1 old."
            type != TxnType.SPLIT && q <= 0 -> "Enter the quantity."
            (hasMoney || type == TxnType.OPENING) && pr.signum() <= 0 -> "Enter the price per share."
            type == TxnType.SELL && existing == null && (pos == null || q > pos.quantity) -> "You hold ${Fmt.qty(pos?.quantity ?: 0)} $sym shares; you cannot sell ${Fmt.qty(q)}."
            payment == PayMethod.MARGIN && (type == TxnType.BUY || type == TxnType.RIGHTS) && account == null -> "Choose the margin account."
            payment == PayMethod.MARGIN && (type == TxnType.BUY || type == TxnType.RIGHTS) && financed.decOrZero().signum() <= 0 -> "Enter the amount financed by the margin loan."
            payment == PayMethod.MARGIN && financed.decOrZero() > total -> "The financed amount cannot exceed the total cost of LKR ${Fmt.money(total)}."
            type == TxnType.SELL && repay.decOrZero() > net -> "The loan repayment cannot exceed the net proceeds of LKR ${Fmt.money(net)}."
            else -> null
        }
        if (err != null) { vm.say(err); return }
        val margin = payment == PayMethod.MARGIN && (type == TxnType.BUY || type == TxnType.RIGHTS)
        val e = TxnEntity(
            id = existing?.id ?: 0, date = date, symbol = sym, type = type, quantity = if (type == TxnType.SPLIT) 0 else q,
            price = if (hasMoney || type == TxnType.OPENING) pr else BigDecimal.ZERO,
            brokerage = fees.brokerage, cseFee = fees.cse, secFee = fees.sec, cdsFee = fees.cds, tax = fees.tax, other = fees.other,
            payment = if (margin) PayMethod.MARGIN else PayMethod.CASH,
            marginAccountId = if (margin || (type == TxnType.SELL && repay.decOrZero().signum() > 0)) account?.id else null,
            financedAmount = if (margin) financed.decOrZero() else BigDecimal.ZERO,
            loanRepayment = if (type == TxnType.SELL && account != null) repay.decOrZero() else BigDecimal.ZERO,
            splitNum = splitNum.toIntOrNull() ?: 1, splitDen = splitDen.toIntOrNull() ?: 1, note = note.trim(),
            feeScheduleId = if (hasMoney) schedule.id else null, createdAt = existing?.createdAt ?: 0
        )
        vm.run(if (existing == null) "${type.label} recorded." else "Changes saved.") { saveTxn(e, company.trim().ifEmpty { null }) }
        nav.back()
    }

    Screen(if (existing == null) "New transaction" else "Edit ${existing.type.label.lowercase()}", onBack = { nav.back() }, actions = {
        if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        TextButton(onClick = { save() }) { Text("Save") }
    }) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (existing == null) Chips(TxnType.values().toList(), type, { it.label }, { type = it; if (it == TxnType.RIGHTS || it == TxnType.OPENING) auto = false })
            DateField(date, { date = it }, "Transaction date", Modifier.fillMaxWidth())
            if (type == TxnType.SELL && existing == null && heldSymbols.isNotEmpty())
                Picker("Stock to sell", heldSymbols, symbol.takeIf { it in heldSymbols }, { s -> s + st.name(s).let { n -> if (n.isEmpty()) "" else " – $n" } }, { symbol = it }, Modifier.fillMaxWidth())
            else {
                TextInput(symbol, { symbol = it.trim() }, "Stock symbol, e.g. HBS.N0000", Modifier.fillMaxWidth(), caps = true)
                val matches = if (symbol.isEmpty() || st.companies.containsKey(symbol)) emptyList() else st.symbols.filter { it.startsWith(symbol) }.take(5)
                if (matches.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { matches.forEach { m -> AssistChip(onClick = { symbol = m }, label = { Text(m) }) } }
                if (symbol.isNotEmpty() && !st.companies.containsKey(symbol)) TextInput(company, { company = it }, "Company name (new company)", Modifier.fillMaxWidth())
                else if (st.name(symbol).isNotEmpty()) Text(st.name(symbol), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (pos != null && pos.isOpen) Text("You hold ${Fmt.qty(pos.quantity)} shares at an average cost of LKR ${Fmt.money(pos.avgCost.setScale(4, java.math.RoundingMode.HALF_UP))}.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            when (type) {
                TxnType.SPLIT -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField(splitNum, { splitNum = it }, "New shares", Modifier.weight(1f), whole = true)
                        NumField(splitDen, { splitDen = it }, "For every old shares", Modifier.weight(1f), whole = true)
                    }
                    Text("Example: a 2-for-1 split is 2 new for every 1 old; a 1-for-10 consolidation is 1 new for every 10 old. Total cost is unchanged, so the average cost adjusts automatically.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TxnType.BONUS -> {
                    NumField(qty, { qty = it }, "Bonus shares received", Modifier.fillMaxWidth(), whole = true)
                    Text("Bonus shares are added at no cost, which lowers your average cost per share.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TxnType.OPENING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField(qty, { qty = it }, "Quantity held", Modifier.weight(1f), whole = true)
                        NumField(price, { price = it }, "Cost per share", Modifier.weight(1f))
                    }
                    Text("Use this for shares you already owned before you started using the app. Enter your average cost per share including purchase charges. Opening balances do not change the cash balance.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    KVMoney("Total cost", gross, bold = true)
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField(qty, { qty = it }, if (type == TxnType.SELL) "Quantity sold" else "Quantity", Modifier.weight(1f), whole = true)
                        NumField(price, { price = it }, "Price per share", Modifier.weight(1f))
                    }
                    Section("Charges", trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Auto", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Switch(checked = auto, onCheckedChange = { auto = it })
                        }
                    }) {
                        Text(if (auto) "Calculated from “${schedule.name}” (in force on ${date.format(Reports.DATE)}, ${Fmt.rate(schedule.totalPct(gross))} in total). Switch Auto off to type the amounts from your contract note."
                             else "Entered by hand. These amounts are stored with the transaction and never change when fee schedules are edited.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField(brokerage, { brokerage = it }, "Brokerage", Modifier.weight(1f), enabled = !auto)
                            NumField(cse, { cse = it }, "CSE fee", Modifier.weight(1f), enabled = !auto)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField(sec, { sec = it }, "SEC levy", Modifier.weight(1f), enabled = !auto)
                            NumField(cds, { cds = it }, "CDS fee", Modifier.weight(1f), enabled = !auto)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField(tax, { tax = it }, "Taxes / levy", Modifier.weight(1f), enabled = !auto)
                            NumField(other, { other = it }, "Other charges", Modifier.weight(1f), enabled = !auto)
                        }
                        Spacer(Modifier.height(8.dp))
                        FeeSummary(type != TxnType.SELL, gross, fees)
                    }
                    if (type == TxnType.SELL) {
                        if (existing == null && pos != null && pos.isOpen && q in 1..pos.quantity && pr.signum() > 0) {
                            val sim = Simulator.simulate(pos, q, pr, schedule, st.result.method)
                            val realized = net.subtract(sim.costOfSold)
                            Section("Result of this sale") {
                                KVMoney("Cost of the shares sold", sim.costOfSold)
                                KVMoney("Net selling proceeds", net)
                                KVMoney("Realized profit / loss", realized, signed = true, bold = true)
                                KV("Return", Fmt.pct(lk.cseportfolio.core.pctOf(realized, sim.costOfSold)), toneColor(realized))
                            }
                        } else if (existing != null) st.result.sells.firstOrNull { it.txnId == existing.id }?.let { s ->
                            Section("Recorded result") { KVMoney("Cost of the shares sold", s.costOfSold); KVMoney("Realized profit / loss", s.realized, signed = true, bold = true) }
                        }
                        if (st.accounts.isNotEmpty()) Section("Margin loan settlement") {
                            Picker("Repay loan from the proceeds", listOf<MarginAccountEntity?>(null) + st.accounts, account, { it?.let { a -> "${a.broker} ${a.accountNo}".trim() } ?: "No repayment" },
                                { a -> account = a
                                    if (a == null) repay = "" else if (repay.isEmpty()) {
                                        val owed = st.result.margin.firstOrNull { it.account.id == a.id }?.outstanding ?: BigDecimal.ZERO
                                        val own = pos?.let { Simulator.simulate(it, q.coerceAtMost(it.quantity), pr, schedule, st.result.method).loanRepayment } ?: BigDecimal.ZERO
                                        repay = (if (own.signum() > 0) own else owed).min(net.max(BigDecimal.ZERO)).field()
                                    } }, Modifier.fillMaxWidth())
                            if (account != null) {
                                NumField(repay, { repay = it }, "Amount applied to the loan", Modifier.fillMaxWidth().padding(top = 8.dp))
                                val owed = st.result.margin.firstOrNull { it.account.id == account?.id }?.outstanding ?: BigDecimal.ZERO
                                KVMoney("Loan outstanding on this account", owed)
                                if (pos != null) KVMoney("…attributed to $symbol (incl. unpaid interest)", pos.financed.add(pos.interestUnpaid))
                                KVMoney("Net cash to you", net.subtract(repay.decOrZero()), bold = true)
                            }
                        }
                    } else {
                        Section("Payment") {
                            Chips(PayMethod.values().toList(), payment, { it.label }, { payment = it })
                            if (payment == PayMethod.MARGIN) {
                                if (st.accounts.isEmpty()) Text("Add a margin account in the Margin tab first.", style = MaterialTheme.typography.bodySmall, color = LocalTones.current.loss)
                                else {
                                    Picker("Margin account", st.accounts, account, { "${it.broker} ${it.accountNo}".trim() }, { account = it }, Modifier.fillMaxWidth().padding(top = 8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                        NumField(financed, { financed = it }, "Amount financed by the loan", Modifier.weight(1f))
                                        TextButton(onClick = { financed = total.field() }) { Text("All") }
                                    }
                                    KVMoney("Paid from your own cash", total.subtract(financed.decOrZero()).max(BigDecimal.ZERO))
                                }
                            }
                        }
                    }
                }
            }
            TextInput(note, { note = it }, "Note (optional), e.g. contract note number", Modifier.fillMaxWidth(), singleLine = false)
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text(if (existing == null) "Save transaction" else "Save changes") }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmDelete && existing != null) ConfirmDialog("Delete this transaction?", "It moves to the recycle bin and can be restored. The deletion is recorded in the audit history.", "Delete",
        { vm.run("Moved to the recycle bin.") { deleteTxn(existing.id) }; nav.back() }, { confirmDelete = false })
}

@Composable
fun CashEditorScreen(st: UiState, vm: MainViewModel, nav: Nav, id: Long) {
    val existing = remember(id) { st.cash.firstOrNull { it.id == id } }
    var type by remember { mutableStateOf(existing?.type ?: CashType.DEPOSIT) }
    var date by remember { mutableStateOf(existing?.date ?: LocalDate.now()) }
    var amount by remember { mutableStateOf(existing?.amount.field()) }
    var symbol by remember { mutableStateOf(existing?.symbol) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    Screen(if (existing == null) "Cash entry" else "Edit cash entry", onBack = { nav.back() }, actions = {
        if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
    }) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Chips(CashType.values().toList(), type, { it.label }, { type = it })
            DateField(date, { date = it }, "Date", Modifier.fillMaxWidth())
            NumField(amount, { amount = it }, "Amount (LKR)", Modifier.fillMaxWidth())
            if (type == CashType.DIVIDEND) Picker("Company paying the dividend", listOf<String?>(null) + st.symbols, symbol, { it ?: "Not specified" }, { symbol = it }, Modifier.fillMaxWidth())
            TextInput(note, { note = it }, "Note (optional)", Modifier.fillMaxWidth(), singleLine = false)
            Text("Deposits and withdrawals keep the cash balance in step with your broker account. Current cash balance: LKR ${Fmt.money(st.result.cash)}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                val a = amount.dec()
                if (a == null || a.signum() <= 0) vm.say("Enter the amount.")
                else {
                    val e = CashEntity(id = existing?.id ?: 0, date = date, type = type, amount = a, symbol = if (type == CashType.DIVIDEND) symbol else null, note = note.trim())
                    vm.run("${type.label} saved.") { saveCash(e) }; nav.back()
                }
            }) { Text("Save") }
        }
    }
    if (confirmDelete && existing != null) ConfirmDialog("Delete this entry?", "It moves to the recycle bin and can be restored.", "Delete",
        { vm.run("Moved to the recycle bin.") { deleteCash(existing.id) }; nav.back() }, { confirmDelete = false })
}
