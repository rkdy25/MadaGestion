package mg.madagestion.ui.party

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import mg.madagestion.core.fmt
import mg.madagestion.data.model.PaymentDirection
import mg.madagestion.data.model.ThirdPartyType
import mg.madagestion.domain.party.PartyMove
import mg.madagestion.domain.party.PartyOperationKind
import mg.madagestion.domain.party.PartyStatement
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.ConfirmDialog
import mg.madagestion.ui.components.EmptyHint
import mg.madagestion.ui.components.FormPage
import mg.madagestion.ui.components.ListPage
import mg.madagestion.ui.components.PrimaryButton
import mg.madagestion.ui.components.SecondaryButton
import mg.madagestion.ui.components.SectionTitle
import mg.madagestion.ui.components.SurfaceCard
import mg.madagestion.ui.components.TotalRow
import mg.madagestion.ui.format.label
import mg.madagestion.ui.navigation.Screen
import mg.madagestion.ui.theme.Danger
import mg.madagestion.ui.theme.Good
import mg.madagestion.ui.theme.Muted
import mg.madagestion.ui.theme.Navy
import mg.madagestion.ui.theme.Teal

/**
 * Compte d'un client ou d'un fournisseur : ce qui a été facturé, les frais et ristournes,
 * ce qui est déjà réglé et le solde exact restant à encaisser ou à payer.
 */
@Composable
fun ThirdPartyDetailScreen(vm: AppViewModel, id: String) {
    val parties by vm.thirdParties.collectAsState()
    val entries by vm.entries.collectAsState()
    val company by vm.company.collectAsState()
    val party = parties.firstOrNull { it.id == id }
    var statement by remember { mutableStateOf<PartyStatement?>(null) }
    var cancelling by remember { mutableStateOf<PartyMove?>(null) }

    LaunchedEffect(id, entries, company?.id) {
        val c = company?.id ?: return@LaunchedEffect
        statement = vm.safely { vm.partyAccounts.statement(c, id) }
    }

    if (party == null) {
        FormPage("Tiers", { vm.nav.pop() }) { EmptyHint("Tiers introuvable.") }
        return
    }
    val customer = party.type == ThirdPartyType.CUSTOMER
    val s = statement
    val moves = s?.moves?.reversed() ?: emptyList()

    ListPage(
        title = party.name,
        onBack = { vm.nav.pop() },
        actions = {
            TextButton(onClick = { vm.nav.push(Screen.TiersEdit(party.id, party.type)) }) {
                Text("Modifier la fiche", color = Teal, fontSize = 12.sp)
            }
        }
    ) {
        item {
            SurfaceCard {
                if (s == null) {
                    Text("Calcul en cours…", color = Muted, fontSize = 13.sp)
                } else {
                    if (s.opening.signum() != 0) TotalRow("Reprise de la situation initiale", s.opening)
                    TotalRow("Facturé", s.invoiced)
                    if (s.charges.signum() != 0) TotalRow("Frais (transport, manutention…)", s.charges)
                    if (s.credits.signum() != 0) TotalRow("Avoirs et ristournes déduits", s.credits)
                    if (s.other.signum() != 0) TotalRow("Autres écritures", s.other)
                    TotalRow("Déjà réglé", s.paid, color = Good)
                    val balance = s.balance
                    TotalRow(
                        when {
                            balance.signum() >= 0 -> if (customer) "Solde à encaisser" else "Solde à payer"
                            customer -> "Avance du client"
                            else -> "Avance versée au fournisseur"
                        },
                        balance.abs(), bold = true, color = if (balance.signum() > 0) Danger else Good
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    if (customer) "Encaisser un règlement ou une avance" else "Payer ou verser une avance",
                    {
                        val direction = if (customer) PaymentDirection.RECEIPT else PaymentDirection.DISBURSEMENT
                        vm.nav.push(Screen.ReglementNew(direction, thirdPartyId = party.id))
                    },
                    Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SecondaryButton("+ Frais", { vm.nav.push(Screen.TiersOperation(party.id, PartyOperationKind.CHARGE)) }, Modifier.weight(1f))
                    SecondaryButton("+ Ristourne", { vm.nav.push(Screen.TiersOperation(party.id, PartyOperationKind.DISCOUNT)) }, Modifier.weight(1f))
                }
            }
        }
        item { SectionTitle("Mouvements du compte") }
        if (s != null && moves.isEmpty()) item { EmptyHint("Aucun mouvement validé pour ce tiers.") }
        items(moves.size) { i ->
            val m = moves[i]
            SurfaceCard(onClick = { vm.nav.push(Screen.EcritureDetail(m.entryId)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.label.ifBlank { m.kind.label() }, color = Navy, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("${m.date} · ${m.kind.label()} · ${m.number}", color = Muted, fontSize = 11.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            (if (m.amount.signum() >= 0) "+ " else "− ") + m.amount.abs().fmt() + " Ar",
                            color = if (m.amount.signum() >= 0) Navy else Good, fontWeight = FontWeight.Bold, fontSize = 13.sp
                        )
                        Text("Solde ${m.balance.fmt()}", color = Muted, fontSize = 11.sp)
                    }
                }
                if (m.cancellable) {
                    TextButton(onClick = { cancelling = m }) { Text("Annuler cette opération", color = Danger, fontSize = 12.sp) }
                }
            }
        }
    }

    cancelling?.let { m ->
        ConfirmDialog(
            "Annuler cette opération ?",
            "Une écriture de contrepassation de ${m.amount.abs().fmt()} Ar sera passée à la date d'aujourd'hui. Le solde du tiers sera rétabli.",
            "Annuler l'opération",
            {
                cancelling = null
                vm.act("Opération annulée.") { vm.partyOperations.cancel(m.entryId, LocalDate.now()) }
            },
            { cancelling = null }
        )
    }
}
