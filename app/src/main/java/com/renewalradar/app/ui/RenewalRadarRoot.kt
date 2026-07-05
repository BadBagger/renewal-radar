package com.renewalradar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.renewalradar.app.data.RenewalItem
import com.renewalradar.app.data.RenewalSettings
import com.renewalradar.app.data.RenewalStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun RenewalRadarRoot(
    state: RenewalUiState,
    onSave: (RenewalItem, () -> Unit) -> Unit,
    onDelete: (RenewalItem, () -> Unit) -> Unit,
    onSettingsChange: (RenewalSettings) -> Unit
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = route == "dashboard",
                    onClick = { navController.navigate("dashboard") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") }
                )
                NavigationBarItem(
                    selected = route == "items",
                    onClick = { navController.navigate("items") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.List, contentDescription = "Renewals") },
                    label = { Text("Renewals") }
                )
                NavigationBarItem(
                    selected = route == "settings",
                    onClick = { navController.navigate("settings") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        },
        floatingActionButton = {
            if (route == "dashboard" || route == "items") {
                FloatingActionButton(onClick = { navController.navigate("edit/0") }) {
                    Icon(Icons.Default.Add, contentDescription = "Add renewal")
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding)
        ) {
            composable("dashboard") {
                DashboardScreen(state, onOpenList = { navController.navigate("items") })
            }
            composable("items") {
                RenewalListScreen(
                    items = state.items,
                    onEdit = { navController.navigate("edit/${it.id}") }
                )
            }
            composable(
                route = "edit/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                val item = state.items.firstOrNull { it.id == id }
                RenewalEditScreen(
                    item = item,
                    defaults = state.settings,
                    onBack = { navController.popBackStack() },
                    onSave = { onSave(it) { navController.popBackStack() } },
                    onDelete = { target -> onDelete(target) { navController.popBackStack() } }
                )
            }
            composable("settings") {
                SettingsScreen(settings = state.settings, onSettingsChange = onSettingsChange)
            }
        }
    }
}

@Composable
private fun DashboardScreen(state: RenewalUiState, onOpenList: () -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Renewal Radar", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Track renewals before they become overdue.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Safe", state.summary.safe, statusColor(RenewalStatus.Safe), Modifier.weight(1f))
                MetricCard("Renew Soon", state.summary.renewSoon, statusColor(RenewalStatus.RenewSoon), Modifier.weight(1f))
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Needs Attention", state.summary.needsAttention, statusColor(RenewalStatus.NeedsAttention), Modifier.weight(1f))
                MetricCard("Overdue", state.summary.overdue, statusColor(RenewalStatus.Overdue), Modifier.weight(1f))
            }
        }
        item {
            Text("Next up", style = MaterialTheme.typography.titleMedium)
        }
        val nextItems = state.items.take(5)
        if (nextItems.isEmpty()) {
            item {
                EmptyState("No renewals yet. Add your first final deadline to start tracking.")
            }
        } else {
            items(nextItems, key = { it.id }) { item ->
                RenewalCard(item = item, onClick = onOpenList)
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(104.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = color)
            Text(count.toString(), style = MaterialTheme.typography.headlineLarge)
        }
    }
}

@Composable
private fun RenewalListScreen(items: List<RenewalItem>, onEdit: (RenewalItem) -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Renewals", style = MaterialTheme.typography.headlineMedium)
        }
        if (items.isEmpty()) {
            item { EmptyState("No tracked deadlines.") }
        } else {
            items(items, key = { it.id }) { item ->
                RenewalCard(item = item, onClick = { onEdit(item) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenewalEditScreen(
    item: RenewalItem?,
    defaults: RenewalSettings,
    onBack: () -> Unit,
    onSave: (RenewalItem) -> Unit,
    onDelete: (RenewalItem) -> Unit
) {
    var title by remember(item?.id) { mutableStateOf(item?.title ?: "") }
    var category by remember(item?.id) { mutableStateOf(item?.category ?: "") }
    var dueDate by remember(item?.id) { mutableStateOf(item?.dueDate ?: LocalDate.now().plusDays(70)) }
    var notes by remember(item?.id) { mutableStateOf(item?.notes ?: "") }
    var renewWindow by remember(item?.id) { mutableStateOf((item?.renewWindowDays ?: defaults.defaultRenewWindowDays).toString()) }
    var attentionWindow by remember(item?.id) { mutableStateOf((item?.attentionWindowDays ?: defaults.defaultAttentionWindowDays).toString()) }
    var notify by remember(item?.id) { mutableStateOf(item?.notify ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (item == null) "Add Renewal" else "Edit Renewal") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (item != null) {
                        IconButton(onClick = { onDelete(item) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete renewal")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(category, { category = it }, label = { Text("Category") }, modifier = Modifier.fillMaxWidth()) }
            item {
                DateField(
                    dueDate = dueDate,
                    onOpenPicker = { showDatePicker = true }
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("Renew window", renewWindow, { renewWindow = it }, Modifier.weight(1f))
                    NumberField("Attention window", attentionWindow, { attentionWindow = it }, Modifier.weight(1f))
                }
            }
            item { OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 3) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Local notifications")
                    Switch(checked = notify, onCheckedChange = { notify = it })
                }
            }
            error?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error) }
            }
            item {
                TextButton(
                    onClick = {
                        val parsed = parseForm(
                            item = item,
                            title = title,
                            category = category,
                            dueDate = dueDate,
                            notes = notes,
                            renewWindow = renewWindow,
                            attentionWindow = attentionWindow,
                            notify = notify
                        )
                        if (parsed.result == null) error = parsed.error else onSave(parsed.result)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Text("Save")
                }
            }
        }
    }

    if (showDatePicker) {
        RenewalDatePickerDialog(
            selectedDate = dueDate,
            onDismiss = { showDatePicker = false },
            onDateSelected = {
                dueDate = it
                showDatePicker = false
            }
        )
    }
}

@Composable
private fun DateField(dueDate: LocalDate, onOpenPicker: () -> Unit) {
    OutlinedTextField(
        value = dueDate.toString(),
        onValueChange = {},
        readOnly = true,
        label = { Text("Final deadline") },
        trailingIcon = {
            IconButton(onClick = onOpenPicker) {
                Icon(Icons.Default.Edit, contentDescription = "Choose deadline")
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenewalDatePickerDialog(
    selectedDate: LocalDate,
    onDismiss: () -> Unit,
    onDateSelected: (LocalDate) -> Unit
) {
    val datePickerState = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = datePickerState.selectedDateMillis ?: return@TextButton
                    onDateSelected(
                        Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                    )
                }
            ) {
                Text("Select")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun SettingsScreen(settings: RenewalSettings, onSettingsChange: (RenewalSettings) -> Unit) {
    var renewWindow by remember(settings.defaultRenewWindowDays) { mutableStateOf(settings.defaultRenewWindowDays.toString()) }
    var attentionWindow by remember(settings.defaultAttentionWindowDays) { mutableStateOf(settings.defaultAttentionWindowDays.toString()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Text("Default example: renew at 70 days, pay attention at 14 days.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            NumberField("Default renew window", renewWindow, {
                renewWindow = it
                updateSettingsFromText(renewWindow, attentionWindow, settings, onSettingsChange)
            }, Modifier.fillMaxWidth())
        }
        item {
            NumberField("Default attention window", attentionWindow, {
                attentionWindow = it
                updateSettingsFromText(renewWindow, attentionWindow, settings, onSettingsChange)
            }, Modifier.fillMaxWidth())
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Daily notification checks")
                Switch(
                    checked = settings.notificationsEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(notificationsEnabled = it)) }
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Dark mode")
                Switch(
                    checked = settings.darkModeEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(darkModeEnabled = it)) }
                )
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("Renewal Radar stores data locally with Room. It uses no Firebase, no cloud sync, and no paid APIs.")
        }
    }
}

@Composable
private fun RenewalCard(item: RenewalItem, onClick: () -> Unit) {
    val status = item.status()
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text("${item.category.ifBlank { "General" }} - Due ${item.dueDate}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.notes.isNotBlank()) Text(item.notes, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AssistChip(
                onClick = onClick,
                label = { Text(status.label) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
            )
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(message, modifier = Modifier.padding(16.dp))
    }
}

private data class ParseResult(val result: RenewalItem?, val error: String?)

private fun parseForm(
    item: RenewalItem?,
    title: String,
    category: String,
    dueDate: LocalDate,
    notes: String,
    renewWindow: String,
    attentionWindow: String,
    notify: Boolean
): ParseResult {
    val cleanTitle = title.trim()
    if (cleanTitle.isBlank()) return ParseResult(null, "Title is required.")

    val renewDays = renewWindow.toIntOrNull() ?: return ParseResult(null, "Renew window is required.")
    val attentionDays = attentionWindow.toIntOrNull() ?: return ParseResult(null, "Attention window is required.")
    if (renewDays < attentionDays) return ParseResult(null, "Renew window must be greater than or equal to attention window.")

    return ParseResult(
        RenewalItem(
            id = item?.id ?: 0,
            title = cleanTitle,
            category = category.trim().ifBlank { "General" },
            dueDate = dueDate,
            notes = notes.trim(),
            renewWindowDays = renewDays,
            attentionWindowDays = attentionDays,
            notify = notify,
            createdAtMillis = item?.createdAtMillis ?: System.currentTimeMillis()
        ),
        null
    )
}

private fun updateSettingsFromText(
    renewWindow: String,
    attentionWindow: String,
    current: RenewalSettings,
    onSettingsChange: (RenewalSettings) -> Unit
) {
    val renewDays = renewWindow.toIntOrNull() ?: return
    val attentionDays = attentionWindow.toIntOrNull() ?: return
    if (renewDays < attentionDays) return
    onSettingsChange(
        current.copy(
            defaultRenewWindowDays = renewDays,
            defaultAttentionWindowDays = attentionDays
        )
    )
}

private fun statusColor(status: RenewalStatus): Color = when (status) {
    RenewalStatus.Safe -> Color(0xFF15803D)
    RenewalStatus.RenewSoon -> Color(0xFF2563EB)
    RenewalStatus.NeedsAttention -> Color(0xFFB45309)
    RenewalStatus.Overdue -> Color(0xFFB91C1C)
}
