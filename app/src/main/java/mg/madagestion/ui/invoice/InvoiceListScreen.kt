package mg.madagestion.ui.invoice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.time.LocalDate
import mg.madagestion.core.fmt
import mg.madagestion.core.sumBd
import mg.madagestion.data.model.DocStatus
import mg.madagestion.data.model.InvoiceKind
import mg.madagestion.data.model.ThirdPartyType
import mg.madagestion.domain.invoicing.amountDue
import mg.madagestion.domain.invoicing.isCredit
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.ChipRow
import mg.madagestion.ui.components.EmptyHint
import mg.madagestion.ui.components.ListPage
import mg.madagestion.ui.components.MetricTile
import mg.madagestion.ui.components.MoneyText
import mg.madagestion.ui.components.StatusPill
import mg.madagestion.ui.components.SurfaceCard
import mg.madagestion.ui.components.TabsRow
import mg.madagestion.ui.format.nameOf
import mg.madagestion.ui.navigation.Screen
import mg.madagestion.ui.theme.Danger
import mg.madagestion.ui.theme.Muted
import mg.madagestion.ui.theme.Navy
import mg.madagestion.ui.theme.Teal

/** Onglets de l'écran : les factures et les avoirs sont listés séparément. */
private const val TAB_INVOICES = 0
private const val TAB_CREDITS = 1

@Composable
fun InvoiceListScreen(vm: AppViewModel, sale: Boolean) {
    val invoices by vm.invoices.collectAsState()
    val allocations by vm.allocations.collectAsState()
    val parties by vm.thirdParties.collectAsState()
    var tab by remember { mutableStateOf(TAB_INVOICES) }
    var filter by remember { mutableStateOf("ALL") }

    val mainKind = if (sale) InvoiceKind.SALE else InvoiceKind.PURCHASE
    val creditKind = if (sale) InvoiceKind.SALE_CREDIT else InvoiceKind.PURCHASE_CREDIT
    val open = remember(invoices, allocations) { openAmounts(invoices, allocations) }
    val creditLeft = remember(invoices, allocations) { creditsAvailable(invoices, allocations) }
    val today = LocalDate.now()

    val shown = if (tab == TAB_INVOICES) {
        invoices.filter { it.kind == mainKind }.filter {
            val o = open[it.id] ?: BigDecimal.ZERO
            when (filter) {
                "DRAFT" -> it.status == DocStatus.DRAFT
                "OPEN" -> o.signum() > 0
                "LATE" -> o.signum() > 0 && it.dueDate.isBefore(today)
                else -> true
            }
        }
    } else {
        invoices.filter { it.kind == creditKind }.filter {
            when (filter) {
                "DRAFT" -> it.status == DocStatus.DRAFT
                "AVAILABLE" -> (creditLeft[it.id] ?: BigDecimal.ZERO).signum() > 0
                else -> true
            }
        }
    }
    val totalOpen = invoices.filter { it.kind == mainKind }.sumBd { open[it.id] ?: BigDecimal.ZERO }
    val lateOpen = invoices.filter { it.kind == mainKind && it.dueDate.isBefore(today) }
        .sumBd { open[it.id] ?: BigDecimal.ZERO }
    val creditsTotal = invoices.filter { it.kind == creditKind }.sumBd { creditLeft[it.id] ?: BigDecimal.ZERO }

    ListPage(
        title = if (sale) "Ventes" else "Achats",
        onBack = if (vm.nav.atRoot) null else ({ vm.nav.pop() }),
        fabLabel = if (tab != TAB_INVOICES) null else if (sale) "Nouvelle facture" else "Nouvel achat",
        onFab = { vm.nav.push(Screen.FactureEdit(null, mainKind)) },
        actions = {
            TextButton(onClick = { vm.nav.push(Screen.Tiers(if (sale) ThirdPartyType.CUSTOMER else ThirdPartyType.SUPPLIER)) }) {
                Text(if (sale) "Clients" else "Fournisseurs", color = Teal, fontSize = 12.sp)
            }
            TextButton(onClick = { vm.nav.push(Screen.Reglements) }) {
                Text("Règlements", color = Teal, fontSize = 12.sp)
            }
        },
        header = {
            TabsRow(listOf(if (sale) "Factures" else "Achats", "Avoirs"), tab) {
                tab = it
                filter = "ALL"
            }
            if (tab == TAB_INVOICES) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile(if (sale) "À encaisser" else "À payer", totalOpen, Modifier.weight(1f))
                    MetricTile("Dont en retard", lateOpen, Modifier.weight(1f), color = if (lateOpen.signum() > 0) Danger else Navy)
                }
                ChipRow(
                    listOf("ALL" to "Tous", "DRAFT" to "Brouillons", "OPEN" to "Non soldés", "LATE" to "En retard"),
                    filter
                ) { filter = it }
            } else {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                    MetricTile("Avoirs restant à imputer", creditsTotal, Modifier.weight(1f))
                }
                ChipRow(listOf("ALL" to "Tous", "DRAFT" to "Brouillons", "AVAILABLE" to "À imputer"), filter) { filter = it }
            }
        }
    ) {
        if (shown.isEmpty()) {
            item {
                EmptyHint(
                    when {
                        filter != "ALL" -> "Aucun document pour ce filtre."
                        tab == TAB_CREDITS -> "Aucun avoir. Un avoir se crée depuis la facture concernée, bouton « Créer un avoir »."
                        else -> "Aucun document. Touchez le bouton ci-dessous pour en créer un."
                    }
                )
            }
        }
        items(shown, key = { it.id }) { inv ->
            val (statusText, statusColor) = invoiceStatus(inv, open[inv.id], creditLeft[inv.id])
            SurfaceCard(onClick = { vm.nav.push(Screen.FactureDetail(inv.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(inv.number ?: "Brouillon", fontWeight = FontWeight.SemiBold, color = Navy)
                        Text(parties.nameOf(inv.thirdPartyId), color = Muted, fontSize = 13.sp)
                        Text(
                            if (inv.kind.isCredit()) "${inv.date} · reste à imputer ${(creditLeft[inv.id] ?: BigDecimal.ZERO).fmt()} Ar"
                            else "${inv.date} · éch. ${inv.dueDate}",
                            color = Muted, fontSize = 11.sp
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        MoneyText(inv.amountDue(), bold = true)
                        StatusPill(statusText, statusColor)
                    }
                }
            }
        }
    }
}
