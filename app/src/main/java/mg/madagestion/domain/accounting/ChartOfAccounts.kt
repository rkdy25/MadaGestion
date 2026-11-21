package mg.madagestion.domain.accounting

import mg.madagestion.data.model.JournalType

/**
 * Extrait indicatif inspiré du PCG 2005 (Madagascar).
 * À faire valider par votre expert-comptable puis à adapter dans Comptabilité > Plan comptable.
 */
object ChartOfAccounts {
    val pcg: List<Pair<String, String>> = listOf(
        // Classe 1 — Capitaux
        "101" to "Capital",
        "106" to "Réserves",
        "110" to "Report à nouveau (solde créditeur)",
        "119" to "Report à nouveau (solde débiteur)",
        "120" to "Résultat de l'exercice (bénéfice)",
        "129" to "Résultat de l'exercice (perte)",
        "164" to "Emprunts auprès des établissements de crédit",
        // Classe 2 — Immobilisations
        "205" to "Logiciels",
        "213" to "Constructions",
        "215" to "Matériel et outillage",
        "2182" to "Matériel de transport",
        "2183" to "Matériel de bureau et informatique",
        "2184" to "Mobilier de bureau",
        "281" to "Amortissements des immobilisations",
        // Classe 3 — Stocks
        "311" to "Marchandises",
        "321" to "Matières premières et fournitures",
        // Classe 4 — Tiers
        "401" to "Fournisseurs",
        "409" to "Fournisseurs débiteurs (avances et acomptes versés)",
        "411" to "Clients",
        "419" to "Clients créditeurs (avances et acomptes reçus)",
        "421" to "Personnel — rémunérations dues",
        "431" to "Organismes sociaux (CNaPS, OSTIE)",
        "4455" to "TVA à décaisser",
        "4456" to "TVA déductible",
        "4457" to "TVA collectée",
        "447" to "Autres impôts, taxes et versements assimilés",
        "471" to "Compte d'attente",
        // Classe 5 — Trésorerie
        "512" to "Banques",
        "531" to "Caisse",
        "532" to "Mobile Money (MVola, Orange Money, Airtel Money)",
        // Classe 6 — Charges
        "601" to "Achats de matières premières",
        "603" to "Variations des stocks",
        "6037" to "Variation des stocks de marchandises",
        "606" to "Achats non stockés (eau, énergie, fournitures)",
        "607" to "Achats de marchandises",
        "609" to "Rabais, remises et ristournes obtenus sur achats",
        "611" to "Sous-traitance générale",
        "613" to "Locations",
        "615" to "Entretien et réparations",
        "616" to "Primes d'assurances",
        "622" to "Honoraires",
        "623" to "Publicité",
        "624" to "Transports de biens et manutention",
        "625" to "Déplacements et missions",
        "626" to "Frais postaux et télécommunications",
        "627" to "Services bancaires",
        "635" to "Autres impôts et taxes",
        "641" to "Rémunérations du personnel",
        "645" to "Charges sociales",
        "661" to "Charges d'intérêts",
        "681" to "Dotations aux amortissements",
        "695" to "Impôts sur les résultats",
        // Classe 7 — Produits
        "701" to "Ventes de produits finis",
        "706" to "Prestations de services",
        "707" to "Ventes de marchandises",
        "708" to "Produits des activités annexes",
        "709" to "Rabais, remises et ristournes accordés",
        "761" to "Produits financiers"
    )

    val journals: List<Triple<String, String, JournalType>> = listOf(
        Triple("AC", "Journal des achats", JournalType.PURCHASES),
        Triple("VE", "Journal des ventes", JournalType.SALES),
        Triple("BQ", "Journal de banque", JournalType.BANK),
        Triple("CA", "Journal de caisse", JournalType.CASH),
        Triple("MM", "Journal Mobile Money", JournalType.MOBILE),
        Triple("OD", "Opérations diverses", JournalType.MISC),
        Triple("AN", "À nouveaux (ouverture)", JournalType.OPENING)
    )
}
