package mg.madagestion.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import mg.madagestion.data.model.Product
import mg.madagestion.data.model.StockMovement
import mg.madagestion.data.model.ThirdParty

@Dao
interface ThirdPartyDao {
    @Insert suspend fun insert(t: ThirdParty)
    @Update suspend fun update(t: ThirdParty)
    @Query("SELECT * FROM third_parties WHERE companyId = :c ORDER BY name") fun observe(c: String): Flow<List<ThirdParty>>
    @Query("SELECT * FROM third_parties WHERE companyId = :c ORDER BY name") suspend fun list(c: String): List<ThirdParty>
    @Query("SELECT * FROM third_parties WHERE id = :id") suspend fun get(id: String): ThirdParty?
}

@Dao
interface ProductDao {
    @Insert suspend fun insert(p: Product)
    @Update suspend fun update(p: Product)
    @Query("SELECT * FROM products WHERE companyId = :c ORDER BY name") fun observe(c: String): Flow<List<Product>>
    @Query("SELECT * FROM products WHERE companyId = :c ORDER BY name") suspend fun list(c: String): List<Product>
    @Query("SELECT * FROM products WHERE id = :id") suspend fun get(id: String): Product?
}

@Dao
interface StockDao {
    @Insert suspend fun insert(m: StockMovement)
    @Query("SELECT * FROM stock_movements WHERE companyId = :c ORDER BY date DESC, createdAt DESC")
    fun observe(c: String): Flow<List<StockMovement>>
    @Query("SELECT * FROM stock_movements WHERE companyId = :c") suspend fun list(c: String): List<StockMovement>
    @Query("UPDATE stock_movements SET entryId = :entryId WHERE sourceType = :sourceType AND sourceId = :sourceId")
    suspend fun linkEntry(sourceType: String, sourceId: String, entryId: String)
    @Query("SELECT COUNT(*) FROM stock_movements WHERE entryId = :entryId") suspend fun countForEntry(entryId: String): Int
    @Query("SELECT * FROM stock_movements WHERE sourceType = :sourceType AND sourceId = :sourceId")
    suspend fun bySource(sourceType: String, sourceId: String): List<StockMovement>
}
