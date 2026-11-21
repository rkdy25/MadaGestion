package mg.madagestion.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/** Plan comptable de l'entreprise (PCG 2005 Madagascar comme profil initial, modifiable). */
@Entity(tableName = "accounts", indices = [Index(value = ["companyId", "code"], unique = true)])
data class Account(
    @PrimaryKey val id: String,
    val companyId: String,
    val code: String,
    val label: String,
    val active: Boolean = true
)

/** Correspondance « rôle comptable » -> compte (ex. CUSTOMERS -> 411). Rien n'est codé en dur. */
@Entity(tableName = "account_mappings", primaryKeys = ["companyId", "mapKey"])
data class AccountMapping(
    val companyId: String,
    val mapKey: String,
    val accountId: String
)

@Entity(tableName = "journals", indices = [Index(value = ["companyId", "code"], unique = true)])
data class Journal(
    @PrimaryKey val id: String,
    val companyId: String,
    val code: String,
    val label: String,
    val type: JournalType
)

/** Période comptable mensuelle, ouverte ou clôturée. */
@Entity(tableName = "periods", indices = [Index(value = ["companyId", "startDate"], unique = true)])
data class Period(
    @PrimaryKey val id: String,
    val companyId: String,
    val label: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val closed: Boolean = false
)
