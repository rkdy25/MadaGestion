package mg.madagestion.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import mg.madagestion.data.model.Allocation
import mg.madagestion.data.model.Invoice
import mg.madagestion.data.model.InvoiceLine
import mg.madagestion.data.model.Payment

@Dao
interface InvoiceDao {
    @Insert suspend fun insert(i: Invoice)
    @Update suspend fun update(i: Invoice)
    @Query("DELETE FROM invoices WHERE id = :id") suspend fun delete(id: String)
    @Insert suspend fun insertLines(lines: List<InvoiceLine>)
    @Query("DELETE FROM invoice_lines WHERE invoiceId = :invoiceId") suspend fun deleteLines(invoiceId: String)
    @Query("SELECT * FROM invoices WHERE id = :id") suspend fun get(id: String): Invoice?
    @Query("SELECT * FROM invoice_lines WHERE invoiceId = :invoiceId ORDER BY position")
    suspend fun lines(invoiceId: String): List<InvoiceLine>
    @Query("SELECT * FROM invoices WHERE companyId = :c ORDER BY date DESC, createdAt DESC")
    fun observe(c: String): Flow<List<Invoice>>
    @Query("SELECT * FROM invoices WHERE companyId = :c") suspend fun list(c: String): List<Invoice>
    @Query("SELECT * FROM invoices WHERE originInvoiceId = :originId") suspend fun creditsOf(originId: String): List<Invoice>
}

@Dao
interface PaymentDao {
    @Insert suspend fun insert(p: Payment)
    @Update suspend fun update(p: Payment)
    @Query("SELECT * FROM payments WHERE companyId = :c ORDER BY date DESC, createdAt DESC")
    fun observe(c: String): Flow<List<Payment>>
    @Query("SELECT * FROM payments WHERE companyId = :c") suspend fun list(c: String): List<Payment>
    @Query("SELECT * FROM payments WHERE id = :id") suspend fun get(id: String): Payment?
}

@Dao
interface AllocationDao {
    @Insert suspend fun insert(a: Allocation)
    @Query("SELECT * FROM allocations WHERE companyId = :c") fun observe(c: String): Flow<List<Allocation>>
    @Query("SELECT * FROM allocations WHERE companyId = :c") suspend fun list(c: String): List<Allocation>
    @Query("SELECT * FROM allocations WHERE invoiceId = :invoiceId") suspend fun forInvoice(invoiceId: String): List<Allocation>
    @Query("SELECT * FROM allocations WHERE sourceId = :sourceId") suspend fun forSource(sourceId: String): List<Allocation>
    @Query("DELETE FROM allocations WHERE sourceId = :sourceId") suspend fun deleteForSource(sourceId: String)
}
