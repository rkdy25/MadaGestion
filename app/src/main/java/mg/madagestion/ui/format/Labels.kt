package mg.madagestion.ui.format

import mg.madagestion.data.model.EntryStatus
import mg.madagestion.data.model.PaymentDirection
import mg.madagestion.data.model.PaymentMethod
import mg.madagestion.data.model.StockMoveType
import mg.madagestion.data.model.ThirdParty
import mg.madagestion.domain.accounting.EntrySource
import mg.madagestion.domain.party.PartyMoveKind

fun PaymentMethod.label(): String = when (this) {
    PaymentMethod.CASH -> "Espèces"
    PaymentMethod.BANK -> "Banque"
    PaymentMethod.MOBILE_MONEY -> "Mobile Money"
}

fun StockMoveType.label(): String = when (this) {
    StockMoveType.RECEIPT -> "Entrée"
    StockMoveType.ISSUE -> "Sortie"
    StockMoveType.ADJUSTMENT -> "Ajustement"
}

fun EntryStatus.label(): String = when (this) {
    EntryStatus.DRAFT -> "Brouillon"
    EntryStatus.VALIDATED -> "Validée"
}

fun List<ThirdParty>.nameOf(id: String?): String = firstOrNull { it.id == id }?.name ?: "—"

fun directionLabel(d: PaymentDirection): String =
    if (d == PaymentDirection.RECEIPT) "Encaissement" else "Décaissement"

fun PartyMoveKind.label(): String = when (this) {
    PartyMoveKind.OPENING -> "Situation initiale"
    PartyMoveKind.INVOICE -> "Facture"
    PartyMoveKind.CREDIT_NOTE -> "Avoir"
    PartyMoveKind.PAYMENT -> "Règlement"
    PartyMoveKind.CHARGE -> "Frais"
    PartyMoveKind.DISCOUNT -> "Ristourne"
    PartyMoveKind.OTHER -> "Écriture"
}

/** Libellé de l'origine d'une écriture générée automatiquement. */
fun entrySourceLabel(sourceType: String?): String = when (sourceType) {
    EntrySource.INVOICE -> "Facture ou avoir"
    EntrySource.PAYMENT -> "Règlement"
    EntrySource.STOCK -> "Ajustement de stock"
    EntrySource.OPENING -> "Situation initiale"
    EntrySource.PARTY_OPERATION -> "Frais ou ristourne sur tiers"
    EntrySource.ADVANCE -> "Imputation d'une avance"
    else -> "Saisie manuelle"
}
