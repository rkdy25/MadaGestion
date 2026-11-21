package mg.madagestion.domain.report

import mg.madagestion.core.Money

/** Exports texte (CSV, séparateur « ; ») partageables depuis l'application. */
object CsvExport {

    fun trialBalance(rows: List<BalanceRow>): String = buildString {
        appendLine("Compte;Libellé;Débit;Crédit;Solde débiteur;Solde créditeur")
        for (r in rows) {
            val n = r.net
            appendLine(
                "${r.account.code};${r.account.label.replace(';', ',')};${Money.ar(r.debit).toPlainString()};" +
                    "${Money.ar(r.credit).toPlainString()};" +
                    "${if (n.signum() > 0) Money.ar(n).toPlainString() else "0"};" +
                    "${if (n.signum() < 0) Money.ar(n.negate()).toPlainString() else "0"}"
            )
        }
    }

    fun ledger(report: LedgerReport): String = buildString {
        appendLine("Grand livre ${report.account.code} ${report.account.label}")
        appendLine("Date;Pièce;Journal;Libellé;Référence;Tiers;Débit;Crédit;Solde")
        appendLine(";;;Report à nouveau;;;;;${Money.ar(report.opening).toPlainString()}")
        for (r in report.rows) {
            appendLine(
                "${r.date};${r.number};${r.journal};${r.label.replace(';', ',')};${r.reference.replace(';', ',')};" +
                    "${r.thirdParty.replace(';', ',')};${Money.ar(r.debit).toPlainString()};" +
                    "${Money.ar(r.credit).toPlainString()};${Money.ar(r.running).toPlainString()}"
            )
        }
    }
}
