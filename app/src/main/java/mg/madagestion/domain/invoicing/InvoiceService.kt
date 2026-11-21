package mg.madagestion.domain.invoicing

import androidx.room.withTransaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import mg.madagestion.core.BusinessException
import mg.madagestion.core.Money
import mg.madagestion.core.newId
import mg.madagestion.core.sumBd
import mg.madagestion.data.AppDatabase
import mg.madagestion.data.model.AllocationSource
import mg.madagestion.data.model.DocStatus
import mg.madagestion.data.model.FeePayer
import mg.madagestion.data.model.Invoice
import mg.madagestion.data.model.InvoiceKind
import mg.madagestion.data.model.InvoiceLine
import mg.madagestion.data.model.JournalType
import mg.madagestion.data.model.PaymentDirection
import mg.madagestion.data.model.PaymentMethod
import mg.madagestion.data.model.Product
import mg.madagestion.data.model.StockMoveType
import mg.madagestion.data.model.ThirdPartyType
import mg.madagestion.domain.accounting.Accounting
import mg.madagestion.domain.accounting.EntrySource
import mg.madagestion.domain.accounting.LineInput
import mg.madagestion.domain.accounting.MappingKey
import mg.madagestion.domain.payment.PaymentService
import mg.madagestion.domain.stock.StockService

data class InvoiceLineInput(
    val productId: String?,
    val accountId: String?,
    val description: String,
    val qty: BigDecimal,
    val unitPrice: BigDecimal,
    val vatRate: BigDecimal
)

/**
 * Transport ou frais annexes saisis avec une facture.
 * @property paidBy COMPANY = l'entreprise paie (sortie de [method]) ; THIRD_PARTY = refacturé au client / facturé par le fournisseur.
 * @property accountId compte choisi ; null = compte par défaut (624 pour une charge, 708 pour des frais refacturés).
 */
data class InvoiceFeeInput(
    val label: String,
    val amount: BigDecimal,
    val paidBy: FeePayer,
    val method: PaymentMethod?,
    val accountId: String?
)

/**
 * Cycle facture : brouillon modifiable -> validation (numéro + écriture + stock) -> verrouillée.
 * Une facture validée ne se modifie jamais : on la corrige par un avoir.
 *
 * Écritures générées à la validation :
 *  - vente : D 411 (et D 419 pour l'avance déduite) / C 707 ou 706, C TVA collectée ;
 *  - achat : D 607 ou 606, D TVA déductible / C 401 (et C 409 pour l'avance déduite) ;
 *    un article stocké entre en stock par D 311 / C 6037 ;
 *  - frais annexes : D 624 / C trésorerie si l'entreprise paie ; sinon portés au compte du tiers
 *    (C 708 refacturé au client, ou D 624 facturé par le fournisseur) ;
 *  - avoir avec retour de marchandises : les articles reviennent en stock (ou en sortent pour un retour
 *    fournisseur), et l'avoir est imputé d'office sur sa facture d'origine.
 */
class InvoiceService(
    private val db: AppDatabase,
    private val accounting: Accounting,
    private val stock: StockService,
    private val payments: PaymentService
) {

    private fun prefix(kind: InvoiceKind) = when (kind) {
        InvoiceKind.SALE -> "FV"
        InvoiceKind.PURCHASE -> "FA"
        InvoiceKind.SALE_CREDIT -> "AV"
        InvoiceKind.PURCHASE_CREDIT -> "AA"
    }

    suspend fun saveDraft(
        companyId: String,
        invoiceId: String?,
        kind: InvoiceKind,
        thirdPartyId: String,
        date: LocalDate,
        dueDate: LocalDate,
        reference: String,
        note: String,
        originInvoiceId: String?,
        lines: List<InvoiceLineInput>,
        fee: InvoiceFeeInput? = null,
        returnsGoods: Boolean = false
    ): String = db.withTransaction {
        if (lines.isEmpty()) throw BusinessException("Ajoutez au moins une ligne.")
        val tp = db.thirdPartyDao().get(thirdPartyId) ?: throw BusinessException("Choisissez un tiers.")
        val expected = if (kind.isSale()) ThirdPartyType.CUSTOMER else ThirdPartyType.SUPPLIER
        if (tp.type != expected) throw BusinessException("Le tiers choisi ne convient pas à ce type de document.")
        if (dueDate.isBefore(date)) throw BusinessException("L'échéance ne peut pas précéder la date du document.")

        val feeAmount = Money.ar(fee?.amount ?: BigDecimal.ZERO)
        if (feeAmount.signum() < 0) throw BusinessException("Le montant des frais ne peut pas être négatif.")
        val hasFee = fee != null && feeAmount.signum() > 0
        if (hasFee && kind.isCredit()) throw BusinessException("Un avoir ne porte pas de frais annexes.")
        if (hasFee && fee!!.paidBy == FeePayer.COMPANY && fee.method == null) {
            throw BusinessException("Indiquez comment l'entreprise a payé les frais (espèces, banque, Mobile Money).")
        }
        if (hasFee && fee!!.accountId != null) {
            val a = db.accountDao().get(fee.accountId)
            if (a == null || a.companyId != companyId) throw BusinessException("Compte des frais inconnu.")
        }

        val existing = invoiceId?.let { db.invoiceDao().get(it) }
        if (existing != null && existing.status != DocStatus.DRAFT) {
            throw BusinessException("Un document validé n'est pas modifiable : créez un avoir.")
        }
        val id = existing?.id ?: newId()

        var totalHt = BigDecimal.ZERO
        var totalVat = BigDecimal.ZERO
        val entities = lines.mapIndexed { i, l ->
            val n = i + 1
            val qty = Money.qty(l.qty)
            if (qty.signum() <= 0) throw BusinessException("Ligne $n : la quantité doit être positive.")
            if (l.unitPrice.signum() < 0) throw BusinessException("Ligne $n : le prix ne peut pas être négatif.")
            if (l.vatRate.signum() < 0 || l.vatRate > BigDecimal(100)) {
                throw BusinessException("Ligne $n : taux de TVA invalide.")
            }
            if (l.description.isBlank() && l.productId == null) throw BusinessException("Ligne $n : décrivez la ligne ou choisissez un article.")
            val ht = Money.ar(qty.multiply(l.unitPrice))
            val vat = Money.ar(ht.multiply(l.vatRate).divide(BigDecimal(100), 2, RoundingMode.HALF_UP))
            totalHt = totalHt.add(ht)
            totalVat = totalVat.add(vat)
            InvoiceLine(
                id = newId(), invoiceId = id, companyId = companyId, position = i,
                productId = l.productId, accountId = l.accountId, description = l.description.trim(),
                qty = qty, unitPrice = l.unitPrice, vatRate = l.vatRate, totalHt = ht, totalVat = vat
            )
        }

        val base = existing ?: Invoice(
            id = id, companyId = companyId, kind = kind, thirdPartyId = thirdPartyId, date = date, dueDate = dueDate
        )
        val invoice = base.copy(
            thirdPartyId = thirdPartyId, date = date, dueDate = dueDate,
            reference = reference.trim(), note = note.trim(),
            originInvoiceId = originInvoiceId ?: base.originInvoiceId,
            totalHt = totalHt, totalVat = totalVat, totalTtc = totalHt.add(totalVat),
            feeLabel = if (hasFee) fee!!.label.trim() else "",
            feeAmount = if (hasFee) feeAmount else BigDecimal.ZERO,
            feeAccountId = if (hasFee) fee!!.accountId else null,
            feePaidBy = if (hasFee) fee!!.paidBy else null,
            feeMethod = if (hasFee && fee!!.paidBy == FeePayer.COMPANY) fee.method else null,
            returnsGoods = kind.isCredit() && returnsGoods
        )
        if (existing == null) {
            db.invoiceDao().insert(invoice)
        } else {
            db.invoiceDao().update(invoice)
            db.invoiceDao().deleteLines(id)
        }
        db.invoiceDao().insertLines(entities)
        accounting.audit(companyId, if (existing == null) "INVOICE_CREATE" else "INVOICE_UPDATE", "INVOICE", id)
        id
    }

    suspend fun deleteDraft(invoiceId: String) {
        db.withTransaction {
            val inv = db.invoiceDao().get(invoiceId) ?: throw BusinessException("Document introuvable.")
            if (inv.status != DocStatus.DRAFT) throw BusinessException("Seul un brouillon peut être supprimé.")
            db.invoiceDao().delete(invoiceId)
            accounting.audit(inv.companyId, "INVOICE_DELETE_DRAFT", "INVOICE", invoiceId)
        }
    }

    /**
     * Prépare un avoir à partir d'une facture validée (lignes recopiées, à ajuster avant validation).
     * Par défaut, les articles stockés sont traités comme retournés.
     */
    suspend fun createCreditDraft(originId: String): String = db.withTransaction {
        val origin = db.invoiceDao().get(originId) ?: throw BusinessException("Facture introuvable.")
        if (origin.status != DocStatus.VALIDATED) throw BusinessException("Seule une facture validée peut donner lieu à un avoir.")
        if (origin.kind.isCredit()) throw BusinessException("On ne crée pas d'avoir sur un avoir.")
        val creditKind = if (origin.kind == InvoiceKind.SALE) InvoiceKind.SALE_CREDIT else InvoiceKind.PURCHASE_CREDIT
        val lines = db.invoiceDao().lines(originId).map {
            InvoiceLineInput(it.productId, null, it.description, it.qty, it.unitPrice, it.vatRate)
        }
        val today = LocalDate.now()
        saveDraft(
            origin.companyId, null, creditKind, origin.thirdPartyId, today, today,
            "Avoir sur ${origin.number ?: ""}".trim(), "", origin.id, lines,
            fee = null, returnsGoods = true
        )
    }

    /**
     * Valide une facture réglée au comptant : la facture et le règlement du reste dû (après avance déduite)
     * sont enregistrés ensemble (tout ou rien), et le règlement est imputé sur la facture.
     */
    suspend fun validateAndSettle(invoiceId: String, method: PaymentMethod, useAdvance: Boolean = true) {
        db.withTransaction {
            val draft = db.invoiceDao().get(invoiceId) ?: throw BusinessException("Document introuvable.")
            if (draft.kind.isCredit()) throw BusinessException("Un avoir ne se règle pas au comptant : imputez-le sur une facture.")
            validate(invoiceId, useAdvance)
            val inv = db.invoiceDao().get(invoiceId) ?: throw BusinessException("Document introuvable.")
            val open = payments.openAmount(invoiceId)
            if (open.signum() > 0) {
                payments.record(
                    companyId = inv.companyId,
                    direction = if (inv.kind.isSale()) PaymentDirection.RECEIPT else PaymentDirection.DISBURSEMENT,
                    thirdPartyId = inv.thirdPartyId,
                    date = inv.date,
                    amount = open,
                    method = method,
                    reference = "Comptant ${inv.number ?: ""}".trim(),
                    allocations = listOf(inv.id to open)
                )
            }
        }
    }

    /** Quantités par article des lignes d'un document. */
    private suspend fun qtyByProduct(invoiceId: String): Map<String, BigDecimal> =
        db.invoiceDao().lines(invoiceId)
            .filter { it.productId != null }
            .groupBy { it.productId!! }
            .mapValues { (_, ls) -> ls.sumBd { it.qty } }

    /** Coût unitaire auquel un article est sorti du stock pour la facture [originId] ; à défaut, CUMP ou prix d'achat. */
    private suspend fun returnUnitCost(product: Product, originId: String?): BigDecimal {
        if (originId != null) {
            val moves = db.stockDao().bySource(EntrySource.INVOICE, originId).filter { it.productId == product.id }
            val qty = moves.sumBd { it.qty.abs() }
            if (qty.signum() > 0) return moves.sumBd { it.value.abs() }.divide(qty, 6, RoundingMode.HALF_UP)
        }
        return if (product.qtyOnHand.signum() > 0) product.stockValue.divide(product.qtyOnHand, 6, RoundingMode.HALF_UP)
        else product.purchasePrice
    }

    /** Valide un brouillon. @param useAdvance déduire les avances disponibles du tiers (factures uniquement). */
    suspend fun validate(invoiceId: String, useAdvance: Boolean = true) {
        db.withTransaction {
            val inv = db.invoiceDao().get(invoiceId) ?: throw BusinessException("Document introuvable.")
            if (inv.status != DocStatus.DRAFT) throw BusinessException("Ce document est déjà validé.")
            val c = inv.companyId
            val lines = db.invoiceDao().lines(invoiceId)
            if (lines.isEmpty()) throw BusinessException("Le document ne comporte aucune ligne.")
            if (inv.totalTtc.signum() <= 0) throw BusinessException("Le total du document doit être positif.")
            val tp = db.thirdPartyDao().get(inv.thirdPartyId) ?: throw BusinessException("Tiers introuvable.")
            accounting.requireOpenPeriod(c, inv.date)

            val credit = inv.kind.isCredit()
            val returns = credit && inv.returnsGoods
            val origin = if (credit) inv.originInvoiceId?.let { db.invoiceDao().get(it) } else null

            // Un avoir ne peut pas dépasser la facture d'origine (en tenant compte des avoirs déjà validés).
            if (origin != null) {
                val others = db.invoiceDao().creditsOf(origin.id).filter { it.status == DocStatus.VALIDATED && it.id != inv.id }
                if (others.sumBd { it.totalTtc }.add(inv.totalTtc) > origin.totalTtc) {
                    throw BusinessException("Les avoirs dépasseraient le montant de la facture d'origine ${origin.number ?: ""}.")
                }
                // Un retour ne peut pas dépasser les quantités facturées, moins celles déjà retournées.
                if (returns) {
                    val invoiced = qtyByProduct(origin.id)
                    val already = HashMap<String, BigDecimal>()
                    for (o in others.filter { it.returnsGoods }) {
                        for ((pid, q) in qtyByProduct(o.id)) already[pid] = (already[pid] ?: BigDecimal.ZERO).add(q)
                    }
                    for ((pid, q) in qtyByProduct(inv.id)) {
                        val product = db.productDao().get(pid) ?: continue
                        if (!product.isStocked) continue
                        val left = (invoiced[pid] ?: BigDecimal.ZERO).subtract(already[pid] ?: BigDecimal.ZERO)
                        if (q > left) {
                            throw BusinessException(
                                "Retour de « ${product.name} » : ${q.stripTrailingZeros().toPlainString()} demandé, " +
                                    "${left.stripTrailingZeros().toPlainString()} au plus (facturé moins déjà retourné)."
                            )
                        }
                    }
                }
            }

            val sale = inv.kind.isSale()
            val tiersDebit = inv.kind == InvoiceKind.SALE || inv.kind == InvoiceKind.PURCHASE_CREDIT
            val otherDebit = !tiersDebit
            val tiersAcc = accounting.mappingId(c, if (sale) MappingKey.CUSTOMERS else MappingKey.SUPPLIERS)

            fun line(accountId: String, amount: BigDecimal, debit: Boolean, tpId: String? = null, label: String = "") =
                if (debit) LineInput(accountId, tpId, label, amount, BigDecimal.ZERO)
                else LineInput(accountId, tpId, label, BigDecimal.ZERO, amount)

            // Montants HT regroupés par compte de produit / charge
            val buckets = LinkedHashMap<String, BigDecimal>()
            val stockQty = LinkedHashMap<String, BigDecimal>() // article -> quantité à sortir ou à recevoir
            val stockValue = LinkedHashMap<String, BigDecimal>() // article -> valeur d'entrée (achats)
            for (l in lines) {
                val product = l.productId?.let { db.productDao().get(it) }
                val stocked = product?.isStocked == true
                val moves = stocked && (!credit || returns)
                val accountId: String = when (inv.kind) {
                    InvoiceKind.SALE ->
                        l.accountId ?: accounting.mappingId(c, if (stocked) MappingKey.SALES_GOODS else MappingKey.SALES_SERVICES)
                    InvoiceKind.SALE_CREDIT ->
                        l.accountId ?: accounting.mappingId(c, if (stocked && returns) MappingKey.SALES_GOODS else MappingKey.SALES_DISCOUNTS)
                    InvoiceKind.PURCHASE ->
                        if (stocked) accounting.mappingId(c, MappingKey.PURCHASES_GOODS)
                        else l.accountId ?: accounting.mappingId(c, MappingKey.PURCHASES_OTHER)
                    InvoiceKind.PURCHASE_CREDIT ->
                        l.accountId ?: accounting.mappingId(c, if (stocked && returns) MappingKey.PURCHASES_GOODS else MappingKey.PURCHASE_DISCOUNTS)
                }
                if (l.totalHt.signum() != 0) {
                    buckets[accountId] = (buckets[accountId] ?: BigDecimal.ZERO).add(l.totalHt)
                }
                if (moves && product != null) {
                    stockQty[product.id] = (stockQty[product.id] ?: BigDecimal.ZERO).add(l.qty)
                    stockValue[product.id] = (stockValue[product.id] ?: BigDecimal.ZERO).add(l.totalHt)
                }
            }

            val year = inv.date.year
            val seq = accounting.nextNumber(c, "INV:${prefix(inv.kind)}:$year")
            val number = "${prefix(inv.kind)}-$year-${seq.toString().padStart(5, '0')}"

            // Avances du tiers déduites de la facture (419 client / 409 fournisseur)
            val due = inv.amountDue()
            val advanceParts = if (useAdvance && !credit) {
                val direction = if (sale) PaymentDirection.RECEIPT else PaymentDirection.DISBURSEMENT
                takeInOrder(payments.advancesOf(c, tp.id, direction), due)
            } else emptyList()
            val advanceTotal = advanceParts.sumBd { it.second }

            val entryLines = mutableListOf<LineInput>()
            val tiersPart = due.subtract(advanceTotal)
            if (tiersPart.signum() > 0) entryLines += line(tiersAcc, tiersPart, tiersDebit, tp.id, "${tp.name} — $number")
            if (advanceTotal.signum() > 0) {
                val advanceAcc = accounting.mappingId(c, if (sale) MappingKey.CUSTOMER_ADVANCES else MappingKey.SUPPLIER_ADVANCES)
                entryLines += line(advanceAcc, advanceTotal, tiersDebit, tp.id, "Avance déduite — ${tp.name} — $number")
            }
            for ((accountId, amount) in buckets) entryLines += line(accountId, amount, otherDebit)
            if (inv.totalVat.signum() > 0) {
                val vatAcc = accounting.mappingId(c, if (sale) MappingKey.VAT_COLLECTED else MappingKey.VAT_DEDUCTIBLE)
                entryLines += line(vatAcc, inv.totalVat, otherDebit)
            }

            // Transport et frais annexes
            if (inv.feeAmount.signum() > 0 && inv.feePaidBy != null) {
                val text = inv.feeLabel.ifBlank { "Transport et frais annexes" }
                val rebilled = sale && inv.feePaidBy == FeePayer.THIRD_PARTY
                val feeAcc = inv.feeAccountId
                    ?: accounting.mappingId(c, if (rebilled) MappingKey.CUSTOMER_CHARGES else MappingKey.SUPPLIER_CHARGES)
                when (inv.feePaidBy) {
                    // Déjà compris dans le montant dû par le client (D 411) ou au fournisseur (C 401)
                    FeePayer.THIRD_PARTY -> entryLines += line(feeAcc, inv.feeAmount, !rebilled, null, text)
                    FeePayer.COMPANY -> {
                        val method = inv.feeMethod ?: PaymentMethod.CASH
                        payments.requireCash(c, method, inv.feeAmount)
                        val treasuryAcc = accounting.mappingId(c, payments.treasuryKey(method))
                        entryLines += line(feeAcc, inv.feeAmount, true, null, text)
                        entryLines += line(treasuryAcc, inv.feeAmount, false, null, "$text payés par l'entreprise")
                    }
                }
            }

            // Mouvements de stock (inventaire permanent, compte 311 contre 6037)
            var stockIn = BigDecimal.ZERO  // entrées valorisées : D 311 / C 6037
            var stockOut = BigDecimal.ZERO // sorties valorisées : D 6037 / C 311
            when (inv.kind) {
                InvoiceKind.SALE -> {
                    for ((productId, qty) in stockQty) {
                        stockOut = stockOut.add(
                            stock.issue(productId, inv.date, qty, StockMoveType.ISSUE, EntrySource.INVOICE, inv.id, number)
                        )
                    }
                }
                InvoiceKind.PURCHASE -> {
                    for ((productId, qty) in stockQty) {
                        val value = stockValue[productId] ?: BigDecimal.ZERO
                        stock.receive(productId, inv.date, qty, value, StockMoveType.RECEIPT, EntrySource.INVOICE, inv.id, number)
                        stockIn = stockIn.add(value)
                    }
                }
                InvoiceKind.SALE_CREDIT -> {
                    // Retour client : l'article revient au coût auquel il était sorti
                    for ((productId, qty) in stockQty) {
                        val product = db.productDao().get(productId) ?: continue
                        val value = Money.ar(qty.multiply(returnUnitCost(product, origin?.id)))
                        stock.receive(productId, inv.date, qty, value, StockMoveType.RECEIPT, EntrySource.INVOICE, inv.id, "Retour $number")
                        stockIn = stockIn.add(value)
                    }
                }
                InvoiceKind.PURCHASE_CREDIT -> {
                    // Retour fournisseur : l'article sort au CUMP
                    for ((productId, qty) in stockQty) {
                        stockOut = stockOut.add(
                            stock.issue(productId, inv.date, qty, StockMoveType.ISSUE, EntrySource.INVOICE, inv.id, "Retour $number")
                        )
                    }
                }
            }
            if (stockIn.signum() > 0 || stockOut.signum() > 0) {
                val stockAccount = accounting.mappingId(c, MappingKey.STOCK)
                val variation = accounting.mappingId(c, MappingKey.STOCK_VARIATION)
                if (stockIn.signum() > 0) {
                    entryLines += line(stockAccount, stockIn, true, null, "Entrée en stock")
                    entryLines += line(variation, stockIn, false, null, "Entrée en stock")
                }
                if (stockOut.signum() > 0) {
                    entryLines += line(variation, stockOut, true, null, "Sortie de stock")
                    entryLines += line(stockAccount, stockOut, false, null, "Sortie de stock")
                }
            }

            val journal = accounting.journalOfType(c, if (sale) JournalType.SALES else JournalType.PURCHASES)
            val entryId = accounting.post(
                companyId = c,
                journalId = journal.id,
                date = inv.date,
                reference = number,
                label = "${inv.kind.label()} $number — ${tp.name}",
                lines = entryLines,
                sourceType = EntrySource.INVOICE,
                sourceId = inv.id
            )
            db.stockDao().linkEntry(EntrySource.INVOICE, inv.id, entryId)
            db.invoiceDao().update(
                inv.copy(number = number, status = DocStatus.VALIDATED, entryId = entryId, validatedAt = System.currentTimeMillis())
            )
            for ((paymentId, amount) in advanceParts) {
                payments.attachAdvance(c, paymentId, inv.id, amount, inv.date, entryId)
            }

            // Avoir : imputé d'office sur sa facture d'origine, qui est ainsi soldée à concurrence de l'avoir.
            if (origin != null && origin.status == DocStatus.VALIDATED && origin.thirdPartyId == inv.thirdPartyId) {
                val part = payments.openAmount(origin.id).min(inv.totalTtc)
                if (part.signum() > 0) {
                    payments.allocate(c, AllocationSource.CREDIT_NOTE, inv.id, origin.id, part, inv.date)
                }
            }
            accounting.audit(c, "INVOICE_VALIDATE", "INVOICE", inv.id, number)
        }
    }
}
