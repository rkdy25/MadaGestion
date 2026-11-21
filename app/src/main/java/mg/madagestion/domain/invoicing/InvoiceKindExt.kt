package mg.madagestion.domain.invoicing

import mg.madagestion.data.model.InvoiceKind

fun InvoiceKind.isSale(): Boolean = this == InvoiceKind.SALE || this == InvoiceKind.SALE_CREDIT

fun InvoiceKind.isCredit(): Boolean = this == InvoiceKind.SALE_CREDIT || this == InvoiceKind.PURCHASE_CREDIT

fun InvoiceKind.label(): String = when (this) {
    InvoiceKind.SALE -> "Facture client"
    InvoiceKind.PURCHASE -> "Facture fournisseur"
    InvoiceKind.SALE_CREDIT -> "Avoir client"
    InvoiceKind.PURCHASE_CREDIT -> "Avoir fournisseur"
}
