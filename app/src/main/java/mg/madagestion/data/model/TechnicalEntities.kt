package mg.madagestion.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sequences", primaryKeys = ["companyId", "name"])
data class Sequence(
    val companyId: String,
    val name: String,
    val last: Int
)

@Entity(tableName = "audit_log", indices = [Index("companyId")])
data class AuditLog(
    @PrimaryKey val id: String,
    val companyId: String,
    val at: Long,
    val action: String,
    val entityType: String,
    val entityId: String,
    val detail: String = ""
)
