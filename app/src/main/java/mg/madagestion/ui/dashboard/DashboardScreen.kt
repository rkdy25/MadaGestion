package mg.madagestion.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.time.LocalDate
import mg.madagestion.core.fmt
import mg.madagestion.data.model.DocStatus
import mg.madagestion.data.model.InvoiceKind
import mg.madagestion.data.model.PaymentDirection
import mg.madagestion.data.model.ThirdPartyType
import mg.madagestion.domain.control.Control
import mg.madagestion.domain.control.Severity
import mg.madagestion.domain.report.DashboardStats
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.MetricTile
import mg.madagestion.ui.components.PrimaryButton
import mg.madagestion.ui.components.SecondaryButton
import mg.madagestion.ui.components.SurfaceCard
import mg.madagestion.ui.invoice.openAmounts
import mg.madagestion.ui.navigation.Screen
import mg.madagestion.ui.theme.Danger
import mg.madagestion.ui.theme.Good
import mg.madagestion.ui.theme.Muted
import mg.madagestion.ui.theme.Navy
import mg.madagestion.ui.theme.Teal
import mg.madagestion.ui.theme.Warning

@Composable
fun DashboardScreen(vm: AppViewModel) {
    val company by vm.company.collectAsState()
    val entries by vm.entries.collectAsState()
    val invoices by vm.invoices.collectAsState()
    val allocations by vm.allocations.collectAsState()
    var stats by remember { mutableStateOf<DashboardStats?>(null) }
    var controls by remember { mutableStateOf<List<Control>?>(null) }
    val year = LocalDate.now().year

    LaunchedEffect(company?.id, entries) {
        val c = company?.id ?: return@LaunchedEffect
        stats = vm.safely { vm.reports.dashboard(c, year) }
        controls = vm.safely { vm.controls.runAll(c) }
    }

    val open = remember(invoices, allocations) { openAmounts(invoices, allocations) }
    val today = LocalDate.now()
    val lateCustomers = invoices.filter { it.kind == InvoiceKind.SALE && (open[it.id] ?: BigDecimal.ZERO).signum() > 0 && it.dueDate.isBefore(today) }
    val lateSuppliers = invoices.filter { it.kind == InvoiceKind.PURCHASE && (open[it.id] ?: BigDecimal.ZERO).signum() > 0 && it.dueDate.isBefore(today) }
    val drafts = invoices.count { it.status == DocStatus.DRAFT }
    val s = stats

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Bonjour !", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Navy)
                Text("Voici l'état de votre activité en $year", color = Muted)
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Navy),
                shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("CHIFFRE D'AFFAIRES $year", color = Color(0xFFD9E5EF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("${(s?.revenue ?: BigDecimal.ZERO).fmt()} Ar", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (s == null) "Calcul en cours…" else "Résultat de l'exercice : ${s.result.fmt()} Ar",
                        color = Color(0xFFD9E5EF), fontSize = 12.sp
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile("Créances clients", s?.receivables ?: BigDecimal.ZERO, Modifier.weight(1f))
                MetricTile("Dettes fournisseurs", s?.payables ?: BigDecimal.ZERO, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile("Trésorerie", s?.treasury ?: BigDecimal.ZERO, Modifier.weight(1f))
                MetricTile("Valeur du stock", s?.stockValue ?: BigDecimal.ZERO, Modifier.weight(1f))
            }
        }
        item {
            val vat = s?.vatToPay ?: BigDecimal.ZERO
            MetricTile(
                if (vat.signum() >= 0) "TVA à décaisser" else "Crédit de TVA",
                vat.abs(), Modifier.fillMaxWidth()
            )
        }

        // Alertes
        item {
            val list = controls
            val errors = list?.count { it.severity == Severity.ERROR } ?: 0
            val warns = list?.count { it.severity == Severity.WARN } ?: 0
            SurfaceCard(onClick = { vm.nav.root(Screen.Controles) }) {
                Text("Contrôles", fontWeight = FontWeight.Bold, color = Navy)
                when {
                    list == null -> Text("Analyse en cours…", color = Muted, fontSize = 13.sp)
                    errors > 0 -> Text("$errors erreur(s) d'intégrité à corriger", color = Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    warns > 0 -> Text("$warns alerte(s) à examiner", color = Warning, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    else -> Text("✓ Aucune anomalie détectée", color = Good, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                if (lateCustomers.isNotEmpty()) {
                    Text("${lateCustomers.size} facture(s) client en retard de paiement", color = Warning, fontSize = 12.sp)
                }
                if (lateSuppliers.isNotEmpty()) {
                    Text("${lateSuppliers.size} facture(s) fournisseur échue(s) à régler", color = Warning, fontSize = 12.sp)
                }
                if (drafts > 0) {
                    Text("$drafts facture(s) en brouillon", color = Muted, fontSize = 12.sp)
                }
            }
        }

        // Saisies rapides
        item { Text("Saisie rapide", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Navy) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    PrimaryButton("Nouvelle vente", { vm.nav.push(Screen.FactureEdit(null, InvoiceKind.SALE)) }, Modifier.weight(1f))
                    PrimaryButton("Nouvel achat", { vm.nav.push(Screen.FactureEdit(null, InvoiceKind.PURCHASE)) }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SecondaryButton("Encaisser", { vm.nav.push(Screen.ReglementNew(PaymentDirection.RECEIPT)) }, Modifier.weight(1f))
                    SecondaryButton("Décaisser", { vm.nav.push(Screen.ReglementNew(PaymentDirection.DISBURSEMENT)) }, Modifier.weight(1f))
                }
                SecondaryButton("Nouvelle écriture comptable", { vm.nav.push(Screen.EcritureEdit(null)) }, Modifier.fillMaxWidth())
            }
        }

        // Modules
        item { Text("Modules", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Navy) }
        item { ModuleCard("Trésorerie", "Caisse, banque et Mobile Money", Icons.Default.AccountBalanceWallet) { vm.nav.push(Screen.Reglements) } }
        item { ModuleCard("Stocks", "Articles, CUMP et mouvements", Icons.Default.Inventory) { vm.nav.push(Screen.Stocks) } }
        item { ModuleCard("Clients et fournisseurs", "Comptes, frais, ristournes et soldes", Icons.Default.People) { vm.nav.push(Screen.Tiers(ThirdPartyType.CUSTOMER)) } }
        item { ModuleCard("Rapports", "Balance, grand livre, résultat, bilan, export Excel", Icons.Default.BarChart) { vm.nav.push(Screen.Rapports) } }
        item { ModuleCard("Situation initiale", "Bilan d'ouverture de l'entreprise", Icons.Default.AccountBalance) { vm.nav.push(Screen.SituationInitiale) } }
    }
}

@Composable
private fun ModuleCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        onClick = onClick, colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(Color(0xFFE7F3F2), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Teal)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = Navy)
                Text(subtitle, fontSize = 12.sp, color = Muted)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Muted)
        }
    }
}
