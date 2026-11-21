package mg.madagestion.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.time.LocalDate

@Entity(tableName = "invoices", indices = [Index("companyId"), Index("thirdPartyId")])
data class Invoice(
    @PrimaryKey val id: String,
    val companyId: String,
    val kind: InvoiceKind,
    val number: String? = null,
    val thirdPartyId: String,
    val date: LocalDate,
    val dueDate: LocalDate,
    /** Référence externe (n° de la facture du fournisseur, bon de commande client…). */
    val reference: String = "",
    val note: String = "",
    val status: DocStatus = DocStatus.DRAFT,
    val totalHt: BigDecimal = BigDecimal.ZERO,
    val totalVat: BigDecimal = BigDecimal.ZERO,
    val totalTtc: BigDecimal = BigDecimal.ZERO,
    val entryId: String? = null,
    val originInvoiceId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val validatedAt: Long? = null,
    /** Transport ou frais annexes saisis avec la facture (hors TVA). */
    val feeLabel: String = "",
    val feeAmount: BigDecimal = BigDecimal.ZERO,
    /** Compte de charge des frais ; null = compte « Transport et frais annexes » du paramétrage. */
    val feeAccountId: String? = null,
    val feePaidBy: FeePayer? = null,
    /** Trésorerie utilisée quand l'entreprise paie elle-même les frais. */
    val feeMethod: PaymentMethod? = null,
    /** Avoir : les articles stockés reviennent en stock (retour client) ou en sortent (retour fournisseur). */
    val returnsGoods: Boolean = false
)

@Entity(
    tableName = "invoice_lines",
    indices = [Index("invoiceId")],
    foreignKeys = [ForeignKey(
        entity = Invoice::class,
        parentColumns = ["id"],
        childColumns = ["invoiceId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class InvoiceLine(
    @PrimaryKey val id: String,
    val invoiceId: String,
    val companyId: String,
    val position: Int = 0,
    val productId: String? = null,
    val accountId: String? = null,
    val description: String = "",
    val qty: BigDecimal = BigDecimal.ONE,
    val unitPrice: BigDecimal = BigDecimal.ZERO,
    val vatRate: BigDecimal = BigDecimal.ZERO,
    val totalHt: BigDecimal = BigDecimal.ZERO,
    val totalVat: BigDecimal = BigDecimal.ZERO
)

@Entity(tableName = "payments", indices = [Index("companyId"), Index("thirdPartyId")])
data class Payment(
    @PrimaryKey val id: String,
    val companyId: String,
    val number: String,
    val direction: PaymentDirection,
    val thirdPartyId: String,
    val date: LocalDate,
    val amount: BigDecimal,
    val method: PaymentMethod,
    val reference: String = "",
    val entryId: String,
    val cancelled: Boolean = false,
    val cancelEntryId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Part du règlement non imputée à l'enregistrement, portée en avance
     * au compte 419 (client) ou 409 (fournisseur).
     */
    val advance: BigDecimal = BigDecimal.ZERO
)

/**
 * Imputation d'un règlement ou d'un avoir sur une facture.
 * Un règlement peut être partiel, multiple, réparti sur plusieurs factures,
 * ou rester non affecté (avance). La part non imputée n'a pas de ligne ici.
 */
@Entity(tableName = "allocations", indices = [Index("companyId"), Index("invoiceId"), Index("sourceId")])
data class Allocation(
    @PrimaryKey val id: String,
    val companyId: String,
    val sourceType: AllocationSource,
    val sourceId: String,
    val invoiceId: String,
    val amount: BigDecimal,
    val date: LocalDate,
    val createdAt: Long = System.currentTimeMillis(),
    /** true = imputation prise sur l'avance (419 / 409) d'un règlement. */
    val fromAdvance: Boolean = false,
    /** Écriture qui a transféré l'avance au compte du tiers (écriture de la facture, ou écriture dédiée). */
    val entryId: String? = null
)
