package mg.madagestion.domain.invoicing

import java.math.BigDecimal
import mg.madagestion.data.model.FeePayer
import mg.madagestion.data.model.Invoice

/** Frais annexes portés au compte du tiers (refacturés au client, ou facturés par le fournisseur). */
fun Invoice.feeOnThirdParty(): BigDecimal =
    if (feePaidBy == FeePayer.THIRD_PARTY) feeAmount else BigDecimal.ZERO

/** Frais annexes payés directement par l'entreprise (sortie de trésorerie). */
fun Invoice.feeOnCompany(): BigDecimal =
    if (feePaidBy == FeePayer.COMPANY) feeAmount else BigDecimal.ZERO

/**
 * Montant dû par le client (ou au fournisseur) : TTC plus les frais portés à son compte.
 * C'est ce montant qui est réglé, imputé et suivi en « reste à régler ».
 */
fun Invoice.amountDue(): BigDecimal = totalTtc.add(feeOnThirdParty())

/**
 * Répartit [wanted] sur des disponibilités prises dans l'ordre (les plus anciennes d'abord).
 * @return les parts prélevées (identifiant, montant), dont la somme vaut au plus [wanted].
 */
fun takeInOrder(available: List<Pair<String, BigDecimal>>, wanted: BigDecimal): List<Pair<String, BigDecimal>> {
    var rest = wanted
    val out = mutableListOf<Pair<String, BigDecimal>>()
    for ((id, amount) in available) {
        if (rest.signum() <= 0) break
        if (amount.signum() <= 0) continue
        val part = if (amount < rest) amount else rest
        out += id to part
        rest = rest.subtract(part)
    }
    return out
}
