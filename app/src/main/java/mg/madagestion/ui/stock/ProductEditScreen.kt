package mg.madagestion.ui.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import mg.madagestion.core.fmt
import mg.madagestion.core.fmtQty
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.AmountField
import mg.madagestion.ui.components.DateField
import mg.madagestion.ui.components.FormPage
import mg.madagestion.ui.components.KeyValue
import mg.madagestion.ui.components.PrimaryButton
import mg.madagestion.ui.components.SecondaryButton
import mg.madagestion.ui.components.SectionTitle
import mg.madagestion.ui.components.SurfaceCard
import mg.madagestion.ui.format.parseDecimal
import mg.madagestion.ui.theme.Muted
import mg.madagestion.ui.theme.Navy

@Composable
fun ProductEditScreen(vm: AppViewModel, productId: String?) {
    val products by vm.products.collectAsState()
    val existing = products.firstOrNull { it.id == productId }

    var sku by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("u") }
    var salePrice by remember { mutableStateOf("") }
    var purchasePrice by remember { mutableStateOf("") }
    var stocked by remember { mutableStateOf(true) }
    var active by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(productId == null) }
    var adjusting by remember { mutableStateOf(false) }

    LaunchedEffect(existing?.id) {
        if (!loaded && existing != null) {
            sku = existing.sku
            name = existing.name
            unit = existing.unit
            salePrice = existing.salePrice.toPlainString()
            purchasePrice = existing.purchasePrice.toPlainString()
            stocked = existing.isStocked
            active = existing.active
            loaded = true
        }
    }

    FormPage(if (productId == null) "Nouvel article" else "Article", { vm.nav.pop() }) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nom de l'article") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = sku, onValueChange = { sku = it }, label = { Text("Référence") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Unité") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AmountField("Prix de vente HT", salePrice, { salePrice = it }, Modifier.weight(1f))
            AmountField("Prix d'achat HT", purchasePrice, { purchasePrice = it }, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Géré en stock", color = Navy, fontWeight = FontWeight.Medium)
                Text("Désactivé pour un service ou un article non stocké.", color = Muted, fontSize = 11.sp)
            }
            Switch(checked = stocked, onCheckedChange = { stocked = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Actif", color = Navy, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Switch(checked = active, onCheckedChange = { active = it })
        }
        PrimaryButton("Enregistrer", {
            vm.act<Unit>("Article enregistré.", { vm.nav.pop() }) {
                vm.stock.saveProduct(
                    vm.requireCompany(), productId, sku, name, unit,
                    parseDecimal(salePrice, "Prix de vente"), parseDecimal(purchasePrice, "Prix d'achat"),
                    stocked, active
                )
            }
        }, Modifier.fillMaxWidth())

        if (existing != null && existing.isStocked) {
            SectionTitle("Stock actuel")
            SurfaceCard {
                KeyValue("Quantité", "${existing.qtyOnHand.fmtQty()} ${existing.unit}")
                KeyValue("Valeur", "${existing.stockValue.fmt()} Ar")
            }
            SecondaryButton("Ajuster le stock (stock initial, inventaire, casse)", { adjusting = true }, Modifier.fillMaxWidth())
            Text(
                "Les entrées et sorties courantes viennent des factures d'achat et de vente. " +
                    "Un ajustement crée une écriture d'opérations diverses.",
                color = Muted, fontSize = 11.sp
            )
        }
    }

    if (adjusting && existing != null) {
        var delta by remember { mutableStateOf("") }
        var cost by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("Inventaire") }
        var date by remember { mutableStateOf(LocalDate.now()) }
        AlertDialog(
            onDismissRequest = { adjusting = false },
            title = { Text("Ajuster « ${existing.name} »") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Quantité positive = entrée, négative = sortie (ex. -2).", color = Muted, fontSize = 12.sp)
                    AmountField("Variation de quantité", delta, { delta = it }, Modifier.fillMaxWidth())
                    AmountField("Coût unitaire (obligatoire si stock vide)", cost, { cost = it }, Modifier.fillMaxWidth())
                    DateField("Date", date, { date = it }, Modifier.fillMaxWidth())
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Motif") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    adjusting = false
                    vm.act("Stock ajusté.") {
                        vm.stock.adjust(
                            vm.requireCompany(), existing.id, date,
                            parseDecimal(delta, "Quantité"),
                            if (cost.isBlank()) null else parseDecimal(cost, "Coût unitaire"),
                            note
                        )
                    }
                }) { Text("Ajuster") }
            },
            dismissButton = { TextButton(onClick = { adjusting = false }) { Text("Annuler") } }
        )
    }
}
