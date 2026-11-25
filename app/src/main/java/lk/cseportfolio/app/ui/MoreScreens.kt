package lk.cseportfolio.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.math.BigDecimal
import java.time.LocalDate
import lk.cseportfolio.app.MainViewModel
import lk.cseportfolio.app.Nav
import lk.cseportfolio.app.UiState
import lk.cseportfolio.app.data.FeeEntity
import lk.cseportfolio.app.data.Notifier
import lk.cseportfolio.app.data.Vault
import lk.cseportfolio.app.data.WatchEntity
import lk.cseportfolio.app.data.describe
import lk.cseportfolio.app.data.toCore
import lk.cseportfolio.core.BackupCrypto
import lk.cseportfolio.core.CostMethod
import lk.cseportfolio.core.Fmt
import lk.cseportfolio.core.Reports
import lk.cseportfolio.core.pctOf

// ---------------------------------------------------------------- watchlist

@Composable
fun WatchlistScreen(st: UiState, vm: MainViewModel, nav: Nav) {
    var edit by remember { mutableStateOf<WatchEntity?>(null) }
    var pricing by remember { mutableStateOf<String?>(null) }
    Screen("Watchlist", onBack = { nav.back() }, actions = {
        if (st.settings.hasPriceSource) IconButton(onClick = { vm.refreshPrices(st.watch.map { it.symbol }.distinct()) }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh prices") }
        IconButton(onClick = { edit = WatchEntity(symbol = "") }) { Icon(Icons.Filled.Add, contentDescription = "Add to watchlist") }
    }) {
        if (st.watch.isEmpty()) { EmptyState("Your watchlist is empty", "Add companies you are thinking of buying, with a target buying and selling price.", "Add a company") { edit = WatchEntity(symbol = "") }; return@Screen }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Alerts fire when a price you enter, import or fetch crosses a target. Prices are never invented: each shows when and how it was last updated.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(st.watch, key = { it.id }) { w ->
                val q = st.quotes[w.symbol]
                Section(w.symbol + (if (w.name.isNotEmpty()) "  ·  ${w.name}" else ""), trailing = { Row {
                    TextButton(onClick = { pricing = w.symbol }) { Text("Price") }
                    TextButton(onClick = { edit = w }) { Text("Edit") } } }) {
                    KV("Current price", q?.let { Fmt.money(it.last) } ?: "not recorded", bold = true)
                    if (q != null) Text("Updated ${stamp(q.updatedAtMillis)} · ${q.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    KV("Target buying price", w.targetBuy?.let { Fmt.money(it) } ?: "–",
                        if (q != null && w.targetBuy != null && q.last <= w.targetBuy) LocalTones.current.gain else MaterialTheme.colorScheme.onSurface)
                    KV("Target selling price", w.targetSell?.let { Fmt.money(it) } ?: "–")
                    val base = q?.last ?: w.targetBuy
                    val upside = if (base != null && w.targetSell != null) pctOf(w.targetSell.subtract(base), base) else null
                    KV(if (q != null) "Expected upside from current price" else "Expected upside from target buy", Fmt.pct(upside), toneColor(upside))
                    if (w.notes.isNotEmpty()) Text(w.notes, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    TextButton(onClick = { nav.newTxn("BUY", w.symbol, "", q?.last?.let { Fmt.plain(it) } ?: "") }) { Text("Buy…") }
                }
            }
        }
    }
    edit?.let { w -> WatchDialog(w, st.symbols, { edit = null }, { vm.run("Watchlist saved.") { saveWatch(it) } }, if (w.id != 0L) ({ vm.run("Removed.") { deleteWatch(w.id) } }) else null) }
    pricing?.let { s -> PriceDialog(s, st.quotes[s], { pricing = null }) { quote -> vm.run("Price saved.") { setQuote(quote) } } }
}

@Composable
private fun WatchDialog(w: WatchEntity, symbols: List<String>, onDismiss: () -> Unit, onSave: (WatchEntity) -> Unit, onDelete: (() -> Unit)?) {
    var symbol by remember { mutableStateOf(w.symbol) }
    var name by remember { mutableStateOf(w.name) }
    var buy by remember { mutableStateOf(w.targetBuy.field()) }
    var sell by remember { mutableStateOf(w.targetSell.field()) }
    var notes by remember { mutableStateOf(w.notes) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (w.id == 0L) "Add to watchlist" else "Edit watchlist entry") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(symbol, { symbol = it.trim() }, "Trading symbol", Modifier.fillMaxWidth(), caps = true)
            val matches = if (symbol.isEmpty() || symbol in symbols) emptyList() else symbols.filter { it.startsWith(symbol) }.take(4)
            if (matches.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { matches.forEach { m -> AssistChip(onClick = { symbol = m }, label = { Text(m) }) } }
            TextInput(name, { name = it }, "Company", Modifier.fillMaxWidth())
            NumField(buy, { buy = it }, "Target buying price", Modifier.fillMaxWidth())
            NumField(sell, { sell = it }, "Target selling price", Modifier.fillMaxWidth())
            TextInput(notes, { notes = it }, "Investment notes", Modifier.fillMaxWidth(), singleLine = false)
            if (onDelete != null) TextButton(onClick = { onDelete(); onDismiss() }) { Text("Remove from watchlist", color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = symbol.isNotBlank(), onClick = {
            onSave(w.copy(symbol = symbol.trim(), name = name.trim(), targetBuy = buy.dec(), targetSell = sell.dec(), notes = notes.trim(), buyNotifiedAt = 0, sellNotifiedAt = 0)); onDismiss()
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// ---------------------------------------------------------------- settings

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LinkRow(title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingsScreen(st: UiState, vm: MainViewModel, nav: Nav, canLock: () -> Boolean) {
    val context = LocalContext.current
    val s = st.settings
    val store = vm.repo.settings
    val saver = rememberSaver(vm)
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var restorePassword by remember { mutableStateOf("") }
    val opener = rememberOpener(vm) { _, bytes -> restoreBytes = bytes; restorePassword = "" }
    var backupPassword by remember { mutableStateOf(Vault.get(context, "backupPassword") ?: "") }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        store.update { it.copy(alerts = granted) }
        if (!granted) vm.say("Notifications are blocked for this app. Allow them in Android settings to get price alerts.")
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) { }
            store.update { it.copy(backupFolder = uri.toString()) }
            vm.say("Backup folder set.")
        }
    }
    var url by remember { mutableStateOf(s.priceUrl) }
    var auth by remember { mutableStateOf(s.priceAuthHeader) }
    var pLast by remember { mutableStateOf(s.pathLast) }
    var pPrev by remember { mutableStateOf(s.pathPrevClose) }
    var pChange by remember { mutableStateOf(s.pathChange) }
    var pPct by remember { mutableStateOf(s.pathChangePct) }
    var pVol by remember { mutableStateOf(s.pathVolume) }
    var pHi by remember { mutableStateOf(s.pathHigh52) }
    var pLo by remember { mutableStateOf(s.pathLow52) }

    Screen("Settings", onBack = { nav.back() }) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("Appearance") { Chips(listOf(0, 1, 2), s.theme, { listOf("Follow phone", "Light", "Dark")[it] }, { v -> store.update { it.copy(theme = v) } }) }

            Section("Calculations") {
                Picker("Cost-basis method", CostMethod.values().toList(), s.costMethod, { it.label }, { m -> store.update { it.copy(costMethod = m) } }, Modifier.fillMaxWidth())
                Text("Weighted average is the default. Changing the method recalculates the cost of shares sold, and therefore realized profit, for every past sale.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                LinkRow("Fee schedules", "Brokerage, CSE, SEC, CDS and levy rates, with history") { nav.go("fees") }
            }

            Section("Security") {
                SwitchRow("App lock", "Ask for fingerprint, face or screen lock when the app opens", s.lockEnabled) { on ->
                    if (on && !canLock()) vm.say("Set up a screen lock or fingerprint on this phone first.") else store.update { it.copy(lockEnabled = on) }
                }
                Text("Your data is stored in an encrypted database on this phone. The key is held in the Android Keystore and never leaves the device.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section("Price alerts") {
                SwitchRow("Notify me when a watchlist target is reached", "Checked each time a price is entered, imported or fetched", s.alerts && Notifier.canNotify(context)) { on ->
                    if (on && Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(context)) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else store.update { it.copy(alerts = on) }
                }
            }

            Section("Backup") {
                SwitchRow("Automatic daily backup", "Saved when the app opens. Last: ${stamp(s.lastAutoBackup)}", s.autoBackup) { on -> store.update { it.copy(autoBackup = on) } }
                OutlinedTextField(value = backupPassword, onValueChange = { backupPassword = it; Vault.put(context, "backupPassword", it) }, label = { Text("Backup password (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), supportingText = { Text("If set, backup files are encrypted. You need this password to restore them, so keep it safe.") })
                LinkRow("Backup folder", if (s.backupFolder.isEmpty()) "Not chosen: backups stay in private app storage, which is erased if the app is uninstalled"
                    else "Daily backups are also copied to the folder you chose") { folderPicker.launch(null) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { saver.save("cse-portfolio-backup-${LocalDate.now()}.cseb") { vm.repo.backupBytes(backupPassword.ifEmpty { null }) } }) { Text("Save backup file") }
                    OutlinedButton(onClick = { opener.open() }) { Text("Restore…") }
                }
                TextButton(onClick = { vm.run { val m = autoBackup(force = true); vm.say(m ?: "Nothing to back up yet.") } }) { Text("Run the automatic backup now") }
                Text("To keep a copy in the cloud, choose a backup folder that your phone syncs (for example a Google Drive folder), or save a backup file to Drive from time to time.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section("Market prices") {
                SwitchRow("Get prices from the CSE website", "Reads the figures cse.lk shows. Unofficial: it can stop working if the CSE changes its site", s.cseSource) { on -> store.update { it.copy(cseSource = on) } }
                SwitchRow("Refresh when the app opens", "Otherwise tap the refresh button on the Dashboard or Portfolio. Last refresh: ${stamp(s.lastPriceRefresh)}", s.autoRefresh) { on -> store.update { it.copy(autoRefresh = on) } }
                Button(enabled = s.cseSource, onClick = { vm.refreshPrices(emptyList()) }) { Text("Refresh prices now") }
                Text("Prices are updated for the companies in your portfolio and watchlist. Each price shows its time and source, and a company with no price on the CSE website keeps its last stored price.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section("Other data source (optional)") {
                Text("Used only when the CSE website option above is off. If your broker or a licensed data vendor gives you a web address that returns a quote as JSON, enter it here with {symbol} where the trading symbol goes.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextInput(url, { url = it.trim() }, "Address, e.g. https://…/quote?code={symbol}", Modifier.fillMaxWidth())
                TextInput(auth, { auth = it }, "Access header (optional), e.g. Authorization: Bearer …", Modifier.fillMaxWidth())
                Text("Field names in the reply (use dots for nested fields, e.g. data.lastTradedPrice)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextInput(pLast, { pLast = it.trim() }, "Latest price", Modifier.weight(1f)); TextInput(pPrev, { pPrev = it.trim() }, "Previous close", Modifier.weight(1f)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextInput(pChange, { pChange = it.trim() }, "Change", Modifier.weight(1f)); TextInput(pPct, { pPct = it.trim() }, "Change %", Modifier.weight(1f)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextInput(pVol, { pVol = it.trim() }, "Volume", Modifier.weight(1f)); TextInput(pHi, { pHi = it.trim() }, "52-wk high", Modifier.weight(1f)); TextInput(pLo, { pLo = it.trim() }, "52-wk low", Modifier.weight(1f)) }
                Button(onClick = {
                    store.update { it.copy(priceUrl = url, priceAuthHeader = auth, pathLast = pLast, pathPrevClose = pPrev, pathChange = pChange, pathChangePct = pPct, pathVolume = pVol, pathHigh52 = pHi, pathLow52 = pLo) }
                    vm.say(if (url.isEmpty()) "Other data source cleared." else "Data source saved. It is used when the CSE website option is off.")
                }) { Text("Save data source") }
            }

            Section("Data") {
                LinkRow("Import", "Excel, CSV or a broker Gain/Loss statement (PDF)") { nav.go("import") }
                LinkRow("Audit history", "Every creation, edit, deletion and restore") { nav.go("audit") }
                LinkRow("Recycle bin", "Restore deleted entries") { nav.go("bin") }
            }
            Text("CSE Portfolio Pro 1.3.0 · private personal investment records. Estimates are not financial advice; always check the contract note from your broker.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
    restoreBytes?.let { bytes ->
        val encrypted = BackupCrypto.isEncrypted(bytes)
        AlertDialog(onDismissRequest = { restoreBytes = null }, title = { Text("Restore this backup?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Everything currently in the app (transactions, margin records, fee schedules, watchlist) will be replaced by the contents of the backup file. This cannot be undone, so save a backup of the current data first if you might need it.")
                if (encrypted) OutlinedTextField(value = restorePassword, onValueChange = { restorePassword = it }, label = { Text("Backup password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            } },
            confirmButton = { TextButton(onClick = { val pw = restorePassword; restoreBytes = null; vm.run { vm.say(restore(bytes, pw.ifEmpty { null })) } }) { Text("Replace my data") } },
            dismissButton = { TextButton(onClick = { restoreBytes = null }) { Text("Cancel") } })
    }
}

// ---------------------------------------------------------------- fee schedules

@Composable
fun FeeSchedulesScreen(st: UiState, vm: MainViewModel, nav: Nav) {
    var edit by remember { mutableStateOf<FeeEntity?>(null) }
    val current = st.fees
    Screen("Fee schedules", onBack = { nav.back() }, actions = {
        IconButton(onClick = { edit = (st.feeEntities.maxByOrNull { it.effectiveFrom } ?: lk.cseportfolio.core.FeeSchedule.cseDefault().let { d ->
            FeeEntity(0, d.name, d.effectiveFrom, d.brokeragePct, d.csePct, d.secPct, d.cdsPct, d.taxPct) }).copy(id = 0, name = "My broker's rates", effectiveFrom = LocalDate.now()) }) {
            Icon(Icons.Filled.Add, contentDescription = "Add schedule")
        }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Banner("New transactions use the schedule in force on their trade date. Charges are stored with each transaction, so editing or adding a schedule never changes trades you have already recorded. " +
                "When your broker or the CSE changes a rate, add a new schedule with the date it takes effect instead of editing the old one.", LocalTones.current.info)
            st.feeEntities.sortedByDescending { it.effectiveFrom }.forEach { f ->
                val c = f.toCore()
                Section(f.name.ifEmpty { "Fee schedule" }, trailing = { TextButton(onClick = { edit = f }) { Text("Edit") } }) {
                    KV("Effective from", f.effectiveFrom.format(Reports.DATE) + if (c.id == current.id) "  ·  in force today" else "", bold = c.id == current.id)
                    KV("Broker commission", Fmt.rate(f.brokeragePct)); KV("CSE fees", Fmt.rate(f.csePct)); KV("SEC levies", Fmt.rate(f.secPct)); KV("CDS charges", Fmt.rate(f.cdsPct))
                    KV("Taxes / share transaction levy", Fmt.rate(f.taxPct)); KV("Other charges", Fmt.rate(f.otherPct))
                    KV("Total on a normal trade", Fmt.rate(c.totalPct()), bold = true)
                    if (f.minBrokerage.signum() > 0) KVMoney("Minimum brokerage", f.minBrokerage)
                    if (f.fixedBrokerage.signum() > 0) KVMoney("Fixed brokerage per trade", f.fixedBrokerage)
                    if (f.fixedOther.signum() > 0) KVMoney("Fixed other charge per trade", f.fixedOther)
                    if (f.tier2Threshold != null && f.tier2Threshold.signum() > 0) KV("Trades above LKR ${Fmt.compact(f.tier2Threshold)}", Fmt.rate(c.totalPct(f.tier2Threshold.add(BigDecimal.ONE))) + " in total")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    edit?.let { f -> FeeDialog(f, { edit = null }, { vm.run("Fee schedule saved.") { saveFee(it) } },
        if (f.id != 0L && st.feeEntities.size > 1) ({ vm.run("Fee schedule deleted.") { deleteFee(f) } }) else null) }
}

@Composable
private fun FeeDialog(f: FeeEntity, onDismiss: () -> Unit, onSave: (FeeEntity) -> Unit, onDelete: (() -> Unit)?) {
    fun init(v: BigDecimal?) = v?.let { Fmt.plain(it) } ?: ""
    var name by remember { mutableStateOf(f.name) }
    var from by remember { mutableStateOf<LocalDate?>(f.effectiveFrom) }
    var brokerage by remember { mutableStateOf(init(f.brokeragePct)) }
    var cse by remember { mutableStateOf(init(f.csePct)) }
    var sec by remember { mutableStateOf(init(f.secPct)) }
    var cds by remember { mutableStateOf(init(f.cdsPct)) }
    var tax by remember { mutableStateOf(init(f.taxPct)) }
    var other by remember { mutableStateOf(init(f.otherPct)) }
    var minB by remember { mutableStateOf(f.minBrokerage.field()) }
    var fixB by remember { mutableStateOf(f.fixedBrokerage.field()) }
    var fixO by remember { mutableStateOf(f.fixedOther.field()) }
    var t2 by remember { mutableStateOf(f.tier2Threshold.field()) }
    var t2b by remember { mutableStateOf(init(f.t2BrokeragePct)) }
    var t2c by remember { mutableStateOf(init(f.t2CsePct)) }
    var t2s by remember { mutableStateOf(init(f.t2SecPct)) }
    var t2d by remember { mutableStateOf(init(f.t2CdsPct)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (f.id == 0L) "New fee schedule" else "Edit fee schedule") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(name, { name = it }, "Name", Modifier.fillMaxWidth())
            DateField(from, { from = it }, "Effective from", Modifier.fillMaxWidth())
            Text("Rates as a percentage of the trade value", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField(brokerage, { brokerage = it }, "Brokerage %", Modifier.weight(1f)); NumField(cse, { cse = it }, "CSE %", Modifier.weight(1f)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField(sec, { sec = it }, "SEC %", Modifier.weight(1f)); NumField(cds, { cds = it }, "CDS %", Modifier.weight(1f)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField(tax, { tax = it }, "Tax / levy %", Modifier.weight(1f)); NumField(other, { other = it }, "Other %", Modifier.weight(1f)) }
            Text("Fixed amounts in LKR (leave blank if none)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField(minB, { minB = it }, "Min brokerage", Modifier.weight(1f)); NumField(fixB, { fixB = it }, "Fixed brokerage", Modifier.weight(1f)) }
            NumField(fixO, { fixO = it }, "Fixed other charge per trade", Modifier.fillMaxWidth())
            Text("Large trades (optional): rates used when the trade value is above the threshold", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NumField(t2, { t2 = it }, "Threshold (LKR)", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField(t2b, { t2b = it }, "Brokerage %", Modifier.weight(1f)); NumField(t2c, { t2c = it }, "CSE %", Modifier.weight(1f)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField(t2s, { t2s = it }, "SEC %", Modifier.weight(1f)); NumField(t2d, { t2d = it }, "CDS %", Modifier.weight(1f)) }
            if (onDelete != null) TextButton(onClick = { onDelete(); onDismiss() }) { Text("Delete this schedule", color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            onSave(FeeEntity(f.id, name.trim(), from ?: LocalDate.now(), brokerage.decOrZero(), cse.decOrZero(), sec.decOrZero(), cds.decOrZero(), tax.decOrZero(), other.decOrZero(),
                minB.decOrZero(), fixB.decOrZero(), fixO.decOrZero(), t2.dec(), t2b.decOrZero(), t2c.decOrZero(), t2s.decOrZero(), t2d.decOrZero()))
            onDismiss()
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// ---------------------------------------------------------------- audit & recycle bin

@Composable
fun AuditScreen(vm: MainViewModel, nav: Nav) {
    val log by vm.audit.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(-1L) }
    Screen("Audit history", onBack = { nav.back() }) {
        if (log.isEmpty()) { EmptyState("No history yet", "Every change you make to your records is listed here."); return@Screen }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            items(log, key = { it.id }) { a ->
                Column(Modifier.fillMaxWidth().clickable { open = if (open == a.id) -1L else a.id }.padding(vertical = 8.dp)) {
                    Row {
                        Text("${a.entity} · ${a.action}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                            color = if (a.action.contains("elete")) LocalTones.current.loss else MaterialTheme.colorScheme.primary)
                        Text(stamp(a.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(a.summary, style = MaterialTheme.typography.bodyMedium)
                    if (open == a.id) {
                        if (a.before.isNotEmpty()) Text("Before: ${a.before}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        if (a.after.isNotEmpty()) Text("After: ${a.after}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun BinRow(title: String, sub: String, onRestore: () -> Unit, onPurge: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onRestore) { Text("Restore") }
        IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.DeleteForever, contentDescription = "Delete permanently", tint = MaterialTheme.colorScheme.error) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    if (confirm) ConfirmDialog("Delete permanently?", "This entry will be removed for good. The audit history keeps a record of it.", "Delete permanently", onPurge, { confirm = false })
}

@Composable
fun RecycleBinScreen(vm: MainViewModel, nav: Nav) {
    val txns by vm.deletedTxns.collectAsStateWithLifecycle()
    val cash by vm.deletedCash.collectAsStateWithLifecycle()
    val margin by vm.deletedMargin.collectAsStateWithLifecycle()
    Screen("Recycle bin", onBack = { nav.back() }) {
        if (txns.isEmpty() && cash.isEmpty() && margin.isEmpty()) { EmptyState("The recycle bin is empty", "Deleted transactions wait here so that an accidental deletion can be undone."); return@Screen }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            items(txns, key = { "t" + it.id }) { t -> BinRow(t.describe(), "Transaction · deleted ${stamp(t.updatedAt)}", { vm.run("Restored.") { restoreTxn(t.id) } }, { vm.run("Deleted permanently.") { purgeTxn(t.id) } }) }
            items(cash, key = { "c" + it.id }) { c -> BinRow("${c.type.label} ${Fmt.lkr(c.amount)} on ${c.date.format(Reports.DATE)}", "Cash entry · deleted ${stamp(c.updatedAt)}",
                { vm.run("Restored.") { restoreCash(c.id) } }, { vm.run("Deleted permanently.") { purgeCash(c.id) } }) }
            items(margin, key = { "m" + it.id }) { m -> BinRow("${m.type.label} ${Fmt.lkr(m.amount)} on ${m.date.format(Reports.DATE)}", "Margin entry · deleted ${stamp(m.updatedAt)}",
                { vm.run("Restored.") { restoreMarginEntry(m.id) } }, { vm.run("Deleted permanently.") { purgeMarginEntry(m.id) } }) }
        }
    }
}
