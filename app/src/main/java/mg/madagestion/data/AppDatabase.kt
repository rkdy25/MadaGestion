package mg.madagestion.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import mg.madagestion.data.dao.AccountDao
import mg.madagestion.data.dao.AllocationDao
import mg.madagestion.data.dao.AuditDao
import mg.madagestion.data.dao.CompanyDao
import mg.madagestion.data.dao.EntryDao
import mg.madagestion.data.dao.InvoiceDao
import mg.madagestion.data.dao.JournalDao
import mg.madagestion.data.dao.MappingDao
import mg.madagestion.data.dao.PaymentDao
import mg.madagestion.data.dao.PeriodDao
import mg.madagestion.data.dao.ProductDao
import mg.madagestion.data.dao.SequenceDao
import mg.madagestion.data.dao.StockDao
import mg.madagestion.data.dao.ThirdPartyDao
import mg.madagestion.data.model.Account
import mg.madagestion.data.model.AccountMapping
import mg.madagestion.data.model.Allocation
import mg.madagestion.data.model.AuditLog
import mg.madagestion.data.model.Company
import mg.madagestion.data.model.EntryLine
import mg.madagestion.data.model.Invoice
import mg.madagestion.data.model.InvoiceLine
import mg.madagestion.data.model.Journal
import mg.madagestion.data.model.JournalEntry
import mg.madagestion.data.model.Payment
import mg.madagestion.data.model.Period
import mg.madagestion.data.model.Product
import mg.madagestion.data.model.Sequence
import mg.madagestion.data.model.StockMovement
import mg.madagestion.data.model.ThirdParty

@Database(
    entities = [
        Company::class, Account::class, AccountMapping::class, Journal::class, Period::class,
        JournalEntry::class, EntryLine::class, ThirdParty::class, Product::class, StockMovement::class,
        Invoice::class, InvoiceLine::class, Payment::class, Allocation::class, Sequence::class, AuditLog::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun companyDao(): CompanyDao
    abstract fun accountDao(): AccountDao
    abstract fun mappingDao(): MappingDao
    abstract fun journalDao(): JournalDao
    abstract fun periodDao(): PeriodDao
    abstract fun entryDao(): EntryDao
    abstract fun thirdPartyDao(): ThirdPartyDao
    abstract fun productDao(): ProductDao
    abstract fun stockDao(): StockDao
    abstract fun invoiceDao(): InvoiceDao
    abstract fun paymentDao(): PaymentDao
    abstract fun allocationDao(): AllocationDao
    abstract fun sequenceDao(): SequenceDao
    abstract fun auditDao(): AuditDao

    companion object {
        const val NAME = "madagestion.db"

        /**
         * Version 0.4.0 : frais annexes sur facture, retour de marchandises sur avoir,
         * avances clients / fournisseurs (419 / 409). Colonnes ajoutées uniquement : aucune donnée n'est modifiée.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE invoices ADD COLUMN feeLabel TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE invoices ADD COLUMN feeAmount TEXT NOT NULL DEFAULT '0'")
                db.execSQL("ALTER TABLE invoices ADD COLUMN feeAccountId TEXT")
                db.execSQL("ALTER TABLE invoices ADD COLUMN feePaidBy TEXT")
                db.execSQL("ALTER TABLE invoices ADD COLUMN feeMethod TEXT")
                db.execSQL("ALTER TABLE invoices ADD COLUMN returnsGoods INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE payments ADD COLUMN advance TEXT NOT NULL DEFAULT '0'")
                db.execSQL("ALTER TABLE allocations ADD COLUMN fromAdvance INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE allocations ADD COLUMN entryId TEXT")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
