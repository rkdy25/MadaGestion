package mg.madagestion.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import mg.madagestion.core.BusinessException
import mg.madagestion.domain.accounting.MappingKey
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.AccountField
import mg.madagestion.ui.components.AmountField
import mg.madagestion.ui.components.FormPage
import mg.madagestion.ui.components.PrimaryButton
import mg.madagestion.ui.components.SecondaryButton
import mg.madagestion.ui.components.SectionTitle
import mg.madagestion.ui.components.SurfaceCard
import mg.madagestion.ui.format.parseDecimal
import mg.madagestion.ui.navigation.Screen
import mg.madagestion.ui.theme.Muted
import mg.madagestion.ui.theme.Navy

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val company by vm.company.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val mappings by vm.mappings.collectAsState()
    val audit by vm.audit.collectAsState()

    var name by remember { mutableStateOf("") }
    var nif by remember { mutableStateOf("") }
    var stat by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var vat by remember { mutableStateOf("20") }
    var loaded by remember { mutableStateOf(false) }
    var showAudit by remember { mutableStateOf(false) }

    LaunchedEffect(company?.id) {
        val c = company
        if (!loaded && c != null) {
            name = c.name; nif = c.nif; stat = c.stat; address = c.address; phone = c.phone
            vat = c.defaultVatRate.stripTrailingZeros().toPlainString()
            loaded = true
        }
    }

    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.act("Sauvegarde enregistrée.") { vm.backupTo(uri) }
    }

    FormPage("Paramètres", { vm.nav.pop() }) {
        SectionTitle("Entreprise")
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Raison sociale") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = nif, onValueChange = { nif = it }, label = { Text("NIF") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = stat, onValueChange = { stat = it }, label = { Text("STAT") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Adresse") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Téléphone") }, singleLine = true, modifier = Modifier.weight(1f))
            AmountField("TVA par défaut %", vat, { vat = it }, Modifier.weight(1f))
        }
        PrimaryButton("Enregistrer l'entreprise", {
            vm.act<Unit>("Entreprise enregistrée.") {
                val current = company ?: throw BusinessException("L'entreprise n'est pas encore initialisée.")
                if (name.isBlank()) throw BusinessException("La raison sociale est obligatoire.")
                val rate = parseDecimal(vat, "Taux de TVA")
                if (rate.signum() < 0 || rate.compareTo(BigDecimal(100)) > 0) throw BusinessException("Le taux de TVA doit être entre 0 et 100.")
                vm.db.companyDao().update(
                    current.copy(name = name.trim(), nif = nif.trim(), stat = stat.trim(), address = address.trim(), phone = phone.trim(), defaultVatRate = rate)
                )
                vm.accounting.audit(current.id, "COMPANY_UPDATE", "COMPANY", current.id, name.trim())
            }
        }, Modifier.fillMaxWidth())

        SectionTitle("Correspondance des comptes")
        Text(
            "Chaque rôle comptable pointe vers un compte de votre plan : les factures, règlements et stocks s'en servent pour générer leurs écritures.",
            color = Muted, fontSize = 12.sp
        )
        MappingKey.values().forEach { key ->
            val current = mappings.firstOrNull { it.mapKey == key.name }?.accountId
            AccountField(
                label = key.label, accounts = accounts, selectedId = current,
                onSelect = { accountId ->
                    accounts.firstOrNull { it.id == accountId }?.code?.let { code ->
                        vm.act("Correspondance enregistrée.") { vm.accounting.setMapping(vm.requireCompany(), key, code) }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        SectionTitle("Situation initiale")
        Text(
            "Bilan de l'entreprise au démarrage de l'application : soldes des comptes, créances, dettes et stock.",
            color = Muted, fontSize = 12.sp
        )
        SecondaryButton("Saisir ou consulter la situation initiale", { vm.nav.push(Screen.SituationInitiale) }, Modifier.fillMaxWidth())

        SectionTitle("Sauvegarde")
        Text(
            "Les données sont stockées uniquement sur ce téléphone. Sauvegardez régulièrement la base : " +
                "choisissez un emplacement (Drive, carte SD…) pour en conserver une copie.",
            color = Muted, fontSize = 12.sp
        )
        PrimaryButton("Sauvegarder la base de données", { backup.launch("madagestion-${LocalDate.now()}.db") }, Modifier.fillMaxWidth())

        SectionTitle("Journal d'audit")
        SecondaryButton(if (showAudit) "Masquer le journal" else "Afficher les 50 dernières actions", { showAudit = !showAudit }, Modifier.fillMaxWidth())
        if (showAudit) {
            audit.take(50).forEach { a ->
                SurfaceCard {
                    Text(a.action, color = Navy, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text(
                        Instant.ofEpochMilli(a.at).toString().replace('T', ' ').take(16) +
                            (if (a.detail.isBlank()) "" else " · ${a.detail}"),
                        color = Muted, fontSize = 11.sp
                    )
                }
            }
        }
        Text("MadaGestion 0.4.0 · Ne pas utiliser pour une tenue comptable réelle sans validation par un expert-comptable.", color = Muted, fontSize = 11.sp)
    }
}
