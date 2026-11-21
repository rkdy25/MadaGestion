package mg.madagestion.ui.invoice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import mg.madagestion.core.BusinessException
import mg.madagestion.core.Money
import mg.madagestion.core.fmt
import mg.madagestion.core.sumBd
import mg.madagestion.data.model.FeePayer
import mg.madagestion.data.model.InvoiceKind
import mg.madagestion.data.model.PaymentDirection
import mg.madagestion.data.model.PaymentMethod
import mg.madagestion.data.model.ThirdPartyType
import mg.madagestion.domain.invoicing.InvoiceFeeInput
import mg.madagestion.domain.invoicing.InvoiceLineInput
import mg.madagestion.domain.invoicing.isCredit
import mg.madagestion.domain.invoicing.isSale
import mg.madagestion.domain.invoicing.label
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.AccountField
import mg.madagestion.ui.components.AmountField
import mg.madagestion.ui.components.DateField
import mg.madagestion.ui.components.DropdownField
import mg.madagestion.ui.components.FormPage
import mg.madagestion.ui.components.PaymentMethodField
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
import mg.madagestion.ui.theme.Navy

private data class LineUi(
    val key: Long,
    val productId: String? = null,
    val accountId: String? = null,
    val description: String = "",
    val qty: String = "1",
    val price: String = "",
    val vat: String = "20"
)

private fun lineHt(l: LineUi): BigDecimal = Money.ar(Money.qty(lenientDecimal(l.qty)).multiply(lenientDecimal(l.price)))

private fun lineVat(l: LineUi): BigDecimal =
    Money.ar(lineHt(l).multiply(lenientDecimal(l.vat)).divide(BigDecimal(100), 2, RoundingMode.HALF_UP))

@Composable
fun InvoiceEditScreen(vm: AppViewModel, invoiceId: String?, kind: InvoiceKind) {
    val company by vm.company.collectAsState()
    val parties by vm.thirdParties.collectAsState()
    val products by vm.products.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val payments by vm.payments.collectAsState()
    val allocations by vm.allocations.collectAsState()

    val sale = kind.isSale()
    var currentId by remember { mutableStateOf(invoiceId) }
    var thirdPartyId by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var dueDate by remember { mutableStateOf(LocalDate.now().plusDays(30)) }
    var reference by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var originId by remember { mutableStateOf<String?>(null) }
    val lines = remember { mutableStateListOf<LineUi>() }
    var nextKey by remember { mutableStateOf(1L) }
    var cash by remember { mutableStateOf(false) }
    var cashMethod by remember { mutableStateOf(PaymentMethod.CASH) }
    val settle = cash && !kind.isCredit()

    // Transport et frais annexes (factures uniquement)
    var feeLabel by remember { mutableStateOf("Transport") }
    var feeAmount by remember { mutableStateOf("") }
    // Vente : l'entreprise paie le transport par défaut ; achat : le fournisseur le facture par défaut.
    var feePaidBy by remember { mutableStateOf(if (sale) FeePayer.COMPANY else FeePayer.THIRD_PARTY) }
    var feeMethod by remember { mutableStateOf(PaymentMethod.CASH) }
    var feeAccountId by remember { mutableStateOf<String?>(null) }
    // Avance du tiers déduite à la validation
    var useAdvance by remember { mutableStateOf(true) }
    // Avoir : retour physique des articles stockés
    var returnsGoods by remember { mutableStateOf(true) }

    val defaultVat = company?.defaultVatRate?.stripTrailingZeros()?.toPlainString() ?: "20"

    LaunchedEffect(invoiceId) {
        if (invoiceId != null) {
            val inv = vm.db.invoiceDao().get(invoiceId)
            if (inv != null) {
                thirdPartyId = inv.thirdPartyId
                date = inv.date
                dueDate = inv.dueDate
                reference = inv.reference
                note = inv.note
                originId = inv.originInvoiceId
                if (inv.feeAmount.signum() > 0) {
                    feeLabel = inv.feeLabel
                    feeAmount = inv.feeAmount.stripTrailingZeros().toPlainString()
                    inv.feePaidBy?.let { feePaidBy = it }
                    inv.feeMethod?.let { feeMethod = it }
                    feeAccountId = inv.feeAccountId
                }
                if (kind.isCredit()) returnsGoods = inv.returnsGoods
                lines.clear()
                vm.db.invoiceDao().lines(invoiceId).forEach { l ->
                    lines.add(
                        LineUi(
                            key = nextKey++, productId = l.productId, accountId = l.accountId,
                            description = l.description,
                            qty = l.qty.stripTrailingZeros().toPlainString(),
                            price = l.unitPrice.stripTrailingZeros().toPlainString(),
                            vat = l.vatRate.stripTrailingZeros().toPlainString()
                        )
                    )
                }
            }
        } else if (lines.isEmpty()) {
            lines.add(LineUi(key = nextKey++, vat = defaultVat))
        }
    }

    val ht = lines.sumBd { lineHt(it) }
    val vat = lines.sumBd { lineVat(it) }
    val fee = if (kind.isCredit()) BigDecimal.ZERO else Money.ar(lenientDecimal(feeAmount)).max(BigDecimal.ZERO)
    val feeOnParty = fee.signum() > 0 && feePaidBy == FeePayer.THIRD_PARTY
    // Celui qui a payé les frais en est crédité : déduits pour le client, ajoutés pour le fournisseur
    val feeEffect = if (!feeOnParty) BigDecimal.ZERO else if (sale) fee.negate() else fee
    val due = ht.add(vat).add(feeEffect)
    val advance = if (kind.isCredit()) BigDecimal.ZERO else advanceAvailable(
        payments, allocations, thirdPartyId,
        if (sale) PaymentDirection.RECEIPT else PaymentDirection.DISBURSEMENT
    )
    val advanceUsed = if (useAdvance) advance.min(due) else BigDecimal.ZERO

    fun submit(validate: Boolean) {
        val c = company?.id ?: return
        vm.act<String>(
            okMessage = if (!validate) "Brouillon enregistré." else if (settle) "Document validé et réglé." else "Document validé.",
            onDone = { vm.nav.pop() }
        ) {
            val tp = thirdPartyId
                ?: throw BusinessException(if (sale) "Choisissez un client." else "Choisissez un fournisseur.")
            val inputs = lines.mapIndexed { i, l ->
                InvoiceLineInput(
                    productId = l.productId,
                    accountId = l.accountId,
                    description = l.description,
                    qty = parseDecimal(l.qty, "Quantité de la ligne ${i + 1}"),
                    unitPrice = parseDecimal(l.price, "Prix de la ligne ${i + 1}"),
                    vatRate = parseDecimal(l.vat, "TVA de la ligne ${i + 1}")
                )
            }
            val feeValue = if (kind.isCredit()) BigDecimal.ZERO else parseDecimal(feeAmount, "Montant des frais")
            val feeInput = if (feeValue.signum() > 0) InvoiceFeeInput(
                label = feeLabel,
                amount = feeValue,
                paidBy = feePaidBy,
                method = if (feePaidBy == FeePayer.COMPANY) feeMethod else null,
                accountId = feeAccountId
            ) else null
            val id = vm.invoiceService.saveDraft(
                c, currentId, kind, tp, date, dueDate, reference, note, originId, inputs,
                fee = feeInput, returnsGoods = returnsGoods
            )
            currentId = id // évite un doublon si la validation échoue après l'enregistrement
            if (validate) {
                if (settle) vm.invoiceService.validateAndSettle(id, cashMethod, useAdvance)
                else vm.invoiceService.validate(id, useAdvance)
            }
            id
        }
    }

    FormPage(
        title = (if (invoiceId == null) "Nouveau : " else "Modifier : ") + kind.label(),
        onBack = { vm.nav.pop() }
    ) {
        DropdownField(
            label = if (sale) "Client" else "Fournisseur",
            options = parties
                .filter { (it.active || it.id == thirdPartyId) && it.type == (if (sale) ThirdPartyType.CUSTOMER else ThirdPartyType.SUPPLIER) }
                .map { it.id to it.name },
            selected = thirdPartyId,
            onSelect = { thirdPartyId = it },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DateField("Date", date, { d ->
                date = d
                if (dueDate.isBefore(d)) dueDate = d
            }, Modifier.weight(1f))
            if (!kind.isCredit()) DateField("Échéance", dueDate, { dueDate = it }, Modifier.weight(1f))
        }
        OutlinedTextField(
            value = reference, onValueChange = { reference = it },
            label = { Text(if (sale) "Référence (bon de commande…)" else "N° de la facture du fournisseur") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        SectionTitle("Lignes")
        lines.forEachIndexed { index, line ->
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ligne ${index + 1}", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    if (lines.size > 1) {
                        IconButton(onClick = { lines.removeAt(index) }) {
                            Icon(Icons.Default.Delete, "Supprimer la ligne", tint = Danger)
                        }
                    }
                }
                val productOptions: List<Pair<String?, String>> =
                    listOf<Pair<String?, String>>(null to "— Libre (sans article) —") +
                        products.filter { it.active || it.id == line.productId }.map { it.id to it.name }
                DropdownField(
                    label = "Article",
                    options = productOptions,
                    selected = line.productId,
                    onSelect = { pid ->
                        val p = products.firstOrNull { it.id == pid }
                        lines[index] = if (p == null) line.copy(productId = null) else line.copy(
                            productId = p.id,
                            description = line.description.ifBlank { p.name },
                            price = (if (sale) p.salePrice else p.purchasePrice).stripTrailingZeros().toPlainString()
                                .let { if (it == "0") line.price else it }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = line.description, onValueChange = { lines[index] = line.copy(description = it) },
                    label = { Text("Description") }, modifier = Modifier.fillMaxWidth()
                )
                val stockedProduct = products.firstOrNull { it.id == line.productId }?.isStocked == true
                if (!stockedProduct) {
                    AccountField(
                        label = if (sale) "Compte de produit (facultatif)" else "Compte de charge (facultatif)",
                        accounts = accounts.filter { it.code.startsWith(if (sale) "7" else "6") },
                        selectedId = line.accountId,
                        onSelect = { lines[index] = line.copy(accountId = it) },
                        modifier = Modifier.fillMaxWidth(),
                        onClear = { lines[index] = line.copy(accountId = null) }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountField("Qté", line.qty, { lines[index] = line.copy(qty = it) }, Modifier.weight(1f))
                    AmountField("Prix unitaire HT", line.price, { lines[index] = line.copy(price = it) }, Modifier.weight(1.6f))
                    AmountField("TVA %", line.vat, { lines[index] = line.copy(vat = it) }, Modifier.weight(1f))
                }
                Text("Total HT : ${lineHt(line).fmt()} Ar", color = Navy, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        SecondaryButton("+ Ajouter une ligne", { lines.add(LineUi(key = nextKey++, vat = defaultVat)) }, Modifier.fillMaxWidth())

        OutlinedTextField(
            value = note, onValueChange = { note = it }, label = { Text("Note (facultatif)") },
            modifier = Modifier.fillMaxWidth()
        )

        if (!kind.isCredit()) {
            SectionTitle("Transport et frais annexes")
            SurfaceCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = feeLabel, onValueChange = { feeLabel = it },
                        label = { Text("Nature (transport, manutention…)") },
                        singleLine = true, modifier = Modifier.weight(1.6f)
                    )
                    AmountField("Montant (Ar)", feeAmount, { feeAmount = it }, Modifier.weight(1f))
                }
                DropdownField(
                    label = "Qui a payé ces frais ?",
                    options = if (sale) listOf(
                        FeePayer.COMPANY to "L'entreprise (sortie de caisse, banque…)",
                        FeePayer.THIRD_PARTY to "Le client (déduit de sa facture)"
                    ) else listOf(
                        FeePayer.THIRD_PARTY to "Le fournisseur (compris dans sa facture)",
                        FeePayer.COMPANY to "L'entreprise (payé directement au transporteur)"
                    ),
                    selected = feePaidBy,
                    onSelect = { feePaidBy = it; feeAccountId = null },
                    modifier = Modifier.fillMaxWidth()
                )
                if (feePaidBy == FeePayer.COMPANY) {
                    PaymentMethodField(feeMethod, { feeMethod = it }, Modifier.fillMaxWidth())
                }
                AccountField(
                    label = "Compte de charge (défaut : 624)",
                    accounts = accounts.filter { it.code.startsWith("6") },
                    selectedId = feeAccountId,
                    onSelect = { feeAccountId = it },
                    modifier = Modifier.fillMaxWidth(),
                    onClear = { feeAccountId = null }
                )
                Text(
                    when {
                        feePaidBy == FeePayer.COMPANY -> "Écriture : débit du compte de charge, crédit de la trésorerie choisie."
                        sale -> "Écriture : débit du compte de charge ; les frais sont déduits du montant dû par le client (411)."
                        else -> "Écriture : les frais s'ajoutent au montant dû au fournisseur (401) et vont au débit du compte de charge."
                    },
                    color = Muted, fontSize = 11.sp
                )
            }
        } else {
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Retour des marchandises", color = Navy, fontWeight = FontWeight.Medium)
                        Text(
                            if (sale) "Les articles stockés reviennent en stock à leur coût de sortie."
                            else "Les articles stockés sortent du stock (renvoyés au fournisseur).",
                            color = Muted, fontSize = 11.sp
                        )
                    }
                    Switch(checked = returnsGoods, onCheckedChange = { returnsGoods = it })
                }
                Text(
                    "À la validation, l'avoir est imputé sur sa facture d'origine, qui est marquée soldée à hauteur de l'avoir.",
                    color = Muted, fontSize = 11.sp
                )
            }
        }

        if (advance.signum() > 0) {
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Déduire l'avance", color = Navy, fontWeight = FontWeight.Medium)
                        Text(
                            (if (sale) "Avance reçue du client (419) : " else "Avance versée au fournisseur (409) : ") +
                                "${advance.fmt()} Ar disponible.",
                            color = Muted, fontSize = 11.sp
                        )
                    }
                    Switch(checked = useAdvance, onCheckedChange = { useAdvance = it })
                }
            }
        }

        SurfaceCard {
            TotalRow("Total HT", ht)
            TotalRow("TVA", vat)
            TotalRow("Total TTC", ht.add(vat), bold = !feeOnParty && advanceUsed.signum() == 0)
            if (fee.signum() > 0) {
                TotalRow(
                    when {
                        feePaidBy == FeePayer.COMPANY -> "Frais payés par l'entreprise (hors facture)"
                        sale -> "Frais payés par le client (déduits)"
                        else -> "Frais facturés par le fournisseur"
                    },
                    fee
                )
            }
            if (feeOnParty) TotalRow(if (sale) "Total dû par le client" else "Total dû au fournisseur", due, bold = advanceUsed.signum() == 0)
            if (advanceUsed.signum() > 0) {
                TotalRow("Avance déduite", advanceUsed, color = Good)
                TotalRow("Reste à régler", due.subtract(advanceUsed), bold = true)
            }
        }
        if (!kind.isCredit()) {
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Réglé au comptant", color = Navy, fontWeight = FontWeight.Medium)
                        Text(
                            if (sale) "Le client paie à la validation : l'encaissement est enregistré avec la facture."
                            else "Le fournisseur est payé à la validation : le paiement est enregistré avec la facture.",
                            color = Muted, fontSize = 11.sp
                        )
                    }
                    Switch(checked = cash, onCheckedChange = { cash = it })
                }
                if (cash) PaymentMethodField(cashMethod, { cashMethod = it }, Modifier.fillMaxWidth())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton("Enregistrer le brouillon", { submit(false) }, Modifier.weight(1f))
            PrimaryButton(
                if (!settle) "Valider" else if (sale) "Valider et encaisser" else "Valider et payer",
                { submit(true) }, Modifier.weight(1f)
            )
        }
        Text(
            "Une fois validé, le document reçoit son numéro, génère l'écriture comptable " +
                "(et le mouvement de stock) et n'est plus modifiable : il se corrige par un avoir.",
            color = Muted, fontSize = 11.sp
        )
    }
}
