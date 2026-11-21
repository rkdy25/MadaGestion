package mg.madagestion.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mg.madagestion.core.BusinessException
import mg.madagestion.data.AppDatabase
import mg.madagestion.data.model.Account
import mg.madagestion.data.model.AccountMapping
import mg.madagestion.data.model.Allocation
import mg.madagestion.data.model.AuditLog
import mg.madagestion.data.model.Company
import mg.madagestion.data.model.Invoice
import mg.madagestion.data.model.Journal
import mg.madagestion.data.model.JournalEntry
import mg.madagestion.data.model.Payment
import mg.madagestion.data.model.Period
import mg.madagestion.data.model.Product
import mg.madagestion.data.model.StockMovement
import mg.madagestion.data.model.ThirdParty
import mg.madagestion.domain.accounting.Accounting
import mg.madagestion.domain.accounting.Bootstrap
import mg.madagestion.domain.accounting.MappingKey
import mg.madagestion.domain.accounting.OpeningBalanceService
import mg.madagestion.domain.control.Controls
import mg.madagestion.domain.invoicing.InvoiceService
import mg.madagestion.domain.party.PartyAccountService
import mg.madagestion.domain.party.PartyOperationService
import mg.madagestion.domain.payment.PaymentService
import mg.madagestion.domain.report.FinancialWorkbook
import mg.madagestion.domain.report.Reports
import mg.madagestion.domain.stock.StockService
import mg.madagestion.ui.navigation.Navigator

/**
 * Point d'accès unique de l'interface : expose les flux de données de l'entreprise
 * et exécute les actions métier en affichant leurs erreurs sous forme de message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val db: AppDatabase = AppDatabase.get(app)
    val accounting = Accounting(db)
    val stock = StockService(db, accounting)
    val paymentService = PaymentService(db, accounting)
    val invoiceService = InvoiceService(db, accounting, stock, paymentService)
    val partyOperations = PartyOperationService(db, accounting)
    val partyAccounts = PartyAccountService(db, accounting)
    val openingBalance = OpeningBalanceService(db, accounting, stock)
    val reports = Reports(db, accounting)
    val financialWorkbook = FinancialWorkbook(db, reports, partyAccounts)
    val controls = Controls(db, accounting)

    /** Messages (succès ou erreur) affichés dans la barre d'information. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Pile de navigation (conservée lors des rotations d'écran). */
    val nav = Navigator()

    val company: StateFlow<Company?> =
        db.companyDao().first().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val companyIds: Flow<String> = company.filterNotNull().map { it.id }.distinctUntilChanged()

    private fun <T> perCompany(f: (String) -> Flow<List<T>>): StateFlow<List<T>> =
        companyIds.flatMapLatest(f).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accounts: StateFlow<List<Account>> = perCompany { db.accountDao().observe(it) }
    val mappings: StateFlow<List<AccountMapping>> = perCompany { db.mappingDao().observe(it) }
    val journals: StateFlow<List<Journal>> = perCompany { db.journalDao().observe(it) }
    val periods: StateFlow<List<Period>> = perCompany { db.periodDao().observe(it) }
    val entries: StateFlow<List<JournalEntry>> = perCompany { db.entryDao().observeAll(it) }
    val thirdParties: StateFlow<List<ThirdParty>> = perCompany { db.thirdPartyDao().observe(it) }
    val products: StateFlow<List<Product>> = perCompany { db.productDao().observe(it) }
    val movements: StateFlow<List<StockMovement>> = perCompany { db.stockDao().observe(it) }
    val invoices: StateFlow<List<Invoice>> = perCompany { db.invoiceDao().observe(it) }
    val payments: StateFlow<List<Payment>> = perCompany { db.paymentDao().observe(it) }
    val allocations: StateFlow<List<Allocation>> = perCompany { db.allocationDao().observe(it) }
    val audit: StateFlow<List<AuditLog>> = perCompany { db.auditDao().observeRecent(it) }

    init {
        viewModelScope.launch {
            try {
                Bootstrap(db, accounting).ensure()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.emit("Initialisation impossible : ${e.message}")
            }
        }
    }

    fun requireCompany(): String =
        company.value?.id ?: throw BusinessException("L'entreprise n'est pas encore initialisée.")

    /**
     * Exécute une action métier. Une [BusinessException] devient un message clair ;
     * toute autre erreur est signalée sans faire planter l'application.
     */
    fun <T> act(okMessage: String? = null, onDone: (T) -> Unit = {}, block: suspend () -> T) {
        viewModelScope.launch {
            try {
                val result = block()
                okMessage?.let { messages.emit(it) }
                onDone(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BusinessException) {
                messages.emit(e.message ?: "Opération refusée.")
            } catch (e: Exception) {
                messages.emit("Erreur : ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Lecture sécurisée pour l'affichage : une erreur devient un message, jamais un plantage. */
    suspend fun <T> safely(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            messages.emit(e.message ?: "Erreur : ${e.javaClass.simpleName}")
            null
        }

    /** Solde débiteur (+) ou créditeur (−) du compte associé à un rôle comptable. */
    suspend fun netOf(key: MappingKey): BigDecimal {
        val c = company.value?.id ?: return BigDecimal.ZERO
        val accountId = accounting.mappingIdOrNull(c, key) ?: return BigDecimal.ZERO
        return accounting.accountNet(c, accountId)
    }

    /** Génère le classeur Excel des rapports financiers et l'écrit à l'emplacement choisi par l'utilisateur. */
    suspend fun exportFinancialWorkbook(uri: Uri, companyId: String, from: LocalDate, to: LocalDate) {
        val content = withContext(Dispatchers.Default) { financialWorkbook.build(companyId, from, to) }
        withContext(Dispatchers.IO) {
            val out = getApplication<Application>().contentResolver.openOutputStream(uri)
                ?: throw BusinessException("Impossible d'écrire le fichier.")
            out.use { it.write(content) }
        }
    }

    /** Copie la base de données dans le fichier choisi par l'utilisateur. */
    suspend fun backupTo(uri: Uri) {
        withContext(Dispatchers.IO) {
            val context = getApplication<Application>()
            // Vide le journal WAL dans le fichier principal avant la copie
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            val file = context.getDatabasePath(AppDatabase.NAME)
            val out = context.contentResolver.openOutputStream(uri)
                ?: throw BusinessException("Impossible d'écrire le fichier de sauvegarde.")
            out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
        }
    }
}
