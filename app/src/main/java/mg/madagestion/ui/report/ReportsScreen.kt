package mg.madagestion.ui.report

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.time.LocalDate
import mg.madagestion.core.fmt
import mg.madagestion.domain.report.BalanceRow
import mg.madagestion.domain.report.CsvExport
import mg.madagestion.domain.report.LedgerReport
import mg.madagestion.domain.report.ResultReport
import mg.madagestion.domain.report.SituationReport
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.AccountField
import mg.madagestion.ui.components.DateField
import mg.madagestion.ui.components.EmptyHint
import mg.madagestion.ui.components.ListPage
import mg.madagestion.ui.components.SectionTitle
import mg.madagestion.ui.components.SurfaceCard
import mg.madagestion.ui.components.TabsRow
import mg.madagestion.ui.components.TotalRow
import mg.madagestion.ui.format.shareText
import mg.madagestion.ui.theme.Danger
import mg.madagestion.ui.theme.Good
import mg.madagestion.ui.theme.Muted
import mg.madagestion.ui.theme.Navy
import mg.madagestion.ui.theme.Teal

private const val XLSX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

@Composable
fun ReportsScreen(vm: AppViewModel) {
    val company by vm.company.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val entries by vm.entries.collectAsState()
    val context = LocalContext.current

    var tab by remember { mutableStateOf(0) }
    var from by remember { mutableStateOf(LocalDate.of(LocalDate.now().year, 1, 1)) }
    var to by remember { mutableStateOf(LocalDate.now()) }
    var ledgerAccount by remember { mutableStateOf<String?>(null) }
    var balance by remember { mutableStateOf<List<BalanceRow>>(emptyList()) }
    var ledger by remember { mutableStateOf<LedgerReport?>(null) }
    var result by remember { mutableStateOf<ResultReport?>(null) }
    var situation by remember { mutableStateOf<SituationReport?>(null) }

    LaunchedEffect(tab, from, to, ledgerAccount, entries, company?.id) {
        val c = company?.id ?: return@LaunchedEffect
        when (tab) {
            0 -> balance = vm.safely { vm.reports.trialBalance(c, from, to) } ?: emptyList()
            1 -> ledger = ledgerAccount?.let { a -> vm.safely { vm.reports.ledger(c, a, from, to) } }
            2 -> result = vm.safely { vm.reports.result(c, from, to) }
            else -> situation = vm.safely { vm.reports.situation(c, to) }
        }
    }

    // Classeur Excel de tous les rapports de la période, enregistré à l'emplacement choisi par l'utilisateur
    val excelExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME_TYPE)) { uri ->
        val c = company?.id
        if (uri != null && c != null) {
            vm.act("Classeur Excel enregistré.") { vm.exportFinancialWorkbook(uri, c, from, to) }
        }
    }

    ListPage(
        title = "Rapports",
        onBack = { vm.nav.pop() },
        actions = {
            if (tab <= 1) {
                TextButton(onClick = {
                    when (tab) {
                        0 -> shareText(context, "Balance générale", CsvExport.trialBalance(balance))
                        else -> ledger?.let { shareText(context, "Grand livre", CsvExport.ledger(it)) }
                    }
                }) { Text("CSV", color = Teal, fontSize = 12.sp) }
            }
            TextButton(onClick = { excelExport.launch("rapports-financiers-$from-au-$to.xlsx") }) {
                Text("Exporter Excel", color = Teal, fontSize = 12.sp)
            }
        },
        header = {
            TabsRow(listOf("Balance", "Grand livre", "Résultat", "Situation"), tab) { tab = it }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tab != 3) DateField("Du", from, { from = it }, Modifier.weight(1f))
                DateField(if (tab == 3) "Situation au" else "Au", to, { to = it }, Modifier.weight(1f))
            }
            if (tab == 1) {
                AccountField(
                    label = "Compte", accounts = accounts, selectedId = ledgerAccount,
                    onSelect = { ledgerAccount = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }
        }
    ) {
        when (tab) {
            0 -> {
                if (balance.isEmpty()) item { EmptyHint("Aucune écriture validée sur cette période.") }
                items(balance, key = { it.account.id }) { r -> BalanceCard(r) }
                if (balance.isNotEmpty()) {
                    item {
                        SurfaceCard {
                            TotalRow("Total débit", balance.fold(BigDecimal.ZERO) { a, r -> a.add(r.debit) }, bold = true)
                            TotalRow("Total crédit", balance.fold(BigDecimal.ZERO) { a, r -> a.add(r.credit) }, bold = true)
                        }
                    }
                }
            }
            1 -> {
                val l = ledger
                if (l == null) {
                    item { EmptyHint("Choisissez un compte pour afficher son grand livre.") }
                } else {
                    item { SurfaceCard { TotalRow("Report à nouveau (solde avant le ${from})", l.opening, bold = true) } }
                    if (l.rows.isEmpty()) item { EmptyHint("Aucun mouvement sur la période.") }
                    items(l.rows.size) { i ->
                        val r = l.rows[i]
                        SurfaceCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${r.date} · ${r.number}", color = Navy, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                    Text(listOf(r.label, r.thirdParty).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 12.sp)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    if (r.debit.signum() > 0) Text("D ${r.debit.fmt()}", color = Navy, fontSize = 13.sp)
                                    if (r.credit.signum() > 0) Text("C ${r.credit.fmt()}", color = Navy, fontSize = 13.sp)
                                    Text("Solde ${r.running.fmt()}", color = Muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    item {
                        SurfaceCard {
                            TotalRow("Total débit", l.totalDebit)
                            TotalRow("Total crédit", l.totalCredit)
                            TotalRow("Solde final (+ débiteur / − créditeur)", l.closing, bold = true)
                        }
                    }
                }
            }
            2 -> {
                val r = result
                if (r == null) {
                    item { EmptyHint("Calcul en cours…") }
                } else {
                    item { SectionTitle("Produits") }
                    items(r.revenues, key = { "r" + it.account.id }) { row ->
                        SurfaceCard { TotalRow("${row.account.code} ${row.account.label}", row.credit.subtract(row.debit)) }
                    }
                    item { SurfaceCard { TotalRow("Total des produits", r.totalRevenue, bold = true, color = Good) } }
                    item { SectionTitle("Charges") }
                    items(r.expenses, key = { "e" + it.account.id }) { row ->
                        SurfaceCard { TotalRow("${row.account.code} ${row.account.label}", row.debit.subtract(row.credit)) }
                    }
                    item { SurfaceCard { TotalRow("Total des charges", r.totalExpense, bold = true, color = Danger) } }
                    item {
                        SurfaceCard {
                            TotalRow(
                                if (r.result.signum() >= 0) "Résultat : bénéfice" else "Résultat : perte",
                                r.result.abs(), bold = true, color = if (r.result.signum() >= 0) Good else Danger
                            )
                        }
                    }
                }
            }
            else -> {
                val s = situation
                if (s == null) {
                    item { EmptyHint("Calcul en cours…") }
                } else {
                    item { SectionTitle("Actif (soldes débiteurs)") }
                    items(s.assets.size) { i ->
                        val (a, v) = s.assets[i]
                        SurfaceCard { TotalRow("${a.code} ${a.label}", v) }
                    }
                    item { SurfaceCard { TotalRow("Total actif", s.totalAssets, bold = true) } }
                    item { SectionTitle("Passif (soldes créditeurs)") }
                    items(s.liabilities.size) { i ->
                        val (a, v) = s.liabilities[i]
                        SurfaceCard { TotalRow("${a.code} ${a.label}", v) }
                    }
                    item { SurfaceCard { TotalRow("Résultat de la période (cumul)", s.result) } }
                    item { SurfaceCard { TotalRow("Total passif", s.totalLiabilities, bold = true) } }
                    item {
                        Text(
                            "Situation simplifiée, calculée à partir des soldes des classes 1 à 5. " +
                                "Elle n'est pas un bilan légal : à faire valider par votre expert-comptable.",
                            color = Muted, fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceCard(r: BalanceRow) {
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${r.account.code} — ${r.account.label}", color = Navy, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text("Débit ${r.debit.fmt()} · Crédit ${r.credit.fmt()}", color = Muted, fontSize = 11.sp)
            }
            val net = r.net
            Text(
                if (net.signum() >= 0) "${net.fmt()} D" else "${net.negate().fmt()} C",
                color = Navy, fontWeight = FontWeight.Bold, fontSize = 13.sp
            )
        }
    }
}
