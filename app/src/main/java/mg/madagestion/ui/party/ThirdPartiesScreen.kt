package mg.madagestion.ui.party

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import mg.madagestion.core.fmt
import mg.madagestion.core.sumBd
import mg.madagestion.data.model.ThirdPartyType
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.EmptyHint
import mg.madagestion.ui.components.ListPage
import mg.madagestion.ui.components.MetricTile
import mg.madagestion.ui.components.StatusPill
import mg.madagestion.ui.components.SurfaceCard
import mg.madagestion.ui.components.TabsRow
import mg.madagestion.ui.navigation.Screen
import mg.madagestion.ui.theme.Muted
import mg.madagestion.ui.theme.Navy
import mg.madagestion.ui.theme.Teal
import mg.madagestion.ui.theme.Warning

/** Clients et fournisseurs, avec le solde exact de leur compte (factures, frais, ristournes, règlements). */
@Composable
fun ThirdPartiesScreen(vm: AppViewModel, initialType: ThirdPartyType) {
    val parties by vm.thirdParties.collectAsState()
    val entries by vm.entries.collectAsState()
    val company by vm.company.collectAsState()
    var tab by remember { mutableStateOf(if (initialType == ThirdPartyType.CUSTOMER) 0 else 1) }
    var balances by remember { mutableStateOf<Map<String, BigDecimal>>(emptyMap()) }
    val type = if (tab == 0) ThirdPartyType.CUSTOMER else ThirdPartyType.SUPPLIER

    LaunchedEffect(entries, company?.id) {
        val c = company?.id ?: return@LaunchedEffect
        balances = vm.safely { vm.partyAccounts.balances(c) } ?: emptyMap()
    }

    val shown = parties.filter { it.type == type }
    val totalDue = shown.sumBd { (balances[it.id] ?: BigDecimal.ZERO).max(BigDecimal.ZERO) }

    ListPage(
        title = "Tiers",
        onBack = { vm.nav.pop() },
        fabLabel = if (tab == 0) "Nouveau client" else "Nouveau fournisseur",
        onFab = { vm.nav.push(Screen.TiersEdit(null, type)) },
        header = {
            TabsRow(listOf("Clients", "Fournisseurs"), tab) { tab = it }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                MetricTile(if (tab == 0) "Total à encaisser" else "Total à payer", totalDue, Modifier.weight(1f))
            }
        }
    ) {
        if (shown.isEmpty()) item { EmptyHint("Aucun ${if (tab == 0) "client" else "fournisseur"} enregistré.") }
        items(shown, key = { it.id }) { t ->
            SurfaceCard(onClick = { vm.nav.push(Screen.TiersDetail(t.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontWeight = FontWeight.SemiBold, color = Navy)
                        Text(listOf(t.phone, t.email).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        val balance = balances[t.id] ?: BigDecimal.ZERO
                        if (balance.signum() > 0) {
                            Text("${balance.fmt()} Ar", fontWeight = FontWeight.Bold, color = Navy)
                            Text(if (tab == 0) "à encaisser" else "à payer", color = Muted, fontSize = 11.sp)
                        } else if (balance.signum() < 0) {
                            Text("${balance.negate().fmt()} Ar", fontWeight = FontWeight.Bold, color = Teal)
                            Text("d'avance", color = Muted, fontSize = 11.sp)
                        }
                        if (!t.active) StatusPill("Inactif", Warning)
                    }
                }
            }
        }
    }
}
