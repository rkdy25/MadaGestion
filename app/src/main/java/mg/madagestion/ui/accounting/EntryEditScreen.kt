package mg.madagestion.ui.accounting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import mg.madagestion.core.BusinessException
import mg.madagestion.core.sumBd
import mg.madagestion.data.model.JournalType
import mg.madagestion.data.model.ThirdPartyType
import mg.madagestion.domain.accounting.LineInput
import mg.madagestion.domain.accounting.MappingKey
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.AccountField
import mg.madagestion.ui.components.AmountField
import mg.madagestion.ui.components.DateField
import mg.madagestion.ui.components.DropdownField
import mg.madagestion.ui.components.FormPage
import mg.madagestion.ui.components.PrimaryButton
import mg.madagestion.ui.components.SecondaryButton
import mg.madagestion.ui.components.SectionTitle
import mg.madagestion.ui.components.SurfaceCard
import mg.madagestion.ui.components.TotalRow
import mg.madagestion.ui.format.lenientDecimal
import mg.madagestion.ui.format.parseDecimal
import mg.madagestion.ui.theme.Danger
import mg.madagestion.ui.theme.Good
import mg.madagestion.ui.theme.Muted

private data class EntryLineUi(
    val key: Long,
    val accountId: String? = null,
    val thirdPartyId: String? = null,
    val label: String = "",
    val debit: String = "",
    val credit: String = ""
)

@Composable
fun EntryEditScreen(vm: AppViewModel, entryId: String?) {
    val journals by vm.journals.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val parties by vm.thirdParties.collectAsState()
    val mappings by vm.mappings.collectAsState()

    var currentId by remember { mutableStateOf(entryId) }
    var journalId by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var reference by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<EntryLineUi>() }
    var nextKey by remember { mutableStateOf(1L) }

    fun mapped(key: MappingKey): String? = mappings.firstOrNull { it.mapKey == key.name }?.accountId
    // Comptes qui exigent un tiers : collectifs et avances
    val customerAccounts = setOfNotNull(mapped(MappingKey.CUSTOMERS), mapped(MappingKey.CUSTOMER_ADVANCES))
    val supplierAccounts = setOfNotNull(mapped(MappingKey.SUPPLIERS), mapped(MappingKey.SUPPLIER_ADVANCES))

    LaunchedEffect(entryId) {
        if (entryId != null) {
            val e = vm.db.entryDao().get(entryId)
            if (e != null) {
                journalId = e.journalId
                date = e.date
                reference = e.reference
                label = e.label
                lines.clear()
                vm.db.entryDao().lines(entryId).forEach { l ->
                    lines.add(
                        EntryLineUi(
                            key = nextKey++, accountId = l.accountId, thirdPartyId = l.thirdPartyId, label = l.label,
                            debit = if (l.debit.signum() == 0) "" else l.debit.toPlainString(),
                            credit = if (l.credit.signum() == 0) "" else l.credit.toPlainString()
                        )
                    )
                }
            }
        } else if (lines.isEmpty()) {
            lines.add(EntryLineUi(nextKey++))
            lines.add(EntryLineUi(nextKey++))
        }
    }
    LaunchedEffect(journals) {
        if (entryId == null && journalId == null) {
            journalId = (journals.firstOrNull { it.type == JournalType.MISC } ?: journals.firstOrNull())?.id
        }
    }

    val totalDebit = lines.sumBd { lenientDecimal(it.debit) }
    val totalCredit = lines.sumBd { lenientDecimal(it.credit) }
    val diff = totalDebit.subtract(totalCredit)

    fun submit(validate: Boolean) {
        vm.act<String>(
            okMessage = if (validate) "Écriture validée." else "Brouillon enregistré.",
            onDone = { vm.nav.pop() }
        ) {
            val c = vm.requireCompany()
            val j = journalId ?: throw BusinessException("Choisissez un journal.")
            val inputs = lines.mapIndexedNotNull { i, l ->
                val empty = l.accountId == null && l.debit.isBlank() && l.credit.isBlank()
                if (empty) null else LineInput(
                    accountId = l.accountId ?: throw BusinessException("Ligne ${i + 1} : choisissez un compte."),
                    thirdPartyId = l.thirdPartyId,
                    label = l.label,
                    debit = parseDecimal(l.debit, "Débit de la ligne ${i + 1}"),
                    credit = parseDecimal(l.credit, "Crédit de la ligne ${i + 1}")
                )
            }
            if (inputs.isEmpty()) throw BusinessException("Saisissez au moins une ligne.")
            val id = vm.accounting.saveDraft(c, currentId, j, date, reference, label, inputs)
            currentId = id // évite un doublon si la validation échoue après l'enregistrement
            if (validate) vm.accounting.validate(id)
            id
        }
    }

    FormPage(if (entryId == null) "Nouvelle écriture" else "Modifier le brouillon", { vm.nav.pop() }) {
        DropdownField(
            label = "Journal",
            options = journals.map { it.id to "${it.code} — ${it.label}" },
            selected = journalId,
            onSelect = { journalId = it },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DateField("Date", date, { date = it }, Modifier.weight(1f))
            OutlinedTextField(
                value = reference, onValueChange = { reference = it },
                label = { Text("Pièce / référence") }, singleLine = true, modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = label, onValueChange = { label = it }, label = { Text("Libellé de l'opération") },
            modifier = Modifier.fillMaxWidth()
        )

        SectionTitle("Lignes")
        lines.forEachIndexed { index, line ->
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ligne ${index + 1}", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        // Complète cette ligne pour équilibrer l'écriture (somme des autres lignes)
                        val others = lines.filterIndexed { i, _ -> i != index }
                        val need = others.sumBd { lenientDecimal(it.debit) }.subtract(others.sumBd { lenientDecimal(it.credit) })
                        lines[index] = when {
                            need.signum() > 0 -> line.copy(debit = "", credit = need.toPlainString())
                            need.signum() < 0 -> line.copy(debit = need.negate().toPlainString(), credit = "")
                            else -> line
                        }
                    }) { Text("Équilibrer", fontSize = 12.sp) }
                    if (lines.size > 2) {
                        IconButton(onClick = { lines.removeAt(index) }) {
                            Icon(Icons.Default.Delete, "Supprimer la ligne", tint = Danger)
                        }
                    }
                }
                AccountField(
                    label = "Compte",
                    accounts = accounts,
                    selectedId = line.accountId,
                    onSelect = { lines[index] = line.copy(accountId = it, thirdPartyId = null) },
                    modifier = Modifier.fillMaxWidth()
                )
                val tpType = when {
                    line.accountId != null && line.accountId in customerAccounts -> ThirdPartyType.CUSTOMER
                    line.accountId != null && line.accountId in supplierAccounts -> ThirdPartyType.SUPPLIER
                    else -> null
                }
                if (tpType != null) {
                    DropdownField(
                        label = if (tpType == ThirdPartyType.CUSTOMER) "Client (obligatoire)" else "Fournisseur (obligatoire)",
                        options = parties.filter { it.type == tpType && (it.active || it.id == line.thirdPartyId) }.map { it.id to it.name },
                        selected = line.thirdPartyId,
                        onSelect = { lines[index] = line.copy(thirdPartyId = it) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = line.label, onValueChange = { lines[index] = line.copy(label = it) },
                    label = { Text("Libellé de la ligne (facultatif)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AmountField("Débit", line.debit, {
                        lines[index] = line.copy(debit = it, credit = if (lenientDecimal(it).signum() > 0) "" else line.credit)
                    }, Modifier.weight(1f))
                    AmountField("Crédit", line.credit, {
                        lines[index] = line.copy(credit = it, debit = if (lenientDecimal(it).signum() > 0) "" else line.debit)
                    }, Modifier.weight(1f))
                }
            }
        }
        SecondaryButton("+ Ajouter une ligne", { lines.add(EntryLineUi(nextKey++)) }, Modifier.fillMaxWidth())

        SurfaceCard {
            TotalRow("Total débit", totalDebit)
            TotalRow("Total crédit", totalCredit)
            if (diff.signum() == 0 && totalDebit.signum() > 0) {
                Text("✓ Écriture équilibrée", color = Good, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            } else {
                TotalRow("Écart à corriger", diff.abs(), bold = true, color = if (diff.signum() == 0) Muted else Danger)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton("Enregistrer le brouillon", { submit(false) }, Modifier.weight(1f))
            PrimaryButton("Valider", { submit(true) }, Modifier.weight(1f), enabled = diff.signum() == 0 && totalDebit.signum() > 0)
        }
        Text(
            "Un brouillon reste modifiable. À la validation, l'écriture reçoit son numéro et devient définitive : " +
                "elle ne se corrige plus que par contrepassation.",
            color = Muted, fontSize = 11.sp
        )
    }
}
