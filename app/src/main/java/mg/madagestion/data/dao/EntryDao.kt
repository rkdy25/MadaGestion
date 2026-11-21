package mg.madagestion.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import mg.madagestion.data.model.EntryLine
import mg.madagestion.data.model.JournalEntry

@Dao
interface EntryDao {
    @Insert suspend fun insert(e: JournalEntry)
    @Update suspend fun update(e: JournalEntry)
    @Query("DELETE FROM journal_entries WHERE id = :id") suspend fun delete(id: String)

    @Insert suspend fun insertLines(lines: List<EntryLine>)
    @Query("DELETE FROM entry_lines WHERE entryId = :entryId") suspend fun deleteLines(entryId: String)

    @Query("SELECT * FROM journal_entries WHERE id = :id") suspend fun get(id: String): JournalEntry?
    @Query("SELECT * FROM entry_lines WHERE entryId = :entryId ORDER BY position") suspend fun lines(entryId: String): List<EntryLine>

    @Query("SELECT * FROM journal_entries WHERE companyId = :c ORDER BY date DESC, createdAt DESC")
    fun observeAll(c: String): Flow<List<JournalEntry>>

    @Query("SELECT * FROM journal_entries WHERE companyId = :c") suspend fun listAll(c: String): List<JournalEntry>

    @Query("SELECT * FROM entry_lines WHERE companyId = :c") suspend fun allLines(c: String): List<EntryLine>

    @Query("SELECT * FROM journal_entries WHERE companyId = :c AND status = 'VALIDATED'")
    suspend fun validated(c: String): List<JournalEntry>

    @Query("SELECT * FROM journal_entries WHERE companyId = :c AND status = 'DRAFT'")
    suspend fun drafts(c: String): List<JournalEntry>

    @Query(
        "SELECT l.* FROM entry_lines l INNER JOIN journal_entries e ON e.id = l.entryId " +
            "WHERE e.companyId = :c AND e.status = 'VALIDATED'"
    )
    suspend fun validatedLines(c: String): List<EntryLine>

    @Query(
        "SELECT l.* FROM entry_lines l INNER JOIN journal_entries e ON e.id = l.entryId " +
            "WHERE e.companyId = :c AND e.status = 'VALIDATED' AND l.accountId = :accountId"
    )
    suspend fun validatedLinesForAccount(c: String, accountId: String): List<EntryLine>

    @Query(
        "SELECT l.* FROM entry_lines l INNER JOIN journal_entries e ON e.id = l.entryId " +
            "WHERE e.companyId = :c AND e.status = 'VALIDATED' AND l.thirdPartyId = :thirdPartyId"
    )
    suspend fun validatedLinesForThirdParty(c: String, thirdPartyId: String): List<EntryLine>

    @Query("SELECT * FROM journal_entries WHERE companyId = :c AND sourceType = :sourceType ORDER BY date DESC, createdAt DESC")
    suspend fun bySource(c: String, sourceType: String): List<JournalEntry>

    @Query(
        "SELECT COUNT(*) FROM journal_entries WHERE companyId = :c AND status = 'DRAFT' " +
            "AND date >= :start AND date <= :end"
    )
    suspend fun draftCountBetween(c: String, start: LocalDate, end: LocalDate): Int
}
