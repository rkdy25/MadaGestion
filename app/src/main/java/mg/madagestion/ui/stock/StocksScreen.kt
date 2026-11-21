package mg.madagestion.ui.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode
import mg.madagestion.core.fmt
import mg.madagestion.core.fmtQty
import mg.madagestion.core.sumBd
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.EmptyHint
import mg.madagestion.ui.components.ListPage
import mg.madagestion.ui.components.MetricTile
import mg.madagestion.ui.components.StatusPill
import mg.madagestion.ui.components.SurfaceCard
import mg.madagestion.ui.components.TabsRow
import mg.madagestion.ui.format.label
import mg.madagestion.ui.navigation.Screen
import mg.madagestion.ui.theme.Danger
import mg.madagestion.ui.theme.Good
import mg.madagestion.ui.theme.Muted
import mg.madagestion.ui.theme.Navy
import mg.madagestion.ui.theme.Warning

@Composable
fun StocksScreen(vm: AppViewModel) {
    val products by vm.products.collectAsState()
    val movements by vm.movements.collectAsState()
    var tab by remember { mutableStateOf(0) }

    ListPage(
        title = "Stocks",
        onBack = { vm.nav.pop() },
        fabLabel = if (tab == 0) "Nouvel article" else null,
        onFab = { vm.nav.push(Screen.ArticleEdit(null)) },
        header = {
            TabsRow(listOf("Articles", "Mouvements"), tab) { tab = it }
            if (tab == 0) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile("Valeur du stock", products.sumBd { it.stockValue }, Modifier.weight(1f))
                    SurfaceCard(modifier = Modifier.weight(1f)) {
                        Text("Articles gérés en stock", color = Muted, fontSize = 11.sp)
                        Text("${products.count { it.isStocked }}", color = Navy, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
        }
    ) {
        if (tab == 0) {
            if (products.isEmpty()) item { EmptyHint("Aucun article. Créez vos articles puis entrez le stock initial par un ajustement.") }
            items(products, key = { it.id }) { p ->
                SurfaceCard(onClick = { vm.nav.push(Screen.ArticleEdit(p.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, color = Navy)
                            Text(
                                (if (p.sku.isBlank()) "" else "${p.sku} · ") + "vente ${p.salePrice.fmt()} Ar",
                                color = Muted, fontSize = 12.sp
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (p.isStocked) {
                                Text(
                                    "${p.qtyOnHand.fmtQty()} ${p.unit}",
                                    fontWeight = FontWeight.Bold,
                                    color = if (p.qtyOnHand.signum() <= 0) Danger else Navy
                                )
                                val cump = if (p.qtyOnHand.signum() > 0)
                                    p.stockValue.divide(p.qtyOnHand, 0, RoundingMode.HALF_UP) else BigDecimal.ZERO
                                Text("CUMP ${cump.fmt()} Ar", color = Muted, fontSize = 11.sp)
                            } else {
                                StatusPill("Service", Muted)
                            }
                            if (!p.active) StatusPill("Inactif", Warning)
                        }
                    }
                }
            }
        } else {
            if (movements.isEmpty()) item { EmptyHint("Aucun mouvement de stock.") }
            items(movements, key = { it.id }) { m ->
                SurfaceCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(products.firstOrNull { it.id == m.productId }?.name ?: "Article", fontWeight = FontWeight.SemiBold, color = Navy)
                            Text("${m.date} · ${m.type.label()}" + if (m.note.isBlank()) "" else " · ${m.note}", color = Muted, fontSize = 11.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                (if (m.qty.signum() > 0) "+" else "") + m.qty.fmtQty(),
                                fontWeight = FontWeight.Bold,
                                color = if (m.qty.signum() >= 0) Good else Danger
                            )
                            Text("${m.value.fmt()} Ar", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
