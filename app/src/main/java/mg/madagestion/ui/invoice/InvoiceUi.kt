package mg.madagestion.ui.invoice

import androidx.compose.ui.graphics.Color
import java.math.BigDecimal
import java.time.LocalDate
import mg.madagestion.core.sumBd
import mg.madagestion.data.model.Allocation
import mg.madagestion.data.model.DocStatus
import mg.madagestion.data.model.Invoice
import mg.madagestion.data.model.InvoiceKind
import mg.madagestion.data.model.Payment
import mg.madagestion.data.model.PaymentDirection
import mg.madagestion.domain.invoicing.amountDue
import mg.madagestion.ui.theme.Danger
import mg.madagestion.ui.theme.Good
import mg.madagestion.ui.theme.Muted
import mg.madagestion.ui.theme.Teal
import mg.madagestion.ui.theme.Warning

/** Reste à régler de chaque facture (client ou fournisseur) validée. Les avoirs n'y figurent pas. */
fun openAmounts(invoices: List<Invoice>, allocations: List<Allocation>): Map<String, BigDecimal> {
    val paid = allocations.groupBy { it.invoiceId }.mapValues { (_, l) -> l.sumBd { it.amount } }
    return invoices
        .filter { it.status == DocStatus.VALIDATED && (it.kind == InvoiceKind.SALE || it.kind == InvoiceKind.PURCHASE) }
        .associate { it.id to it.amountDue().subtract(paid[it.id] ?: BigDecimal.ZERO) }
}

/** Montant de chaque avoir validé qui n'est pas encore imputé sur une facture. */
fun creditsAvailable(invoices: List<Invoice>, allocations: List<Allocation>): Map<String, BigDecimal> {
    val used = allocations.groupBy { it.sourceId }.mapValues { (_, l) -> l.sumBd { it.amount } }
    return invoices
        .filter { it.status == DocStatus.VALIDATED && (it.kind == InvoiceKind.SALE_CREDIT || it.kind == InvoiceKind.PURCHASE_CREDIT) }
        .associate { it.id to it.totalTtc.subtract(used[it.id] ?: BigDecimal.ZERO) }
}

/** Avance encore disponible d'un tiers (419 pour un client, 409 pour un fournisseur). */
fun advanceAvailable(
    payments: List<Payment>,
    allocations: List<Allocation>,
    thirdPartyId: String?,
    direction: PaymentDirection
): BigDecimal {
    if (thirdPartyId == null) return BigDecimal.ZERO
    val usedFromAdvance = allocations.filter { it.fromAdvance }.groupBy { it.sourceId }
        .mapValues { (_, l) -> l.sumBd { it.amount } }
    return payments
        .filter { !it.cancelled && it.thirdPartyId == thirdPartyId && it.direction == direction }
        .sumBd { it.advance.subtract(usedFromAdvance[it.id] ?: BigDecimal.ZERO).max(BigDecimal.ZERO) }
}

/**
 * Statut affiché d'un document.
 * @param open reste à régler (factures) ; null pour un avoir.
 * @param creditLeft part d'un avoir non encore imputée.
 */
fun invoiceStatus(inv: Invoice, open: BigDecimal?, creditLeft: BigDecimal? = null): Pair<String, Color> {
    if (inv.status == DocStatus.DRAFT) return "Brouillon" to Warning
    if (open == null) {
        // Avoir
        return if (creditLeft != null && creditLeft.signum() <= 0) "Soldé" to Good else "À imputer" to Teal
    }
    return when {
        open.signum() <= 0 -> "Soldée" to Good
        inv.dueDate.isBefore(LocalDate.now()) -> "En retard" to Danger
        open < inv.amountDue() -> "Partielle" to Teal
        else -> "À régler" to Muted
    }
}
