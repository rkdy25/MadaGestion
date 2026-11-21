package mg.madagestion.ui.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mg.madagestion.domain.control.Control
import mg.madagestion.domain.control.Severity
import mg.madagestion.ui.AppViewModel
import mg.madagestion.ui.components.EmptyHint
import mg.madagestion.ui.components.ListPage
import mg.madagestion.ui.components.SurfaceCard
import mg.madagestion.ui.theme.Danger
import mg.madagestion.ui.theme.Good
import mg.madagestion.ui.theme.Muted
import mg.madagestion.ui.theme.Navy
import mg.madagestion.ui.theme.Warning

fun severityColor(s: Severity): Color = when (s) {
    Severity.OK -> Good
    Severity.INFO -> Muted
    Severity.WARN -> Warning
    Severity.ERROR -> Danger
}

fun severityIcon(s: Severity): ImageVector = when (s) {
    Severity.OK -> Icons.Default.CheckCircle
    Severity.INFO -> Icons.Default.Info
    Severity.WARN -> Icons.Default.Warning
    Severity.ERROR -> Icons.Default.Error
}

@Composable
fun ControlsScreen(vm: AppViewModel) {
    val company by vm.company.collectAsState()
    val entries by vm.entries.collectAsState()
    val invoices by vm.invoices.collectAsState()
    val payments by vm.payments.collectAsState()
    var controls by remember { mutableStateOf<List<Control>?>(null) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(company?.id, refresh, entries, invoices, payments) {
        val c = company?.id ?: return@LaunchedEffect
        controls = vm.safely { vm.controls.runAll(c) }
    }

    val list = controls
    ListPage(
        title = "Contrôles",
        onBack = if (vm.nav.atRoot) null else ({ vm.nav.pop() }),
        actions = {
            IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, "Relancer les contrôles", tint = Navy) }
        },
        header = {
            if (list != null) {
                val errors = list.count { it.severity == Severity.ERROR }
                val warns = list.count { it.severity == Severity.WARN }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SurfaceCard(modifier = Modifier.weight(1f)) {
                        Text("Erreurs", color = Muted, fontSize = 11.sp)
                        Text("$errors", color = if (errors > 0) Danger else Good, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                    SurfaceCard(modifier = Modifier.weight(1f)) {
                        Text("Alertes", color = Muted, fontSize = 11.sp)
                        Text("$warns", color = if (warns > 0) Warning else Good, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                    SurfaceCard(modifier = Modifier.weight(1f)) {
                        Text("Conformes", color = Muted, fontSize = 11.sp)
                        Text("${list.count { it.severity == Severity.OK }}", color = Good, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                }
            }
        }
    ) {
        if (list == null) {
            item { EmptyHint("Analyse en cours…") }
        } else {
            // Les anomalies d'abord
            items(list.sortedByDescending { it.severity.ordinal }, key = { it.title }) { c ->
                val color = severityColor(c.severity)
                SurfaceCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(severityIcon(c.severity), contentDescription = null, tint = color)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(c.title, fontWeight = FontWeight.SemiBold, color = Navy)
                            Text(c.summary, color = color, fontSize = 13.sp)
                        }
                    }
                    c.details.forEach { d ->
                        Text("• $d", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, start = 34.dp))
                    }
                }
            }
            item {
                Text(
                    "Ces contrôles relisent toute la base : ils servent de filet de sécurité même si une règle a été contournée. " +
                        "Ils ne remplacent pas la revue d'un expert-comptable.",
                    color = Muted, fontSize = 11.sp
                )
            }
        }
    }
}
