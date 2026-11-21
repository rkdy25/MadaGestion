package mg.madagestion.ui.accounting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.time.LocalDate
import mg.madagestion.core.BusinessException
import mg.madagestion.core.sumBd
import mg.madagestion.data.model.Account
import mg.madagestion.data.model.EntryStatus
import mg.madagestion.data.model.Period
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.ChipRow
import mg.madagestion.ui.components.ConfirmDialog
import mg.madagestion.ui.components.EmptyHint
import mg.madagestion.ui.components.ListPage
import mg.madagestion.ui.components.MoneyText
import mg.madagestion.ui.components.StatusPill
import mg.madagestion.ui.components.SurfaceCard
import mg.madagestion.ui.components.TabsRow
import mg.madagestion.ui.navigation.Screen
import mg.madagestion.ui.theme.Good
import mg.madagestion.ui.theme.Muted
import mg.madagestion.ui.theme.Navy
import mg.madagestion.ui.theme.Teal
import mg.madagestion.ui.theme.Warning

@Composable
fun AccountingScreen(vm: AppViewModel) {
    val entries by vm.entries.collectAsState()
    val journals by vm.journals.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val periods by vm.periods.collectAsState()
    val company by vm.company.collectAsState()

    var tab by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf("ALL") }
    var query by remember { mutableStateOf("") }
    var totals by remember { mutableStateOf<Map<String, BigDecimal>>(emptyMap()) }
    var accountDialog by remember { mutableStateOf<Account?>(null) }
    var newAccount by remember { mutableStateOf(false) }
    var newYear by remember { mutableStateOf(false) }
    var confirmPeriod by remember { mutableStateOf<Period?>(null) }

    LaunchedEffect(entries, company?.id) {
        val c = company?.id ?: return@LaunchedEffect
        totals = vm.safely {
            vm.db.entryDao().allLines(c).groupBy { it.entryId }.mapValues { (_, l) -> l.sumBd { it.debit } }
        } ?: emptyMap()
    }

    ListPage(
        title = "Comptabilité",
        onBack = if (vm.nav.atRoot) null else ({ vm.nav.pop() }),
        fabLabel = when (tab) { 0 -> "Nouvelle écriture"; 1 -> "Nouveau compte"; else -> "Ajouter un exercice" },
        onFab = {
            when (tab) {
                0 -> vm.nav.push(Screen.EcritureEdit(null))
                1 -> newAccount = true
                else -> newYear = true
            }
        },
        actions = {
            TextButton(onClick = { vm.nav.push(Screen.SituationInitiale) }) { Text("Ouverture", color = Teal, fontSize = 12.sp) }
            TextButton(onClick = { vm.nav.push(Screen.Rapports) }) { Text("Rapports", color = Teal, fontSize = 12.sp) }
        },
        header = {
            TabsRow(listOf("Écritures", "Plan comptable", "Périodes"), tab) { tab = it }
            if (tab == 0) {
                ChipRow(listOf("ALL" to "Toutes", "DRAFT" to "Brouillons", "VALIDATED" to "Validées"), filter) { filter = it }
            }
            if (tab == 1) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Rechercher (code ou libellé)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    ) {
        when (tab) {
            0 -> {
                val shown = entries.filter {
                    when (filter) {
                        "DRAFT" -> it.status == EntryStatus.DRAFT
                        "VALIDATED" -> it.status == EntryStatus.VALIDATED
                        else -> true
                    }
                }
                if (shown.isEmpty()) item { EmptyHint("Aucune écriture. Les factures et règlements génèrent leurs écritures automatiquement ; utilisez « Nouvelle écriture » pour les opérations diverses.") }
                items(shown, key = { it.id }) { e ->
                    val journalCode = journals.firstOrNull { it.id == e.journalId }?.code ?: ""
                    val (pillText, pillColor) = when {
                        e.status == EntryStatus.DRAFT -> "Brouillon" to Warning
                        e.reversedById != null -> "Contrepassée" to Muted
                        e.reversalOfId != null -> "Contrepassation" to Muted
                        else -> "Validée" to Good
                    }
                    SurfaceCard(onClick = { vm.nav.push(Screen.EcritureDetail(e.id)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.number ?: "Brouillon", fontWeight = FontWeight.SemiBold, color = Navy)
                                Text(e.label.ifBlank { e.reference.ifBlank { "(sans libellé)" } }, color = Muted, fontSize = 13.sp)
                                Text("${e.date} · $journalCode" + if (e.sourceType != null) " · automatique" else "", color = Muted, fontSize = 11.sp)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                MoneyText(totals[e.id] ?: BigDecimal.ZERO, bold = true)
                                StatusPill(pillText, pillColor)
                            }
                        }
                    }
                }
            }
            1 -> {
                val q = query.trim()
                val shown = accounts.filter { q.isBlank() || it.code.startsWith(q) || it.label.contains(q, ignoreCase = true) }
                items(shown, key = { it.id }) { a ->
                    SurfaceCard(onClick = { accountDialog = a }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.code, fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.padding(end = 12.dp))
                            Text(a.label, color = Navy, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            if (!a.active) StatusPill("Inactif", Warning)
                        }
                    }
                }
            }
            else -> {
                if (periods.isEmpty()) item { EmptyHint("Aucune période.") }
                items(periods, key = { it.id }) { p ->
                    SurfaceCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.label, fontWeight = FontWeight.SemiBold, color = Navy)
                                Text("${p.startDate} → ${p.endDate}", color = Muted, fontSize = 11.sp)
                            }
                            StatusPill(if (p.closed) "Clôturée" else "Ouverte", if (p.closed) Muted else Good)
                            TextButton(onClick = { confirmPeriod = p }) { Text(if (p.closed) "Rouvrir" else "Clôturer") }
                        }
                    }
                }
            }
        }
    }

    // --- Boîtes de dialogue ---
    if (newAccount || accountDialog != null) {
        AccountDialog(
            account = accountDialog,
            onDismiss = { newAccount = false; accountDialog = null },
            onSave = { code, label, active ->
                val editing = accountDialog
                newAccount = false
                accountDialog = null
                vm.act("Compte enregistré.") {
                    if (editing == null) vm.accounting.addAccount(vm.requireCompany(), code, label)
                    else vm.accounting.updateAccount(editing.id, label, active)
                }
            }
        )
    }
    if (newYear) {
        var year by remember { mutableStateOf((LocalDate.now().year + 1).toString()) }
        AlertDialog(
            onDismissRequest = { newYear = false },
            title = { Text("Ajouter un exercice") },
            text = {
                OutlinedTextField(
                    value = year, onValueChange = { year = it }, label = { Text("Année (ex. 2027)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    newYear = false
                    vm.act("Exercice créé.") {
                        val y = year.trim().toIntOrNull() ?: throw BusinessException("Année invalide.")
                        vm.accounting.ensureYear(vm.requireCompany(), y)
                    }
                }) { Text("Créer") }
            },
            dismissButton = { TextButton(onClick = { newYear = false }) { Text("Annuler") } }
        )
    }
    confirmPeriod?.let { p ->
        ConfirmDialog(
            if (p.closed) "Rouvrir la période ${p.label} ?" else "Clôturer la période ${p.label} ?",
            if (p.closed) "Les écritures redeviendront possibles à ces dates."
            else "Plus aucune écriture ne sera possible à ces dates. Les brouillons de la période doivent d'abord être validés ou supprimés.",
            if (p.closed) "Rouvrir" else "Clôturer",
            {
                confirmPeriod = null
                vm.act(if (p.closed) "Période rouverte." else "Période clôturée.") {
                    vm.accounting.setPeriodClosed(p.id, !p.closed)
                }
            },
            { confirmPeriod = null }
        )
    }
}

@Composable
private fun AccountDialog(account: Account?, onDismiss: () -> Unit, onSave: (String, String, Boolean) -> Unit) {
    var code by remember { mutableStateOf(account?.code ?: "") }
    var label by remember { mutableStateOf(account?.label ?: "") }
    var active by remember { mutableStateOf(account?.active ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (account == null) "Nouveau compte" else "Compte ${account.code}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (account == null) {
                    OutlinedTextField(
                        value = code, onValueChange = { code = it }, label = { Text("Code (2 à 10 chiffres)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Libellé") })
                if (account != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Actif", modifier = Modifier.weight(1f))
                        Switch(checked = active, onCheckedChange = { active = it })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(code, label, active) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
