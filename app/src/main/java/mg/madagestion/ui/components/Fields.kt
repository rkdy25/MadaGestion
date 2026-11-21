package mg.madagestion.ui.components

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import mg.madagestion.data.model.Account
import mg.madagestion.data.model.PaymentMethod
import mg.madagestion.ui.format.label
import mg.madagestion.ui.theme.Navy

@Composable
fun AmountField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}

@Composable
fun DateField(label: String, value: LocalDate, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier) {
        OutlinedTextField(
            value = value.toString(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            Modifier.matchParentSize().clickable {
                DatePickerDialog(
                    context,
                    { _, y, m, d -> onChange(LocalDate.of(y, m + 1, d)) },
                    value.year, value.monthValue - 1, value.dayOfMonth
                ).show()
            }
        )
    }
}

@Composable
fun <T> DropdownField(
    label: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val text = options.firstOrNull { it.first == selected }?.second ?: ""
    Box(modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.second) },
                    onClick = {
                        onSelect(option.first)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
fun PaymentMethodField(selected: PaymentMethod, onSelect: (PaymentMethod) -> Unit, modifier: Modifier = Modifier) {
    DropdownField(
        label = "Mode de règlement",
        options = PaymentMethod.values().map { it to it.label() },
        selected = selected,
        onSelect = onSelect,
        modifier = modifier
    )
}

/** Sélecteur de compte avec recherche par code ou libellé. */
@Composable
fun AccountField(
    label: String,
    accounts: List<Account>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null
) {
    var open by remember { mutableStateOf(false) }
    val selected = accounts.firstOrNull { it.id == selectedId }
    Box(modifier) {
        OutlinedTextField(
            value = selected?.let { "${it.code} — ${it.label}" } ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                if (selected != null && onClear != null) {
                    IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = "Effacer") }
                } else {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        // La zone cliquable laisse libre le bouton d'effacement à droite
        Box(Modifier.matchParentSize().padding(end = 48.dp).clickable { open = true })
    }
    if (open) {
        AccountPickerDialog(
            accounts = accounts,
            onDismiss = { open = false },
            onPick = {
                onSelect(it)
                open = false
            }
        )
    }
}

@Composable
fun AccountPickerDialog(accounts: List<Account>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = accounts.filter {
        it.active && (query.isBlank() || it.code.startsWith(query.trim()) || it.label.contains(query.trim(), ignoreCase = true))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choisir un compte") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Code ou libellé") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(filtered, key = { it.id }) { a ->
                        Text(
                            "${a.code} — ${a.label}",
                            color = Navy,
                            fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(a.id) }.padding(vertical = 10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}
