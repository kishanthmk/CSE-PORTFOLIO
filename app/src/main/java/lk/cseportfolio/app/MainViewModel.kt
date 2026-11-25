package lk.cseportfolio.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lk.cseportfolio.app.data.AuditEntity
import lk.cseportfolio.app.data.CashEntity
import lk.cseportfolio.app.data.CompanyEntity
import lk.cseportfolio.app.data.FeeEntity
import lk.cseportfolio.app.data.MarginAccountEntity
import lk.cseportfolio.app.data.MarginEntryEntity
import lk.cseportfolio.app.data.Repository
import lk.cseportfolio.app.data.Settings
import lk.cseportfolio.app.data.TxnEntity
import lk.cseportfolio.app.data.WatchEntity
import lk.cseportfolio.app.data.toCore
import lk.cseportfolio.app.data.toQuote
import lk.cseportfolio.core.EngineInput
import lk.cseportfolio.core.FeeSchedule
import lk.cseportfolio.core.PortfolioEngine
import lk.cseportfolio.core.PortfolioResult
import lk.cseportfolio.core.Quote
import lk.cseportfolio.core.Summary
import lk.cseportfolio.core.Valuation

/** Everything the screens need, recomputed by the engine whenever any record changes. */
class UiState(
    val result: PortfolioResult,
    val summary: Summary,
    val companies: Map<String, CompanyEntity>,
    val quotes: Map<String, Quote>,
    val feeEntities: List<FeeEntity>,
    val txns: List<TxnEntity>,
    val cash: List<CashEntity>,
    val accounts: List<MarginAccountEntity>,
    val marginEntries: List<MarginEntryEntity>,
    val watch: List<WatchEntity>,
    val settings: Settings
) {
    val schedules: List<FeeSchedule> = feeEntities.map { it.toCore() }
    /** Fee schedule in force today (used for estimates). */
    val fees: FeeSchedule = FeeSchedule.forDate(schedules, LocalDate.now()) ?: FeeSchedule.cseDefault()
    fun feesFor(date: LocalDate): FeeSchedule = FeeSchedule.forDate(schedules, date) ?: fees
    fun name(symbol: String): String = companies[symbol]?.name ?: ""
    val symbols: List<String> get() = companies.keys.sorted()
}

private class Raw(val txns: List<TxnEntity>, val cash: List<CashEntity>, val accounts: List<MarginAccountEntity>,
                  val marginEntries: List<MarginEntryEntity>, val fees: List<FeeEntity>)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo: Repository = (app as App).repository
    private val dao = repo.dao

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val state: StateFlow<UiState?> =
        combine(
            combine(dao.txns(), dao.cash(), dao.accounts(), dao.marginEntries(), dao.fees()) { t, c, a, m, f -> Raw(t, c, a, m, f) },
            dao.companies(), dao.watch(), repo.settings.flow
        ) { raw, companies, watch, settings ->
            val result = PortfolioEngine.compute(EngineInput(
                txns = raw.txns.map { it.toCore() }, cash = raw.cash.map { it.toCore() }, accounts = raw.accounts.map { it.toCore() },
                marginEntries = raw.marginEntries.map { it.toCore() }, method = settings.costMethod, asOf = LocalDate.now()))
            val quotes = companies.mapNotNull { it.toQuote() }.associateBy { it.symbol }
            val schedules = raw.fees.map { it.toCore() }
            val fees = FeeSchedule.forDate(schedules, LocalDate.now()) ?: FeeSchedule.cseDefault()
            UiState(result, Valuation.summarize(result, quotes, fees), companies.associateBy { it.symbol }, quotes, raw.fees,
                raw.txns, raw.cash, raw.accounts, raw.marginEntries, watch, settings) as UiState?
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val audit: StateFlow<List<AuditEntity>> = dao.audit().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val deletedTxns: StateFlow<List<TxnEntity>> = dao.deletedTxns().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val deletedCash: StateFlow<List<CashEntity>> = dao.deletedCash().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val deletedMargin: StateFlow<List<MarginEntryEntity>> = dao.deletedMarginEntries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            try { repo.seedDefaults(); repo.autoBackup() } catch (e: Exception) { messages.tryEmit("Start-up task failed: ${e.message}") }
            // refresh prices on opening, at most once every five minutes; failures (e.g. no internet) stay silent
            val s = repo.settings.flow.value
            if (s.cseSource && s.autoRefresh && System.currentTimeMillis() - s.lastPriceRefresh > 5 * 60 * 1000L) {
                try { val m = repo.refreshFromCse(); if (m.startsWith("Updated")) messages.tryEmit(m) } catch (e: Exception) { }
            }
            if (s.autoRefresh && System.currentTimeMillis() - repo.market.globalAt.value > 15 * 60 * 1000L) try { repo.refreshGlobal() } catch (e: Exception) { }
        }
    }

    /** Runs a data change off the main thread and reports the outcome in a snackbar. */
    fun run(done: String? = null, block: suspend Repository.() -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try { repo.block(); if (done != null) messages.tryEmit(done) }
            catch (e: Exception) { messages.tryEmit(e.message ?: "Something went wrong.") }
        }
    }

    fun say(text: String) { messages.tryEmit(text) }

    /** Fetches prices for the given symbols from the user's configured source. */
    fun refreshPrices(symbols: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            if (repo.settings.flow.value.cseSource) {
                try { messages.tryEmit(repo.refreshFromCse(symbols.ifEmpty { null })) } catch (e: Exception) { messages.tryEmit(e.message ?: "Could not update prices.") }
                return@launch
            }
            var ok = 0; var firstError: String? = null
            for (s in symbols) try { repo.setQuote(repo.fetchQuote(s)); ok++ } catch (e: Exception) { if (firstError == null) firstError = e.message }
            messages.tryEmit(if (firstError == null) "Updated $ok price(s)." else "Updated $ok of ${symbols.size} price(s). $firstError")
        }
    }
}
