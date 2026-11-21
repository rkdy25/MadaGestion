package mg.madagestion.domain.payment

import androidx.room.withTransaction
import java.math.BigDecimal
import java.time.LocalDate
import mg.madagestion.core.BusinessException
import mg.madagestion.core.Money
import mg.madagestion.core.newId
import mg.madagestion.core.sumBd
import mg.madagestion.data.AppDatabase
import mg.madagestion.data.model.Allocation
import mg.madagestion.data.model.AllocationSource
import mg.madagestion.data.model.DocStatus
import mg.madagestion.data.model.InvoiceKind
import mg.madagestion.data.model.JournalType
import mg.madagestion.data.model.Payment
import mg.madagestion.data.model.PaymentDirection
import mg.madagestion.data.model.PaymentMethod
import mg.madagestion.data.model.ThirdPartyType
import mg.madagestion.domain.accounting.Accounting
import mg.madagestion.domain.accounting.EntrySource
import mg.madagestion.domain.accounting.LineInput
import mg.madagestion.domain.accounting.MappingKey
import mg.madagestion.domain.invoicing.amountDue

/**
 * Règlements (encaissements / décaissements) et imputations.
 *
 * La part d'un règlement imputée sur des factures est portée au compte collectif (411 / 401).
 * La part non imputée est une avance : elle est portée au compte 419 (client) ou 409 (fournisseur)
 * et rejoint le compte collectif quand elle est imputée sur une facture.
 */
class PaymentService(private val db: AppDatabase, private val accounting: Accounting) {

    fun treasuryKey(m: PaymentMethod) = when (m) {
        PaymentMethod.CASH -> MappingKey.CASH
        PaymentMethod.BANK -> MappingKey.BANK
        PaymentMethod.MOBILE_MONEY -> MappingKey.MOBILE
    }

    private fun journalType(m: PaymentMethod) = when (m) {
        PaymentMethod.CASH -> JournalType.CASH
        PaymentMethod.BANK -> JournalType.BANK
        PaymentMethod.MOBILE_MONEY -> JournalType.MOBILE
    }

    /** Refuse une sortie d'espèces qui rendrait la caisse négative. */
    suspend fun requireCash(companyId: String, method: PaymentMethod, amount: BigDecimal) {
        if (method != PaymentMethod.CASH) return
        val available = accounting.accountNet(companyId, accounting.mappingId(companyId, MappingKey.CASH))
        if (available < amount) {
            throw BusinessException("Solde de caisse insuffisant (disponible : ${available.toPlainString()} Ar).")
        }
    }

    /** Reste à payer d'une facture (montant dû moins imputations). */
    suspend fun openAmount(invoiceId: String): BigDecimal {
        val inv = db.invoiceDao().get(invoiceId) ?: throw BusinessException("Facture introuvable.")
        return inv.amountDue().subtract(db.allocationDao().forInvoice(invoiceId).sumBd { it.amount })
    }

    /** Part d'un règlement restée au compte collectif sans imputation (règlements antérieurs à la 0.4.0). */
    private fun regularLeft(p: Payment, allocations: List<Allocation>): BigDecimal =
        p.amount.subtract(p.advance).subtract(allocations.filter { !it.fromAdvance }.sumBd { it.amount })

    /** Part de l'avance (419 / 409) d'un règlement qui n'est pas encore imputée. */
    private fun advanceLeft(p: Payment, allocations: List<Allocation>): BigDecimal =
        p.advance.subtract(allocations.filter { it.fromAdvance }.sumBd { it.amount })

    /**
     * Avances encore disponibles d'un tiers, règlement par règlement, des plus anciens aux plus récents.
     * @param direction RECEIPT = avances reçues d'un client ; DISBURSEMENT = avances versées à un fournisseur.
     */
    suspend fun advancesOf(companyId: String, thirdPartyId: String, direction: PaymentDirection): List<Pair<String, BigDecimal>> {
        val allocations = db.allocationDao().list(companyId).groupBy { it.sourceId }
        return db.paymentDao().list(companyId)
            .filter { !it.cancelled && it.thirdPartyId == thirdPartyId && it.direction == direction }
            .sortedWith(compareBy({ it.date }, { it.createdAt }))
            .map { it.id to advanceLeft(it, allocations[it.id].orEmpty()) }
            .filter { it.second.signum() > 0 }
    }

    /**
     * Enregistre un règlement, génère l'écriture et impute les montants demandés.
     * @param allocations paires (facture, montant). Le solde non imputé devient une avance (419 / 409).
     */
    suspend fun record(
        companyId: String,
        direction: PaymentDirection,
        thirdPartyId: String,
        date: LocalDate,
        amount: BigDecimal,
        method: PaymentMethod,
        reference: String,
        allocations: List<Pair<String, BigDecimal>>
    ): String = db.withTransaction {
        val amt = Money.ar(amount)
        if (amt.signum() <= 0) throw BusinessException("Le montant du règlement doit être positif.")
        val tp = db.thirdPartyDao().get(thirdPartyId) ?: throw BusinessException("Choisissez un tiers.")
        val receipt = direction == PaymentDirection.RECEIPT
        val expected = if (receipt) ThirdPartyType.CUSTOMER else ThirdPartyType.SUPPLIER
        if (tp.type != expected) {
            throw BusinessException(if (receipt) "Un encaissement concerne un client." else "Un décaissement concerne un fournisseur.")
        }
        val allocTotal = allocations.sumBd { Money.ar(it.second) }
        if (allocTotal > amt) throw BusinessException("Les imputations dépassent le montant du règlement.")
        val advance = amt.subtract(allocTotal)
        accounting.requireOpenPeriod(companyId, date)

        val treasuryAcc = accounting.mappingId(companyId, treasuryKey(method))
        if (!receipt) requireCash(companyId, method, amt)

        val prefix = if (receipt) "ENC" else "DEC"
        val seq = accounting.nextNumber(companyId, "PAY:$prefix:${date.year}")
        val number = "$prefix-${date.year}-${seq.toString().padStart(5, '0')}"
        val text = "$number — ${tp.name}"
        val tiersAcc = accounting.mappingId(companyId, if (receipt) MappingKey.CUSTOMERS else MappingKey.SUPPLIERS)
        val advanceAcc = if (advance.signum() > 0) {
            accounting.mappingId(companyId, if (receipt) MappingKey.CUSTOMER_ADVANCES else MappingKey.SUPPLIER_ADVANCES)
        } else null

        // Encaissement : D trésorerie / C 411 (imputé) + C 419 (avance)
        // Décaissement : D 401 (imputé) + D 409 (avance) / C trésorerie
        val lines = mutableListOf<LineInput>()
        if (receipt) lines += LineInput(treasuryAcc, null, text, amt, BigDecimal.ZERO)
        if (allocTotal.signum() > 0) {
            lines += if (receipt) LineInput(tiersAcc, tp.id, text, BigDecimal.ZERO, allocTotal)
            else LineInput(tiersAcc, tp.id, text, allocTotal, BigDecimal.ZERO)
        }
        if (advanceAcc != null) {
            lines += if (receipt) LineInput(advanceAcc, tp.id, "Avance $text", BigDecimal.ZERO, advance)
            else LineInput(advanceAcc, tp.id, "Avance $text", advance, BigDecimal.ZERO)
        }
        if (!receipt) lines += LineInput(treasuryAcc, null, text, BigDecimal.ZERO, amt)

        val journal = accounting.journalOfType(companyId, journalType(method))
        val label = (if (receipt) "Encaissement" else "Décaissement") + " $text"
        val paymentId = newId()
        val entryId = accounting.post(companyId, journal.id, date, number, label, lines, EntrySource.PAYMENT, paymentId)
        db.paymentDao().insert(
            Payment(
                id = paymentId, companyId = companyId, number = number, direction = direction,
                thirdPartyId = thirdPartyId, date = date, amount = amt, method = method,
                reference = reference.trim(), entryId = entryId, advance = advance
            )
        )
        for ((invoiceId, a) in allocations) {
            val part = Money.ar(a)
            if (part.signum() > 0) allocate(companyId, AllocationSource.PAYMENT, paymentId, invoiceId, part, date)
        }
        accounting.audit(companyId, "PAYMENT_RECORD", "PAYMENT", paymentId, number)
        paymentId
    }

    /**
     * Annule un règlement : les avances déjà imputées sont reprises (D 411 / C 419, ou D 409 / C 401),
     * puis l'écriture du règlement est contrepassée et ses imputations supprimées.
     */
    suspend fun cancel(paymentId: String, date: LocalDate) {
        db.withTransaction {
            val p = db.paymentDao().get(paymentId) ?: throw BusinessException("Règlement introuvable.")
            if (p.cancelled) throw BusinessException("Ce règlement est déjà annulé.")
            val transferred = db.allocationDao().forSource(paymentId).filter { it.fromAdvance }.sumBd { it.amount }
            if (transferred.signum() > 0) {
                val receipt = p.direction == PaymentDirection.RECEIPT
                val tiersAcc = accounting.mappingId(p.companyId, if (receipt) MappingKey.CUSTOMERS else MappingKey.SUPPLIERS)
                val advanceAcc = accounting.mappingId(
                    p.companyId, if (receipt) MappingKey.CUSTOMER_ADVANCES else MappingKey.SUPPLIER_ADVANCES
                )
                val text = "Reprise de l'avance ${p.number}"
                // Sens inverse du transfert fait à l'imputation
                val lines = if (receipt) listOf(
                    LineInput(tiersAcc, p.thirdPartyId, text, transferred, BigDecimal.ZERO),
                    LineInput(advanceAcc, p.thirdPartyId, text, BigDecimal.ZERO, transferred)
                ) else listOf(
                    LineInput(advanceAcc, p.thirdPartyId, text, transferred, BigDecimal.ZERO),
                    LineInput(tiersAcc, p.thirdPartyId, text, BigDecimal.ZERO, transferred)
                )
                val journal = accounting.journalOfType(p.companyId, JournalType.MISC)
                accounting.post(p.companyId, journal.id, date, p.number, text, lines, EntrySource.ADVANCE, p.id)
            }
            val reversalId = accounting.reverse(p.entryId, date, allowSource = true)
            db.allocationDao().deleteForSource(paymentId)
            db.paymentDao().update(p.copy(cancelled = true, cancelEntryId = reversalId))
            accounting.audit(p.companyId, "PAYMENT_CANCEL", "PAYMENT", paymentId, p.number)
        }
    }

    /**
     * Rattache à une facture l'avance d'un règlement déjà soldée dans l'écriture de cette facture
     * (D 419 ou C 409 portés par la facture elle-même) : aucune écriture supplémentaire.
     * À appeler dans la transaction de validation de la facture.
     */
    suspend fun attachAdvance(
        companyId: String,
        paymentId: String,
        invoiceId: String,
        amount: BigDecimal,
        date: LocalDate,
        invoiceEntryId: String
    ) {
        db.allocationDao().insert(
            Allocation(
                newId(), companyId, AllocationSource.PAYMENT, paymentId, invoiceId, amount, date,
                fromAdvance = true, entryId = invoiceEntryId
            )
        )
        accounting.audit(companyId, "ADVANCE_APPLY", "INVOICE", invoiceId, amount.toPlainString())
    }

    /**
     * Impute un règlement (ou un avoir) sur une facture.
     * - part restée au compte collectif, ou avoir : aucune écriture, le compte du tiers porte déjà les montants ;
     * - part prise sur une avance : écriture de transfert D 419 / C 411 (client) ou D 401 / C 409 (fournisseur).
     */
    suspend fun allocate(
        companyId: String,
        sourceType: AllocationSource,
        sourceId: String,
        invoiceId: String,
        amount: BigDecimal,
        date: LocalDate
    ) {
        db.withTransaction {
            val part = Money.ar(amount)
            if (part.signum() <= 0) throw BusinessException("Le montant imputé doit être positif.")
            val inv = db.invoiceDao().get(invoiceId) ?: throw BusinessException("Facture introuvable.")
            if (inv.status != DocStatus.VALIDATED) throw BusinessException("On n'impute que sur une facture validée.")
            if (inv.kind != InvoiceKind.SALE && inv.kind != InvoiceKind.PURCHASE) {
                throw BusinessException("On n'impute pas sur un avoir.")
            }
            val invoiceOpen = inv.amountDue().subtract(db.allocationDao().forInvoice(invoiceId).sumBd { it.amount })
            if (part > invoiceOpen) {
                throw BusinessException("Montant supérieur au reste à payer de la facture ${inv.number ?: ""} (${invoiceOpen.toPlainString()} Ar).")
            }
            val used = db.allocationDao().forSource(sourceId)

            when (sourceType) {
                AllocationSource.PAYMENT -> {
                    val p = db.paymentDao().get(sourceId) ?: throw BusinessException("Règlement introuvable.")
                    if (p.cancelled) throw BusinessException("Ce règlement est annulé.")
                    val receipt = p.direction == PaymentDirection.RECEIPT
                    val ok = (receipt && inv.kind == InvoiceKind.SALE) || (!receipt && inv.kind == InvoiceKind.PURCHASE)
                    if (!ok) throw BusinessException("Ce règlement ne correspond pas à ce type de facture.")
                    if (p.thirdPartyId != inv.thirdPartyId) throw BusinessException("Le tiers du règlement diffère de celui de la facture.")
                    val regular = regularLeft(p, used).max(BigDecimal.ZERO)
                    val advance = advanceLeft(p, used).max(BigDecimal.ZERO)
                    if (part > regular.add(advance)) {
                        throw BusinessException("Montant supérieur au disponible (${regular.add(advance).toPlainString()} Ar).")
                    }
                    val regularPart = part.min(regular)
                    val advancePart = part.subtract(regularPart)
                    if (regularPart.signum() > 0) {
                        db.allocationDao().insert(Allocation(newId(), companyId, sourceType, sourceId, invoiceId, regularPart, date))
                    }
                    if (advancePart.signum() > 0) {
                        val tiersAcc = accounting.mappingId(companyId, if (receipt) MappingKey.CUSTOMERS else MappingKey.SUPPLIERS)
                        val advanceAcc = accounting.mappingId(
                            companyId, if (receipt) MappingKey.CUSTOMER_ADVANCES else MappingKey.SUPPLIER_ADVANCES
                        )
                        val text = "Avance ${p.number} imputée sur ${inv.number ?: ""}".trim()
                        val lines = if (receipt) listOf(
                            LineInput(advanceAcc, p.thirdPartyId, text, advancePart, BigDecimal.ZERO),
                            LineInput(tiersAcc, p.thirdPartyId, text, BigDecimal.ZERO, advancePart)
                        ) else listOf(
                            LineInput(tiersAcc, p.thirdPartyId, text, advancePart, BigDecimal.ZERO),
                            LineInput(advanceAcc, p.thirdPartyId, text, BigDecimal.ZERO, advancePart)
                        )
                        val journal = accounting.journalOfType(companyId, JournalType.MISC)
                        val entryId = accounting.post(companyId, journal.id, date, p.number, text, lines, EntrySource.ADVANCE, p.id)
                        db.allocationDao().insert(
                            Allocation(
                                newId(), companyId, sourceType, sourceId, invoiceId, advancePart, date,
                                fromAdvance = true, entryId = entryId
                            )
                        )
                    }
                }
                AllocationSource.CREDIT_NOTE -> {
                    val cn = db.invoiceDao().get(sourceId) ?: throw BusinessException("Avoir introuvable.")
                    if (cn.status != DocStatus.VALIDATED) throw BusinessException("L'avoir n'est pas validé.")
                    val ok = (cn.kind == InvoiceKind.SALE_CREDIT && inv.kind == InvoiceKind.SALE) ||
                        (cn.kind == InvoiceKind.PURCHASE_CREDIT && inv.kind == InvoiceKind.PURCHASE)
                    if (!ok) throw BusinessException("Cet avoir ne correspond pas à ce type de facture.")
                    if (cn.thirdPartyId != inv.thirdPartyId) throw BusinessException("Le tiers de l'avoir diffère de celui de la facture.")
                    val remaining = cn.totalTtc.subtract(used.sumBd { it.amount })
                    if (part > remaining) {
                        throw BusinessException("Montant supérieur au disponible (${remaining.toPlainString()} Ar).")
                    }
                    db.allocationDao().insert(Allocation(newId(), companyId, sourceType, sourceId, invoiceId, part, date))
                }
            }
            accounting.audit(companyId, "ALLOCATE", "INVOICE", invoiceId, "${sourceType.name} ${part.toPlainString()}")
        }
    }
}
