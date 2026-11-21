package mg.madagestion.ui.invoice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import java.time.LocalDate
import mg.madagestion.core.fmt
import mg.madagestion.core.fmtQty
import mg.madagestion.core.newId
import mg.madagestion.core.sumBd
import mg.madagestion.data.model.AllocationSource
import mg.madagestion.data.model.DocStatus
import mg.madagestion.data.model.FeePayer
import mg.madagestion.data.model.InvoiceKind
import mg.madagestion.data.model.InvoiceLine
import mg.madagestion.data.model.PaymentDirection
import mg.madagestion.domain.invoicing.amountDue
import mg.madagestion.domain.invoicing.isCredit
import mg.madagestion.domain.invoicing.isSale
import mg.madagestion.domain.invoicing.label
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.AllocateDialog
import mg.madagestion.ui.components.CashSettleDialog
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
import mg.madagestion.ui.format.nameOf
import mg.madagestion.ui.format.parseDecimal
import mg.madagestion.ui.navigation.Screen
import mg.madagestion.ui.theme.Danger
import mg.madagestion.ui.theme.Good
import mg.madagestion.ui.theme.Muted
import mg.madagestion.ui.theme.Navy

@Composable
fun InvoiceDetailScreen(vm: AppViewModel, id: String) {
    val invoices by vm.invoices.collectAsState()
    val parties by vm.thirdParties.collectAsState()
    val products by vm.products.collectAsState()
    val allocations by vm.allocations.collectAsState()
    val payments by vm.payments.collectAsState()
    val inv = invoices.firstOrNull { it.id == id }
    var lines by remember { mutableStateOf<List<InvoiceLine>>(emptyList()) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var allocating by remember { mutableStateOf(false) }

    LaunchedEffect(id, inv?.totalTtc, inv?.status) { lines = vm.db.invoiceDao().lines(id) }

    if (inv == null) {
        FormPage("Document", { vm.nav.pop() }) { EmptyHint("Document introuvable (peut-être supprimé).") }
        return
    }

    val open = remember(invoices, allocations) { openAmounts(invoices, allocations) }
    val invOpen = open[inv.id]
    val creditAvailable =
        if (inv.kind.isCredit() && inv.status == DocStatus.VALIDATED)
            inv.totalTtc.subtract(allocations.filter { it.sourceId == inv.id }.sumBd { it.amount })
        else BigDecimal.ZERO
    val (statusText, statusColor) = invoiceStatus(inv, invOpen, if (inv.kind.isCredit()) creditAvailable else null)
    // Avance déduite par l'écriture de la facture (validée) ou disponible pour la déduire (brouillon)
    val advanceDeducted = allocations.filter { it.invoiceId == inv.id && it.fromAdvance && it.entryId == inv.entryId }
        .sumBd { it.amount }
    val advanceForDraft = if (inv.status == DocStatus.DRAFT && !inv.kind.isCredit()) {
        advanceAvailable(
            payments, allocations, inv.thirdPartyId,
            if (inv.kind.isSale()) PaymentDirection.RECEIPT else PaymentDirection.DISBURSEMENT
        ).min(inv.amountDue())
    } else BigDecimal.ZERO
    val canCredit = inv.status == DocStatus.VALIDATED && !inv.kind.isCredit()
    val originNumber = inv.originInvoiceId?.let { oid -> invoices.firstOrNull { it.id == oid }?.number }

    FormPage(inv.number ?: "Brouillon", { vm.nav.pop() }) {
        SurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(inv.kind.label(), fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.weight(1f))
                StatusPill(statusText, statusColor)
            }
            KeyValue(if (inv.kind.isSale()) "Client" else "Fournisseur", parties.nameOf(inv.thirdPartyId))
            KeyValue("Date", inv.date.toString())
            if (!inv.kind.isCredit()) KeyValue("Échéance", inv.dueDate.toString())
            if (inv.reference.isNotBlank()) KeyValue("Référence", inv.reference)
            if (originNumber != null) KeyValue("Avoir sur", originNumber)
            if (inv.note.isNotBlank()) Text(inv.note, color = Muted, fontSize = 12.sp)
        }

        SectionTitle("Lignes")
        SurfaceCard {
            lines.forEach { l ->
                val name = l.description.ifBlank { products.firstOrNull { it.id == l.productId }?.name ?: "Ligne" }
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(name, color = Navy, fontSize = 14.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "${l.qty.fmtQty()} × ${l.unitPrice.fmt()} Ar · TVA ${l.vatRate.stripTrailingZeros().toPlainString()} %",
                            color = Muted, fontSize = 12.sp
                        )
                        Text("${l.totalHt.fmt()} Ar", color = Navy, fontSize = 13.sp)
                    }
                }
            }
        }
        SurfaceCard {
            TotalRow("Total HT", inv.totalHt)
            TotalRow("TVA", inv.totalVat)
            TotalRow("Total TTC", inv.totalTtc, bold = true)
            if (inv.feeAmount.signum() > 0) {
                val feeText = inv.feeLabel.ifBlank { "Frais annexes" }
                TotalRow(
                    when {
                        inv.feePaidBy == FeePayer.COMPANY -> "$feeText (payé par l'entreprise)"
                        inv.kind.isSale() -> "$feeText (payé par le client, déduit)"
                        else -> "$feeText (facturé par le fournisseur)"
                    },
                    inv.feeAmount
                )
                if (inv.feePaidBy == FeePayer.THIRD_PARTY) TotalRow("Total dû", inv.amountDue(), bold = true)
            }
            if (advanceDeducted.signum() > 0) TotalRow("dont avance déduite", advanceDeducted, color = Good)
            if (advanceForDraft.signum() > 0) TotalRow("Avance déductible à la validation", advanceForDraft, color = Good)
            if (inv.kind.isCredit() && inv.returnsGoods) {
                Text("Avec retour des marchandises en stock.", color = Muted, fontSize = 12.sp)
            }
            if (invOpen != null) {
                TotalRow("Déjà réglé", inv.amountDue().subtract(invOpen), color = Good)
                TotalRow("Reste à régler", invOpen, bold = true, color = if (invOpen.signum() > 0) Danger else Good)
            }
            if (inv.kind.isCredit() && inv.status == DocStatus.VALIDATED) {
                TotalRow("Avoir encore disponible", creditAvailable, bold = true)
            }
        }

        if (inv.status == DocStatus.DRAFT) {
            PrimaryButton("Valider le document", { confirm = "validate" }, Modifier.fillMaxWidth())
            if (!inv.kind.isCredit()) {
                SecondaryButton("Valider et régler au comptant", { confirm = "cash" }, Modifier.fillMaxWidth())
            }
            SecondaryButton("Modifier", { vm.nav.push(Screen.FactureEdit(inv.id, inv.kind)) }, Modifier.fillMaxWidth())
            DangerButton("Supprimer le brouillon", { confirm = "delete" }, Modifier.fillMaxWidth())
        } else {
            if (invOpen != null && invOpen.signum() > 0) {
                val direction = if (inv.kind == InvoiceKind.SALE) PaymentDirection.RECEIPT else PaymentDirection.DISBURSEMENT
                PrimaryButton(
                    if (inv.kind == InvoiceKind.SALE) "Enregistrer un encaissement" else "Enregistrer un paiement",
                    { vm.nav.push(Screen.ReglementNew(direction, inv.id)) },
                    Modifier.fillMaxWidth()
                )
            }
            if (inv.kind.isCredit() && creditAvailable.signum() > 0) {
                SecondaryButton("Imputer l'avoir sur une facture", { allocating = true }, Modifier.fillMaxWidth())
            }
            if (canCredit) {
                SecondaryButton("Créer un avoir", { confirm = "credit" }, Modifier.fillMaxWidth())
            }
            inv.entryId?.let { eid ->
                SecondaryButton("Voir l'écriture comptable", { vm.nav.push(Screen.EcritureDetail(eid)) }, Modifier.fillMaxWidth())
            }
        }
    }

    when (confirm) {
        "validate" -> ConfirmDialog(
            "Valider ce document ?",
            "Il recevra son numéro définitif et génèrera l'écriture comptable (et le mouvement de stock). Il ne sera plus modifiable." +
                (if (advanceForDraft.signum() > 0) " L'avance disponible de ${advanceForDraft.fmt()} Ar sera déduite." else ""),
            "Valider",
            { confirm = null; vm.act("Document validé.") { vm.invoiceService.validate(inv.id) } },
            { confirm = null }
        )
        "cash" -> CashSettleDialog(
            receipt = inv.kind.isSale(),
            amount = inv.amountDue().subtract(advanceForDraft),
            onConfirm = { method ->
                confirm = null
                vm.act("Document validé et réglé.") { vm.invoiceService.validateAndSettle(inv.id, method) }
            },
            onDismiss = { confirm = null }
        )
        "delete" -> ConfirmDialog(
            "Supprimer ce brouillon ?", "Cette action est définitive.", "Supprimer",
            { confirm = null; vm.act<Unit>("Brouillon supprimé.", { vm.nav.pop() }) { vm.invoiceService.deleteDraft(inv.id) } },
            { confirm = null }
        )
        "credit" -> ConfirmDialog(
            "Créer un avoir ?",
            "Un brouillon d'avoir reprenant les lignes de ${inv.number ?: "la facture"} sera créé. Ajustez-le puis validez-le.",
            "Créer",
            {
                confirm = null
                val creditKind = if (inv.kind == InvoiceKind.SALE) InvoiceKind.SALE_CREDIT else InvoiceKind.PURCHASE_CREDIT
                vm.act<String>("Brouillon d'avoir créé.", { newId -> vm.nav.push(Screen.FactureEdit(newId, creditKind)) }) {
                    vm.invoiceService.createCreditDraft(inv.id)
                }
            },
            { confirm = null }
        )
    }

    if (allocating) {
        val wanted = if (inv.kind == InvoiceKind.SALE_CREDIT) InvoiceKind.SALE else InvoiceKind.PURCHASE
        val candidates = invoices
            .filter { it.kind == wanted && it.thirdPartyId == inv.thirdPartyId && it.status == DocStatus.VALIDATED }
            .mapNotNull { f -> open[f.id]?.takeIf { it.signum() > 0 }?.let { f to it } }
        if (candidates.isEmpty()) {
            ConfirmDialog(
                "Aucune facture à imputer", "Ce tiers n'a aucune facture non soldée.", "OK",
                { allocating = false }, { allocating = false }
            )
        } else {
            AllocateDialog(
                candidates = candidates,
                available = creditAvailable,
                onConfirm = { invoiceId, text ->
                    allocating = false
                    vm.act("Avoir imputé.") {
                        vm.paymentService.allocate(
                            vm.requireCompany(), AllocationSource.CREDIT_NOTE, inv.id, invoiceId,
                            parseDecimal(text), LocalDate.now()
                        )
                    }
                },
                onDismiss = { allocating = false }
            )
        }
    }
}
