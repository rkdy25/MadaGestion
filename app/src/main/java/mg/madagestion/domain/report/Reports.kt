package mg.madagestion.domain.report

import java.math.BigDecimal
import java.time.LocalDate
import mg.madagestion.core.BusinessException
import mg.madagestion.core.sumBd
import mg.madagestion.data.AppDatabase
import mg.madagestion.data.model.Account
import mg.madagestion.data.model.EntryLine
import mg.madagestion.data.model.JournalEntry
import mg.madagestion.domain.accounting.Accounting
import mg.madagestion.domain.accounting.MappingKey
import mg.madagestion.domain.accounting.accountClass

class Reports(private val db: AppDatabase, private val accounting: Accounting) {

    private suspend fun posted(c: String, from: LocalDate?, to: LocalDate?): List<Pair<JournalEntry, EntryLine>> {
        val entries = db.entryDao().validated(c).associateBy { it.id }
        return db.entryDao().validatedLines(c).mapNotNull { l ->
            val e = entries[l.entryId] ?: return@mapNotNull null
            if (from != null && e.date.isBefore(from)) return@mapNotNull null
            if (to != null && e.date.isAfter(to)) return@mapNotNull null
            e to l
        }
    }

    suspend fun trialBalance(c: String, from: LocalDate?, to: LocalDate?): List<BalanceRow> {
        val accounts = db.accountDao().list(c).associateBy { it.id }
        return posted(c, from, to)
            .groupBy({ it.second.accountId }, { it.second })
            .mapNotNull { (accountId, ls) ->
                accounts[accountId]?.let { BalanceRow(it, ls.sumBd { l -> l.debit }, ls.sumBd { l -> l.credit }) }
            }
            .sortedBy { it.account.code }
    }

    suspend fun ledger(c: String, accountId: String, from: LocalDate?, to: LocalDate?): LedgerReport {
        val account = db.accountDao().get(accountId) ?: throw BusinessException("Compte introuvable.")
        val all = posted(c, null, to).filter { it.second.accountId == accountId }
        return ledgerOf(account, all, from, journalCodes(c), partyNames(c))
    }

    /** Grand livre général : tous les comptes mouvementés jusqu'à [to], dans l'ordre du plan comptable. */
    suspend fun generalLedger(c: String, from: LocalDate?, to: LocalDate?): List<LedgerReport> {
        val accounts = db.accountDao().list(c).associateBy { it.id }
        val journals = journalCodes(c)
        val parties = partyNames(c)
        return posted(c, null, to)
            .groupBy { it.second.accountId }
            .mapNotNull { (accountId, lines) -> accounts[accountId]?.let { ledgerOf(it, lines, from, journals, parties) } }
            .sortedBy { it.account.code }
    }

    private suspend fun journalCodes(c: String): Map<String, String> =
        db.journalDao().list(c).associate { it.id to it.code }

    private suspend fun partyNames(c: String): Map<String, String> =
        db.thirdPartyDao().list(c).associate { it.id to it.name }

    /** @param all lignes validées du compte jusqu'à la date de fin ; celles d'avant [from] forment le report. */
    private fun ledgerOf(
        account: Account,
        all: List<Pair<JournalEntry, EntryLine>>,
        from: LocalDate?,
        journals: Map<String, String>,
        parties: Map<String, String>
    ): LedgerReport {
        val before = if (from == null) emptyList() else all.filter { it.first.date.isBefore(from) }
        val inRange = if (from == null) all else all.filter { !it.first.date.isBefore(from) }
        val opening = before.sumBd { it.second.debit }.subtract(before.sumBd { it.second.credit })
        var running = opening
        val sorted = inRange.sortedWith(
            compareBy<Pair<JournalEntry, EntryLine>>({ it.first.date }, { it.first.number ?: "" }, { it.second.position })
        )
        val rows = sorted.map { (e, l) ->
            running = running.add(l.debit).subtract(l.credit)
            LedgerRow(
                date = e.date,
                number = e.number ?: "",
                journal = journals[e.journalId] ?: "",
                label = l.label.ifBlank { e.label },
                reference = e.reference,
                thirdParty = l.thirdPartyId?.let { parties[it] } ?: "",
                debit = l.debit,
                credit = l.credit,
                running = running
            )
        }
        return LedgerReport(account, opening, rows, rows.sumBd { it.debit }, rows.sumBd { it.credit })
    }

    suspend fun result(c: String, from: LocalDate?, to: LocalDate?): ResultReport {
        val rows = trialBalance(c, from, to)
        val revenues = rows.filter { it.account.accountClass() == '7' }
        val expenses = rows.filter { it.account.accountClass() == '6' }
        return ResultReport(
            revenues = revenues,
            expenses = expenses,
            totalRevenue = revenues.sumBd { it.credit.subtract(it.debit) },
            totalExpense = expenses.sumBd { it.debit.subtract(it.credit) }
        )
    }

    /** Situation simplifiée : actif = soldes débiteurs des classes 2-5, passif = soldes créditeurs des classes 1-5 + résultat cumulé. */
    suspend fun situation(c: String, to: LocalDate?): SituationReport {
        val rows = trialBalance(c, null, to)
        val assets = mutableListOf<Pair<Account, BigDecimal>>()
        val liabilities = mutableListOf<Pair<Account, BigDecimal>>()
        for (r in rows) {
            val cls = r.account.accountClass()
            if (cls in '1'..'5') {
                if (r.net.signum() > 0) assets += r.account to r.net
                else if (r.net.signum() < 0) liabilities += r.account to r.net.negate()
            }
        }
        val res = rows.filter { it.account.accountClass() == '7' }.sumBd { it.credit.subtract(it.debit) }
            .subtract(rows.filter { it.account.accountClass() == '6' }.sumBd { it.debit.subtract(it.credit) })
        return SituationReport(assets, liabilities, res)
    }

    suspend fun dashboard(c: String, year: Int): DashboardStats {
        val yearRows = trialBalance(c, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
        val revenue = yearRows.filter { it.account.code.startsWith("70") }.sumBd { it.credit.subtract(it.debit) }
        val result = yearRows.filter { it.account.accountClass() == '7' }.sumBd { it.credit.subtract(it.debit) }
            .subtract(yearRows.filter { it.account.accountClass() == '6' }.sumBd { it.debit.subtract(it.credit) })
        val all = trialBalance(c, null, null)
        fun netOf(key: MappingKey, id: String?): BigDecimal =
            if (id == null) BigDecimal.ZERO else all.firstOrNull { it.account.id == id }?.net ?: BigDecimal.ZERO
        // Créances et dettes nettes des avances (411 + 419, 401 + 409)
        val customers = netOf(MappingKey.CUSTOMERS, accounting.mappingIdOrNull(c, MappingKey.CUSTOMERS))
            .add(netOf(MappingKey.CUSTOMER_ADVANCES, accounting.mappingIdOrNull(c, MappingKey.CUSTOMER_ADVANCES)))
        val suppliers = netOf(MappingKey.SUPPLIERS, accounting.mappingIdOrNull(c, MappingKey.SUPPLIERS))
            .add(netOf(MappingKey.SUPPLIER_ADVANCES, accounting.mappingIdOrNull(c, MappingKey.SUPPLIER_ADVANCES)))
        val cash = netOf(MappingKey.CASH, accounting.mappingIdOrNull(c, MappingKey.CASH))
        val bank = netOf(MappingKey.BANK, accounting.mappingIdOrNull(c, MappingKey.BANK))
        val mobile = netOf(MappingKey.MOBILE, accounting.mappingIdOrNull(c, MappingKey.MOBILE))
        val stock = netOf(MappingKey.STOCK, accounting.mappingIdOrNull(c, MappingKey.STOCK))
        val vatCollected = netOf(MappingKey.VAT_COLLECTED, accounting.mappingIdOrNull(c, MappingKey.VAT_COLLECTED)).negate()
        val vatDeductible = netOf(MappingKey.VAT_DEDUCTIBLE, accounting.mappingIdOrNull(c, MappingKey.VAT_DEDUCTIBLE))
        return DashboardStats(
            revenue = revenue,
            result = result,
            receivables = customers,
            payables = suppliers.negate(),
            treasury = cash.add(bank).add(mobile),
            stockValue = stock,
            vatToPay = vatCollected.subtract(vatDeductible)
        )
    }
}
