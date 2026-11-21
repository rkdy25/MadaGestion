package mg.madagestion.ui.party

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import mg.madagestion.data.model.ThirdPartyType
import mg.madagestion.domain.accounting.accountClass
import mg.madagestion.domain.party.PartyOperationKind
import mg.madagestion.domain.party.defaultMapping
import mg.madagestion.domain.party.label
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.AccountField
import mg.madagestion.ui.components.AmountField
import mg.madagestion.ui.components.ChipRow
import mg.madagestion.ui.components.DateField
import mg.madagestion.ui.components.EmptyHint
import mg.madagestion.ui.components.FormPage
import mg.madagestion.ui.components.PrimaryButton
import mg.madagestion.ui.format.parseDecimal
import mg.madagestion.ui.theme.Muted

/** Saisie d'un frais (transport, manutention…) ou d'une ristourne rattaché à un client ou à un fournisseur. */
@Composable
fun PartyOperationScreen(vm: AppViewModel, thirdPartyId: String, kind: PartyOperationKind) {
    val parties by vm.thirdParties.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val mappings by vm.mappings.collectAsState()
    val party = parties.firstOrNull { it.id == thirdPartyId }

    var date by remember { mutableStateOf(LocalDate.now()) }
    var amount by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf<String?>(null) }

    if (party == null) {
        FormPage("Opération", { vm.nav.pop() }) { EmptyHint("Tiers introuvable.") }
        return
    }
    val customer = party.type == ThirdPartyType.CUSTOMER
    val charge = kind == PartyOperationKind.CHARGE
    val suggestions = if (charge) listOf("Transport", "Manutention", "Emballage") else listOf("Ristourne", "Remise", "Rabais")
    val defaultAccount = mappings.firstOrNull { it.mapKey == kind.defaultMapping(party.type).name }
        ?.let { m -> accounts.firstOrNull { it.id == m.accountId } }

    FormPage(kind.label(party.type), { vm.nav.pop() }) {
        Text(party.name, color = Muted, fontSize = 13.sp)
        ChipRow(suggestions.map { it to it }, label) { label = it }
        OutlinedTextField(
            value = label, onValueChange = { label = it },
            label = { Text("Nature (transport, manutention…)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DateField("Date", date, { date = it }, Modifier.weight(1f))
            AmountField("Montant (Ar)", amount, { amount = it }, Modifier.weight(1f))
        }
        OutlinedTextField(
            value = reference, onValueChange = { reference = it },
            label = { Text("Référence (facultatif)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        AccountField(
            label = "Compte de contrepartie (facultatif)",
            accounts = accounts.filter { it.accountClass() == (if (customer) '7' else '6') },
            selectedId = accountId,
            onSelect = { accountId = it },
            modifier = Modifier.fillMaxWidth(),
            onClear = { accountId = null }
        )
        if (accountId == null && defaultAccount != null) {
            Text("Compte utilisé par défaut : ${defaultAccount.code} — ${defaultAccount.label}", color = Muted, fontSize = 11.sp)
        }
        PrimaryButton("Enregistrer", {
            vm.act<String>("Opération enregistrée.", { vm.nav.pop() }) {
                vm.partyOperations.record(
                    vm.requireCompany(), party.id, kind, date, parseDecimal(amount), label, reference, accountId
                )
            }
        }, Modifier.fillMaxWidth())
        Text(
            "Ce montant " + (if (charge) "s'ajoute au" else "se déduit du") +
                (if (customer) " solde à encaisser de ${party.name}." else " solde à payer à ${party.name}."),
            color = Muted, fontSize = 11.sp
        )
    }
}
