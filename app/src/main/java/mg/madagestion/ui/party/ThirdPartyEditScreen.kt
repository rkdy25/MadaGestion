package mg.madagestion.ui.party

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import mg.madagestion.core.BusinessException
import mg.madagestion.core.newId
import mg.madagestion.data.model.ThirdParty
import mg.madagestion.data.model.ThirdPartyType
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.FormPage
import mg.madagestion.ui.components.PrimaryButton
import mg.madagestion.ui.navigation.Screen
import mg.madagestion.ui.theme.Navy

@Composable
fun ThirdPartyEditScreen(vm: AppViewModel, id: String?, type: ThirdPartyType) {
    val parties by vm.thirdParties.collectAsState()
    val existing = parties.firstOrNull { it.id == id }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var nif by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(id == null) }

    LaunchedEffect(existing?.id) {
        if (!loaded && existing != null) {
            name = existing.name; phone = existing.phone; email = existing.email
            nif = existing.nif; address = existing.address; active = existing.active
            loaded = true
        }
    }
    val noun = if (type == ThirdPartyType.CUSTOMER) "client" else "fournisseur"

    FormPage(if (id == null) "Nouveau $noun" else "Modifier le $noun", { vm.nav.pop() }) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Téléphone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("E-mail") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = nif, onValueChange = { nif = it }, label = { Text("NIF") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Adresse") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Actif", color = Navy, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Switch(checked = active, onCheckedChange = { active = it })
        }
        PrimaryButton("Enregistrer", {
            vm.act<Unit>("Enregistré.", {
                vm.nav.pop()
                // Création depuis la liste : on revient sur l'onglet du type créé.
                if (id == null) vm.nav.replace(Screen.Tiers(type))
            }) {
                if (name.isBlank()) throw BusinessException("Le nom est obligatoire.")
                val c = vm.requireCompany()
                if (existing == null) {
                    val newPartyId = newId()
                    vm.db.thirdPartyDao().insert(
                        ThirdParty(newPartyId, c, type, name.trim(), phone.trim(), email.trim(), nif.trim(), address.trim(), active)
                    )
                    vm.accounting.audit(c, "THIRD_PARTY_CREATE", "THIRD_PARTY", newPartyId, name.trim())
                } else {
                    vm.db.thirdPartyDao().update(
                        existing.copy(name = name.trim(), phone = phone.trim(), email = email.trim(), nif = nif.trim(), address = address.trim(), active = active)
                    )
                    vm.accounting.audit(c, "THIRD_PARTY_UPDATE", "THIRD_PARTY", existing.id, name.trim())
                }
            }
        }, Modifier.fillMaxWidth())
    }
}
