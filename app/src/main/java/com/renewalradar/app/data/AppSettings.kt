package com.renewalradar.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val DEFAULT_BANK_BACKEND_URL = "https://renewal-radar-bank-sync.example.com"

data class RenewalSettings(
    val defaultRenewWindowDays: Int = 70,
    val defaultAttentionWindowDays: Int = 14,
    val notificationsEnabled: Boolean = true,
    val darkModeEnabled: Boolean = false,
    val firstLaunchSetupComplete: Boolean = false,
    val bankBackendUrl: String = DEFAULT_BANK_BACKEND_URL,
    val mockPremiumBankSyncEnabled: Boolean = true
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("renewal_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val settings: StateFlow<RenewalSettings> = state.asStateFlow()

    fun update(settings: RenewalSettings) {
        prefs.edit()
            .putInt(KEY_RENEW, settings.defaultRenewWindowDays)
            .putInt(KEY_ATTENTION, settings.defaultAttentionWindowDays)
            .putBoolean(KEY_NOTIFICATIONS, settings.notificationsEnabled)
            .putBoolean(KEY_DARK_MODE, settings.darkModeEnabled)
            .putBoolean(KEY_FIRST_LAUNCH_SETUP_COMPLETE, settings.firstLaunchSetupComplete)
            .putString(KEY_BANK_BACKEND_URL, settings.bankBackendUrl)
            .putBoolean(KEY_MOCK_PREMIUM_BANK_SYNC, settings.mockPremiumBankSyncEnabled)
            .apply()
        state.value = settings
    }

    fun current(): RenewalSettings = state.value

    private fun read(): RenewalSettings {
        val storedBackendUrl = prefs.getString(KEY_BANK_BACKEND_URL, DEFAULT_BANK_BACKEND_URL)
            ?: DEFAULT_BANK_BACKEND_URL
        val migratedBackendUrl = if (storedBackendUrl.isStaleBackendUrl()) {
            DEFAULT_BANK_BACKEND_URL
        } else {
            storedBackendUrl
        }
        if (migratedBackendUrl != storedBackendUrl) {
            prefs.edit().putString(KEY_BANK_BACKEND_URL, migratedBackendUrl).apply()
        }
        return RenewalSettings(
            defaultRenewWindowDays = prefs.getInt(KEY_RENEW, 70),
            defaultAttentionWindowDays = prefs.getInt(KEY_ATTENTION, 14),
            notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS, true),
            darkModeEnabled = prefs.getBoolean(KEY_DARK_MODE, false),
            firstLaunchSetupComplete = prefs.getBoolean(KEY_FIRST_LAUNCH_SETUP_COMPLETE, false),
            bankBackendUrl = migratedBackendUrl,
            mockPremiumBankSyncEnabled = prefs.getBoolean(KEY_MOCK_PREMIUM_BANK_SYNC, true)
        )
    }

    private fun String.isStaleBackendUrl(): Boolean {
        val normalized = trim().lowercase()
        return normalized.isBlank() ||
            "renewalradar.example" in normalized ||
            "api.example.com" in normalized ||
            "renewal-radar-bank-sync.loca.lt" in normalized ||
            "trycloudflare.com" in normalized ||
            "ngrok" in normalized ||
            "localhost" in normalized ||
            "127.0.0.1" in normalized
    }

    private companion object {
        const val KEY_RENEW = "default_renew_window_days"
        const val KEY_ATTENTION = "default_attention_window_days"
        const val KEY_NOTIFICATIONS = "notifications_enabled"
        const val KEY_DARK_MODE = "dark_mode_enabled"
        const val KEY_FIRST_LAUNCH_SETUP_COMPLETE = "first_launch_setup_complete"
        const val KEY_BANK_BACKEND_URL = "bank_backend_url"
        const val KEY_MOCK_PREMIUM_BANK_SYNC = "mock_premium_bank_sync"
    }
}
