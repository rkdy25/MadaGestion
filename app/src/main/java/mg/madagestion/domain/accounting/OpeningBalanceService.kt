package mg.madagestion.domain.accounting

import androidx.room.withTransaction
import java.math.BigDecimal
import java.time.LocalDate
import mg.madagestion.core.BusinessException
import mg.madagestion.core.Money
import mg.madagestion.core.fmt
import mg.madagestion.core.newId
import mg.madagestion.core.sameAs
import mg.madagestion.core.sumBd
import mg.madagestion.data.AppDatabase
import mg.madagestion.data.model.JournalEntry
import mg.madagestion.data.model.JournalType
import mg.madagestion.data.model.StockMoveType
import mg.madagestion.domain.stock.StockService

/** Solde d'ouverture d'un compte de bilan ; [thirdPartyId] est obligatoire sur un compte client ou fournisseur. */
data class OpeningAccountLine(
    val accountId: String,
    val thirdPartyId: String?,
    val debit: BigDecimal,
    val credit: BigDecimal
)

/** Stock d'ouverture d'un article géré en stock. */
data class OpeningStockLine(
    val productId: String,
    val qty: BigDecimal,
    val unitCost: BigDecimal
)

/**
 * Situation initiale : reprise du bilan de l'entreprise à la date de démarrage de l'application.
 *
 * Elle passe une seule écriture équilibrée au journal des à-nouveaux. Le stock se saisit par article
 * (et non sur le compte de stock) afin que les quantités, le CUMP et la comptabilité restent d'accord.
 */
class OpeningBalanceService(
    private val db: AppDatabase,
    private val accounting: Accounting,
    private val stock: StockService
) {

    /** Situations initiales déjà enregistrées, de la plus récente à la plus ancienne. */
    suspend fun list(companyId: String): List<JournalEntry> =
        db.entryDao().bySource(companyId, EntrySource.OPENING)

    /** @return l'identifiant de l'écriture d'ouverture. */
    suspend fun post(
        companyId: String,
        date: LocalDate,
        accountLines: List<OpeningAccountLine>,
        stockLines: List<OpeningStockLine>
    ): String = db.withTransaction {
        if (accountLines.isEmpty() && stockLines.isEmpty()) throw BusinessException("La situation initiale est vide.")
        accounting.requireOpenPeriod(companyId, date)
        val stockAcc = accounting.mappingId(companyId, MappingKey.STOCK)

        val lines = mutableListOf<LineInput>()
        accountLines.forEachIndexed { i, l ->
            val n = i + 1
            val account = db.accountDao().get(l.accountId) ?: throw BusinessException("Ligne $n : compte inconnu.")
            if (account.accountClass() !in '1'..'5') {
                throw BusinessException("Ligne $n : le compte ${account.code} est un compte de charges ou de produits, il ne figure pas dans un bilan.")
            }
            if (account.id == stockAcc) {
                throw BusinessException("Ligne $n : saisissez le stock par article dans « Stock initial », pas sur le compte ${account.code}.")
            }
            lines += LineInput(l.accountId, l.thirdPartyId, LABEL, Money.ar(l.debit), Money.ar(l.credit))
        }

        // Stock d'ouverture : (article, quantité, valeur), contrôlé avant toute écriture
        val receipts = stockLines.mapIndexed { i, s ->
            val n = i + 1
            val product = db.productDao().get(s.productId) ?: throw BusinessException("Stock, ligne $n : article inconnu.")
            if (!product.isStocked) throw BusinessException("Stock, ligne $n : « ${product.name} » n'est pas géré en stock.")
            val qty = Money.qty(s.qty)
            if (qty.signum() <= 0) throw BusinessException("Stock, ligne $n : la quantité doit être positive.")
            if (s.unitCost.signum() < 0) throw BusinessException("Stock, ligne $n : le coût unitaire ne peut pas être négatif.")
            Triple(product.id, qty, Money.ar(qty.multiply(s.unitCost)))
        }
        val stockValue = receipts.sumBd { it.third }
        if (stockValue.signum() > 0) lines += LineInput(stockAcc, null, "Stock initial", stockValue, BigDecimal.ZERO)

        val debit = lines.sumBd { it.debit }
        val credit = lines.sumBd { it.credit }
        if (!debit.sameAs(credit)) {
            throw BusinessException(
                "Bilan déséquilibré : actif ${debit.fmt()} Ar, passif ${credit.fmt()} Ar (écart ${debit.subtract(credit).abs().fmt()} Ar)."
            )
        }

        val sourceId = newId()
        for ((productId, qty, value) in receipts) {
            stock.receive(productId, date, qty, value, StockMoveType.ADJUSTMENT, EntrySource.OPENING, sourceId, LABEL)
        }

        val journal = accounting.journalOfType(companyId, JournalType.OPENING)
        val entryId = accounting.post(
            companyId = companyId,
            journalId = journal.id,
            date = date,
            reference = LABEL,
            label = "$LABEL au $date",
            lines = lines,
            sourceType = EntrySource.OPENING,
            sourceId = sourceId
        )
        db.stockDao().linkEntry(EntrySource.OPENING, sourceId, entryId)
        accounting.audit(companyId, "OPENING_POST", "ENTRY", entryId, debit.toPlainString())
        entryId
    }

    /**
     * Annule une situation initiale par contrepassation.
     * Refusé si elle a fait entrer du stock : les articles ont pu être vendus depuis, il faut alors
     * corriger par des ajustements de stock et une écriture d'opérations diverses.
     */
    suspend fun cancel(entryId: String, date: LocalDate) {
        db.withTransaction {
            val e = db.entryDao().get(entryId) ?: throw BusinessException("Situation initiale introuvable.")
            if (e.sourceType != EntrySource.OPENING) throw BusinessException("Cette écriture n'est pas une situation initiale.")
            if (db.stockDao().countForEntry(entryId) > 0) {
                throw BusinessException(
                    "Cette situation initiale a fait entrer du stock : corrigez-la par des ajustements de stock et une écriture d'opérations diverses."
                )
            }
            accounting.reverse(entryId, date, allowSource = true)
            accounting.audit(e.companyId, "OPENING_CANCEL", "ENTRY", entryId, e.number ?: "")
        }
    }

    private companion object {
        const val LABEL = "Situation initiale"
    }
}
