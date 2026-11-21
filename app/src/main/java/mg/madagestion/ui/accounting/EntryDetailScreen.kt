package mg.madagestion.ui.accounting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import mg.madagestion.core.fmt
import mg.madagestion.core.newId
import mg.madagestion.core.sumBd
import mg.madagestion.data.model.EntryLine
import mg.madagestion.data.model.EntryStatus
import mg.madagestion.domain.accounting.EntrySource
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.ConfirmDialog
import mg.madagestion.ui.components.DangerButton
import mg.madagestion.ui.components.EmptyHint
import mg.madagestion.ui.components.FormPage
import mg.madagestion.ui.components.KeyValue
import mg.madagestion.ui.components.PrimaryButton
import mg.madagestion.ui.components.SecondaryButton
import mg.madagestion.ui.components.SectionTitle
import mg.madagestion.ui.components.StatusPill
import mg.madagestion.ui.components.SurfaceCard
import mg.madagestion.ui.components.TotalRow
import mg.madagestion.ui.format.entrySourceLabel
import mg.madagestion.ui.format.nameOf
import mg.madagestion.ui.navigation.Screen
import mg.madagestion.ui.theme.Good
import mg.madagestion.ui.theme.Muted
import mg.madagestion.ui.theme.Navy
import mg.madagestion.ui.theme.Warning

@Composable
fun EntryDetailScreen(vm: AppViewModel, id: String) {
    val entries by vm.entries.collectAsState()
    val journals by vm.journals.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val parties by vm.thirdParties.collectAsState()
    val invoices by vm.invoices.collectAsState()
    val payments by vm.payments.collectAsState()
    val e = entries.firstOrNull { it.id == id }
    var lines by remember { mutableStateOf<List<EntryLine>>(emptyList()) }
    var confirm by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id, e?.status) { lines = vm.db.entryDao().lines(id) }

    if (e == null) {
        FormPage("Écriture", { vm.nav.pop() }) { EmptyHint("Écriture introuvable (peut-être supprimée).") }
        return
    }
    val totalDebit = lines.sumBd { it.debit }
    val totalCredit = lines.sumBd { it.credit }
    val sourceInvoice = invoices.firstOrNull { it.entryId == e.id }
    val sourcePayment = payments.firstOrNull { it.entryId == e.id || it.cancelEntryId == e.id }
    val canReverse = e.status == EntryStatus.VALIDATED && e.sourceType == null && e.reversedById == null && e.reversalOfId == null

    FormPage(e.number ?: "Brouillon", { vm.nav.pop() }) {
        SurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.label.ifBlank { "(sans libellé)" }, fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.weight(1f))
                StatusPill(
                    if (e.status == EntryStatus.DRAFT) "Brouillon" else "Validée",
                    if (e.status == EntryStatus.DRAFT) Warning else Good
                )
            }
            KeyValue("Journal", journals.firstOrNull { it.id == e.journalId }?.let { "${it.code} — ${it.label}" } ?: "")
            KeyValue("Date", e.date.toString())
            if (e.reference.isNotBlank()) KeyValue("Référence", e.reference)
            if (e.sourceType != null) KeyValue("Origine", entrySourceLabel(e.sourceType))
        }
        SectionTitle("Lignes")
        lines.forEach { l ->
            val a = accounts.firstOrNull { it.id == l.accountId }
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${a?.code ?: "?"} — ${a?.label ?: ""}", color = Navy, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        val sub = listOf(l.thirdPartyId?.let { parties.nameOf(it) } ?: "", l.label).filter { it.isNotBlank() }.joinToString(" · ")
                        if (sub.isNotBlank()) Text(sub, color = Muted, fontSize = 11.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (l.debit.signum() > 0) Text("D ${l.debit.fmt()}", color = Navy, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        if (l.credit.signum() > 0) Text("C ${l.credit.fmt()}", color = Navy, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
        }
        SurfaceCard {
            TotalRow("Total débit", totalDebit)
            TotalRow("Total crédit", totalCredit)
        }

        if (e.status == EntryStatus.DRAFT) {
            PrimaryButton("Valider l'écriture", { confirm = "validate" }, Modifier.fillMaxWidth())
            SecondaryButton("Modifier", { vm.nav.push(Screen.EcritureEdit(e.id)) }, Modifier.fillMaxWidth())
            DangerButton("Supprimer le brouillon", { confirm = "delete" }, Modifier.fillMaxWidth())
        } else {
            sourceInvoice?.let { f ->
                SecondaryButton("Voir le document ${f.number ?: ""}", { vm.nav.push(Screen.FactureDetail(f.id)) }, Modifier.fillMaxWidth())
            }
            sourcePayment?.let { p ->
                SecondaryButton("Voir le règlement ${p.number}", { vm.nav.push(Screen.ReglementDetail(p.id)) }, Modifier.fillMaxWidth())
            }
            e.reversedById?.let { rid ->
                SecondaryButton("Voir la contrepassation", { vm.nav.push(Screen.EcritureDetail(rid)) }, Modifier.fillMaxWidth())
            }
            e.reversalOfId?.let { oid ->
                SecondaryButton("Voir l'écriture d'origine", { vm.nav.push(Screen.EcritureDetail(oid)) }, Modifier.fillMaxWidth())
            }
            if (canReverse) {
                DangerButton("Contrepasser cette écriture", { confirm = "reverse" }, Modifier.fillMaxWidth())
            } else if (e.sourceType != null && sourceInvoice == null && sourcePayment == null) {
                Text(
                    when (e.sourceType) {
                        EntrySource.OPENING -> "Cette écriture est une situation initiale : elle s'annule depuis l'écran « Situation initiale »."
                        EntrySource.PARTY_OPERATION -> "Cette écriture est un frais ou une ristourne : elle s'annule depuis le compte du tiers."
                        else -> "Cette écriture est générée automatiquement : corrigez l'opération d'origine."
                    },
                    color = Muted, fontSize = 12.sp
                )
            }
        }
    }

    when (confirm) {
        "validate" -> ConfirmDialog(
            "Valider cette écriture ?", "Elle sera numérotée et ne sera plus modifiable.", "Valider",
            { confirm = null; vm.act("Écriture validée.") { vm.accounting.validate(e.id) } },
            { confirm = null }
        )
        "delete" -> ConfirmDialog(
            "Supprimer ce brouillon ?", "Cette action est définitive.", "Supprimer",
            { confirm = null; vm.act<Unit>("Brouillon supprimé.", { vm.nav.pop() }) { vm.accounting.deleteDraft(e.id) } },
            { confirm = null }
        )
        "reverse" -> ConfirmDialog(
            "Contrepasser cette écriture ?",
            "Une écriture inverse, datée d'aujourd'hui, sera validée. L'écriture d'origine reste intacte et liée.",
            "Contrepasser",
            {
                confirm = null
                vm.act<String>("Écriture contrepassée.", { newId -> vm.nav.push(Screen.EcritureDetail(newId)) }) {
                    vm.accounting.reverse(e.id, LocalDate.now())
                }
            },
            { confirm = null }
        )
    }
}
