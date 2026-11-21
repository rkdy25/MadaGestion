package mg.madagestion.domain.accounting

/**
 * Origine d'une écriture générée automatiquement (champ `sourceType` de l'écriture).
 * Une écriture qui porte une origine ne se contrepasse pas à la main : on corrige le document.
 */
object EntrySource {
    const val INVOICE = "INVOICE"
    const val PAYMENT = "PAYMENT"
    const val STOCK = "STOCK"

    /** Situation initiale (bilan d'ouverture). */
    const val OPENING = "OPENING"

    /** Frais ou ristourne porté au compte d'un client ou d'un fournisseur. */
    const val PARTY_OPERATION = "PARTY_OP"

    /** Avance (419 / 409) reportée sur le compte du tiers lors de son imputation sur une facture. */
    const val ADVANCE = "ADVANCE"
}
