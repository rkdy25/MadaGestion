package mg.madagestion.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.time.LocalDate

@Entity(
    tableName = "journal_entries",
    indices = [Index("companyId"), Index("journalId"), Index(value = ["companyId", "number"])]
)
data class JournalEntry(
    @PrimaryKey val id: String,
    val companyId: String,
    val journalId: String,
    val date: LocalDate,
    val reference: String = "",
    val label: String = "",
    val status: EntryStatus = EntryStatus.DRAFT,
    /** Numéro attribué à la validation (séquence sans trou par journal et par année). */
    val number: String? = null,
    /** INVOICE, PAYMENT, STOCK… : écriture générée par un document (non contrepassable à la main). */
    val sourceType: String? = null,
    val sourceId: String? = null,
    val reversalOfId: String? = null,
    val reversedById: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val validatedAt: Long? = null
)

@Entity(
    tableName = "entry_lines",
    indices = [Index("entryId"), Index("accountId"), Index("companyId")],
    foreignKeys = [ForeignKey(
        entity = JournalEntry::class,
        parentColumns = ["id"],
        childColumns = ["entryId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class EntryLine(
    @PrimaryKey val id: String,
    val entryId: String,
    val companyId: String,
    val accountId: String,
    val thirdPartyId: String? = null,
    val label: String = "",
    val debit: BigDecimal = BigDecimal.ZERO,
    val credit: BigDecimal = BigDecimal.ZERO,
    val position: Int = 0
)
