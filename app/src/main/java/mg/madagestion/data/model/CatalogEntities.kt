package mg.madagestion.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.time.LocalDate

@Entity(tableName = "third_parties", indices = [Index("companyId")])
data class ThirdParty(
    @PrimaryKey val id: String,
    val companyId: String,
    val type: ThirdPartyType,
    val name: String,
    val phone: String = "",
    val email: String = "",
    val nif: String = "",
    val address: String = "",
    val active: Boolean = true
)

@Entity(tableName = "products", indices = [Index("companyId")])
data class Product(
    @PrimaryKey val id: String,
    val companyId: String,
    val sku: String = "",
    val name: String,
    val unit: String = "u",
    val salePrice: BigDecimal = BigDecimal.ZERO,
    val purchasePrice: BigDecimal = BigDecimal.ZERO,
    /** true = article géré en stock (CUMP) ; false = service / article non stocké. */
    val isStocked: Boolean = true,
    val qtyOnHand: BigDecimal = BigDecimal.ZERO,
    /** Valeur totale du stock de l'article, en Ar (CUMP = stockValue / qtyOnHand). */
    val stockValue: BigDecimal = BigDecimal.ZERO,
    val active: Boolean = true
)

@Entity(tableName = "stock_movements", indices = [Index("companyId"), Index("productId")])
data class StockMovement(
    @PrimaryKey val id: String,
    val companyId: String,
    val productId: String,
    val date: LocalDate,
    val type: StockMoveType,
    /** Quantité signée : positive en entrée, négative en sortie. */
    val qty: BigDecimal,
    /** Valeur signée en Ar. */
    val value: BigDecimal,
    val sourceType: String? = null,
    val sourceId: String? = null,
    val entryId: String? = null,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
