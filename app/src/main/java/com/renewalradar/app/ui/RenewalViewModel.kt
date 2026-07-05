package com.renewalradar.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.renewalradar.app.data.RenewalItem
import com.renewalradar.app.data.RenewalRepository
import com.renewalradar.app.data.RenewalSettings
import com.renewalradar.app.data.RenewalStatus
import com.renewalradar.app.data.SettingsStore
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
    val settings: RenewalSettings = RenewalSettings(),
    val summary: DashboardSummary = DashboardSummary()
)

class RenewalViewModel(
    private val repository: RenewalRepository,
    private val settingsStore: SettingsStore
) : ViewModel() {
    val uiState: StateFlow<RenewalUiState> = combine(
        repository.items,
        settingsStore.settings
    ) { items, settings ->
        val today = LocalDate.now()
        RenewalUiState(
            items = items,
            settings = settings,
            summary = DashboardSummary(
                safe = items.count { it.status(today) == RenewalStatus.Safe },
                renewSoon = items.count { it.status(today) == RenewalStatus.RenewSoon },
                needsAttention = items.count { it.status(today) == RenewalStatus.NeedsAttention },
                overdue = items.count { it.status(today) == RenewalStatus.Overdue }
            )
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

    class Factory(
        private val repository: RenewalRepository,
        private val settingsStore: SettingsStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RenewalViewModel(repository, settingsStore) as T
        }
    }
}
