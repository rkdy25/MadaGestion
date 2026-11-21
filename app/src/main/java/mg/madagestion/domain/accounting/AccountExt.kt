package mg.madagestion.domain.accounting

import mg.madagestion.data.model.Account

/** Classe du plan comptable : premier chiffre du code (1 à 5 = bilan, 6 = charges, 7 = produits). */
fun Account.accountClass(): Char = code.firstOrNull() ?: '0'
