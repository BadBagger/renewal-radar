package com.renewalradar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
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
import com.renewalradar.app.data.BankConnectionRepository
import com.renewalradar.app.data.BankConnectionStatus
import com.renewalradar.app.data.CandidateStatus
import com.renewalradar.app.data.ConnectedAccount
import com.renewalradar.app.data.RenewalCandidate
import com.renewalradar.app.data.RenewalItem
import com.renewalradar.app.data.RenewalSettings
import com.renewalradar.app.data.RenewalStatus
import com.renewalradar.app.data.formatCents
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun RenewalRadarRoot(
    state: RenewalUiState,
    onSave: (RenewalItem, () -> Unit) -> Unit,
    onDelete: (RenewalItem, () -> Unit) -> Unit,
    onSettingsChange: (RenewalSettings) -> Unit,
    onConnectAccount: () -> Unit,
    onSyncBankAccounts: () -> Unit,
    onDisconnectAccount: (ConnectedAccount) -> Unit,
    onUpdateCandidate: (RenewalCandidate) -> Unit,
    onConfirmCandidate: (RenewalCandidate) -> Unit,
    onConfirmCandidates: (List<RenewalCandidate>) -> Unit,
    onIgnoreCandidate: (RenewalCandidate) -> Unit,
    onIgnoreCandidates: (List<RenewalCandidate>) -> Unit,
    onDismissCandidate: (RenewalCandidate) -> Unit,
    onDeleteCandidate: (RenewalCandidate) -> Unit
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
                    selected = route == "accounts",
                    onClick = { navController.navigate("accounts") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.CreditCard, contentDescription = "Accounts") },
                    label = { Text("Accounts") }
                )
                NavigationBarItem(
                    selected = route == "candidates",
                    onClick = { navController.navigate("candidates") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Search, contentDescription = "Detected") },
                    label = { Text("Detected") }
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
            composable("accounts") {
                ConnectedAccountsScreen(
                    state = state,
                    onConnectAccount = onConnectAccount,
                    onSyncBankAccounts = onSyncBankAccounts,
                    onDisconnectAccount = onDisconnectAccount,
                    onOpenCandidates = { navController.navigate("candidates") }
                )
            }
            composable("candidates") {
                CandidateReviewScreen(
                    candidates = state.renewalCandidates,
                    onUpdateCandidate = onUpdateCandidate,
                    onConfirmCandidate = onConfirmCandidate,
                    onConfirmCandidates = onConfirmCandidates,
                    onIgnoreCandidate = onIgnoreCandidate,
                    onIgnoreCandidates = onIgnoreCandidates,
                    onDismissCandidate = onDismissCandidate,
                    onDeleteCandidate = onDeleteCandidate
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
                SettingsScreen(
                    settings = state.settings,
                    onSettingsChange = onSettingsChange,
                    onOpenConnectedAccounts = { navController.navigate("accounts") }
                )
            }
        }
    }

    if (!state.settings.firstLaunchSetupComplete) {
        FirstLaunchSetupDialog(
            onAddFirstRenewal = {
                onSettingsChange(state.settings.copy(firstLaunchSetupComplete = true))
                navController.navigate("edit/0") { launchSingleTop = true }
            },
            onConnectAccounts = {
                onSettingsChange(state.settings.copy(firstLaunchSetupComplete = true))
                navController.navigate("accounts") { launchSingleTop = true }
                onConnectAccount()
            },
            onSkip = {
                onSettingsChange(state.settings.copy(firstLaunchSetupComplete = true))
            }
        )
    }
}

@Composable
private fun FirstLaunchSetupDialog(
    onAddFirstRenewal: () -> Unit,
    onConnectAccounts: () -> Unit,
    onSkip: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Set up Renewal Radar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add renewals manually now, or connect a bank or card with Plaid Link.")
                Text("Bank sync requires the secure backend. Manual renewal tracking works without it.")
            }
        },
        confirmButton = {
            TextButton(onClick = onAddFirstRenewal) {
                Text("Add renewal")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onConnectAccounts) {
                    Text("Connect bank/card")
                }
                TextButton(onClick = onSkip) {
                    Text("Not now")
                }
            }
        }
    )
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

@Composable
private fun ConnectedAccountsScreen(
    state: RenewalUiState,
    onConnectAccount: () -> Unit,
    onSyncBankAccounts: () -> Unit,
    onDisconnectAccount: (ConnectedAccount) -> Unit,
    onOpenCandidates: () -> Unit
) {
    val displayAccounts = state.connectedAccounts.filterNot {
        it.accountId == BankConnectionRepository.CONNECTING_PLACEHOLDER_ID
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Connected Accounts", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Connect banks and cards through Plaid Link to detect recurring charges before they surprise you.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            AccountStateCard(state.connectionState)
        }
        item {
            PrivacyCard()
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onConnectAccount,
                    enabled = state.connectionState != BankConnectionStatus.Connecting &&
                        state.connectionState != BankConnectionStatus.Syncing,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Connect bank or card")
                }
                Button(
                    onClick = onSyncBankAccounts,
                    enabled = !state.bankSyncInProgress &&
                        state.connectedAccounts.any { it.status == BankConnectionStatus.Connected },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (state.bankSyncInProgress) "Syncing" else "Sync now")
                }
            }
        }
        state.bankMessage?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            TextButton(onClick = onOpenCandidates, modifier = Modifier.fillMaxWidth()) {
                Text("Review detected renewals (${state.renewalCandidates.count { it.status == CandidateStatus.Pending }})")
            }
        }
        if (displayAccounts.isEmpty()) {
            item {
                EmptyState("Not connected. Add a bank or card to start detecting recurring charges.")
            }
        } else {
            items(
                displayAccounts,
                key = { it.accountId }
            ) { account ->
                ConnectedAccountCard(account = account, onDisconnect = { onDisconnectAccount(account) })
            }
        }
    }
}

@Composable
private fun AccountStateCard(status: BankConnectionStatus) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Sync status", style = MaterialTheme.typography.titleMedium)
            Text(status.displayLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PrivacyCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Privacy and security", style = MaterialTheme.typography.titleMedium)
            Text("Renewal Radar uses read-only transaction data to detect recurring charges. We never see or store your bank login.")
            Text("Plaid Link handles bank login. Android sends only the public_token to a secure HTTPS backend.")
            Text("Plaid access tokens stay encrypted on the backend. The app receives only safe account, transaction, and subscription summaries.")
        }
    }
}

@Composable
private fun ConnectedAccountCard(account: ConnectedAccount, onDisconnect: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(account.institutionName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${account.accountName} ${account.accountMask.maskForDisplay()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Account type: ${account.accountType}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Last synced: ${account.lastSyncedAtMillis.formatTimestamp()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AssistChip(
                    onClick = {},
                    label = { Text(account.status.displayLabel()) }
                )
            }
            if (account.status == BankConnectionStatus.Connected) {
                TextButton(onClick = onDisconnect) {
                    Text("Disconnect")
                }
            }
        }
    }
}

@Composable
private fun CandidateReviewScreen(
    candidates: List<RenewalCandidate>,
    onUpdateCandidate: (RenewalCandidate) -> Unit,
    onConfirmCandidate: (RenewalCandidate) -> Unit,
    onConfirmCandidates: (List<RenewalCandidate>) -> Unit,
    onIgnoreCandidate: (RenewalCandidate) -> Unit,
    onIgnoreCandidates: (List<RenewalCandidate>) -> Unit,
    onDismissCandidate: (RenewalCandidate) -> Unit,
    onDeleteCandidate: (RenewalCandidate) -> Unit
) {
    var selectedIds by remember(candidates) { mutableStateOf(emptySet<String>()) }
    val highConfidence = candidates.filter { it.status == CandidateStatus.Pending && it.confidence >= 0.80f }
    val needsReview = candidates.filter { it.status == CandidateStatus.Pending && it.confidence < 0.80f }
    val ignored = candidates.filter { it.status == CandidateStatus.Ignored || it.status == CandidateStatus.Dismissed }
    val selectedCandidates = candidates.filter { it.candidateId in selectedIds && it.status == CandidateStatus.Pending }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Detected renewals", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Review each candidate before it becomes a renewal. Nothing is added automatically.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onConfirmCandidates(highConfidence) },
                    enabled = highConfidence.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Track all high-confidence")
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        onIgnoreCandidates(selectedCandidates)
                        selectedIds = emptySet()
                    },
                    enabled = selectedCandidates.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Ignore selected")
                }
                TextButton(
                    onClick = {
                        selectedCandidates.forEach(onDismissCandidate)
                        selectedIds = emptySet()
                    },
                    enabled = selectedCandidates.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Review later")
                }
            }
        }
        if (highConfidence.isEmpty() && needsReview.isEmpty() && ignored.isEmpty()) {
            item {
                EmptyState("No recurring charges detected yet. Try syncing again after more transactions are available.")
            }
        } else {
            candidateSection(
                title = "High confidence",
                candidates = highConfidence,
                selectedIds = selectedIds,
                onSelectedChange = { candidate, selected ->
                    selectedIds = if (selected) selectedIds + candidate.candidateId else selectedIds - candidate.candidateId
                },
                onUpdateCandidate = onUpdateCandidate,
                onConfirmCandidate = onConfirmCandidate,
                onIgnoreCandidate = onIgnoreCandidate,
                onDeleteCandidate = onDeleteCandidate
            )
            candidateSection(
                title = "Needs review",
                candidates = needsReview,
                selectedIds = selectedIds,
                onSelectedChange = { candidate, selected ->
                    selectedIds = if (selected) selectedIds + candidate.candidateId else selectedIds - candidate.candidateId
                },
                onUpdateCandidate = onUpdateCandidate,
                onConfirmCandidate = onConfirmCandidate,
                onIgnoreCandidate = onIgnoreCandidate,
                onDeleteCandidate = onDeleteCandidate
            )
            candidateSection(
                title = "Ignored/dismissed",
                candidates = ignored,
                selectedIds = emptySet(),
                onSelectedChange = { _, _ -> },
                onUpdateCandidate = onUpdateCandidate,
                onConfirmCandidate = onConfirmCandidate,
                onIgnoreCandidate = onIgnoreCandidate,
                onDeleteCandidate = onDeleteCandidate,
                selectable = false
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.candidateSection(
    title: String,
    candidates: List<RenewalCandidate>,
    selectedIds: Set<String>,
    onSelectedChange: (RenewalCandidate, Boolean) -> Unit,
    onUpdateCandidate: (RenewalCandidate) -> Unit,
    onConfirmCandidate: (RenewalCandidate) -> Unit,
    onIgnoreCandidate: (RenewalCandidate) -> Unit,
    onDeleteCandidate: (RenewalCandidate) -> Unit,
    selectable: Boolean = true
) {
    item {
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
    if (candidates.isEmpty()) {
        item {
            EmptyState("No candidates in this section.")
        }
    } else {
        items(candidates, key = { it.candidateId }) { candidate ->
            CandidateCard(
                candidate = candidate,
                selected = candidate.candidateId in selectedIds,
                selectable = selectable,
                onSelectedChange = { onSelectedChange(candidate, it) },
                onUpdateCandidate = onUpdateCandidate,
                onConfirm = { onConfirmCandidate(candidate) },
                onIgnore = { onIgnoreCandidate(candidate) },
                onDelete = { onDeleteCandidate(candidate) }
            )
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: RenewalCandidate,
    selected: Boolean,
    selectable: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onUpdateCandidate: (RenewalCandidate) -> Unit,
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
    onDelete: () -> Unit
) {
    var editing by remember(candidate.candidateId) { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (selectable) {
                    Checkbox(checked = selected, onCheckedChange = onSelectedChange)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(candidate.merchantName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Expected around ${candidate.nextChargeDate.prettyDate()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Usually ${formatCents(candidate.averageAmountCents)}" +
                            candidate.amountVarianceCents.takeIf { it > 0 }?.let { " +/- ${formatCents(it)}" }.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Last charged ${candidate.lastChargeDate.prettyDate()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${candidate.cadence} - ${candidate.confidenceLabel()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Detected from ${candidate.safeAccountLabel()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (candidate.nextChargeWindowStart != candidate.nextChargeWindowEnd) {
                        Text(
                            "Window ${candidate.nextChargeWindowStart.prettyDate()} to ${candidate.nextChargeWindowEnd.prettyDate()}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (candidate.reasonDetected.isNotBlank()) {
                        Text(
                            "Why detected: ${candidate.reasonDetected}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    WatchOuts(candidate)
                    LastTransactions(candidate)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onConfirm, enabled = candidate.status == CandidateStatus.Pending) { Text("Track this") }
                TextButton(onClick = { editing = true }) { Text("Edit") }
                TextButton(onClick = onIgnore, enabled = candidate.status == CandidateStatus.Pending) { Text("Ignore") }
            }
        }
    }

    if (editing) {
        CandidateEditDialog(
            candidate = candidate,
            onDismiss = { editing = false },
            onSave = {
                onUpdateCandidate(it)
                editing = false
            }
        )
    }
}

@Composable
private fun WatchOuts(candidate: RenewalCandidate) {
    val watchOuts = candidate.watchOuts.lines().map { it.trim() }.filter { it.isNotBlank() }
    if (watchOuts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Watch-outs", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
        watchOuts.forEach { item ->
            Text(item, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LastTransactions(candidate: RenewalCandidate) {
    val lines = candidate.matchingTransactions.lines().map { it.trim() }.filter { it.isNotBlank() }.takeLast(3)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Last matching transactions", style = MaterialTheme.typography.labelLarge)
        if (lines.isEmpty()) {
            Text(
                "Matched ${candidate.transactionsUsed.coerceAtLeast(2)} recent charges.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            lines.forEach { line ->
                Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CandidateEditDialog(
    candidate: RenewalCandidate,
    onDismiss: () -> Unit,
    onSave: (RenewalCandidate) -> Unit
) {
    var merchant by remember(candidate.candidateId) { mutableStateOf(candidate.merchantName) }
    var amount by remember(candidate.candidateId) { mutableStateOf(candidate.amountCents.toString()) }
    var cadence by remember(candidate.candidateId) { mutableStateOf(candidate.cadence) }
    var nextDate by remember(candidate.candidateId) { mutableStateOf(candidate.nextChargeDate) }
    var category by remember(candidate.candidateId) { mutableStateOf(candidate.category) }
    var account by remember(candidate.candidateId) { mutableStateOf(candidate.safeAccountLabel()) }
    var reminderDays by remember(candidate.candidateId) { mutableStateOf(candidate.reminderDays.toString()) }
    var isBill by remember(candidate.candidateId) { mutableStateOf(candidate.candidateType == "bill") }
    var notes by remember(candidate.candidateId) { mutableStateOf(candidate.notes) }
    var showDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit detected renewal") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { OutlinedTextField(merchant, { merchant = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth()) }
                item { NumberField("Amount in cents", amount, { amount = it }, Modifier.fillMaxWidth()) }
                item { OutlinedTextField(cadence, { cadence = it }, label = { Text("Billing cycle") }, modifier = Modifier.fillMaxWidth()) }
                item { DateField(nextDate, label = "Next charge date", onOpenPicker = { showDatePicker = true }) }
                item { OutlinedTextField(category, { category = it }, label = { Text("Category") }, modifier = Modifier.fillMaxWidth()) }
                item {
                    OutlinedTextField(
                        account,
                        { account = it },
                        label = { Text("Payment account/card") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Reminder timing", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0 to "Same-day", 1 to "1 day", 3 to "3 days", 7 to "7 days").forEach { (days, label) ->
                                TextButton(onClick = { reminderDays = days.toString() }) {
                                    Text(label)
                                }
                            }
                        }
                        NumberField("Custom days before", reminderDays, { reminderDays = it }, Modifier.fillMaxWidth())
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (isBill) "Bill" else "Subscription")
                        Switch(checked = isBill, onCheckedChange = { isBill = it })
                    }
                }
                item {
                    OutlinedTextField(
                        notes,
                        { notes = it },
                        label = { Text("Notes") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cents = amount.toIntOrNull() ?: candidate.amountCents
                    onSave(
                        candidate.copy(
                            merchantName = merchant.trim().ifBlank { candidate.merchantName },
                            amountCents = cents,
                            averageAmountCents = cents,
                            cadence = cadence.trim().ifBlank { candidate.cadence },
                            nextChargeDate = nextDate,
                            nextChargeWindowStart = nextDate,
                            nextChargeWindowEnd = nextDate,
                            userEditedNextDate = candidate.userEditedNextDate || nextDate != candidate.nextChargeDate,
                            category = category.trim(),
                            accountNickname = account.trim().ifBlank { candidate.safeAccountLabel() },
                            reminderDays = reminderDays.toIntOrNull() ?: candidate.reminderDays,
                            candidateType = if (isBill) "bill" else "subscription",
                            notes = notes.trim()
                        )
                    )
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    if (showDatePicker) {
        RenewalDatePickerDialog(
            selectedDate = nextDate,
            onDismiss = { showDatePicker = false },
            onDateSelected = {
                nextDate = it
                showDatePicker = false
            }
        )
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
private fun DateField(dueDate: LocalDate, label: String = "Final deadline", onOpenPicker: () -> Unit) {
    OutlinedTextField(
        value = dueDate.toString(),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
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
private fun SettingsScreen(
    settings: RenewalSettings,
    onSettingsChange: (RenewalSettings) -> Unit,
    onOpenConnectedAccounts: () -> Unit
) {
    var renewWindow by remember(settings.defaultRenewWindowDays) { mutableStateOf(settings.defaultRenewWindowDays.toString()) }
    var attentionWindow by remember(settings.defaultAttentionWindowDays) { mutableStateOf(settings.defaultAttentionWindowDays.toString()) }
    var bankBackendUrl by remember(settings.bankBackendUrl) { mutableStateOf(settings.bankBackendUrl) }

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
            TextButton(onClick = onOpenConnectedAccounts, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CreditCard, contentDescription = null)
                Text("Connected Accounts")
            }
        }
        item {
            OutlinedTextField(
                value = bankBackendUrl,
                onValueChange = {
                    bankBackendUrl = it
                    onSettingsChange(settings.copy(bankBackendUrl = it.trim()))
                },
                label = { Text("Bank sync backend URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
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

private fun String.maskForDisplay(): String = if (isBlank()) "****" else "****$this"

private fun Long?.formatTimestamp(): String {
    if (this == null) return "Never"
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
    return Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(formatter)
}

private fun BankConnectionStatus.displayLabel(): String = when (this) {
    BankConnectionStatus.NotConnected -> "Not connected"
    BankConnectionStatus.Connecting -> "Connecting"
    BankConnectionStatus.Connected -> "Connected"
    BankConnectionStatus.Syncing -> "Syncing"
    BankConnectionStatus.SyncFailed -> "Sync failed"
    BankConnectionStatus.Disconnected -> "Disconnected"
    BankConnectionStatus.PermissionRevoked -> "Permission revoked"
}

private fun RenewalCandidate.confidenceLabel(): String = when {
    confidence >= 0.80f -> "High confidence"
    confidence >= 0.55f -> "Medium confidence"
    else -> "Low confidence"
}

private fun RenewalCandidate.safeAccountLabel(): String =
    accountNickname
        .trim()
        .takeIf { it.isNotBlank() }
        ?: "Connected account"

private fun LocalDate.prettyDate(): String =
    format(DateTimeFormatter.ofPattern("MMM d"))

private fun statusColor(status: RenewalStatus): Color = when (status) {
    RenewalStatus.Safe -> Color(0xFF15803D)
    RenewalStatus.RenewSoon -> Color(0xFF2563EB)
    RenewalStatus.NeedsAttention -> Color(0xFFB45309)
    RenewalStatus.Overdue -> Color(0xFFB91C1C)
}
