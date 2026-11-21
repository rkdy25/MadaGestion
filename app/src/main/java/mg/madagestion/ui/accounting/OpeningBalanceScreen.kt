package mg.madagestion.ui.accounting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import java.math.BigDecimal
import java.time.LocalDate
import mg.madagestion.core.BusinessException
import mg.madagestion.core.Money
import mg.madagestion.core.fmt
import mg.madagestion.core.sumBd
import mg.madagestion.data.model.JournalEntry
import mg.madagestion.data.model.ThirdPartyType
import mg.madagestion.domain.accounting.MappingKey
import mg.madagestion.domain.accounting.OpeningAccountLine
import mg.madagestion.domain.accounting.OpeningStockLine
import mg.madagestion.domain.accounting.accountClass
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.AccountField
import mg.madagestion.ui.components.AmountField
import mg.madagestion.ui.components.ConfirmDialog
import mg.madagestion.ui.components.DateField
import mg.madagestion.ui.components.DropdownField
import mg.madagestion.ui.components.FormPage
import mg.madagestion.ui.components.PrimaryButton
import mg.madagestion.ui.components.SecondaryButton
import mg.madagestion.ui.components.SectionTitle
import mg.madagestion.ui.components.StatusPill
import mg.madagestion.ui.components.SurfaceCard
import mg.madagestion.ui.components.TotalRow
import mg.madagestion.ui.format.lenientDecimal
import mg.madagestion.ui.format.parseDecimal
import mg.madagestion.ui.navigation.Screen
import mg.madagestion.ui.theme.Danger
import mg.madagestion.ui.theme.Good
import mg.madagestion.ui.theme.Muted
import mg.madagestion.ui.theme.Navy

private data class OpeningLineUi(
    val key: Long,
    val accountId: String? = null,
    val thirdPartyId: String? = null,
    val debit: String = "",
    val credit: String = ""
)

private data class OpeningStockUi(
    val key: Long,
    val productId: String? = null,
    val qty: String = "",
    val cost: String = ""
)

private fun stockValue(l: OpeningStockUi): BigDecimal =
    Money.ar(Money.qty(lenientDecimal(l.qty)).multiply(lenientDecimal(l.cost)))

/**
 * Situation initiale : saisie du bilan de l'entreprise au démarrage de l'application
 * (soldes des comptes, créances et dettes par tiers, stock par article).
 */
@Composable
fun OpeningBalanceScreen(vm: AppViewModel) {
    val company by vm.company.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val parties by vm.thirdParties.collectAsState()
    val products by vm.products.collectAsState()
    val mappings by vm.mappings.collectAsState()
    val entries by vm.entries.collectAsState()

    var date by remember { mutableStateOf(LocalDate.of(LocalDate.now().year, 1, 1)) }
    val lines = remember { mutableStateListOf<OpeningLineUi>() }
    val stockLines = remember { mutableStateListOf<OpeningStockUi>() }
    var nextKey by remember { mutableStateOf(1L) }
    var existing by remember { mutableStateOf<List<JournalEntry>>(emptyList()) }
    var confirmPost by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf<JournalEntry?>(null) }

    LaunchedEffect(entries, company?.id) {
        val c = company?.id ?: return@LaunchedEffect
        existing = vm.safely { vm.openingBalance.list(c) } ?: emptyList()
    }
    LaunchedEffect(Unit) {
        if (lines.isEmpty()) {
            lines.add(OpeningLineUi(nextKey++))
            lines.add(OpeningLineUi(nextKey++))
        }
    }

    fun mapped(key: MappingKey): String? = mappings.firstOrNull { it.mapKey == key.name }?.accountId
    val stockAcc = mapped(MappingKey.STOCK)
    // Comptes qui exigent un tiers : collectifs et avances
    val customerAccounts = setOfNotNull(mapped(MappingKey.CUSTOMERS), mapped(MappingKey.CUSTOMER_ADVANCES))
    val supplierAccounts = setOfNotNull(mapped(MappingKey.SUPPLIERS), mapped(MappingKey.SUPPLIER_ADVANCES))
    val balanceAccounts = accounts.filter { it.accountClass() in '1'..'5' && it.id != stockAcc }
    val capital = accounts.firstOrNull { it.code == "101" && it.active }

    val stockTotal = stockLines.sumBd { stockValue(it) }
    val totalDebit = lines.sumBd { lenientDecimal(it.debit) }.add(stockTotal)
    val totalCredit = lines.sumBd { lenientDecimal(it.credit) }
    val gap = totalDebit.subtract(totalCredit)
    val active = existing.filter { it.reversedById == null }

    fun submit() {
        vm.act<String>("Situation initiale enregistrée.", { entryId -> vm.nav.replace(Screen.EcritureDetail(entryId)) }) {
            val accountInputs = lines.mapIndexedNotNull { i, l ->
                val empty = l.accountId == null && l.debit.isBlank() && l.credit.isBlank()
                if (empty) null else OpeningAccountLine(
                    accountId = l.accountId ?: throw BusinessException("Ligne ${i + 1} : choisissez un compte."),
                    thirdPartyId = l.thirdPartyId,
                    debit = parseDecimal(l.debit, "Débit de la ligne ${i + 1}"),
                    credit = parseDecimal(l.credit, "Crédit de la ligne ${i + 1}")
                )
            }
            val stockInputs = stockLines.mapIndexedNotNull { i, l ->
                val empty = l.productId == null && l.qty.isBlank() && l.cost.isBlank()
                if (empty) null else OpeningStockLine(
                    productId = l.productId ?: throw BusinessException("Stock, ligne ${i + 1} : choisissez un article."),
                    qty = parseDecimal(l.qty, "Quantité du stock, ligne ${i + 1}"),
                    unitCost = parseDecimal(l.cost, "Coût unitaire du stock, ligne ${i + 1}")
                )
            }
            vm.openingBalance.post(vm.requireCompany(), date, accountInputs, stockInputs)
        }
    }

    FormPage("Situation initiale", { vm.nav.pop() }) {
        Text(
            "Reprenez ici le bilan de l'entreprise à la date de démarrage : l'actif au débit, le passif au crédit. " +
                "Les deux totaux doivent être égaux.",
            color = Muted, fontSize = 12.sp
        )

        if (existing.isNotEmpty()) {
            SectionTitle("Déjà enregistrée")
            existing.forEach { e ->
                SurfaceCard(onClick = { vm.nav.push(Screen.EcritureDetail(e.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.number ?: "", fontWeight = FontWeight.SemiBold, color = Navy)
                            Text(e.label, color = Muted, fontSize = 12.sp)
                        }
                        if (e.reversedById != null) {
                            StatusPill("Annulée", Muted)
                        } else {
                            TextButton(onClick = { cancelling = e }) { Text("Annuler", color = Danger) }
                        }
                    }
                }
            }
            if (active.isNotEmpty()) {
                Text(
                    "Une situation initiale existe déjà. N'en saisissez une autre que pour la compléter : les montants s'additionnent.",
                    color = Danger, fontSize = 12.sp
                )
            }
        }

        DateField("Date de la situation", date, { date = it }, Modifier.fillMaxWidth())

        SectionTitle("Comptes du bilan")
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
                AccountField(
                    label = "Compte (classes 1 à 5)",
                    accounts = balanceAccounts,
                    selectedId = line.accountId,
                    onSelect = { lines[index] = line.copy(accountId = it, thirdPartyId = null) },
                    modifier = Modifier.fillMaxWidth()
                )
                val partyType = when (line.accountId) {
                    null -> null
                    in customerAccounts -> ThirdPartyType.CUSTOMER
                    in supplierAccounts -> ThirdPartyType.SUPPLIER
                    else -> null
                }
                if (partyType != null) {
                    DropdownField(
                        label = if (partyType == ThirdPartyType.CUSTOMER) "Client" else "Fournisseur",
                        options = parties.filter { it.type == partyType && (it.active || it.id == line.thirdPartyId) }.map { it.id to it.name },
                        selected = line.thirdPartyId,
                        onSelect = { lines[index] = line.copy(thirdPartyId = it) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountField("Débit (actif)", line.debit, { lines[index] = line.copy(debit = it) }, Modifier.weight(1f))
                    AmountField("Crédit (passif)", line.credit, { lines[index] = line.copy(credit = it) }, Modifier.weight(1f))
                }
            }
        }
        SecondaryButton("+ Ajouter un compte", { lines.add(OpeningLineUi(nextKey++)) }, Modifier.fillMaxWidth())

        SectionTitle("Stock initial")
        Text(
            "Le stock se saisit par article : l'application met à jour les quantités, le coût moyen et le compte de stock.",
            color = Muted, fontSize = 12.sp
        )
        val stocked = products.filter { it.isStocked }
        stockLines.forEachIndexed { index, line ->
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Article ${index + 1}", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { stockLines.removeAt(index) }) {
                        Icon(Icons.Default.Delete, "Supprimer l'article", tint = Danger)
                    }
                }
                DropdownField(
                    label = "Article",
                    options = stocked.filter { it.active || it.id == line.productId }.map { it.id to it.name },
                    selected = line.productId,
                    onSelect = { pid ->
                        val p = stocked.firstOrNull { it.id == pid }
                        val cost = p?.purchasePrice?.stripTrailingZeros()?.toPlainString() ?: ""
                        stockLines[index] = line.copy(productId = pid, cost = if (line.cost.isBlank() && cost != "0") cost else line.cost)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountField("Quantité", line.qty, { stockLines[index] = line.copy(qty = it) }, Modifier.weight(1f))
                    AmountField("Coût unitaire", line.cost, { stockLines[index] = line.copy(cost = it) }, Modifier.weight(1f))
                }
                Text("Valeur : ${stockValue(line).fmt()} Ar", color = Navy, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (stocked.isEmpty()) {
            Text("Aucun article géré en stock : créez d'abord vos articles dans le module Stocks.", color = Muted, fontSize = 12.sp)
        } else {
            SecondaryButton("+ Ajouter un article", { stockLines.add(OpeningStockUi(nextKey++)) }, Modifier.fillMaxWidth())
        }

        SurfaceCard {
            if (stockTotal.signum() > 0) TotalRow("Dont stock initial", stockTotal)
            TotalRow("Total actif (débit)", totalDebit, bold = true)
            TotalRow("Total passif (crédit)", totalCredit, bold = true)
            TotalRow(
                if (gap.signum() == 0) "Bilan équilibré" else "Écart à corriger",
                gap.abs(), bold = true, color = if (gap.signum() == 0) Good else Danger
            )
        }
        if (gap.signum() != 0 && capital != null) {
            SecondaryButton("Porter l'écart au compte ${capital.code} ${capital.label}", {
                lines.add(
                    if (gap.signum() > 0) OpeningLineUi(nextKey++, accountId = capital.id, credit = gap.toPlainString())
                    else OpeningLineUi(nextKey++, accountId = capital.id, debit = gap.negate().toPlainString())
                )
            }, Modifier.fillMaxWidth())
        }
        PrimaryButton("Enregistrer la situation initiale", { confirmPost = true }, Modifier.fillMaxWidth(), enabled = gap.signum() == 0 && totalDebit.signum() > 0)
        Text(
            "L'écriture est passée au journal des à-nouveaux, validée et verrouillée. " +
                "Faites valider votre bilan d'ouverture par votre expert-comptable.",
            color = Muted, fontSize = 11.sp
        )
    }

    if (confirmPost) {
        ConfirmDialog(
            "Enregistrer la situation initiale ?",
            "Une écriture d'ouverture de ${totalDebit.fmt()} Ar sera validée au $date. Elle ne sera plus modifiable.",
            "Enregistrer",
            { confirmPost = false; submit() },
            { confirmPost = false }
        )
    }
    cancelling?.let { e ->
        ConfirmDialog(
            "Annuler ${e.number ?: "cette situation"} ?",
            "Une écriture de contrepassation sera passée à la date d'aujourd'hui. Impossible si la situation a fait entrer du stock.",
            "Annuler la situation",
            {
                cancelling = null
                vm.act("Situation initiale annulée.") { vm.openingBalance.cancel(e.id, LocalDate.now()) }
            },
            { cancelling = null }
        )
    }
}
