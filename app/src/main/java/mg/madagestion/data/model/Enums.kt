package mg.madagestion.data.model

enum class EntryStatus { DRAFT, VALIDATED }

enum class JournalType { PURCHASES, SALES, BANK, CASH, MOBILE, MISC, OPENING }

enum class ThirdPartyType { CUSTOMER, SUPPLIER }

enum class InvoiceKind { SALE, PURCHASE, SALE_CREDIT, PURCHASE_CREDIT }

enum class DocStatus { DRAFT, VALIDATED }

enum class PaymentDirection { RECEIPT, DISBURSEMENT }

enum class PaymentMethod { CASH, BANK, MOBILE_MONEY }

enum class StockMoveType { RECEIPT, ISSUE, ADJUSTMENT }

enum class AllocationSource { PAYMENT, CREDIT_NOTE }

/**
 * Qui supporte le transport ou les frais annexes saisis sur une facture.
 *  - COMPANY : l'entreprise paie elle-même (sortie de trésorerie) ;
 *  - THIRD_PARTY : le montant passe par le compte du tiers (refacturé au client, ou facturé par le fournisseur).
 */
enum class FeePayer { COMPANY, THIRD_PARTY }
