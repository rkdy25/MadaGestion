package mg.madagestion.domain.party

import java.math.BigDecimal
import java.time.LocalDate
import mg.madagestion.core.BusinessException
import mg.madagestion.core.sumBd
import mg.madagestion.data.AppDatabase
import mg.madagestion.data.model.JournalEntry
import mg.madagestion.data.model.ThirdParty
import mg.madagestion.data.model.ThirdPartyType
import mg.madagestion.domain.accounting.Accounting
import mg.madagestion.domain.accounting.EntrySource
import mg.madagestion.domain.accounting.MappingKey
import mg.madagestion.domain.invoicing.isCredit

/** Nature d'un mouvement du compte d'un tiers. */
enum class PartyMoveKind { OPENING, INVOICE, CREDIT_NOTE, PAYMENT, CHARGE, DISCOUNT, OTHER }

/**
 * Mouvement du compte d'un tiers.
 * @property amount positif = le tiers doit davantage (client) ou on lui doit davantage (fournisseur) ; négatif = l'inverse.
 * @property balance solde du compte après ce mouvement.
 * @property cancellable vrai pour un frais ou une ristourne encore annulable.
 */
data class PartyMove(
    val entryId: String,
    val date: LocalDate,
    val number: String,
    val label: String,
    val kind: PartyMoveKind,
    val amount: BigDecimal,
    val balance: BigDecimal,
    val cancellable: Boolean
)

/**
 * Compte d'un client ou d'un fournisseur, reconstitué à partir des écritures validées.
 * [balance] est le montant exact restant à encaisser (client) ou à payer (fournisseur) ;
 * négatif, il représente une avance ou un avoir en faveur du tiers.
 */
data class PartyStatement(
    val party: ThirdParty,
    val moves: List<PartyMove>,
    val opening: BigDecimal,
    val invoiced: BigDecimal,
    val charges: BigDecimal,
    val credits: BigDecimal,
    val paid: BigDecimal,
    val other: BigDecimal
) {
    val balance: BigDecimal get() = opening.add(invoiced).add(charges).add(other).subtract(credits).subtract(paid)
}

/** Lecture du compte des tiers : le compte collectif (411 / 401) et les avances (419 / 409), ventilés par tiers, font foi. */
class PartyAccountService(private val db: AppDatabase, private val accounting: Accounting) {

    /** Comptes qui forment le compte d'un client (411 + 419) ou d'un fournisseur (401 + 409). */
    suspend fun partyAccountIds(companyId: String, customer: Boolean): Set<String> = setOfNotNull(
        accounting.mappingIdOrNull(companyId, if (customer) MappingKey.CUSTOMERS else MappingKey.SUPPLIERS),
        accounting.mappingIdOrNull(companyId, if (customer) MappingKey.CUSTOMER_ADVANCES else MappingKey.SUPPLIER_ADVANCES)
    )

    /**
     * Solde de chaque tiers ayant au moins un mouvement (clé = identifiant du tiers).
     * Les avances (419 / 409) sont rapprochées du compte collectif : le solde est net des avances.
     */
    suspend fun balances(companyId: String): Map<String, BigDecimal> {
        val customers = partyAccountIds(companyId, customer = true)
        val suppliers = partyAccountIds(companyId, customer = false)
        val out = HashMap<String, BigDecimal>()
        for (l in db.entryDao().validatedLines(companyId)) {
            val tp = l.thirdPartyId ?: continue
            val signed = when (l.accountId) {
                in customers -> l.debit.subtract(l.credit)
                in suppliers -> l.credit.subtract(l.debit)
                else -> continue
            }
            out[tp] = (out[tp] ?: BigDecimal.ZERO).add(signed)
        }
        return out
    }

    suspend fun statement(companyId: String, thirdPartyId: String): PartyStatement {
        val party = db.thirdPartyDao().get(thirdPartyId) ?: throw BusinessException("Tiers introuvable.")
        val customer = party.type == ThirdPartyType.CUSTOMER
        val accounts = partyAccountIds(companyId, customer)
        val entries = db.entryDao().validated(companyId).associateBy { it.id }
        val invoices = db.invoiceDao().list(companyId).associateBy { it.id }

        fun kindOf(e: JournalEntry): PartyMoveKind {
            // Une contrepassation prend la nature de l'écriture qu'elle annule : les deux se compensent.
            val origin = e.reversalOfId?.let { entries[it] } ?: e
            return when (origin.sourceType) {
                EntrySource.OPENING -> PartyMoveKind.OPENING
                EntrySource.PAYMENT -> PartyMoveKind.PAYMENT
                EntrySource.ADVANCE -> PartyMoveKind.PAYMENT
                EntrySource.INVOICE ->
                    if (invoices[origin.sourceId]?.kind?.isCredit() == true) PartyMoveKind.CREDIT_NOTE else PartyMoveKind.INVOICE
                EntrySource.PARTY_OPERATION ->
                    if (origin.sourceId == PartyOperationKind.DISCOUNT.name) PartyMoveKind.DISCOUNT else PartyMoveKind.CHARGE
                else -> PartyMoveKind.OTHER
            }
        }

        // Un mouvement par écriture : la part au compte collectif et la part en avance sont additionnées.
        // Un transfert d'avance (D 419 / C 411) s'annule et n'apparaît donc pas.
        val raw = db.entryDao().validatedLinesForThirdParty(companyId, thirdPartyId)
            .filter { it.accountId in accounts }
            .groupBy { it.entryId }
            .mapNotNull { (entryId, ls) ->
                val e = entries[entryId] ?: return@mapNotNull null
                val net = ls.sumBd { it.debit }.subtract(ls.sumBd { it.credit })
                val amount = if (customer) net else net.negate()
                if (amount.signum() == 0) null else Triple(e, ls.first(), amount)
            }
            .sortedWith(compareBy({ it.first.date }, { it.first.validatedAt ?: it.first.createdAt }, { it.first.number ?: "" }))

        var running = BigDecimal.ZERO
        val moves = raw.map { (e, l, amount) ->
            running = running.add(amount)
            PartyMove(
                entryId = e.id,
                date = e.date,
                number = e.number ?: "",
                label = e.label.ifBlank { l.label },
                kind = kindOf(e),
                amount = amount,
                balance = running,
                cancellable = e.sourceType == EntrySource.PARTY_OPERATION && e.reversedById == null
            )
        }
        fun total(vararg kinds: PartyMoveKind): BigDecimal = moves.filter { it.kind in kinds }.sumBd { it.amount }
        return PartyStatement(
            party = party,
            moves = moves,
            opening = total(PartyMoveKind.OPENING),
            invoiced = total(PartyMoveKind.INVOICE),
            charges = total(PartyMoveKind.CHARGE),
            credits = total(PartyMoveKind.CREDIT_NOTE, PartyMoveKind.DISCOUNT).negate(),
            paid = total(PartyMoveKind.PAYMENT).negate(),
            other = total(PartyMoveKind.OTHER)
        )
    }
}
