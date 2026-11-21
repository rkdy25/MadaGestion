package mg.madagestion.domain.report

import java.math.BigDecimal
import java.time.LocalDate
import mg.madagestion.core.sumBd
import mg.madagestion.data.model.Account

data class BalanceRow(val account: Account, val debit: BigDecimal, val credit: BigDecimal) {
    /** Positif = solde débiteur, négatif = solde créditeur. */
    val net: BigDecimal get() = debit.subtract(credit)
}

data class LedgerRow(
    val date: LocalDate,
    val number: String,
    val journal: String,
    val label: String,
    val reference: String,
    val thirdParty: String,
    val debit: BigDecimal,
    val credit: BigDecimal,
    val running: BigDecimal
)

data class LedgerReport(
    val account: Account,
    val opening: BigDecimal,
    val rows: List<LedgerRow>,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal
) {
    val closing: BigDecimal get() = opening.add(totalDebit).subtract(totalCredit)
}

data class ResultReport(
    val revenues: List<BalanceRow>,
    val expenses: List<BalanceRow>,
    val totalRevenue: BigDecimal,
    val totalExpense: BigDecimal
) {
    val result: BigDecimal get() = totalRevenue.subtract(totalExpense)
}

data class SituationReport(
    val assets: List<Pair<Account, BigDecimal>>,
    val liabilities: List<Pair<Account, BigDecimal>>,
    val result: BigDecimal
) {
    val totalAssets: BigDecimal get() = assets.sumBd { it.second }
    val totalLiabilities: BigDecimal get() = liabilities.sumBd { it.second }.add(result)
}

data class DashboardStats(
    val revenue: BigDecimal,
    val result: BigDecimal,
    val receivables: BigDecimal,
    val payables: BigDecimal,
    val treasury: BigDecimal,
    val stockValue: BigDecimal,
    val vatToPay: BigDecimal
)
