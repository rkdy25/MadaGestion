package mg.madagestion.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import mg.madagestion.core.fmt
import mg.madagestion.data.model.Invoice
import mg.madagestion.data.model.PaymentMethod
import mg.madagestion.ui.theme.Muted

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

/**
 * Boîte de dialogue d'imputation d'un règlement ou d'un avoir sur une facture.
 * Les textes saisis sont renvoyés tels quels : l'appelant les convertit dans son action métier.
 */
@Composable
fun AllocateDialog(
    candidates: List<Pair<Invoice, BigDecimal>>,
    available: BigDecimal,
    onConfirm: (invoiceId: String, amountText: String) -> Unit,
    onDismiss: () -> Unit
) {
    fun suggested(open: BigDecimal): String = (if (open.compareTo(available) < 0) open else available).toPlainString()
    var selected by remember { mutableStateOf(candidates.firstOrNull()?.first?.id) }
    var amount by remember { mutableStateOf(candidates.firstOrNull()?.let { suggested(it.second) } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Imputer sur une facture") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Disponible : ${available.fmt()} Ar", color = Muted, fontSize = 13.sp)
                DropdownField(
                    label = "Facture",
                    options = candidates.map { (inv, open) ->
                        inv.id to "${inv.number ?: "?"} — reste ${open.fmt()} Ar"
                    },
                    selected = selected,
                    onSelect = { id ->
                        selected = id
                        candidates.firstOrNull { it.first.id == id }?.let { amount = suggested(it.second) }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                AmountField("Montant à imputer", amount, { amount = it }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let { onConfirm(it, amount) } }) { Text("Imputer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

/** Validation d'une facture réglée au comptant : choix du mode de règlement. */
@Composable
fun CashSettleDialog(
    receipt: Boolean,
    amount: BigDecimal,
    onConfirm: (PaymentMethod) -> Unit,
    onDismiss: () -> Unit
) {
    var method by remember { mutableStateOf(PaymentMethod.CASH) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Valider et régler au comptant ?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    (if (receipt) "L'encaissement" else "Le paiement") + " de ${amount.fmt()} Ar sera enregistré avec la validation. " +
                        "Le document ne sera plus modifiable.",
                    color = Muted, fontSize = 13.sp
                )
                PaymentMethodField(method, { method = it }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(method) }) { Text("Valider") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
