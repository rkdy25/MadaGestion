package mg.madagestion.domain.stock

import androidx.room.withTransaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import mg.madagestion.core.BusinessException
import mg.madagestion.core.Money
import mg.madagestion.core.newId
import mg.madagestion.core.sameAs
import mg.madagestion.data.AppDatabase
import mg.madagestion.data.model.JournalType
import mg.madagestion.data.model.Product
import mg.madagestion.data.model.StockMoveType
import mg.madagestion.data.model.StockMovement
import mg.madagestion.domain.accounting.Accounting
import mg.madagestion.domain.accounting.EntrySource
import mg.madagestion.domain.accounting.LineInput
import mg.madagestion.domain.accounting.MappingKey

/**
 * Gestion de stock en coût unitaire moyen pondéré (CUMP) avec inventaire permanent.
 * La valeur du stock de chaque article est suivie en Ar : elle reste égale, à l'ariary près,
 * au solde du compte de stock dans la comptabilité (contrôle « Stock » dans l'application).
 */
class StockService(private val db: AppDatabase, private val accounting: Accounting) {

    suspend fun saveProduct(
        companyId: String,
        productId: String?,
        sku: String,
        name: String,
        unit: String,
        salePrice: BigDecimal,
        purchasePrice: BigDecimal,
        isStocked: Boolean,
        active: Boolean
    ) {
        db.withTransaction {
            if (name.isBlank()) throw BusinessException("Le nom de l'article est obligatoire.")
            if (salePrice.signum() < 0 || purchasePrice.signum() < 0) throw BusinessException("Un prix ne peut pas être négatif.")
            val existing = productId?.let { db.productDao().get(it) }
            if (existing == null) {
                val id = newId()
                db.productDao().insert(
                    Product(
                        id = id, companyId = companyId, sku = sku.trim(), name = name.trim(),
                        unit = unit.trim().ifBlank { "u" },
                        salePrice = Money.ar(salePrice), purchasePrice = Money.ar(purchasePrice),
                        isStocked = isStocked, active = active
                    )
                )
                accounting.audit(companyId, "PRODUCT_CREATE", "PRODUCT", id, name.trim())
            } else {
                if (existing.isStocked != isStocked &&
                    (existing.qtyOnHand.signum() != 0 || existing.stockValue.signum() != 0)
                ) {
                    throw BusinessException("Impossible de changer le mode de gestion d'un article qui a du stock.")
                }
                db.productDao().update(
                    existing.copy(
                        sku = sku.trim(), name = name.trim(), unit = unit.trim().ifBlank { "u" },
                        salePrice = Money.ar(salePrice), purchasePrice = Money.ar(purchasePrice),
                        isStocked = isStocked, active = active
                    )
                )
                accounting.audit(companyId, "PRODUCT_UPDATE", "PRODUCT", existing.id, name.trim())
            }
        }
    }

    /** Entrée en stock (à appeler dans une transaction). */
    suspend fun receive(
        productId: String,
        date: LocalDate,
        qty: BigDecimal,
        value: BigDecimal,
        type: StockMoveType,
        sourceType: String?,
        sourceId: String?,
        note: String = ""
    ) {
        val p = db.productDao().get(productId) ?: throw BusinessException("Article introuvable.")
        db.productDao().update(p.copy(qtyOnHand = p.qtyOnHand.add(qty), stockValue = p.stockValue.add(value)))
        db.stockDao().insert(
            StockMovement(newId(), p.companyId, p.id, date, type, qty, value, sourceType, sourceId, null, note)
        )
    }

    /** Sortie de stock valorisée au CUMP ; renvoie le coût sorti, en Ar (à appeler dans une transaction). */
    suspend fun issue(
        productId: String,
        date: LocalDate,
        qty: BigDecimal,
        type: StockMoveType,
        sourceType: String?,
        sourceId: String?,
        note: String = ""
    ): BigDecimal {
        val p = db.productDao().get(productId) ?: throw BusinessException("Article introuvable.")
        if (qty.compareTo(p.qtyOnHand) > 0) {
            throw BusinessException(
                "Stock insuffisant pour « ${p.name} » : disponible ${p.qtyOnHand.stripTrailingZeros().toPlainString()}, demandé ${qty.stripTrailingZeros().toPlainString()}."
            )
        }
        val cost = if (qty.sameAs(p.qtyOnHand)) {
            p.stockValue
        } else {
            Money.ar(p.stockValue.multiply(qty).divide(p.qtyOnHand, 6, RoundingMode.HALF_UP))
        }
        db.productDao().update(p.copy(qtyOnHand = p.qtyOnHand.subtract(qty), stockValue = p.stockValue.subtract(cost)))
        db.stockDao().insert(
            StockMovement(newId(), p.companyId, p.id, date, type, qty.negate(), cost.negate(), sourceType, sourceId, null, note)
        )
        return cost
    }

    /** Ajustement / inventaire / stock initial, avec écriture comptable en OD. */
    suspend fun adjust(
        companyId: String,
        productId: String,
        date: LocalDate,
        qtyDelta: BigDecimal,
        unitCost: BigDecimal?,
        note: String
    ) {
        db.withTransaction {
            val product = db.productDao().get(productId) ?: throw BusinessException("Article introuvable.")
            if (!product.isStocked) throw BusinessException("Cet article n'est pas géré en stock.")
            val delta = Money.qty(qtyDelta)
            if (delta.signum() == 0) throw BusinessException("La quantité d'ajustement ne peut pas être nulle.")
            accounting.requireOpenPeriod(companyId, date)

            val stockAcc = accounting.mappingId(companyId, MappingKey.STOCK)
            val varAcc = accounting.mappingId(companyId, MappingKey.STOCK_VARIATION)
            val sourceId = newId()
            val amount: BigDecimal
            if (delta.signum() > 0) {
                val cost = unitCost ?: if (product.qtyOnHand.signum() > 0) {
                    product.stockValue.divide(product.qtyOnHand, 6, RoundingMode.HALF_UP)
                } else {
                    throw BusinessException("Indiquez un coût unitaire : l'article n'a pas encore de stock valorisé.")
                }
                if (cost.signum() < 0) throw BusinessException("Le coût unitaire ne peut pas être négatif.")
                amount = Money.ar(delta.multiply(cost))
                receive(productId, date, delta, amount, StockMoveType.ADJUSTMENT, EntrySource.STOCK, sourceId, note)
            } else {
                amount = issue(productId, date, delta.negate(), StockMoveType.ADJUSTMENT, EntrySource.STOCK, sourceId, note)
            }
            if (amount.signum() != 0) {
                val lines = if (delta.signum() > 0) {
                    listOf(
                        LineInput(stockAcc, debit = amount, label = "Ajustement de stock"),
                        LineInput(varAcc, credit = amount, label = "Ajustement de stock")
                    )
                } else {
                    listOf(
                        LineInput(varAcc, debit = amount, label = "Ajustement de stock"),
                        LineInput(stockAcc, credit = amount, label = "Ajustement de stock")
                    )
                }
                val journal = accounting.journalOfType(companyId, JournalType.MISC)
                val entryId = accounting.post(
                    companyId, journal.id, date, "Stock", "Ajustement de stock — ${product.name}",
                    lines, EntrySource.STOCK, sourceId
                )
                db.stockDao().linkEntry(EntrySource.STOCK, sourceId, entryId)
            }
            accounting.audit(companyId, "STOCK_ADJUST", "PRODUCT", productId, "$delta")
        }
    }
}
