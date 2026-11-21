package mg.madagestion.ui.payment

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import java.time.LocalDate
import mg.madagestion.core.fmt
import mg.madagestion.core.sumBd
import mg.madagestion.data.model.AllocationSource
import mg.madagestion.data.model.InvoiceKind
import mg.madagestion.data.model.PaymentDirection
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.AllocateDialog
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
import mg.madagestion.ui.format.directionLabel
import mg.madagestion.ui.format.label
import mg.madagestion.ui.format.nameOf
import mg.madagestion.ui.format.parseDecimal
import mg.madagestion.ui.invoice.openAmounts
import mg.madagestion.ui.navigation.Screen
import mg.madagestion.ui.theme.Muted
import mg.madagestion.ui.theme.Navy

@Composable
fun PaymentDetailScreen(vm: AppViewModel, id: String) {
    val payments by vm.payments.collectAsState()
    val parties by vm.thirdParties.collectAsState()
    val invoices by vm.invoices.collectAsState()
    val allocations by vm.allocations.collectAsState()
    val p = payments.firstOrNull { it.id == id }
    var confirmCancel by remember { mutableStateOf(false) }
    var allocating by remember { mutableStateOf(false) }

    if (p == null) {
        FormPage("Règlement", { vm.nav.pop() }) { EmptyHint("Règlement introuvable.") }
        return
    }
    val mine = allocations.filter { it.sourceId == p.id }
    val unallocated = p.amount.subtract(mine.sumBd { it.amount })
    val open = remember(invoices, allocations) { openAmounts(invoices, allocations) }
    val wanted = if (p.direction == PaymentDirection.RECEIPT) InvoiceKind.SALE else InvoiceKind.PURCHASE
    val candidates = invoices
        .filter { it.kind == wanted && it.thirdPartyId == p.thirdPartyId }
        .mapNotNull { f -> open[f.id]?.takeIf { it.signum() > 0 }?.let { f to it } }

    FormPage(p.number, { vm.nav.pop() }) {
        SurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(directionLabel(p.direction), fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.weight(1f))
                if (p.cancelled) StatusPill("Annulé", Muted)
            }
            KeyValue(if (p.direction == PaymentDirection.RECEIPT) "Client" else "Fournisseur", parties.nameOf(p.thirdPartyId))
            KeyValue("Date", p.date.toString())
            KeyValue("Mode", p.method.label())
            if (p.reference.isNotBlank()) KeyValue("Référence", p.reference)
            TotalRow("Montant", p.amount, bold = true)
            if (!p.cancelled) TotalRow(if (p.direction == PaymentDirection.RECEIPT) "Non imputé (avance 419)" else "Non imputé (avance 409)", unallocated)
        }
        SectionTitle("Imputations")
        if (mine.isEmpty()) EmptyHint("Aucune imputation sur facture.")
        mine.forEach { a ->
            SurfaceCard(onClick = { vm.nav.push(Screen.FactureDetail(a.invoiceId)) }) {
                KeyValue(
                    invoices.firstOrNull { it.id == a.invoiceId }?.number ?: "Facture",
                    "${a.amount.fmt()} Ar"
                )
            }
        }
        if (!p.cancelled && unallocated.signum() > 0 && candidates.isNotEmpty()) {
            PrimaryButton("Imputer le solde sur une facture", { allocating = true }, Modifier.fillMaxWidth())
        }
        SecondaryButton("Voir l'écriture comptable", { vm.nav.push(Screen.EcritureDetail(p.entryId)) }, Modifier.fillMaxWidth())
        if (!p.cancelled) {
            DangerButton("Annuler ce règlement", { confirmCancel = true }, Modifier.fillMaxWidth())
        } else {
            p.cancelEntryId?.let { eid ->
                SecondaryButton("Voir l'écriture d'annulation", { vm.nav.push(Screen.EcritureDetail(eid)) }, Modifier.fillMaxWidth())
            }
        }
    }

    if (confirmCancel) {
        ConfirmDialog(
            "Annuler ce règlement ?",
            "Une écriture de contrepassation sera passée à la date d'aujourd'hui et les imputations seront supprimées : les factures concernées redeviendront à régler.",
            "Annuler le règlement",
            { confirmCancel = false; vm.act("Règlement annulé.") { vm.paymentService.cancel(p.id, LocalDate.now()) } },
            { confirmCancel = false }
        )
    }
    if (allocating) {
        AllocateDialog(
            candidates = candidates,
            available = unallocated,
            onConfirm = { invoiceId, text ->
                allocating = false
                vm.act("Règlement imputé.") {
                    vm.paymentService.allocate(
                        vm.requireCompany(), AllocationSource.PAYMENT, p.id, invoiceId, parseDecimal(text), LocalDate.now()
                    )
                }
            },
            onDismiss = { allocating = false }
        )
    }
}
