package mg.madagestion.domain.party

import androidx.room.withTransaction
import java.math.BigDecimal
import java.time.LocalDate
import mg.madagestion.core.BusinessException
import mg.madagestion.core.Money
import mg.madagestion.data.AppDatabase
import mg.madagestion.data.model.JournalType
import mg.madagestion.data.model.ThirdPartyType
import mg.madagestion.domain.accounting.Accounting
import mg.madagestion.domain.accounting.EntrySource
import mg.madagestion.domain.accounting.LineInput
import mg.madagestion.domain.accounting.MappingKey

/** Opération diverse portée au compte d'un tiers, en dehors des factures et des règlements. */
enum class PartyOperationKind {
    /** Frais qui augmentent le solde du tiers : transport, manutention… */
    CHARGE,

    /** Ristourne ou remise qui diminue le solde du tiers. */
    DISCOUNT
}

fun PartyOperationKind.label(type: ThirdPartyType): String = when (this) {
    PartyOperationKind.CHARGE -> if (type == ThirdPartyType.CUSTOMER) "Frais à la charge du client" else "Frais facturés par le fournisseur"
    PartyOperationKind.DISCOUNT -> if (type == ThirdPartyType.CUSTOMER) "Ristourne accordée" else "Ristourne obtenue"
}

/** Rôle comptable de la contrepartie quand l'utilisateur ne choisit pas de compte. */
fun PartyOperationKind.defaultMapping(type: ThirdPartyType): MappingKey = when (this) {
    PartyOperationKind.CHARGE -> if (type == ThirdPartyType.CUSTOMER) MappingKey.CUSTOMER_CHARGES else MappingKey.SUPPLIER_CHARGES
    PartyOperationKind.DISCOUNT -> if (type == ThirdPartyType.CUSTOMER) MappingKey.SALES_DISCOUNTS else MappingKey.PURCHASE_DISCOUNTS
}

/**
 * Frais et ristournes rattachés à un client ou à un fournisseur.
 *
 * Chaque opération passe une écriture d'opérations diverses sur le compte collectif du tiers :
 * son solde (reste à encaisser ou à payer) en tient donc compte immédiatement.
 * L'écriture est la seule trace de l'opération ; elle s'annule par contrepassation.
 */
class PartyOperationService(private val db: AppDatabase, private val accounting: Accounting) {

    /**
     * @param accountId compte de contrepartie choisi par l'utilisateur ; null = compte par défaut du paramétrage.
     * @return l'identifiant de l'écriture générée.
     */
    suspend fun record(
        companyId: String,
        thirdPartyId: String,
        kind: PartyOperationKind,
        date: LocalDate,
        amount: BigDecimal,
        label: String,
        reference: String,
        accountId: String?
    ): String = db.withTransaction {
        val amt = Money.ar(amount)
        if (amt.signum() <= 0) throw BusinessException("Le montant doit être positif.")
        if (label.isBlank()) throw BusinessException("Indiquez la nature de l'opération (transport, manutention…).")
        val tp = db.thirdPartyDao().get(thirdPartyId) ?: throw BusinessException("Choisissez un tiers.")
        val customer = tp.type == ThirdPartyType.CUSTOMER

        val tiersAcc = accounting.mappingId(companyId, if (customer) MappingKey.CUSTOMERS else MappingKey.SUPPLIERS)
        val otherAcc = accountId ?: accounting.mappingId(companyId, kind.defaultMapping(tp.type))
        if (otherAcc == tiersAcc) throw BusinessException("Le compte de contrepartie ne peut pas être le compte du tiers.")

        // Le solde du tiers augmente : au débit pour un client, au crédit pour un fournisseur.
        val increases = kind == PartyOperationKind.CHARGE
        val tiersDebit = increases == customer
        val text = label.trim()
        val lines = listOf(
            line(tiersAcc, amt, tiersDebit, tp.id, "$text — ${tp.name}"),
            line(otherAcc, amt, !tiersDebit, null, text)
        )
        val journal = accounting.journalOfType(companyId, JournalType.MISC)
        val entryId = accounting.post(
            companyId = companyId,
            journalId = journal.id,
            date = date,
            reference = reference.trim(),
            label = "${kind.label(tp.type)} — $text — ${tp.name}",
            lines = lines,
            sourceType = EntrySource.PARTY_OPERATION,
            sourceId = kind.name
        )
        accounting.audit(companyId, "PARTY_OPERATION_RECORD", "THIRD_PARTY", tp.id, "${kind.name} ${amt.toPlainString()}")
        entryId
    }

    /** Annule l'opération par contrepassation de son écriture. */
    suspend fun cancel(entryId: String, date: LocalDate) {
        db.withTransaction {
            val e = db.entryDao().get(entryId) ?: throw BusinessException("Opération introuvable.")
            if (e.sourceType != EntrySource.PARTY_OPERATION) throw BusinessException("Cette écriture n'est pas une opération sur tiers.")
            accounting.reverse(entryId, date, allowSource = true)
            accounting.audit(e.companyId, "PARTY_OPERATION_CANCEL", "ENTRY", entryId, e.number ?: "")
        }
    }

    private fun line(accountId: String, amount: BigDecimal, debit: Boolean, thirdPartyId: String?, label: String) =
        if (debit) LineInput(accountId, thirdPartyId, label, amount, BigDecimal.ZERO)
        else LineInput(accountId, thirdPartyId, label, BigDecimal.ZERO, amount)
}
