package mg.madagestion.domain.accounting

/** Rôles comptables utilisés par le moteur. Chacun pointe vers un compte réglable dans les Paramètres. */
enum class MappingKey(val label: String, val defaultCode: String) {
    CUSTOMERS("Clients (compte collectif)", "411"),
    SUPPLIERS("Fournisseurs (compte collectif)", "401"),
    CUSTOMER_ADVANCES("Avances reçues des clients", "419"),
    SUPPLIER_ADVANCES("Avances versées aux fournisseurs", "409"),
    VAT_COLLECTED("TVA collectée", "4457"),
    VAT_DEDUCTIBLE("TVA déductible", "4456"),
    SALES_GOODS("Ventes de marchandises", "707"),
    SALES_SERVICES("Prestations de services", "706"),
    SALES_DISCOUNTS("Rabais accordés (avoirs clients)", "709"),
    PURCHASES_GOODS("Achats de marchandises", "607"),
    PURCHASES_OTHER("Achats non stockés (par défaut)", "606"),
    PURCHASE_DISCOUNTS("Rabais obtenus (avoirs fournisseurs)", "609"),
    STOCK("Stock de marchandises", "311"),
    STOCK_VARIATION("Variation de stock (coût des ventes)", "6037"),
    CASH("Caisse", "531"),
    BANK("Banque", "512"),
    MOBILE("Mobile Money", "532"),
    CUSTOMER_CHARGES("Frais refacturés aux clients (transport, manutention…)", "708"),
    SUPPLIER_CHARGES("Transport et frais annexes (charge)", "624")
}
