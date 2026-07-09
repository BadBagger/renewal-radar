package com.renewalradar.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.renewalradar.app.data.BankConnectionRepository
import com.renewalradar.app.data.BankConnectionStatus
import com.renewalradar.app.data.BankSyncRepository
import com.renewalradar.app.data.ConnectedAccount
import com.renewalradar.app.data.PlaidLinkMetadata
import com.renewalradar.app.data.PlaidPublicToken
import com.renewalradar.app.data.RenewalCandidate
import com.renewalradar.app.data.RenewalCandidateRepository
import com.renewalradar.app.data.RenewalItem
import com.renewalradar.app.data.RenewalRepository
import com.renewalradar.app.data.RenewalSettings
import com.renewalradar.app.data.RenewalStatus
import com.renewalradar.app.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DashboardSummary(
    val safe: Int = 0,
    val renewSoon: Int = 0,
    val needsAttention: Int = 0,
    val overdue: Int = 0
)

data class RenewalUiState(
    val items: List<RenewalItem> = emptyList(),
    val connectedAccounts: List<ConnectedAccount> = emptyList(),
    val renewalCandidates: List<RenewalCandidate> = emptyList(),
    val settings: RenewalSettings = RenewalSettings(),
    val summary: DashboardSummary = DashboardSummary(),
    val bankSyncInProgress: Boolean = false,
    val bankMessage: String? = null,
    val pendingPlaidLinkToken: String? = null,
    val connectionState: BankConnectionStatus = BankConnectionStatus.NotConnected
)

class RenewalViewModel(
    private val repository: RenewalRepository,
    private val settingsStore: SettingsStore,
    private val bankConnectionRepository: BankConnectionRepository,
    private val bankSyncRepository: BankSyncRepository,
    private val renewalCandidateRepository: RenewalCandidateRepository
) : ViewModel() {
    private val bankSyncInProgress = MutableStateFlow(false)
    private val bankMessage = MutableStateFlow<String?>(null)
    private val pendingPlaidLinkToken = MutableStateFlow<String?>(null)
    private val connectionState = MutableStateFlow(BankConnectionStatus.NotConnected)

    init {
        viewModelScope.launch {
            bankConnectionRepository.clearConnectingPlaceholder()
        }
    }

    private val baseState = combine(
        repository.items,
        settingsStore.settings,
        bankConnectionRepository.connectedAccounts,
        renewalCandidateRepository.candidates
    ) { items, settings, accounts, candidates ->
        val today = LocalDate.now()
        RenewalUiState(
            items = items,
            connectedAccounts = accounts,
            renewalCandidates = candidates,
            settings = settings,
            summary = DashboardSummary(
                safe = items.count { it.status(today) == RenewalStatus.Safe },
                renewSoon = items.count { it.status(today) == RenewalStatus.RenewSoon },
                needsAttention = items.count { it.status(today) == RenewalStatus.NeedsAttention },
                overdue = items.count { it.status(today) == RenewalStatus.Overdue }
            )
        )
    }

    val uiState: StateFlow<RenewalUiState> = combine(
        baseState,
        bankSyncInProgress,
        bankMessage,
        pendingPlaidLinkToken,
        connectionState
    ) { state, syncing, message, linkToken, connection ->
        state.copy(
            bankSyncInProgress = syncing,
            bankMessage = message,
            pendingPlaidLinkToken = linkToken,
            connectionState = state.inferConnectionState(syncing, connection)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RenewalUiState())

    fun save(item: RenewalItem, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.save(item)
            onDone()
        }
    }

    fun delete(item: RenewalItem, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.delete(item)
            onDone()
        }
    }

    fun updateSettings(settings: RenewalSettings) {
        settingsStore.update(settings)
    }

    fun startPlaidLink() {
        viewModelScope.launch {
            connectionState.value = BankConnectionStatus.Connecting
            bankMessage.value = "Requesting a secure Plaid Link token..."
            runCatching {
                bankConnectionRepository.markConnectingPlaceholder()
                bankConnectionRepository.createLinkToken()
            }.onSuccess { linkToken ->
                if (linkToken.mockMode) {
                    connectMockBackend()
                } else {
                    pendingPlaidLinkToken.value = linkToken.token
                    bankMessage.value = "Opening Plaid Link..."
                }
            }.onFailure { error ->
                bankConnectionRepository.clearConnectingPlaceholder()
                connectionState.value = BankConnectionStatus.Disconnected
                bankMessage.value = error.toBankErrorMessage("Backend unavailable. Try again later.")
            }
        }
    }

    private suspend fun connectMockBackend() {
        connectionState.value = BankConnectionStatus.Connecting
        bankMessage.value = "Demo bank backend connected. Syncing sample recurring charges..."
        runCatching {
            bankConnectionRepository.exchangePublicToken(
                PlaidPublicToken("public-mock-renewal-radar"),
                PlaidLinkMetadata(
                    institution = com.renewalradar.app.data.PlaidInstitutionMetadata(
                        id = "ins_mock",
                        name = "Plaid Sandbox Bank"
                    ),
                    accounts = listOf(
                        com.renewalradar.app.data.PlaidAccountMetadata(
                            id = "mock-checking",
                            name = "Everyday Checking",
                            mask = "0000",
                            type = "depository",
                            subtype = "checking"
                        )
                    )
                )
            )
            bankSyncInProgress.value = true
            bankSyncRepository.syncNow()
        }.onSuccess {
            connectionState.value = BankConnectionStatus.Connected
            bankMessage.value = "Demo account synced. Review detected recurring charges."
        }.onFailure { error ->
            bankSyncRepository.markSyncFailed()
            connectionState.value = BankConnectionStatus.SyncFailed
            bankMessage.value = error.toBankErrorMessage("Demo sync failed. Renewal tracking still works.")
        }.also {
            bankSyncInProgress.value = false
        }
    }

    fun markPlaidLinkLaunched() {
        pendingPlaidLinkToken.value = null
    }

    fun handlePlaidSuccess(publicToken: String, metadata: PlaidLinkMetadata) {
        viewModelScope.launch {
            if (metadata.accounts.isEmpty()) {
                bankConnectionRepository.clearConnectingPlaceholder()
                connectionState.value = BankConnectionStatus.Disconnected
                bankMessage.value = "No supported checking, savings, or credit card accounts were found."
                return@launch
            }

            connectionState.value = BankConnectionStatus.Connecting
            bankMessage.value = "Bank connected. Saving secure account summary..."
            runCatching {
                bankConnectionRepository.exchangePublicToken(PlaidPublicToken(publicToken), metadata)
                bankSyncInProgress.value = true
                bankSyncRepository.syncNow()
            }.onSuccess {
                connectionState.value = BankConnectionStatus.Connected
                bankMessage.value = "Connected and synced. Review detected recurring charges."
            }.onFailure { error ->
                bankSyncRepository.markSyncFailed()
                connectionState.value = BankConnectionStatus.SyncFailed
                bankMessage.value = error.toBankErrorMessage("Connected, but sync failed. Renewal tracking still works.")
            }.also {
                bankSyncInProgress.value = false
            }
        }
    }

    fun handlePlaidExit(message: String, permissionRevoked: Boolean = false) {
        pendingPlaidLinkToken.value = null
        connectionState.value = if (permissionRevoked) {
            BankConnectionStatus.PermissionRevoked
        } else {
            BankConnectionStatus.Disconnected
        }
        bankMessage.value = message
    }

    fun syncBankAccounts() {
        viewModelScope.launch {
            bankSyncInProgress.value = true
            bankMessage.value = null
            runCatching {
                bankSyncRepository.syncNow()
            }.onSuccess {
                connectionState.value = BankConnectionStatus.Connected
                bankMessage.value = "Sync complete. Review detected subscription candidates before adding them."
            }.onFailure { error ->
                bankSyncRepository.markSyncFailed()
                connectionState.value = BankConnectionStatus.SyncFailed
                bankMessage.value = error.toBankErrorMessage("Sync failed. Renewal tracking still works.")
            }.also {
                bankSyncInProgress.value = false
            }
        }
    }

    fun disconnectAccount(account: ConnectedAccount) {
        viewModelScope.launch {
            runCatching {
                bankConnectionRepository.disconnect(account.accountId)
            }.onSuccess {
                connectionState.value = BankConnectionStatus.Disconnected
                bankMessage.value = "${account.institutionName} disconnected."
            }.onFailure { error ->
                bankMessage.value = error.toBankErrorMessage("Disconnect failed. Try again.")
            }
        }
    }

    fun updateCandidate(candidate: RenewalCandidate) {
        viewModelScope.launch {
            renewalCandidateRepository.updateCandidate(candidate)
        }
    }

    fun confirmCandidate(candidate: RenewalCandidate) {
        viewModelScope.launch {
            repository.save(renewalCandidateRepository.toRenewalItem(candidate))
            renewalCandidateRepository.markConfirmed(candidate)
            bankMessage.value = "${candidate.merchantName} added to renewals."
        }
    }

    fun confirmCandidates(candidates: List<RenewalCandidate>) {
        viewModelScope.launch {
            candidates.forEach { candidate ->
                repository.save(renewalCandidateRepository.toRenewalItem(candidate))
                renewalCandidateRepository.markConfirmed(candidate)
            }
            if (candidates.isNotEmpty()) {
                bankMessage.value = "${candidates.size} detected renewals added."
            }
        }
    }

    fun ignoreCandidate(candidate: RenewalCandidate) {
        viewModelScope.launch {
            renewalCandidateRepository.ignore(candidate)
        }
    }

    fun ignoreCandidates(candidates: List<RenewalCandidate>) {
        viewModelScope.launch {
            renewalCandidateRepository.ignoreAll(candidates)
        }
    }

    fun dismissCandidate(candidate: RenewalCandidate) {
        viewModelScope.launch {
            renewalCandidateRepository.dismiss(candidate)
        }
    }

    fun deleteCandidate(candidate: RenewalCandidate) {
        viewModelScope.launch {
            renewalCandidateRepository.delete(candidate)
        }
    }

    class Factory(
        private val repository: RenewalRepository,
        private val settingsStore: SettingsStore,
        private val bankConnectionRepository: BankConnectionRepository,
        private val bankSyncRepository: BankSyncRepository,
        private val renewalCandidateRepository: RenewalCandidateRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RenewalViewModel(
                repository,
                settingsStore,
                bankConnectionRepository,
                bankSyncRepository,
                renewalCandidateRepository
            ) as T
        }
    }
}

private fun RenewalUiState.inferConnectionState(
    syncing: Boolean,
    fallback: BankConnectionStatus
): BankConnectionStatus = when {
    syncing -> BankConnectionStatus.Syncing
    connectedAccounts.any { it.status == BankConnectionStatus.PermissionRevoked } -> BankConnectionStatus.PermissionRevoked
    connectedAccounts.any { it.status == BankConnectionStatus.SyncFailed } -> BankConnectionStatus.SyncFailed
    connectedAccounts.any { it.status == BankConnectionStatus.Connected } -> BankConnectionStatus.Connected
    connectedAccounts.any { it.status == BankConnectionStatus.Connecting } -> BankConnectionStatus.Connecting
    connectedAccounts.any { it.status == BankConnectionStatus.Disconnected } -> BankConnectionStatus.Disconnected
    else -> fallback
}

private fun Throwable.toBankErrorMessage(fallback: String): String {
    val text = message.orEmpty().lowercase()
    return when {
        "backend url is not configured" in text -> "Bank backend URL was stale. Restart the app once, then tap Connect bank or card again."
        "bank backend must use https" in text -> "Bank sync backend must use HTTPS. Local HTTP is only for emulator development."
        "link_token" in text && ("expired" in text || "invalid" in text) -> "Link token expired. Tap Connect bank or card again."
        "network" in text || "unable" in text || "failed" in text -> "Network error or backend unavailable. Try again later."
        "institution" in text && "failed" in text -> "Institution connection failed. Try again or choose another institution."
        "no supported" in text -> "No supported checking, savings, or credit card accounts were found."
        "relink" in text || "permission" in text || "revoked" in text -> "Permission revoked. Reconnect the institution to continue syncing."
        else -> fallback
    }
}
