package mg.madagestion.ui.payment

import androidx.compose.foundation.layout.Arrangement
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
import mg.madagestion.data.model.PaymentDirection
import mg.madagestion.domain.accounting.MappingKey
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.EmptyHint
import mg.madagestion.ui.components.ListPage
import mg.madagestion.ui.components.MetricTile
import mg.madagestion.ui.components.PrimaryButton
import mg.madagestion.ui.components.SecondaryButton
import mg.madagestion.ui.components.StatusPill
import mg.madagestion.ui.components.SurfaceCard
import mg.madagestion.ui.format.label
import mg.madagestion.ui.format.nameOf
import mg.madagestion.ui.navigation.Screen
import mg.madagestion.ui.theme.Danger
import mg.madagestion.ui.theme.Good
import mg.madagestion.ui.theme.Muted
import mg.madagestion.ui.theme.Navy
import mg.madagestion.ui.theme.Warning

@Composable
fun PaymentsScreen(vm: AppViewModel) {
    val payments by vm.payments.collectAsState()
    val parties by vm.thirdParties.collectAsState()
    val entries by vm.entries.collectAsState()
    val company by vm.company.collectAsState()
    var balances by remember { mutableStateOf<List<BigDecimal>?>(null) }

    LaunchedEffect(entries, company?.id) {
        if (company == null) return@LaunchedEffect
        balances = vm.safely {
            listOf(vm.netOf(MappingKey.CASH), vm.netOf(MappingKey.BANK), vm.netOf(MappingKey.MOBILE))
        }
    }

    ListPage(
        title = "Trésorerie",
        onBack = { vm.nav.pop() },
        header = {
            val b = balances
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("Caisse", b?.get(0) ?: BigDecimal.ZERO, Modifier.weight(1f), if ((b?.get(0) ?: BigDecimal.ZERO).signum() < 0) Danger else Navy)
                MetricTile("Banque", b?.get(1) ?: BigDecimal.ZERO, Modifier.weight(1f), if ((b?.get(1) ?: BigDecimal.ZERO).signum() < 0) Warning else Navy)
                MetricTile("Mobile Money", b?.get(2) ?: BigDecimal.ZERO, Modifier.weight(1f), if ((b?.get(2) ?: BigDecimal.ZERO).signum() < 0) Danger else Navy)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("+ Encaissement", { vm.nav.push(Screen.ReglementNew(PaymentDirection.RECEIPT)) }, Modifier.weight(1f))
                SecondaryButton("+ Décaissement", { vm.nav.push(Screen.ReglementNew(PaymentDirection.DISBURSEMENT)) }, Modifier.weight(1f))
            }
        }
    ) {
        if (payments.isEmpty()) item { EmptyHint("Aucun règlement enregistré.") }
        items(payments, key = { it.id }) { p ->
            val receipt = p.direction == PaymentDirection.RECEIPT
            SurfaceCard(onClick = { vm.nav.push(Screen.ReglementDetail(p.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.number, fontWeight = FontWeight.SemiBold, color = Navy)
                        Text(parties.nameOf(p.thirdPartyId), color = Muted, fontSize = 13.sp)
                        Text("${p.date} · ${p.method.label()}", color = Muted, fontSize = 11.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            (if (receipt) "+ " else "− ") + p.amount.fmt() + " Ar",
                            color = if (p.cancelled) Muted else if (receipt) Good else Danger,
                            fontWeight = FontWeight.Bold
                        )
                        if (p.cancelled) StatusPill("Annulé", Muted)
                    }
                }
            }
        }
    }
}
