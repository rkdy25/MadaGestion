package mg.madagestion.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import mg.madagestion.data.model.Account
import mg.madagestion.data.model.AccountMapping
import mg.madagestion.data.model.Journal
import mg.madagestion.data.model.JournalType
import mg.madagestion.data.model.Period

@Dao
interface AccountDao {
    @Insert suspend fun insertAll(list: List<Account>)
    @Insert suspend fun insert(a: Account)
    @Update suspend fun update(a: Account)
    @Query("SELECT * FROM accounts WHERE companyId = :c ORDER BY code") fun observe(c: String): Flow<List<Account>>
    @Query("SELECT * FROM accounts WHERE companyId = :c ORDER BY code") suspend fun list(c: String): List<Account>
    @Query("SELECT * FROM accounts WHERE id = :id") suspend fun get(id: String): Account?
    @Query("SELECT * FROM accounts WHERE companyId = :c AND code = :code") suspend fun byCode(c: String, code: String): Account?
    @Query("SELECT COUNT(*) FROM entry_lines WHERE accountId = :id") suspend fun usage(id: String): Int
}

@Dao
interface MappingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(m: AccountMapping)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(list: List<AccountMapping>)
    @Query("SELECT * FROM account_mappings WHERE companyId = :c") fun observe(c: String): Flow<List<AccountMapping>>
    @Query("SELECT * FROM account_mappings WHERE companyId = :c") suspend fun list(c: String): List<AccountMapping>
    @Query("SELECT * FROM account_mappings WHERE companyId = :c AND mapKey = :key") suspend fun get(c: String, key: String): AccountMapping?
}

@Dao
interface JournalDao {
    @Insert suspend fun insertAll(list: List<Journal>)
    @Query("SELECT * FROM journals WHERE companyId = :c ORDER BY code") fun observe(c: String): Flow<List<Journal>>
    @Query("SELECT * FROM journals WHERE companyId = :c ORDER BY code") suspend fun list(c: String): List<Journal>
    @Query("SELECT * FROM journals WHERE id = :id") suspend fun get(id: String): Journal?
    @Query("SELECT * FROM journals WHERE companyId = :c AND type = :t ORDER BY code LIMIT 1")
    suspend fun firstOfType(c: String, t: JournalType): Journal?
}

@Dao
interface PeriodDao {
    @Insert suspend fun insert(p: Period)
    @Update suspend fun update(p: Period)
    @Query("SELECT * FROM periods WHERE companyId = :c ORDER BY startDate") fun observe(c: String): Flow<List<Period>>
    @Query("SELECT * FROM periods WHERE companyId = :c ORDER BY startDate") suspend fun list(c: String): List<Period>
    @Query("SELECT * FROM periods WHERE id = :id") suspend fun get(id: String): Period?
    @Query("SELECT * FROM periods WHERE companyId = :c AND startDate <= :d AND endDate >= :d LIMIT 1")
    suspend fun forDate(c: String, d: LocalDate): Period?
}
