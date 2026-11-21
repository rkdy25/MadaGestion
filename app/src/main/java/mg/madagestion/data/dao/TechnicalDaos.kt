package mg.madagestion.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import mg.madagestion.data.model.AuditLog
import mg.madagestion.data.model.Sequence

@Dao
interface SequenceDao {
    @Query("SELECT * FROM sequences WHERE companyId = :c AND name = :name") suspend fun get(c: String, name: String): Sequence?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(s: Sequence)
}

@Dao
interface AuditDao {
    @Insert suspend fun insert(a: AuditLog)
    @Query("SELECT * FROM audit_log WHERE companyId = :c ORDER BY at DESC LIMIT 300") fun observeRecent(c: String): Flow<List<AuditLog>>
}
