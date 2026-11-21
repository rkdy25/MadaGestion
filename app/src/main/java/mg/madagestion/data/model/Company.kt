package mg.madagestion.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(tableName = "companies")
data class Company(
    @PrimaryKey val id: String,
    val name: String,
    val nif: String = "",
    val stat: String = "",
    val address: String = "",
    val phone: String = "",
    val defaultVatRate: BigDecimal = BigDecimal("20"),
    val createdAt: Long = System.currentTimeMillis()
)
