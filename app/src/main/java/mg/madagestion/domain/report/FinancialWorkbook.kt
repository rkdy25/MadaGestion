package mg.madagestion.domain.report

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import mg.madagestion.core.sumBd
import mg.madagestion.data.AppDatabase
import mg.madagestion.data.model.ThirdPartyType
import mg.madagestion.domain.party.PartyAccountService
import mg.madagestion.domain.report.XlsxStyle.AMOUNT
import mg.madagestion.domain.report.XlsxStyle.AMOUNT_BOLD
import mg.madagestion.domain.report.XlsxStyle.BOLD
import mg.madagestion.domain.report.XlsxStyle.DATE
import mg.madagestion.domain.report.XlsxStyle.HEADER
import mg.madagestion.domain.report.XlsxStyle.TITLE

/**
 * Classeur Excel des rapports financiers d'une période :
 * balance, compte de résultat, bilan, grand livre général et soldes des tiers.
 */
class FinancialWorkbook(
    private val db: AppDatabase,
    private val reports: Reports,
    private val partyAccounts: PartyAccountService
) {

    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    suspend fun build(companyId: String, from: LocalDate, to: LocalDate): ByteArray {
        val company = db.companyDao().firstOnce()?.name ?: ""
        val period = "du ${from.format(dateFormat)} au ${to.format(dateFormat)}"
        val sheets = listOf(
            balanceSheet(company, period, reports.trialBalance(companyId, from, to)),
            resultSheet(company, period, reports.result(companyId, from, to)),
            situationSheet(company, to, reports.situation(companyId, to)),
            ledgerSheet(company, period, from, reports.generalLedger(companyId, from, to)),
            partiesSheet(company, companyId)
        )
        val out = ByteArrayOutputStream()
        XlsxWriter.write(sheets, out)
        return out.toByteArray()
    }

    private fun text(v: String, style: XlsxStyle = XlsxStyle.NORMAL) = XlsxCell(v, style)
    private fun amount(v: BigDecimal, bold: Boolean = false) = XlsxCell(v, if (bold) AMOUNT_BOLD else AMOUNT)
    private val empty = XlsxCell(null)

    private fun XlsxSheet.heading(company: String, title: String) {
        row(text(company, TITLE))
        row(text(title, BOLD))
        blank()
    }

    private fun XlsxSheet.header(vararg labels: String) {
        row(*labels.map { text(it, HEADER) }.toTypedArray())
    }

    private fun balanceSheet(company: String, period: String, rows: List<BalanceRow>): XlsxSheet {
        val sheet = XlsxSheet("Balance", listOf(12, 46, 16, 16, 16, 16), frozenRows = 4)
        sheet.heading(company, "Balance générale $period")
        sheet.header("Compte", "Libellé", "Débit", "Crédit", "Solde débiteur", "Solde créditeur")
        for (r in rows) {
            val net = r.net
            sheet.row(
                text(r.account.code), text(r.account.label), amount(r.debit), amount(r.credit),
                amount(net.max(BigDecimal.ZERO)), amount(net.negate().max(BigDecimal.ZERO))
            )
        }
        sheet.row(
            empty, text("Totaux", BOLD), amount(rows.sumBd { it.debit }, true), amount(rows.sumBd { it.credit }, true),
            amount(rows.sumBd { it.net.max(BigDecimal.ZERO) }, true), amount(rows.sumBd { it.net.negate().max(BigDecimal.ZERO) }, true)
        )
        return sheet
    }

    private fun resultSheet(company: String, period: String, r: ResultReport): XlsxSheet {
        val sheet = XlsxSheet("Résultat", listOf(12, 52, 18), frozenRows = 4)
        sheet.heading(company, "Compte de résultat $period")
        sheet.header("Compte", "Libellé", "Montant")
        sheet.row(text("PRODUITS", BOLD))
        for (row in r.revenues) sheet.row(text(row.account.code), text(row.account.label), amount(row.credit.subtract(row.debit)))
        sheet.row(empty, text("Total des produits", BOLD), amount(r.totalRevenue, true))
        sheet.blank()
        sheet.row(text("CHARGES", BOLD))
        for (row in r.expenses) sheet.row(text(row.account.code), text(row.account.label), amount(row.debit.subtract(row.credit)))
        sheet.row(empty, text("Total des charges", BOLD), amount(r.totalExpense, true))
        sheet.blank()
        sheet.row(empty, text(if (r.result.signum() >= 0) "Résultat : bénéfice" else "Résultat : perte", BOLD), amount(r.result.abs(), true))
        return sheet
    }

    private fun situationSheet(company: String, to: LocalDate, s: SituationReport): XlsxSheet {
        val sheet = XlsxSheet("Bilan", listOf(12, 52, 18), frozenRows = 4)
        sheet.heading(company, "Bilan simplifié au ${to.format(dateFormat)}")
        sheet.header("Compte", "Libellé", "Montant")
        sheet.row(text("ACTIF", BOLD))
        for ((account, value) in s.assets) sheet.row(text(account.code), text(account.label), amount(value))
        sheet.row(empty, text("Total actif", BOLD), amount(s.totalAssets, true))
        sheet.blank()
        sheet.row(text("PASSIF", BOLD))
        for ((account, value) in s.liabilities) sheet.row(text(account.code), text(account.label), amount(value))
        sheet.row(empty, text("Résultat de la période (cumul)"), amount(s.result))
        sheet.row(empty, text("Total passif", BOLD), amount(s.totalLiabilities, true))
        return sheet
    }

    private fun ledgerSheet(company: String, period: String, from: LocalDate, ledgers: List<LedgerReport>): XlsxSheet {
        val sheet = XlsxSheet("Grand livre", listOf(12, 18, 9, 44, 18, 26, 16, 16, 16), frozenRows = 4)
        sheet.heading(company, "Grand livre général $period")
        sheet.header("Date", "Pièce", "Journal", "Libellé", "Référence", "Tiers", "Débit", "Crédit", "Solde")
        for (l in ledgers) {
            if (l.rows.isEmpty() && l.opening.signum() == 0) continue
            sheet.row(text("${l.account.code} — ${l.account.label}", BOLD))
            sheet.row(
                empty, empty, empty, text("Report au ${from.format(dateFormat)}"), empty, empty, empty, empty, amount(l.opening)
            )
            for (r in l.rows) {
                sheet.row(
                    XlsxCell(r.date, DATE), text(r.number), text(r.journal), text(r.label), text(r.reference),
                    text(r.thirdParty), amount(r.debit), amount(r.credit), amount(r.running)
                )
            }
            sheet.row(
                empty, empty, empty, text("Total ${l.account.code}", BOLD), empty, empty,
                amount(l.totalDebit, true), amount(l.totalCredit, true), amount(l.closing, true)
            )
            sheet.blank()
        }
        return sheet
    }

    private suspend fun partiesSheet(company: String, companyId: String): XlsxSheet {
        val sheet = XlsxSheet("Tiers", listOf(14, 40, 20, 20), frozenRows = 4)
        sheet.heading(company, "Soldes des clients et des fournisseurs à ce jour")
        sheet.header("Type", "Nom", "À encaisser", "À payer")
        val balances = partyAccounts.balances(companyId)
        val parties = db.thirdPartyDao().list(companyId).sortedWith(compareBy({ it.type }, { it.name }))
        var receivable = BigDecimal.ZERO
        var payable = BigDecimal.ZERO
        for (p in parties) {
            val balance = balances[p.id] ?: continue
            if (balance.signum() == 0) continue
            if (p.type == ThirdPartyType.CUSTOMER) {
                receivable = receivable.add(balance)
                sheet.row(text("Client"), text(p.name), amount(balance), empty)
            } else {
                payable = payable.add(balance)
                sheet.row(text("Fournisseur"), text(p.name), empty, amount(balance))
            }
        }
        sheet.row(empty, text("Totaux", BOLD), amount(receivable, true), amount(payable, true))
        return sheet
    }
}
