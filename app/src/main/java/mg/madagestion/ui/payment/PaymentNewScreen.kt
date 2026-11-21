package mg.madagestion.ui.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.time.LocalDate
import mg.madagestion.core.BusinessException
import mg.madagestion.core.fmt
import mg.madagestion.core.sumBd
import mg.madagestion.data.model.PaymentDirection
import mg.madagestion.data.model.PaymentMethod
import mg.madagestion.data.model.ThirdPartyType
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.AmountField
import mg.madagestion.ui.components.DateField
import mg.madagestion.ui.components.DropdownField
import mg.madagestion.ui.components.EmptyHint
import mg.madagestion.ui.components.FormPage
import mg.madagestion.ui.components.PaymentMethodField
import mg.madagestion.ui.components.PrimaryButton
import mg.madagestion.ui.components.SecondaryButton
import mg.madagestion.ui.components.SectionTitle
import mg.madagestion.ui.components.SurfaceCard
import mg.madagestion.ui.components.TotalRow
import mg.madagestion.ui.format.directionLabel
import mg.madagestion.ui.format.lenientDecimal
import mg.madagestion.ui.format.parseDecimal
import mg.madagestion.ui.invoice.openAmounts
import mg.madagestion.ui.theme.Danger
import mg.madagestion.ui.theme.Muted
import mg.madagestion.ui.theme.Navy

@Composable
fun PaymentNewScreen(vm: AppViewModel, direction: PaymentDirection, invoiceId: String?, presetThirdPartyId: String? = null) {
    val parties by vm.thirdParties.collectAsState()
    val invoices by vm.invoices.collectAsState()
    val allocations by vm.allocations.collectAsState()
    val receipt = direction == PaymentDirection.RECEIPT

    var thirdPartyId by remember { mutableStateOf(presetThirdPartyId) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(PaymentMethod.CASH) }
    var reference by remember { mutableStateOf("") }
    val alloc = remember { mutableStateMapOf<String, String>() }
    var prefilled by remember { mutableStateOf(false) }

    val open = remember(invoices, allocations) { openAmounts(invoices, allocations) }

    // Pré-remplissage quand on arrive depuis une facture
    LaunchedEffect(invoices, allocations) {
        if (!prefilled && invoiceId != null) {
            val inv = invoices.firstOrNull { it.id == invoiceId }
            val o = open[invoiceId]
            if (inv != null && o != null) {
                thirdPartyId = inv.thirdPartyId
                amount = o.toPlainString()
                alloc[inv.id] = o.toPlainString()
                prefilled = true
            }
        }
    }

    val openInvoices = invoices
        .filter { it.thirdPartyId == thirdPartyId && (open[it.id] ?: BigDecimal.ZERO).signum() > 0 }
        .sortedBy { it.dueDate }
    val allocated = alloc.values.sumBd { lenientDecimal(it) }
    val total = lenientDecimal(amount)

    FormPage(directionLabel(direction), { vm.nav.pop() }) {
        DropdownField(
            label = if (receipt) "Client" else "Fournisseur",
            options = parties
                .filter { (it.active || it.id == thirdPartyId) && it.type == (if (receipt) ThirdPartyType.CUSTOMER else ThirdPartyType.SUPPLIER) }
                .map { it.id to it.name },
            selected = thirdPartyId,
            onSelect = { thirdPartyId = it; alloc.clear() },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DateField("Date", date, { date = it }, Modifier.weight(1f))
            AmountField("Montant (Ar)", amount, { amount = it }, Modifier.weight(1f))
        }
        PaymentMethodField(method, { method = it }, Modifier.fillMaxWidth())
        OutlinedTextField(
            value = reference, onValueChange = { reference = it },
            label = { Text("Référence (n° de chèque, transaction…)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        SectionTitle("Imputation sur les factures")
        if (thirdPartyId == null) {
            EmptyHint("Choisissez d'abord un tiers.")
        } else if (openInvoices.isEmpty()) {
            EmptyHint(if (receipt) "Aucune facture non soldée : le montant sera enregistré en avance du client (419)." else "Aucune facture non soldée : le montant sera enregistré en avance au fournisseur (409).")
        } else {
            SecondaryButton("Répartir automatiquement (échéances les plus anciennes)", {
                var rest = lenientDecimal(amount)
                alloc.clear()
                for (inv in openInvoices) {
                    if (rest.signum() <= 0) break
                    val o = open[inv.id] ?: BigDecimal.ZERO
                    val part = if (rest.compareTo(o) < 0) rest else o
                    alloc[inv.id] = part.toPlainString()
                    rest = rest.subtract(part)
                }
            }, Modifier.fillMaxWidth())
            openInvoices.forEach { inv ->
                SurfaceCard {
                    Text("${inv.number ?: "?"} · éch. ${inv.dueDate}", fontWeight = FontWeight.SemiBold, color = Navy, fontSize = 14.sp)
                    Text("Reste à régler : ${(open[inv.id] ?: BigDecimal.ZERO).fmt()} Ar", color = Muted, fontSize = 12.sp)
                    AmountField(
                        "Montant imputé", alloc[inv.id] ?: "",
                        { alloc[inv.id] = it }, Modifier.fillMaxWidth()
                    )
                }
            }
        }
        SurfaceCard {
            TotalRow("Montant du règlement", total)
            TotalRow("Imputé sur des factures", allocated)
            val rest = total.subtract(allocated)
            TotalRow(
                if (rest.signum() < 0) "Imputation en excès" else if (receipt) "Avance du client (419)" else "Avance au fournisseur (409)",
                rest.abs(), bold = true, color = if (rest.signum() < 0) Danger else Navy
            )
        }
        PrimaryButton("Enregistrer le règlement", {
            vm.act<String>("Règlement enregistré.", { vm.nav.pop() }) {
                val tp = thirdPartyId ?: throw BusinessException("Choisissez un tiers.")
                val parts = alloc.mapNotNull { (id, text) ->
                    val a = parseDecimal(text, "Montant imputé")
                    if (a.signum() > 0) id to a else null
                }
                vm.paymentService.record(
                    vm.requireCompany(), direction, tp, date, parseDecimal(amount), method, reference, parts
                )
            }
        }, Modifier.fillMaxWidth())
        Text(
            "Le règlement génère automatiquement l'écriture (trésorerie / compte du tiers). " +
                "La part non imputée est portée en avance (419 / 409) ; elle sera déduite de la prochaine facture du tiers. " +
                "Un décaissement en espèces est refusé si la caisse devient négative.",
            color = Muted, fontSize = 11.sp
        )
    }
}
