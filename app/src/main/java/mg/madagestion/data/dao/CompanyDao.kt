package mg.madagestion.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import mg.madagestion.data.model.Company

@Dao
interface CompanyDao {
    @Insert suspend fun insert(c: Company)
    @Update suspend fun update(c: Company)
    @Query("SELECT * FROM companies ORDER BY createdAt LIMIT 1") fun first(): Flow<Company?>
    @Query("SELECT * FROM companies ORDER BY createdAt LIMIT 1") suspend fun firstOnce(): Company?
}
