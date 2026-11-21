package mg.madagestion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import mg.madagestion.ui.accounting.AccountingScreen
import mg.madagestion.ui.accounting.EntryDetailScreen
import mg.madagestion.ui.accounting.EntryEditScreen
import mg.madagestion.ui.accounting.OpeningBalanceScreen
import mg.madagestion.ui.control.ControlsScreen
import mg.madagestion.ui.dashboard.DashboardScreen
import mg.madagestion.ui.invoice.InvoiceDetailScreen
import mg.madagestion.ui.invoice.InvoiceEditScreen
import mg.madagestion.ui.invoice.InvoiceListScreen
import mg.madagestion.ui.navigation.Screen
import mg.madagestion.ui.party.PartyOperationScreen
import mg.madagestion.ui.party.ThirdPartiesScreen
import mg.madagestion.ui.party.ThirdPartyDetailScreen
import mg.madagestion.ui.party.ThirdPartyEditScreen
import mg.madagestion.ui.payment.PaymentDetailScreen
import mg.madagestion.ui.payment.PaymentNewScreen
import mg.madagestion.ui.payment.PaymentsScreen
import mg.madagestion.ui.report.ReportsScreen
import mg.madagestion.ui.settings.SettingsScreen
import mg.madagestion.ui.stock.ProductEditScreen
import mg.madagestion.ui.stock.StocksScreen
import mg.madagestion.ui.theme.Canvas
import mg.madagestion.ui.theme.Navy
import mg.madagestion.ui.theme.Teal

private data class BottomTab(val label: String, val icon: ImageVector, val root: Screen)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MadaGestionApp(vm: AppViewModel = viewModel()) {
    val company by vm.company.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val nav = vm.nav

    LaunchedEffect(Unit) {
        vm.messages.collect { msg ->
            snackbar.currentSnackbarData?.dismiss()
            launch { snackbar.showSnackbar(msg, duration = SnackbarDuration.Long) }
        }
    }

    BackHandler(enabled = !nav.atRoot) { nav.pop() }

    val tabs = listOf(
        BottomTab("Accueil", Icons.Default.Home, Screen.Home),
        BottomTab("Ventes", Icons.Default.PointOfSale, Screen.Factures(true)),
        BottomTab("Achats", Icons.Default.ShoppingCart, Screen.Factures(false)),
        BottomTab("Compta", Icons.Default.AccountBalance, Screen.Compta),
        BottomTab("Contrôles", Icons.Default.CheckCircle, Screen.Controles)
    )

    MaterialTheme(colorScheme = lightColorScheme(primary = Navy, secondary = Teal, background = Canvas)) {
        Scaffold(
            modifier = Modifier.imePadding(),
            containerColor = Canvas,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("MadaGestion", fontWeight = FontWeight.Bold, color = Color.White)
                            Text(company?.name ?: "", fontSize = 11.sp, color = Color(0xFFD9E5EF))
                        }
                    },
                    actions = {
                        IconButton(onClick = { if (nav.current != Screen.Reglages) nav.push(Screen.Reglages) }) {
                            Icon(Icons.Default.Settings, "Paramètres", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy)
                )
            },
            // La barre du bas n'existe qu'aux écrans de premier niveau : les formulaires gagnent de la place.
            bottomBar = {
                if (nav.atRoot) {
                    NavigationBar(containerColor = Color.White) {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = nav.current == tab.root,
                                onClick = { nav.root(tab.root) },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label, fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFE7F3F2))
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                // L'écran affiché dépend de la pile ; la clé évite de mélanger les états de deux écrans.
                val screen = nav.current
                androidx.compose.runtime.key(screen) { ScreenHost(vm, screen) }
            }
        }
    }
}

@Composable
private fun ScreenHost(vm: AppViewModel, screen: Screen) {
    when (screen) {
        Screen.Home -> DashboardScreen(vm)
        is Screen.Factures -> InvoiceListScreen(vm, screen.sale)
        is Screen.FactureEdit -> InvoiceEditScreen(vm, screen.id, screen.kind)
        is Screen.FactureDetail -> InvoiceDetailScreen(vm, screen.id)
        Screen.Reglements -> PaymentsScreen(vm)
        is Screen.ReglementNew -> PaymentNewScreen(vm, screen.direction, screen.invoiceId, screen.thirdPartyId)
        is Screen.ReglementDetail -> PaymentDetailScreen(vm, screen.id)
        Screen.Stocks -> StocksScreen(vm)
        is Screen.ArticleEdit -> ProductEditScreen(vm, screen.id)
        is Screen.Tiers -> ThirdPartiesScreen(vm, screen.type)
        is Screen.TiersEdit -> ThirdPartyEditScreen(vm, screen.id, screen.type)
        is Screen.TiersDetail -> ThirdPartyDetailScreen(vm, screen.id)
        is Screen.TiersOperation -> PartyOperationScreen(vm, screen.thirdPartyId, screen.kind)
        Screen.Compta -> AccountingScreen(vm)
        Screen.SituationInitiale -> OpeningBalanceScreen(vm)
        is Screen.EcritureEdit -> EntryEditScreen(vm, screen.id)
        is Screen.EcritureDetail -> EntryDetailScreen(vm, screen.id)
        Screen.Rapports -> ReportsScreen(vm)
        Screen.Controles -> ControlsScreen(vm)
        Screen.Reglages -> SettingsScreen(vm)
    }
}
